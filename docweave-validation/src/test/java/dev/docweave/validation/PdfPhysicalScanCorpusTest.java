package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.ocr.TesseractOcr;
import dev.docweave.ocr.TesseractOptions;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfOptions;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.*;

/** Optional original-scan structural evaluation; annotations do not supply an OCR text oracle. */
class PdfPhysicalScanCorpusTest {
    @Test
    void optionalOriginalScansUsePublishedStructuralAnnotations() throws Exception {
        final String cache = System.getProperty("docweave.pdf.scans.cache");
        final String report = System.getProperty("docweave.pdf.scans.report");
        if (cache == null && report == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(report);
        final String executable = System.getProperty("docweave.tesseract.executable");
        final String data = System.getProperty("docweave.tesseract.tessdata");
        assertNotNull(executable);
        assertNotNull(data);
        final var mapper = JsonMapper.builder().build();
        final String coverage = System.getProperty("docweave.pdf.scans.coverage", "BASELINE");
        assertTrue(List.of("BASELINE", "ALL_PUBLISHED_SAMPLES").contains(coverage), "Unknown scan coverage");
        final boolean extended = coverage.equals("ALL_PUBLISHED_SAMPLES");
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(
                "benchmarks/review/pdf-physical-scans/" + (extended ? "extended-manifest.json" : "manifest.json"))));
        final var names = extended ? List.of("cTDaR_s001", "cTDaR_s002", "cTDaR_s003", "cTDaR_s004", "cTDaR_s101",
                                             "cTDaR_s102", "cTDaR_s103", "cTDaR_s104", "cTDaR_t00040")
                                   : List.of("cTDaR_s003", "cTDaR_s004", "cTDaR_s101", "cTDaR_t00040");
        for (final var input : manifest.get("inputs")) {
            final byte[] bytes = Files.readAllBytes(Path.of(cache).resolve(input.get("filename").asString()));
            assertEquals(input.get("bytes").asLong(), bytes.length);
            assertEquals(input.get("sha256").asString(), hash(bytes));
            final var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            assertEquals(input.get("git_blob_sha1").asString(), HexFormat.of().formatHex(digest.digest(bytes)));
        }
        final var base = TesseractOptions.defaults();
        final String languageSelection = System.getProperty("docweave.pdf.scans.languages");
        final var languages = languageSelection == null
                                      ? List.of("eng")
                                      : Arrays.stream(languageSelection.split(",", -1)).map(String::strip).toList();
        final var engine = TesseractOcr.createWithWords(new TesseractOptions(Path.of(executable), Path.of(data),
                languages, base.timeout(), 1, base.maxImagePixels(), base.maxPngBytes(), base.maxOutputBytes(),
                base.maxLines(), base.maxCharacters())
                        .withCellSegmentation(TesseractOptions.CellSegmentation.SINGLE_BLOCK));
        final var languageHashes = new LinkedHashMap<String, String>();
        for (final var language : languages) {
            languageHashes.put(language, hash(Files.readAllBytes(Path.of(data).resolve(language + ".traineddata"))));
        }
        final var tableMode =
                PdfOcrOptions.TableDetection.valueOf(System.getProperty("docweave.pdf.scans.tableDetection", "RULED"));
        final var defaults = PdfOcrOptions.defaults();
        final int dpi = Integer.getInteger("docweave.pdf.scans.dpi", defaults.dpi());
        final var ocr =
                new PdfOcrOptions(dpi, defaults.maxRenderPixels(), defaults.maxLines(), defaults.maxCharacters())
                        .withTableDetection(tableMode);
        try (final var layout = PdfLayoutModels.create("docweave.pdf.scans", tableMode)) {
            final var nativeOptions = PdfOptions.defaults().withTableCellCoordinates(true);
            final var orientation = new PdfCorpusOrientation("docweave.pdf.scans");
            final var partitioner = orientation.partitioner(nativeOptions, ocr, engine, layout);
            final var processor = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)));
            final var results = new ArrayList<Map<String, Object>>();
            for (final String name : names) {
                final var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                final var xml = factory.newDocumentBuilder().parse(Path.of(cache).resolve(name + ".xml").toFile());
                final var annotated = new ArrayList<Map<String, Object>>();
                final var sets = new ArrayList<java.util.Set<String>>();
                final var envelopes = new ArrayList<double[]>();
                final var tables = xml.getElementsByTagName("table");
                for (int table = 0; table < tables.getLength(); table++) {
                    final var sourceTable = (Element) tables.item(table);
                    if (extended) {
                        envelopes.add(sourceEnvelope(sourceTable));
                    }
                    final var cells = sourceTable.getElementsByTagName("cell");
                    final var anchors = new HashSet<String>();
                    int merged = 0;
                    int rows = 0;
                    int columns = 0;
                    int duplicate = 0;
                    for (int index = 0; index < cells.getLength(); index++) {
                        final var cell = (Element) cells.item(index);
                        final int row = Integer.parseInt(cell.getAttribute("start-row"));
                        final int column = Integer.parseInt(cell.getAttribute("start-col"));
                        final int endRow =
                                cell.hasAttribute("end-row") ? Integer.parseInt(cell.getAttribute("end-row")) : row;
                        final int endColumn =
                                cell.hasAttribute("end-col") ? Integer.parseInt(cell.getAttribute("end-col")) : column;
                        assertTrue(endRow >= row && endColumn >= column);
                        final int rowSpan = endRow - row + 1;
                        final int columnSpan = endColumn - column + 1;
                        if (rowSpan > 1 || columnSpan > 1) {
                            merged++;
                        }
                        if (!anchors.add(key(row, column, rowSpan, columnSpan))) {
                            duplicate++;
                        }
                        rows = Math.max(rows, endRow + 1);
                        columns = Math.max(columns, endColumn + 1);
                    }
                    annotated.add(Map.of("rows", rows, "columns", columns, "cell_records", cells.getLength(),
                            "unique_anchors", anchors.size(), "merged_records", merged, "duplicate_anchor_records",
                            duplicate));
                    sets.add(java.util.Set.copyOf(anchors));
                }
                final var entry = new LinkedHashMap<String, Object>();
                entry.put("input", name);
                if (layout != null) {
                    entry.put("layout_graph_sha256", System.getProperty("docweave.pdf.scans.layoutSha256"));
                    final var detectionHash = System.getProperty("docweave.pdf.scans.detectionSha256");
                    if (detectionHash != null) {
                        entry.put("detection_graph_sha256", detectionHash);
                    }
                    if ("YOLOX".equals(System.getProperty("docweave.pdf.scans.detectionFamily"))) {
                        entry.put("detection_family", "YOLOX");
                    }
                }
                entry.put("published_structure", annotated);
                orientation.begin();
                try {
                    final var result =
                            processor.partition(pdf(Files.readAllBytes(Path.of(cache).resolve(name + ".jpg"))));
                    final var emitted = new ArrayList<Map<String, Object>>();
                    for (final var table :
                            result.elements().stream().filter(e -> e.type() == ElementType.Table).toList()) {
                    final var extension = (Map<?, ?>)table.metadata().get("pdf_table_cells");
                    final var anchors = new HashSet<String>();
                    for (final var value : (List<?>) extension.get("cells")) {
                        final var cell = (Map<?, ?>)value;
                        anchors.add(key(number(cell, "row_index"), number(cell, "column_index"),
                                number(cell, "row_span"), number(cell, "column_span")));
                    }
                    int best = 0;
                    for (final var expected : sets) {
                        final var common = new HashSet<>(anchors);
                        common.retainAll(expected);
                        best = Math.max(best, common.size());
                    }
                    final var prediction = new LinkedHashMap<String, Object>();
                    prediction.put("rows", extension.get("row_count"));
                    prediction.put("columns", extension.get("column_count"));
                    prediction.put("anchors", anchors.size());
                    prediction.put("best_anchor_position_span_matches", best);
                    if (extended) {
                        final var comparisons = new ArrayList<Map<String, Object>>();
                        final var envelope = predictedEnvelope((Map<?, ?>) table.metadata().get("coordinates"));
                        for (int reference = 0; reference < sets.size(); reference++) {
                            final var expected = sets.get(reference);
                            final var common = new HashSet<>(anchors);
                            common.retainAll(expected);
                            final var shape = annotated.get(reference);
                            comparisons.add(Map.of("reference_index", reference, "table_envelope_iou",
                                    iou(envelope, envelopes.get(reference)), "anchor_position_span_matches",
                                    common.size(), "same_dimensions",
                                    number(extension, "row_count") == number(shape, "rows")
                                            && number(extension, "column_count") == number(shape, "columns"),
                                    "anchor_set_equal", anchors.equals(expected), "published_anchor_subset",
                                    anchors.containsAll(expected)));
                        }
                        prediction.put("reference_comparisons", comparisons);
                    }
                    emitted.add(prediction);
                    }
                    entry.put("emitted_tables", emitted);
                    entry.put("ordinary_ocr_lines",
                            result.elements()
                                    .stream()
                                    .filter(e
                                            -> e.type() != ElementType.Table
                                                       && "ocr".equals(e.metadata().get("pdf_text_method")))
                                    .count());
                    entry.put("source_images",
                            result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
                    assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
                } catch (final PartitionException failure) {
                    entry.put("typed_failure", failure.code().name());
                    // A noisy source may exceed explicit table/run limits; retain that diagnostic, never fabricate a
                    // match.
                    assertTrue(List.of(PartitionException.Code.TABLE_LIMIT, PartitionException.Code.OCR_LIMIT)
                                       .contains(failure.code()),
                            failure.code().name());
                }
                orientation.describe(entry);
                results.add(entry);
                System.out.println("PDF scan corpus " + tableMode + ": " + name + " (" + results.size() + "/"
                                   + names.size() + ")");
            }
            final var description = new LinkedHashMap<String, Object>(Map.of("runtime", Runtime.version().toString(),
                    "scope",
                    "Original cTDaR raster embedded whole at150DPI; structural diagnostics, no OCR transcription oracle",
                    "render_dpi", dpi, "coverage", coverage, "table_detection", tableMode.name(), "commit",
                    manifest.get("commit").asString(), "model_sha256", languageHashes.get(languages.getFirst()),
                    "executable_sha256", hash(Files.readAllBytes(Path.of(executable))), "results", results));
            if (languageSelection != null) {
                description.put("languages", languages);
                description.put("language_model_sha256", languageHashes);
            }
            Files.write(Path.of(report), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(description),
                    StandardOpenOption.CREATE_NEW);
        }
    }

    /**
     * Embeds original JPEG bytes without source-region crops or geometric deskew, targeting original pixels at150DPI.
     */
    private static DocumentSource pdf(final byte[] jpeg) throws Exception {
        final var image = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertNotNull(image);
        final int width = image.getWidth();
        final int height = image.getHeight();
        image.flush();
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(width * 72f / 150, height * 72f / 150));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(JPEGFactory.createFromStream(document, new ByteArrayInputStream(jpeg)), 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "physical-scan.pdf");
        }
    }

    /** Reads a published table polygon's enclosing source-pixel rectangle without deskew/annotation repair. */
    private static double[] sourceEnvelope(final Element table) {
        final var coordinates = (Element) table.getElementsByTagName("Coords").item(0);
        assertNotNull(coordinates);
        final double[] box = {
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (final var point : coordinates.getAttribute("points").strip().split("\\s+")) {
            final var pair = point.split(",");
            assertEquals(2, pair.length);
            final double x = Double.parseDouble(pair[0]);
            final double y = Double.parseDouble(pair[1]);
            assertTrue(Double.isFinite(x) && Double.isFinite(y));
            box[0] = Math.min(box[0], x);
            box[1] = Math.min(box[1], y);
            box[2] = Math.max(box[2], x);
            box[3] = Math.max(box[3], y);
        }
        assertTrue(box[2] > box[0] && box[3] > box[1]);
        return box;
    }

    /** Restores displayed PDF units to source pixels from the fixed150DPI whole-image embedding. */
    private static double[] predictedEnvelope(final Map<?, ?> coordinates) {
        final var points = (List<?>) coordinates.get("points");
        final var first = (List<?>) points.getFirst();
        final var third = (List<?>) points.get(2);
        return new double[] {((Number) first.get(0)).doubleValue() * 150 / 72,
                ((Number) first.get(1)).doubleValue() * 150 / 72, ((Number) third.get(0)).doubleValue() * 150 / 72,
                ((Number) third.get(1)).doubleValue() * 150 / 72};
    }

    /** Axis-aligned table-envelope IoU only; not polygon/cell/text accuracy or a probability. */
    private static double iou(final double[] first, final double[] second) {
        final double overlap = Math.max(0, Math.min(first[2], second[2]) - Math.max(first[0], second[0]))
                               * Math.max(0, Math.min(first[3], second[3]) - Math.max(first[1], second[1]));
        final double area =
                (first[2] - first[0]) * (first[3] - first[1]) + (second[2] - second[0]) * (second[3] - second[1]);
        return overlap / (area - overlap);
    }

    /** Extracts a bounded numeric metadata field. */
    private static int number(final Map<?, ?> value, final String key) {
        return ((Number) value.get(key)).intValue();
    }

    /** Forms an exact positional span key; no geometric warping or row/column alignment correction. */
    private static String key(final int row, final int column, final int rowSpan, final int columnSpan) {
        return row + ":" + column + ":" + rowSpan + ":" + columnSpan;
    }

    /** Records byte identity without redistributing source scans or text. */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
