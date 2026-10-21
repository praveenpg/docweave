package dev.docweave.layout;

import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfTableLayoutProvider;
import dev.docweave.partition.TableLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

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
 * Compares public whole-page model and observed-rule composition strategies on pinned local rasters.
 * No annotation, OCR text, case-specific crop or new recognition algorithm enters inference.
 * Source images, their derivatives and model weights remain local and are never copied to evidence.
 */
public final class CaptureRuledScanComposition {
    /** Prevents construction of the diagnostic launcher. */
    private CaptureRuledScanComposition() {
    }

    /**
     * Runs the existing model, rules-only and rule-preferred strategies with uniform default budgets.
     * Each model invocation retains its own30-second deadline; composition adds its existing work
     * ceiling, not an extra shared native deadline across these three independent diagnostic profiles.
     * @param args source cache, pinned JPEG TSV, NEW output, detector/hash, structure/hash, DPI150 or300
     * @throws Exception if input identities, fresh outputs, graph schema or inference requirements fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 8) {
            throw new IllegalArgumentException(
                    "Expected cache, pins TSV, NEW output, detector/hash, structure/hash, DPI");
        }
        final var cache = Path.of(args[0]);
        final int dpi = Integer.parseInt(args[7]);
        if (dpi != 150 && dpi != 300) {
            throw new IllegalArgumentException("Expected fixed150 or300DPI profile");
        }
        final var output = Files.createDirectory(Path.of(args[2]));
        final var detection = OnnxTableOptions.defaults(Path.of(args[3]), args[4]);
        final var structure = OnnxTableOptions.defaults(Path.of(args[5]), args[6])
                                      .withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
        final var ocr = PdfOcrOptions.defaults();
        try (final var model = OnnxTableLayout.create(detection, structure)) {
            final PdfTableLayoutProvider rules =
                    PdfTableLayoutProvider.preferRuled(image -> List.of(), TableLimits.defaults(), ocr);
            final PdfTableLayoutProvider composed =
                    PdfTableLayoutProvider.preferRuled(model, TableLimits.defaults(), ocr);
            for (final var line : Files.readAllLines(Path.of(args[1]))) {
                final var pin = line.split("\t");
                if (pin.length != 2 || !pin[0].matches("cTDaR_[st][0-9]+\\.jpg") || !pin[1].matches("[a-f0-9]{64}")) {
                    throw new IllegalArgumentException("Unexpected pinned source identity");
                }
                final var bytes = Files.readAllBytes(cache.resolve(pin[0]));
                final var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!actual.equals(pin[1])) {
                    throw new IllegalArgumentException("Original JPEG identity mismatch");
                }
                final var directory = Files.createDirectory(output.resolve(pin[0].replace(".jpg", "")));
                final var dimensions = dimensions(bytes);
                try (final var document = Loader.loadPDF(pdf(bytes, dimensions))) {
                    final var page = document.getPage(0).getMediaBox();
                    final float scale = dpi / 72f;
                    final int width = Math.max(1, (int) Math.floor(page.getWidth() * scale));
                    final int height = Math.max(1, (int) Math.floor(page.getHeight() * scale));
                    write(directory.resolve("source.tsv"),
                            List.of("sha256\twidth\theight\toriginal_width\toriginal_height\tpdf_width\tpdf_height\trender_dpi",
                                    actual + "\t" + width + "\t" + height + "\t" + dimensions.width() + "\t"
                                            + dimensions.height() + "\t" + page.getWidth() + "\t" + page.getHeight()
                                            + "\t" + dpi));
                    if ((long) width * height > ocr.maxRenderPixels()) {
                        write(directory.resolve("outcome.txt"), List.of("OCR_LIMIT"));
                        continue;
                    }
                    final var image = new PDFRenderer(document).renderImageWithDPI(0, dpi, ImageType.RGB);
                    try {
                        if (image.getWidth() != width || image.getHeight() != height) {
                            throw new IllegalStateException("Renderer dimensions differ from preflight");
                        }
                        try (final var encoded = new ByteArrayOutputStream()) {
                            if (!ImageIO.write(image, "PNG", encoded)) {
                                throw new IOException("No PNG encoder for raster identity");
                            }
                            write(directory.resolve("render.sha256"),
                                    List.of(HexFormat.of().formatHex(
                                            MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()))));
                        }
                        capture(image, model, directory.resolve("model"));
                        capture(image, rules, directory.resolve("rules"));
                        capture(image, composed, directory.resolve("composed"));
                    } finally {
                        image.flush();
                    }
                }
                System.out.println("Captured rule composition: " + pin[0] + " at" + dpi + "DPI");
            }
        }
    }

    /**
     * Original JPEG coordinate extent, obtained without decoding a full source pixel raster.
     * @param width encoded source width
     * @param height encoded source height
     */
    private record Dimensions(int width, int height) {}

    /**
     * Reads original dimensions before allocating any rendered raster.
     * @param bytes pinned original JPEG encoding
     * @return original source coordinate extent
     * @throws IOException if metadata cannot be decoded
     */
    private static Dimensions dimensions(final byte[] bytes) throws IOException {
        try (final var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            final var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("No JPEG reader");
            }
            final var reader = readers.next();
            try {
                reader.setInput(input);
                return new Dimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Embeds the complete original JPEG at150DPI physical size; only render DPI changes.
     * @param bytes unchanged JPEG encoding
     * @param dimensions original JPEG extent
     * @return owned one-page PDF encoding
     * @throws IOException if PDF construction or serialization fails
     */
    private static byte[] pdf(final byte[] bytes, final Dimensions dimensions) throws IOException {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page =
                    new PDPage(new PDRectangle(dimensions.width() * 72f / 150, dimensions.height() * 72f / 150));
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
     * Records complete source-frame topology and provenance without interpreting reference labels.
     * Typed resource failures remain visible; unexpected I/O or schema failures abort the diagnostic.
     * @param image borrowed rendered page, never modified by this helper
     * @param provider explicit non-owned strategy
     * @param output NEW profile directory
     * @throws IOException if output or an untyped provider failure occurs
     */
    private static void capture(final BufferedImage image, final PdfTableLayoutProvider provider, final Path output)
            throws IOException {
        Files.createDirectory(output);
        final var tables = new ArrayList<String>();
        tables.add("crop\ttable\trows\tcolumns\tleft\ttop\tright\tbottom\tprovenance");
        final var anchors = new ArrayList<String>();
        anchors.add("crop\ttable\trow\tcolumn\trow_span\tcolumn_span\tleft\ttop\tright\tbottom");
        try {
            final var recognized = provider.locate(image);
            for (int index = 0; index < recognized.size(); index++) {
                final var table = recognized.get(index);
                tables.add("0\t" + index + "\t" + table.rows() + "\t" + table.columns() + "\t" + bounds(table.bounds())
                           + "\t" + table.provenance());
                for (final var anchor : table.anchors()) {
                    anchors.add("0\t" + index + "\t" + anchor.row() + "\t" + anchor.column() + "\t" + anchor.rowSpan()
                                + "\t" + anchor.columnSpan() + "\t" + bounds(anchor.bounds()));
                }
            }
            write(output.resolve("outcome.txt"), List.of("OK"));
        } catch (final PartitionException failure) {
            write(output.resolve("outcome.txt"), List.of(failure.code().name()));
        }
        write(output.resolve("tables.tsv"), tables);
        write(output.resolve("anchors.tsv"), anchors);
    }

    /**
     * Encodes an unchanged source-frame rectangle without another coordinate transform.
     * @param box finite positive raster rectangle
     * @return tab-separated bounds
     */
    private static String bounds(final PdfTableLayoutProvider.Box box) {
        return box.left() + "\t" + box.top() + "\t" + box.right() + "\t" + box.bottom();
    }

    /**
     * Saves new numeric/hash/provenance evidence without replacing a prior capture.
     * @param path NEW output file
     * @param rows source-free diagnostics
     * @throws IOException if output cannot be written
     */
    private static void write(final Path path, final List<String> rows) throws IOException {
        Files.write(path, rows, StandardOpenOption.CREATE_NEW);
    }
}
