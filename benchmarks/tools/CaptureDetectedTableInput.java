package dev.docweave.layout;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import tools.jackson.databind.json.JsonMapper;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Captures unchanged detector/crop/structure geometry for a selected local JPEG or pinned original PDF page.
 * This standalone diagnostic uses package-private model machinery, not a public service API.
 * Source images and derived rasters stay in a caller-selected local directory.
 */
public final class CaptureDetectedTableInput {
    /** Prevents construction of the diagnostic launcher. */
    private CaptureDetectedTableInput() {
    }

    /**
     * Replays whole-JPEG150DPI embedding and the existing two-model crop pipeline without OCR or source repair.
     * No annotation is read and no model score or predicted object is modified.
     * @param args JPEG, new output directory, detector graph, detector SHA256, structure graph, structure SHA256;
     *             optionally original-pdf, one-based source page and source PDF SHA256 for original-page audit
     * @throws Exception if an input, graph, resource budget or fresh-output requirement fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 6 && (args.length != 9 || !args[6].equals("original-pdf"))) {
            throw new IllegalArgumentException("Expected JPEG, new directory, detector/hash and structure/hash");
        }
        final var output = Files.createDirectory(Path.of(args[1]));
        if (Files.size(Path.of(args[0])) > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Diagnostic input exceeds16MiB");
        }
        final var bytes = Files.readAllBytes(Path.of(args[0]));
        final boolean original = args.length == 9;
        final String sourceHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (original && !sourceHash.equals(args[8])) {
            throw new IllegalArgumentException("Original PDF identity mismatch");
        }
        final int pageIndex = original ? Integer.parseInt(args[7]) - 1 : 0;
        write(output.resolve("source.sha256"),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        write(output.resolve("java-runtime.txt"), Runtime.version() + "\n" + System.getProperty("java.vendor"));
        final var detection = policy(Path.of(args[2]), args[3], original);
        final var structure = policy(Path.of(args[4]), args[5], original)
                                      .withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
        try (final var document = Loader.loadPDF(original ? bytes : pdf(bytes));
                final var detector = new ManagedTatrSession(detection, 3);
                final var recognizer = new ManagedTatrSession(structure, 7)) {
            final var page = document.getPage(pageIndex);
            final double predictedPixels = Math.ceil(page.getCropBox().getWidth() * 150 / 72.0)
                                           * Math.ceil(page.getCropBox().getHeight() * 150 / 72.0);
            if (predictedPixels > 16_000_000) {
                throw new IllegalArgumentException("Diagnostic raster exceeds16M pixels");
            }
            final var image = new PDFRenderer(document).renderImageWithDPI(pageIndex, 150, ImageType.RGB);
            try {
                if (original) {
                    writeJson(output.resolve("original-frame.json"),
                            Map.of("pdf_sha256", sourceHash, "source_page", pageIndex + 1, "source_rotation",
                                    page.getRotation(), "render_width", image.getWidth(), "render_height",
                                    image.getHeight(), "dpi", 150));
                }
                final var deadline = new LayoutDeadline(structure.timeout());
                final var work = new LayoutWork(deadline);
                detector.infer(image, deadline, objects -> {
                    objects(output.resolve("detections.tsv"), objects);
                    final var crops = DetectionCrops.plan(objects, image, structure, work);
                    final var cropRows = new ArrayList<String>();
                    cropRows.add("index\tleft\ttop\tright\tbottom");
                    for (int index = 0; index < crops.size(); index++) {
                        final var crop = crops.get(index);
                        cropRows.add(index + "\t" + crop.left() + "\t" + crop.top() + "\t" + crop.right() + "\t"
                                     + crop.bottom());
                        final var raster = copy(image, crop);
                        try {
                            final int selected = index;
                            recognizer.infer(raster, deadline, predictions -> {
                                objects(output.resolve("crop-" + selected + "-objects.tsv"), predictions);
                                final var tables = new TableTopology(structure, work).tables(predictions);
                                if (original) {
                                    final var pixels =
                                            new BilinearTableImageTransform().prepare(raster, structure, deadline);
                                    final var counts = new ArrayList<Long>();
                                    for (int label = 0; label < 6; label++) {
                                        final int selectedLabel = label;
                                        counts.add(predictions.stream()
                                                        .filter(object -> object.label() == selectedLabel)
                                                        .count());
                                    }
                                    writeJson(output.resolve("crop-" + selected + "-stages.json"),
                                            Map.of("source_width", raster.getWidth(), "source_height",
                                                    raster.getHeight(), "model_width", pixels.width(), "model_height",
                                                    pixels.height(), "decoded_positive_classes_0_through_5", counts,
                                                    "decoded_objects", predictions.size(), "row_filter_stages",
                                                    rowStages(predictions, structure, work), "tables",
                                                    tables.stream()
                                                            .map(table
                                                                    -> Map.of("rows", table.rows(), "columns",
                                                                            table.columns(), "anchors",
                                                                            table.anchors().size(), "header_rows",
                                                                            table.headerRows()))
                                                            .toList()));
                                }
                                final var anchors = new ArrayList<String>();
                                anchors.add("table\trow\tcolumn\trow_span\tcolumn_span\tleft\ttop\tright\tbottom");
                                for (int table = 0; table < tables.size(); table++) {
                                    for (final var anchor : tables.get(table).anchors()) {
                                        final var box = anchor.bounds();
                                        anchors.add(table + "\t" + anchor.row() + "\t" + anchor.column() + "\t"
                                                    + anchor.rowSpan() + "\t" + anchor.columnSpan() + "\t" + box.left()
                                                    + "\t" + box.top() + "\t" + box.right() + "\t" + box.bottom());
                                    }
                                }
                                Files.write(output.resolve("crop-" + selected + "-anchors.tsv"), anchors,
                                        StandardOpenOption.CREATE_NEW);
                                return tables.size();
                            });
                        } finally {
                            raster.flush();
                        }
                    }
                    Files.write(output.resolve("crops.tsv"), cropRows, StandardOpenOption.CREATE_NEW);
                    return crops.size();
                });
            } finally {
                image.flush();
            }
        }
    }

    /**
     * Replays the exact private table-membership/suppression policies without exposing production APIs.
     * Reflection is diagnostic-only: a renamed private method must fail this tool rather than silently
     * substituting a copied algorithm or changing thresholds. All work uses the same bounded counter.
     * @param objects unchanged thresholded structure objects
     * @param options unchanged geometry policy
     * @param work shared call budget
     * @return text-free stage counts per suppressed table candidate
     * @throws java.io.IOException if introspection or the bounded policy replay fails
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowStages(final List<LayoutObject> objects, final OnnxTableOptions options,
            final LayoutWork work) throws java.io.IOException {
        try {
            final var suppression = TableTopology.class.getDeclaredMethod("suppress", List.class, double.class);
            final var fraction = TableTopology.class.getDeclaredMethod("fraction",
                    dev.docweave.partition.PdfTableLayoutProvider.Box.class,
                    dev.docweave.partition.PdfTableLayoutProvider.Box.class);
            suppression.setAccessible(true);
            fraction.setAccessible(true);
            final var topology = new TableTopology(options, work);
            final var tableObjects = objects.stream().filter(object -> object.label() == 0).toList();
            final var tables = (List<LayoutObject>) suppression.invoke(topology, tableObjects, 0.5);
            final var rows = objects.stream().filter(object -> object.label() == 2).toList();
            final var result = new ArrayList<Map<String, Object>>();
            for (final var table : tables) {
                final var members = new ArrayList<LayoutObject>();
                for (final var row : rows) {
                    work.step();
                    if ((double) fraction.invoke(null, row.box(), table.box()) >= 0.5) {
                        members.add(row);
                    }
                }
                final var kept = (List<LayoutObject>) suppression.invoke(topology, members, 0.5);
                result.add(Map.of("decoded_rows", rows.size(), "member_rows", members.size(), "excluded_by_membership",
                        rows.size() - members.size(), "retained_after_suppression", kept.size(), "suppressed_rows",
                        members.size() - kept.size()));
            }
            return List.copyOf(result);
        } catch (final ReflectiveOperationException failure) {
            throw new java.io.IOException("Private row-policy diagnostic failed", failure);
        }
    }

    /**
     * Keeps the historical JPEG policy and selects the existing four-thread native-page audit policy.
     * @param path explicit local graph
     * @param hash expected graph SHA256
     * @param original whether to match original native/model captures
     * @return immutable otherwise unchanged model policy
     */
    private static OnnxTableOptions policy(final Path path, final String hash, final boolean original) {
        final var defaults = OnnxTableOptions.defaults(path, hash);
        return original ? new OnnxTableOptions(path, hash, defaults.timeout(), defaults.maxConcurrentCalls(),
                                  defaults.maxModelBytes(), defaults.maxInputPixels(), defaults.maxQueries(),
                                  defaults.tableLimits(), defaults.minimumScore(), 4, defaults.mergeConflicts())
                        : defaults;
    }

    /**
     * Writes text-free stage geometry/counts without replacing previous evidence.
     * @param path fresh output file
     * @param value numeric/hash metadata
     * @throws java.io.IOException if the new report cannot be written
     */
    private static void writeJson(final Path path, final Object value) throws java.io.IOException {
        Files.write(path, JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsBytes(value),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Reproduces the corpus embedding using original JPEG bytes and whole-page media dimensions.
     * @param bytes original JPEG encoding
     * @return encoded one-page PDF
     * @throws Exception if image decoding or PDF serialization fails
     */
    private static byte[] pdf(final byte[] bytes) throws Exception {
        final var image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IllegalArgumentException("Unreadable JPEG");
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
            return output.toByteArray();
        }
    }

    /**
     * Copies the existing preflighted crop over white, matching the production pipeline's pixel ownership.
     * @param source borrowed whole-page raster
     * @param crop integer source rectangle from the unchanged planner
     * @return owned RGB raster for the caller to release
     */
    private static BufferedImage copy(final BufferedImage source, final DetectionCrops.Crop crop) {
        final int width = crop.right() - crop.left();
        final int height = crop.bottom() - crop.top();
        final var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, crop.left(), crop.top(), crop.right(), crop.bottom(), null);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * Records finite thresholded model objects without transcriptions or interpretation.
     * @param path new TSV output
     * @param values unchanged decoded objects
     * @throws java.io.IOException if fresh output cannot be written
     */
    private static void objects(final Path path, final List<LayoutObject> values) throws java.io.IOException {
        final var rows = new ArrayList<String>();
        rows.add("label\tscore\tleft\ttop\tright\tbottom");
        for (final var value : values) {
            final var box = value.box();
            rows.add(value.label() + "\t" + value.score() + "\t" + box.left() + "\t" + box.top() + "\t" + box.right()
                     + "\t" + box.bottom());
        }
        Files.write(path, rows, StandardOpenOption.CREATE_NEW);
    }

    /**
     * Writes one fresh UTF-8 diagnostic file.
     * @param path new output path
     * @param value diagnostic value
     * @throws java.io.IOException if the fresh output cannot be written
     */
    private static void write(final Path path, final String value) throws java.io.IOException {
        Files.writeString(path, value + "\n", StandardOpenOption.CREATE_NEW);
    }
}
