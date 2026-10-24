package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.layout.OnnxTableLayout;
import io.docweave.layout.OnnxTableOptions;
import io.docweave.ocr.TesseractOcr;
import io.docweave.ocr.TesseractOptions;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opt-in real OCR capture on pinned complete PDF pages, without reference CSV or ROI access.
 * Local output contains document text and must not be committed or distributed as evidence.
 * No engine, model, source document or annotation is downloaded by this launcher.
 */
public final class CaptureBusinessCellText {
    /** Prevents launcher construction. */
    private CaptureBusinessCellText() {
    }

    /**
     * Captures fixed PAGE-word and independent CELL policies using production public factories.
     * @param args cache, four-column case TSV, NEW output directory, executable, tessdata directory;
     *             optionally detector path/hash and structure path/hash for explicit model-only layout
     * @throws Exception if identities, resources or unexpected extraction failures prevent capture
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 5 && args.length != 9) {
            throw new IllegalArgumentException(
                    "Expected cache, cases TSV, NEW output, executable, tessdata; optionally detector/hash, structure/hash");
        }
        if (args.length == 5) {
            capture(args, null);
        } else {
            try (final var model = OnnxTableLayout.create(modelOptions(Path.of(args[5]), args[6], false),
                         modelOptions(Path.of(args[7]), args[8], true))) {
                capture(args, model);
            }
        }
    }

    /**
     * Creates fixed four-thread model policies without deriving settings from reference labels.
     * @param path explicitly supplied trusted local graph
     * @param hash expected graph SHA256
     * @param structure whether the explicit higher-score merge policy applies
     * @return immutable options with otherwise historical model defaults
     */
    private static OnnxTableOptions modelOptions(final Path path, final String hash, final boolean structure) {
        final var defaults = OnnxTableOptions.defaults(path, hash);
        return new OnnxTableOptions(path, hash, defaults.timeout(), defaults.maxConcurrentCalls(),
                defaults.maxModelBytes(), defaults.maxInputPixels(), defaults.maxQueries(), defaults.tableLimits(),
                defaults.minimumScore(), 4,
                structure ? OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE
                          : OnnxTableOptions.MergeConflicts.REJECT_TABLE);
    }

    /**
     * Runs the same rasterization/OCR/output pipeline for the selected layout dependency.
     * @param args validated launcher arguments, never reference annotations
     * @param model borrowed managed model, or null for the original compact rule-only control
     * @throws Exception if identities, resources or unexpected extraction failures prevent capture
     */
    private static void capture(final String[] args, final PdfTableLayoutProvider model) throws Exception {
        final var output = Files.createDirectory(Path.of(args[2]));
        final var defaults = TesseractOptions.defaults();
        final var executable = Path.of(args[3]);
        final var tessdata = Path.of(args[4]);
        final var engineOptions = new TesseractOptions(executable, tessdata, List.of("eng"), defaults.timeout(),
                defaults.maxConcurrentProcesses(), defaults.maxImagePixels(), defaults.maxPngBytes(),
                defaults.maxOutputBytes(), defaults.maxLines(), defaults.maxCharacters())
                                          .withCellSegmentation(TesseractOptions.CellSegmentation.SINGLE_BLOCK);
        final var engine = TesseractOcr.createWithWords(engineOptions);
        write(output.resolve("runtime.json"),
                Map.of("java", System.getProperty("java.runtime.version"), "executable_sha256",
                        sha256(Files.readAllBytes(executable)), "english_model_sha256",
                        sha256(Files.readAllBytes(tessdata.resolve("eng.traineddata")))));
        for (final var line : Files.readAllLines(Path.of(args[1]))) {
            final var pin = line.split("\t");
            if (pin.length != 4 || !pin[0].matches("[a-z-]+") || !pin[1].matches("[A-Za-z0-9_.-]+\\.pdf")
                    || !pin[3].matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Unexpected source identity");
            }
            final var source = Files.readAllBytes(Path.of(args[0]).resolve(pin[1]));
            if (!sha256(source).equals(pin[3])) {
                throw new IllegalArgumentException("PDF identity mismatch");
            }
            final var directory = Files.createDirectory(output.resolve(pin[0]));
            final byte[] derivative;
            try (final var document = Loader.loadPDF(source)) {
                final int index = Integer.parseInt(pin[2]) - 1;
                final var page = document.getPage(index);
                final var box = page.getCropBox();
                if (page.getRotation() % 90 != 0 || page.getUserUnit() != 1 || box.getLowerLeftX() != 0
                        || box.getLowerLeftY() != 0) {
                    throw new IllegalArgumentException("Evaluation requires a quarter-turn origin-zero unit crop");
                }
                // PDFRenderer applies declared page rotation; preserve its displayed frame for ROI comparison.
                final float pageWidth = page.getRotation() % 180 == 0 ? box.getWidth() : box.getHeight();
                final float pageHeight = page.getRotation() % 180 == 0 ? box.getHeight() : box.getWidth();
                final long width = Math.max(1, (long) Math.floor(pageWidth * 150 / 72.0));
                final long height = Math.max(1, (long) Math.floor(pageHeight * 150 / 72.0));
                if (width * height > 16_000_000) {
                    throw new IllegalArgumentException("Selected page exceeds frozen raster budget");
                }
                final var image = new PDFRenderer(document).renderImageWithDPI(index, 150, ImageType.RGB);
                try (final var rasterPdf = new PDDocument(); final var bytes = new ByteArrayOutputStream()) {
                    final var rasterPage = new PDPage(new PDRectangle(pageWidth, pageHeight));
                    rasterPdf.addPage(rasterPage);
                    try (final var content = new PDPageContentStream(rasterPdf, rasterPage)) {
                        content.drawImage(
                                LosslessFactory.createFromImage(rasterPdf, image), 0, 0, pageWidth, pageHeight);
                    }
                    rasterPdf.save(bytes);
                    derivative = bytes.toByteArray();
                    write(directory.resolve("source.json"),
                            Map.of("source_page", index + 1, "pdf_sha256", pin[3], "source_rotation",
                                    page.getRotation(), "page_width", pageWidth, "page_height", pageHeight,
                                    "render_width", image.getWidth(), "render_height", image.getHeight(), "dpi", 150));
                } finally {
                    image.flush();
                }
            }
            for (final boolean cells : List.of(false, true)) {
                final var pageRasterHashes = new ArrayList<String>();
                final var pageCalls = new AtomicInteger();
                final var cellCalls = new AtomicInteger();
                final var layoutCalls = new AtomicInteger();
                final var layoutTables = new AtomicInteger();
                final PdfWordOcrProvider words = (image, kind) -> {
                    pageCalls.incrementAndGet();
                    pageRasterHashes.add(rasterSha256(image));
                    return engine.recognizeWords(image, kind);
                };
                final PdfOcrProvider cellEngine = image -> {
                    cellCalls.incrementAndGet();
                    return engine.recognize(image, PdfOcrProvider.InputKind.TABLE_CELL);
                };
                final var policy = new PdfOcrOptions(150, 16_000_000, 10_000, 1_000_000,
                        PdfOcrOptions.Selection.TEXTLESS_PAGES, 64,
                        cells ? PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS
                              : PdfOcrOptions.TableDetection.PROVIDER_LAYOUT,
                        1024, PdfOcrOptions.ReadingOrder.SOURCE_ORDER, 0);
                final var rules = PdfTableLayoutProvider.ruled(
                        TableLimits.defaults(), policy, PdfTableLayoutProvider.RuledProfile.COMPACT);
                final PdfTableLayoutProvider layout = image -> {
                    layoutCalls.incrementAndGet();
                    final var located = model == null ? rules.locate(image) : model.locate(image);
                    layoutTables.addAndGet(located.size());
                    return located;
                };
                final var nativePolicy = PdfOptions.defaults().withTableCellCoordinates(true);
                final var pdf =
                        cells ? PdfPartitioner.createWithCellOcr(nativePolicy, policy, words, layout, cellEngine)
                              : PdfPartitioner.create(nativePolicy, policy, words, layout);
                final var processor = DocWeave.create(PartitionerRegistry.builder().register(pdf).build());
                final var report = new LinkedHashMap<String, Object>();
                final var tables = new ArrayList<Map<String, Object>>();
                try {
                    final var result = processor.partition(DocumentSource.bytes(derivative, "raster.pdf"));
                    for (final var element : result.elements()) {
                        if (element.type() == ElementType.Table) {
                            tables.add(element.metadata());
                        }
                    }
                    report.put("outcome", "SUCCESS");
                    report.put("element_count", result.elements().size());
                } catch (final PartitionException failure) {
                    // Preserve typed failures in the denominator; unexpected I/O aborts the launcher.
                    report.put("outcome", failure.code().name());
                }
                report.put("layout_strategy", model == null ? "compact_rules" : "onnx_detection_structure");
                report.put("tables", tables);
                report.put("page_calls", pageCalls.get());
                report.put("page_raster_sha256", pageRasterHashes);
                report.put("cell_calls", cellCalls.get());
                report.put("layout_calls", layoutCalls.get());
                report.put("layout_tables", layoutTables.get());
                write(directory.resolve(cells ? "cells.json" : "page.json"), report);
                System.out.println(pin[0] + ": " + (cells ? "CELL" : "PAGE") + " " + report.get("outcome") + ", tables="
                                   + tables.size() + ", page_calls=" + pageCalls + ", cell_calls=" + cellCalls);
            }
        }
    }

    /**
     * Hashes borrowed rendered pixels in row-major ARGB order without retaining or modifying them.
     * @param image actual PAGE input seen by the production OCR strategy
     * @return pixel SHA256, with dimensions recorded separately in source geometry
     * @throws java.io.IOException if digest setup or interruption prevents capture
     */
    private static String rasterSha256(final BufferedImage image) throws java.io.IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final java.security.NoSuchAlgorithmException failure) {
            throw new java.io.IOException("Missing required SHA-256", failure);
        }
        final var row = new byte[image.getWidth() * 4];
        for (int y = 0; y < image.getHeight(); y++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("Raster hashing interrupted");
            }
            for (int x = 0; x < image.getWidth(); x++) {
                final int pixel = image.getRGB(x, y);
                for (int component = 0; component < 4; component++) {
                    row[x * 4 + component] = (byte) (pixel >>> (24 - component * 8));
                }
            }
            digest.update(row);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Computes a byte identity without including source content in diagnostics.
     * @param bytes owned encoding
     * @return lowercase SHA256 identity
     * @throws Exception if the mandatory digest is unavailable
     */
    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Writes fresh local JSON, which may contain recognized document text.
     * @param path new output file
     * @param value JSON-compatible report
     * @throws Exception if serialization or output fails
     */
    private static void write(final Path path, final Object value) throws Exception {
        Files.writeString(
                path, JsonMapper.builder().build().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
