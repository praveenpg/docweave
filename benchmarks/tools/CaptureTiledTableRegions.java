package io.docweave.layout;

import io.docweave.partition.PartitionException;
import io.docweave.partition.PdfTableLayoutProvider;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Diagnostic comparison of whole-page versus two fixed overlapping vertical detector windows.
 * Reads pinned JPEG identities only, never table annotations. It does not alter production code,
 * select a winning window, merge proposals, run OCR or establish aggregate production limits.
 */
public final class CaptureTiledTableRegions {
    /** Prevents construction of the command-line diagnostic. */
    private CaptureTiledTableRegions() {
    }

    /**
     * Runs three independent region profiles on each explicitly pinned JPEG using unchanged graphs.
     * Source rasters must satisfy the original whole-page pixel limit before any window is copied.
     * Each profile retains the original model thresholds, crop planner and structure policy;
     * separate deadlines make this an experiment, not one production transaction.
     * @param args cache, filename/SHA256 TSV, new output directory, detector/hash, structure/hash
     * @throws Exception if identities, sessions or fresh-output preconditions fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException(
                    "Expected cache, pins TSV, NEW output and detector/hash, structure/hash");
        }
        final var output = Files.createDirectory(Path.of(args[2]));
        final var detection = OnnxTableOptions.defaults(Path.of(args[3]), args[4]);
        final var structure = OnnxTableOptions.defaults(Path.of(args[5]), args[6])
                                      .withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
        try (final var detector = new ManagedTatrSession(detection, 3);
                final var recognizer = new ManagedTatrSession(structure, 7)) {
            for (final var row : Files.readAllLines(Path.of(args[1]))) {
                final var fields = row.split("\t");
                if (fields.length != 2 || !fields[0].matches("cTDaR_[st][0-9]+\\.jpg")
                        || !fields[1].matches("[a-f0-9]{64}")) {
                    throw new IllegalArgumentException("Invalid pinned JPEG identity");
                }
                final var bytes = Files.readAllBytes(Path.of(args[0]).resolve(fields[0]));
                final var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!actual.equals(fields[1])) {
                    throw new IllegalArgumentException("JPEG identity mismatch: " + fields[0]);
                }
                final var directory = Files.createDirectory(output.resolve(fields[0].replace(".jpg", "")));
                final var embedded = pdf(bytes);
                try (final var document = Loader.loadPDF(embedded.bytes())) {
                    final var image = new PDFRenderer(document).renderImageWithDPI(0, 150, ImageType.RGB);
                    try {
                        write(directory.resolve("source.tsv"),
                                List.of("sha256\twidth\theight\toriginal_width\toriginal_height\tpdf_width\tpdf_height\trender_dpi",
                                        actual + "\t" + image.getWidth() + "\t" + image.getHeight() + "\t"
                                                + embedded.width() + "\t" + embedded.height() + "\t"
                                                + document.getPage(0).getMediaBox().getWidth() + "\t"
                                                + document.getPage(0).getMediaBox().getHeight() + "\t150"));
                        if ((long) image.getWidth() * image.getHeight() > detection.maxInputPixels()) {
                            write(directory.resolve("outcome.txt"), List.of("OCR_LIMIT"));
                            continue;
                        }
                        final int split = (int) Math.ceil(image.getHeight() * .65);
                        final int bottom = image.getHeight() - split;
                        capture(image, 0, "whole", directory, detector, recognizer, structure);
                        final var upper = copy(image, new DetectionCrops.Crop(0, 0, image.getWidth(), split));
                        try {
                            capture(upper, 0, "upper", directory, detector, recognizer, structure);
                        } finally {
                            upper.flush();
                        }
                        final var lower =
                                copy(image, new DetectionCrops.Crop(0, bottom, image.getWidth(), image.getHeight()));
                        try {
                            capture(lower, bottom, "lower", directory, detector, recognizer, structure);
                        } finally {
                            lower.flush();
                        }
                    } finally {
                        image.flush();
                    }
                }
                System.out.println("Captured all fixed profiles: " + fields[0]);
            }
        }
    }

    /**
     * Records predictions and complete table structures in original whole-page pixel coordinates.
     * @param image borrowed whole/window raster
     * @param pageTop window origin in the full raster
     * @param profile fixed profile name
     * @param directory fresh source output directory
     * @param detector borrowed detector session
     * @param recognizer borrowed structure session
     * @param options unchanged structure limits and merge policy
     * @throws IOException if diagnostic output or a nontyped inference failure occurs
     */
    private static void capture(final BufferedImage image, final int pageTop, final String profile,
            final Path directory, final ManagedTatrSession detector, final ManagedTatrSession recognizer,
            final OnnxTableOptions options) throws IOException {
        final var output = Files.createDirectory(directory.resolve(profile));
        final var deadline = new LayoutDeadline(options.timeout());
        final var work = new LayoutWork(deadline);
        final var detections = new ArrayList<String>();
        detections.add("label\tscore\tleft\ttop\tright\tbottom\tartificial_edge_contact");
        final var tables = new ArrayList<String>();
        tables.add("crop\ttable\trows\tcolumns\tleft\ttop\tright\tbottom");
        final var anchors = new ArrayList<String>();
        anchors.add("crop\ttable\trow\tcolumn\trow_span\tcolumn_span\tleft\ttop\tright\tbottom");
        try {
            detector.infer(image, deadline, objects -> {
                for (final var object : objects) {
                    final var box = object.box();
                    final boolean seam = (profile.equals("upper") && box.bottom() >= image.getHeight() - 10)
                                         || (profile.equals("lower") && box.top() <= 10);
                    detections.add(
                            object.label() + "\t" + object.score() + "\t" + bounds(box, 0, pageTop) + "\t" + seam);
                }
                final var crops = DetectionCrops.plan(objects, image, options, work);
                for (int index = 0; index < crops.size(); index++) {
                    final var crop = crops.get(index);
                    final var raster = copy(image, crop);
                    try {
                        final int cropIndex = index;
                        recognizer.infer(raster, deadline, predictions -> {
                            final var recognized = new TableTopology(options, work).tables(predictions);
                            for (int tableIndex = 0; tableIndex < recognized.size(); tableIndex++) {
                                final var table = recognized.get(tableIndex);
                                tables.add(cropIndex + "\t" + tableIndex + "\t" + table.rows() + "\t" + table.columns()
                                           + "\t" + bounds(table.bounds(), crop.left(), crop.top() + pageTop));
                                for (final var anchor : table.anchors()) {
                                    anchors.add(cropIndex + "\t" + tableIndex + "\t" + anchor.row() + "\t"
                                                + anchor.column() + "\t" + anchor.rowSpan() + "\t" + anchor.columnSpan()
                                                + "\t" + bounds(anchor.bounds(), crop.left(), crop.top() + pageTop));
                                }
                            }
                            return recognized.size();
                        });
                    } finally {
                        raster.flush();
                    }
                }
                return crops.size();
            });
            write(output.resolve("outcome.txt"), List.of("OK"));
        } catch (final PartitionException failure) {
            write(output.resolve("outcome.txt"), List.of(failure.code().name()));
        }
        write(output.resolve("detections.tsv"), detections);
        write(output.resolve("tables.tsv"), tables);
        write(output.resolve("anchors.tsv"), anchors);
    }

    /**
     * Encodes an enclosing rectangle after adding one exact integer window/crop origin.
     * @param box source-relative box
     * @param x horizontal origin
     * @param y vertical origin
     * @return tab-separated original-frame rectangle
     */
    private static String bounds(final PdfTableLayoutProvider.Box box, final int x, final int y) {
        return (box.left() + x) + "\t" + (box.top() + y) + "\t" + (box.right() + x) + "\t" + (box.bottom() + y);
    }

    /**
     * Copies a bounded source rectangle over white, without changing the borrowed raster.
     * @param source original pixels
     * @param crop validated integer rectangle
     * @return owned RGB raster released by the caller
     */
    private static BufferedImage copy(final BufferedImage source, final DetectionCrops.Crop crop) {
        final var image =
                new BufferedImage(crop.right() - crop.left(), crop.bottom() - crop.top(), BufferedImage.TYPE_INT_RGB);
        final var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(source, 0, 0, image.getWidth(), image.getHeight(), crop.left(), crop.top(), crop.right(),
                    crop.bottom(), null);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * Encoded local PDF and its original JPEG coordinate frame.
     * @param bytes owned PDF encoding
     * @param width original JPEG width
     * @param height original JPEG height
     */
    private record Embedded(byte[] bytes, int width, int height) {}

    /**
     * Embeds the original JPEG bytes at150DPI, matching the retained corpus pipeline.
     * @param bytes pinned JPEG encoding
     * @return owned one-page PDF bytes
     * @throws IOException if image decoding or PDF serialization fails
     */
    private static Embedded pdf(final byte[] bytes) throws IOException {
        final var image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("Unreadable pinned JPEG");
        }
        final int width = image.getWidth();
        final int height = image.getHeight();
        image.flush();
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(width * 72f / 150, height * 72f / 150));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(JPEGFactory.createFromStream(document, new ByteArrayInputStream(bytes)), 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            document.save(output);
            return new Embedded(output.toByteArray(), width, height);
        }
    }

    /**
     * Saves numeric/identity diagnostics only, rejecting accidental evidence replacement.
     * @param path new output file
     * @param rows text-free geometry or source identity records
     * @throws IOException if the new output cannot be written
     */
    private static void write(final Path path, final List<String> rows) throws IOException {
        Files.write(path, rows, StandardOpenOption.CREATE_NEW);
    }
}
