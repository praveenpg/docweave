package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.ocr.TesseractOcr;
import dev.docweave.ocr.TesseractOptions;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfTableLayoutProvider;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/** Optional published image/HTML/text evaluation; misses remain diagnostics, never candidate-generated oracles. */
class PdfPubTabNetCorpusTest {
    private record Cell(int row, int column, int rowSpan, int columnSpan, String text) {}

    private record Grid(int rows, int columns, List<Cell> cells) {}

    private record RasterPdf(DocumentSource source, int width, int height, byte[] encoded) {}

    /** Independent source-envelope overlap, not glyph-outline or recognition accuracy. */
    private record EnvelopeCoverage(boolean center, boolean full, boolean tolerated, double fraction) {}

    @Test
    void optionalPublishedTextEnvelopeCoverage() throws Exception {
        final String prefix = "docweave.pdf.pubtabnet.geometry";
        final String cache = System.getProperty(prefix + ".cache");
        final String report = System.getProperty(prefix + ".report");
        if (cache == null && report == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(report);
        assertNull(System.getProperty(prefix + ".detectionModel"), "This profile isolates structure geometry");
        final var mapper = JsonMapper.builder().build();
        final var manifest = verifiedManifest(Path.of(cache));
        final var base = PdfOcrOptions.defaults();
        final int dpi = Integer.getInteger(prefix + ".dpi", 300);
        final var limits = new PdfOcrOptions(dpi, base.maxRenderPixels(), base.maxLines(), base.maxCharacters());
        final var records = new ArrayList<Map<String, Object>>();
        int anchors = 0;
        int labeled = 0;
        try (final var layout = PdfLayoutModels.create(prefix, PdfOcrOptions.TableDetection.PROVIDER_LAYOUT)) {
            for (final String line : Files.readAllLines(Path.of(cache).resolve("PubTabNet_Examples.jsonl"))) {
                final var reference = mapper.readTree(line);
                final String name = reference.get("filename").asString();
                assertTrue(name.matches("PMC[0-9]+_[0-9]+_[0-9]+\\.png"));
                final var labels = reference.get("html").get("cells");
                final var expected = grid(
                        "<table>" + tokens(reference.get("html").get("structure").get("tokens")) + "</table>", labels);
                anchors += expected.cells().size();
                final var embedded = pdf(Files.readAllBytes(Path.of(cache).resolve(name)), name);
                try (final var document = Loader.loadPDF(embedded.encoded())) {
                    final var media = document.getPage(0).getMediaBox();
                    final long width = (long) Math.ceil(media.getWidth() * (double) dpi / 72);
                    final long height = (long) Math.ceil(media.getHeight() * (double) dpi / 72);
                    assertTrue(width > 0 && height > 0 && width <= limits.maxRenderPixels() / height,
                            "Geometry raster must fit the existing explicit pixel budget");
                    final var raster = new PDFRenderer(document).renderImageWithDPI(0, dpi, ImageType.RGB);
                    try {
                        final var tables = layout.locate(raster);
                        assertTrue(tables.size() <= 1, "Structure-only model has one table candidate");
                        final var record =
                                new LinkedHashMap<String, Object>(coverage(expected, labels, tables, embedded.width(),
                                        embedded.height(), media.getWidth() * (double) dpi / 72 / embedded.width(),
                                        media.getHeight() * (double) dpi / 72 / embedded.height()));
                        labeled += ((Number) record.get("labeled")).intValue();
                        record.put("input", name)
                        ;
                        record.put("published_anchors", expected.cells().size())
                        ;
                        record.put("emitted_layouts", tables.size())
                        ;
                        record.put("render_width", raster.getWidth())
                        ;
                        record.put("render_height", raster.getHeight())
                        ;
                        records.add(record);
                        System.out.println("PDF text-envelope coverage: " + name + " (" + records.size() + "/20)");
                    } finally {
                        raster.flush();
                    }
                }
            }
        }
        assertEquals(20, records.size());
        assertEquals(1380, anchors);
        assertEquals(1230, labeled, "Every pinned nonempty source box stays in the denominator, including misses");
        CorpusReview.write(Path.of(report),
                Map.of("scope", "all20_published_train_text_envelopes_not_glyph_or_ocr_accuracy", "source_commit",
                        manifest.get("commit").asString(), "render_dpi", dpi, "layout_merge_conflicts",
                        System.getProperty(prefix + ".layoutMergeConflicts", "REJECT_TABLE"), "prefer_ruled",
                        Boolean.getBoolean(prefix + ".preferRuled"), "layout_graph_sha256",
                        System.getProperty(prefix + ".layoutSha256"), "java_version",
                        System.getProperty("java.version"), "runtime_classpath", ValidationProvenance.classpath(),
                        "harness_sha256",
                        hash(Files.readAllBytes(Path.of(
                                "docweave-validation/src/test/java/dev/docweave/validation/PdfPubTabNetCorpusTest.java"))),
                        "records", records));
    }

    @Test
    void envelopeCoverageHasIndependentAnalyticalControls() throws Exception {
        final var predicted = new PdfTableLayoutProvider.Box(20, 20, 100, 60);
        final var full = overlap(predicted, 24, 24, 96, 56, 2, 2);
        assertTrue(full.full());
        assertEquals(1, full.fraction());
        final var clipped = overlap(predicted, 16, 24, 96, 56, 2, 2);
        assertTrue(clipped.center());
        assertFalse(clipped.full());
        assertFalse(clipped.tolerated());
        assertEquals(.95, clipped.fraction(), 1e-12);
        final var nearly = overlap(predicted, 19, 24, 96, 56, 2, 2);
        assertFalse(nearly.full());
        assertTrue(nearly.tolerated());
        assertEquals(76.0 / 77, nearly.fraction(), 1e-12);
        assertEquals(0, overlap(predicted, 101, 24, 110, 56, 2, 2).fraction());
        final var missing = coverage(new Grid(1, 1, List.of(new Cell(0, 0, 1, 1, ""))),
                JsonMapper.builder().build().readTree("[{\"bbox\":[12,12,48,28]}]"), List.of(), 100, 50, 2, 2);
        assertEquals(1, missing.get("labeled"));
        assertEquals(1, missing.get("missing_anchor"));
        assertEquals(0.0, missing.get("label_area_fraction_sum"));
        final var invalid = coverage(new Grid(1, 1, List.of(new Cell(0, 0, 1, 1, ""))),
                JsonMapper.builder().build().readTree("[{\"bbox\":5}]"), List.of(), 100, 50, 2, 2);
        assertEquals(1, invalid.get("invalid_annotations"));
        assertEquals(0, invalid.get("unlabeled"));
        final var inset = new PdfTableLayoutProvider.Box(20.2, 20.2, 99.8, 59.8);
        final var rounded = coverage(new Grid(1, 1, List.of(new Cell(0, 0, 1, 1, ""))),
                JsonMapper.builder().build().readTree("[{\"bbox\":[10,10,50,30]}]"),
                List.of(new PdfTableLayoutProvider.Table(1, 1, inset,
                        List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, inset)), "analytical-rounding")),
                100, 50, 2, 2);
        assertEquals(0, rounded.get("full_inside"));
        assertEquals(1, rounded.get("rounded_crop_full_inside"));
        assertEquals(1.0, rounded.get("rounded_crop_label_area_fraction_sum"));
    }

    /** Measures all valid source boxes; missing layouts/anchors contribute zero coverage rather than exclusions. */
    private static Map<String, Object> coverage(final Grid expected, final JsonNode labels,
            final List<PdfTableLayoutProvider.Table> tables, final int sourceWidth, final int sourceHeight,
            final double sx, final double sy) {
        final var predicted = new HashMap<Cell, PdfTableLayoutProvider.Box>();
        for (final var table : tables) {
            for (final var anchor : table.anchors()) {
                predicted.put(new Cell(anchor.row(), anchor.column(), anchor.rowSpan(), anchor.columnSpan(), ""),
                        anchor.bounds());
            }
        }
        int labeled = 0;
        int invalid = 0;
        int unlabeled = 0;
        int missing = 0;
        final var missingReasons = new LinkedHashMap<String, Integer>();
        int center = 0;
        int full = 0;
        int tolerated = 0;
        int roundedFull = 0;
        double fractions = 0;
        double roundedFractions = 0;
        for (int index = 0; index < expected.cells().size(); index++) {
            final var bbox = labels.get(index).get("bbox");
            if (bbox == null || bbox.isNull() || (bbox.isArray() && bbox.size() == 0)) {
                unlabeled++;
                continue;
            }
            if (!bbox.isArray() || bbox.size() != 4) {
                invalid++;
                continue;
            }
            if (!bbox.get(0).isNumber() || !bbox.get(1).isNumber() || !bbox.get(2).isNumber()
                    || !bbox.get(3).isNumber()) {
                invalid++;
                continue;
            }
            final double left = bbox.get(0).asDouble();
            final double top = bbox.get(1).asDouble();
            final double right = bbox.get(2).asDouble();
            final double bottom = bbox.get(3).asDouble();
            if (!Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(right) || !Double.isFinite(bottom)
                    || left < 0 || top < 0 || right <= left || bottom <= top || right > sourceWidth
                    || bottom > sourceHeight) {
                invalid++;
                continue;
            }
            labeled++;
            final var cell = expected.cells().get(index);
            final var box = predicted.get(new Cell(cell.row(), cell.column(), cell.rowSpan(), cell.columnSpan(), ""));
            if (box == null) {
                missing++;
                missingReasons.merge(missingAnchorReason(cell, tables), 1, Integer::sum);
                continue;
            }
            final var result = overlap(box, left * sx, top * sy, right * sx, bottom * sy, sx, sy);
            center += result.center() ? 1 : 0;
            full += result.full() ? 1 : 0;
            tolerated += result.tolerated() ? 1 : 0;
            fractions += result.fraction();
            final var crop = new PdfTableLayoutProvider.Box(
                    Math.floor(box.left()), Math.floor(box.top()), Math.ceil(box.right()), Math.ceil(box.bottom()));
            final var rounded = overlap(crop, left * sx, top * sy, right * sx, bottom * sy, sx, sy);
            roundedFull += rounded.full() ? 1 : 0;
            roundedFractions += rounded.fraction();
        }
        final var result = new LinkedHashMap<String, Object>(Map.of("labeled", labeled, "invalid_annotations", invalid,
                "unlabeled", unlabeled, "missing_anchor", missing, "center_inside", center, "full_inside", full,
                "full_inside_one_source_pixel", tolerated, "label_area_fraction_sum", fractions,
                "rounded_crop_full_inside", roundedFull, "rounded_crop_label_area_fraction_sum", roundedFractions));
        result.put("missing_anchor_reasons", Map.copyOf(missingReasons));
        return Map.copyOf(result);
    }

    /**
     * Classifies a missing positional/span key without inferring OCR loss or repairing predicted topology.
     * Categories are exclusive in this order; shifted axes can produce span/coverage symptoms too.
     * This diagnostic is restricted to the structure-only profile with at most one predicted table.
     * @param cell published anchor whose full positional/span key is absent
     * @param tables unchanged structure-only model output
     * @return descriptive structural mismatch, not a causal model-error or accuracy label
     */
    private static String missingAnchorReason(final Cell cell, final List<PdfTableLayoutProvider.Table> tables) {
        if (tables.isEmpty()) {
            return "no_table";
        }
        assertEquals(1, tables.size(), "Missing-anchor audit requires a single structure candidate");
        final var table = tables.getFirst();
        if ((long) cell.row() + cell.rowSpan() > table.rows()
                || (long) cell.column() + cell.columnSpan() > table.columns()) {
            return "outside_predicted_grid";
        }
        if (table.anchors().stream().anyMatch(
                    anchor -> anchor.row() == cell.row() && anchor.column() == cell.column())) {
            return "different_span_at_origin";
        }
        if (table.anchors().stream().anyMatch(anchor
                    -> cell.row() >= anchor.row() && cell.row() < (long) anchor.row() + anchor.rowSpan()
                               && cell.column() >= anchor.column()
                               && cell.column() < (long) anchor.column() + anchor.columnSpan())) {
            return "origin_covered_by_other_anchor";
        }
        return "uncovered_origin";
    }

    @Test
    void missingAnchorCategoriesKeepStructuralSymptomsSeparate() {
        final var box = new PdfTableLayoutProvider.Box(0, 0, 100, 100);
        final var merged = List.of(new PdfTableLayoutProvider.Table(
                2, 2, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 2, 2, box)), "analytical"));
        assertEquals("no_table", missingAnchorReason(new Cell(0, 0, 1, 1, ""), List.of()));
        assertEquals("outside_predicted_grid", missingAnchorReason(new Cell(2, 0, 1, 1, ""), merged));
        assertEquals("outside_predicted_grid", missingAnchorReason(new Cell(0, 1, 1, 2, ""), merged));
        assertEquals("different_span_at_origin", missingAnchorReason(new Cell(0, 0, 1, 1, ""), merged));
        assertEquals("origin_covered_by_other_anchor", missingAnchorReason(new Cell(1, 1, 1, 1, ""), merged));
        final var partial = List.of(new PdfTableLayoutProvider.Table(
                2, 2, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "analytical-incomplete"));
        assertEquals("uncovered_origin", missingAnchorReason(new Cell(1, 1, 1, 1, ""), partial));
    }

    /** Computes unmodified label-area coverage; tolerance is separate and never expands the raw intersection. */
    private static EnvelopeCoverage overlap(final PdfTableLayoutProvider.Box box, final double left, final double top,
            final double right, final double bottom, final double toleranceX, final double toleranceY) {
        final double cx = (left + right) / 2;
        final double cy = (top + bottom) / 2;
        final double intersection = Math.max(0, Math.min(right, box.right()) - Math.max(left, box.left()))
                                    * Math.max(0, Math.min(bottom, box.bottom()) - Math.max(top, box.top()));
        return new EnvelopeCoverage(cx >= box.left() && cx <= box.right() && cy >= box.top() && cy <= box.bottom(),
                left >= box.left() && top >= box.top() && right <= box.right() && bottom <= box.bottom(),
                left >= box.left() - toleranceX && top >= box.top() - toleranceY && right <= box.right() + toleranceX
                        && bottom <= box.bottom() + toleranceY,
                intersection / ((right - left) * (bottom - top)));
    }

    /** Verifies the original corpus asset identities before either OCR or geometry-only evaluation. */
    private static JsonNode verifiedManifest(final Path cache) throws Exception {
        final var manifest = JsonMapper.builder().build().readTree(
                Files.readAllBytes(Path.of("benchmarks/review/pdf-pubtabnet/manifest.json")));
        for (final var input : manifest.get("inputs")) {
            final byte[] bytes = Files.readAllBytes(cache.resolve(input.get("filename").asString()));
            assertEquals(input.get("bytes").asLong(), bytes.length);
            assertEquals(input.get("sha256").asString(), hash(bytes));
            final var blob = MessageDigest.getInstance("SHA-1");
            blob.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            assertEquals(input.get("git_blob_sha1").asString(), HexFormat.of().formatHex(blob.digest(bytes)));
        }
        return manifest;
    }

    @Test
    void optionalPublishedExamples() throws Exception {
        final String cache = System.getProperty("docweave.pdf.pubtabnet.cache");
        final String report = System.getProperty("docweave.pdf.pubtabnet.report");
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
        final var manifest = verifiedManifest(Path.of(cache));
        final var pageMode = TesseractOptions.PageSegmentation.valueOf(
                System.getProperty("docweave.pdf.pubtabnet.pageSegmentation", "AUTO"));
        final var cellMode = TesseractOptions.CellSegmentation.valueOf(
                System.getProperty("docweave.pdf.pubtabnet.cellSegmentation", "SINGLE_BLOCK"));
        final var borders = TesseractOptions.CellBorders.valueOf(
                System.getProperty("docweave.pdf.pubtabnet.cellBorders", "RETAIN"));
        final var engine = TesseractOcr.createWithWords(
                engineOptions(Path.of(executable), Path.of(data), pageMode, cellMode, borders));
        final var tableMode = PdfOcrOptions.TableDetection.valueOf(
                System.getProperty("docweave.pdf.pubtabnet.tableDetection", "RULED"));
        final String cellData = System.getProperty("docweave.pdf.pubtabnet.cellTessdata");
        final PdfOcrProvider baseCells;
        final String cellModelHash;
        if (cellData == null) {
            baseCells = null;
            cellModelHash = null;
        } else {
            assertEquals(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS, tableMode,
                    "Separate cellTessdata requires provider-layout cell refinement");
            cellModelHash = hash(Files.readAllBytes(Path.of(cellData).resolve("eng.traineddata")));
            baseCells = TesseractOcr.create(
                    engineOptions(Path.of(executable), Path.of(cellData), pageMode, cellMode, borders));
        }
        final int dpi = Integer.getInteger("docweave.pdf.pubtabnet.dpi", 150);
        final int cellContext = Integer.parseInt(System.getProperty("docweave.pdf.pubtabnet.cellContextPixels", "0"));
        if (cellContext > 0) {
            assertEquals(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS, tableMode,
                    "This context profile requires provider-layout cell refinement");
        }
        final var baseOcr = PdfOcrOptions.defaults();
        final var ocr = new PdfOcrOptions(dpi, baseOcr.maxRenderPixels(), baseOcr.maxLines(), baseOcr.maxCharacters())
                                .withTableDetection(tableMode)
                                .withCellContextPixels(cellContext);
        final int cellScale = Integer.parseInt(System.getProperty("docweave.pdf.pubtabnet.cellScaleFactor", "1"));
        if (cellScale != 1) {
            assertEquals(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS, tableMode,
                    "Magnified CELL recognition requires provider-layout cell refinement");
        }
        final PdfOcrProvider cells =
                cellScale == 1 ? baseCells
                               : PdfOcrProvider.upscaled(baseCells == null ? engine : baseCells, cellScale, ocr);
        final String graphHash = System.getProperty("docweave.pdf.pubtabnet.layoutSha256");
        try (final var layout = PdfLayoutModels.create("docweave.pdf.pubtabnet", tableMode)) {
            final var pdfOptions = PdfOptions.defaults().withTableCellCoordinates(true);
            final var orientation = new PdfCorpusOrientation("docweave.pdf.pubtabnet");
            final var partitioner = orientation.partitioner(pdfOptions, ocr, engine, layout, cells);
            final var processor = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)));
            final var results = new ArrayList<Map<String, Object>>();
            final var names = new HashSet<String>();
            int annotatedCells = 0;
            for (final String line : Files.readAllLines(Path.of(cache).resolve("PubTabNet_Examples.jsonl"))) {
                final var reference = mapper.readTree(line);
                final String name = reference.get("filename").asString();
                assertTrue(name.matches("PMC[0-9]+_[0-9]+_[0-9]+\\.png"));
                assertTrue(names.add(name));
                assertEquals("train", reference.get("split").asString());
                final var labels = reference.get("html").get("cells");
                final var expected = grid(
                        "<table>" + tokens(reference.get("html").get("structure").get("tokens")) + "</table>", labels);
                annotatedCells += expected.cells().size();
                final String expectedText =
                        normalize(String.join(" ", expected.cells().stream().map(Cell::text).toList()));
                final var entry = new LinkedHashMap<String, Object>();
                entry.put("input", name);
                if (layout != null) {
                    entry.put("layout_graph_sha256", graphHash);
                    final var detectionHash = System.getProperty("docweave.pdf.pubtabnet.detectionSha256");
                    if (detectionHash != null) {
                        entry.put("detection_graph_sha256", detectionHash);
                    }
                }
                entry.put("upstream_split", "train");
                entry.put("published_rows", expected.rows());
                entry.put("published_columns", expected.columns());
                entry.put("published_anchors", expected.cells().size());
                entry.put("published_reference_characters", expectedText.codePointCount(0, expectedText.length()));
                orientation.begin();
                try {
                    final var input = pdf(Files.readAllBytes(Path.of(cache).resolve(name)), name);
                    final var result = processor.partition(input.source());
                    final var tables = new ArrayList<Map<String, Object>>();
                    for (final var table :
                            result.elements().stream().filter(e -> e.type() == ElementType.Table).toList()) {
                        if (cellContext > 0) {
                            assertEquals(cellContext, table.metadata().get("pdf_cell_ocr_context_pixels"));
                        }
                        final var observed = grid((String) table.metadata().get("text_as_html"), null);
                        int matches = 0;
                        for (final var cell : observed.cells()) {
                            if (expected.cells().contains(cell)) {
                                matches++;
                            }
                        }
                        final long positions =
                                observed.cells()
                                        .stream()
                                        .filter(actual
                                                -> expected.cells().stream().anyMatch(
                                                        referenceCell -> samePosition(actual, referenceCell)))
                                        .count();
                        final var geometry = centers(expected, labels, table.metadata(), input.width(), input.height());
                        if (tableMode == PdfOcrOptions.TableDetection.RULED && dpi == 300
                                && name.equals("PMC4003957_018_00.png")) {
                            assertEquals(expected.cells().size(), positions, "Published merged topology");
                            assertTrue(geometry.get("labeled") > 0);
                            assertEquals(geometry.get("labeled"), geometry.get("matched"),
                                    "Published text centers inside corresponding cells");
                        }
                        final boolean shape = observed.rows() == expected.rows()
                                              && observed.columns() == expected.columns()
                                              && observed.cells().size() == expected.cells().size();
                        tables.add(Map.of("rows", observed.rows(), "columns", observed.columns(), "anchors",
                                observed.cells().size(), "positional_span_text_matches", matches,
                                "positional_span_matches", positions, "text_center_containment", geometry, "same_shape",
                                shape, "complete_shape_text_match", shape && matches == expected.cells().size()));
                    }
                    if (tableMode == PdfOcrOptions.TableDetection.RULED && dpi == 300
                            && name.equals("PMC4003957_018_00.png")) {
                        assertEquals(1, tables.size(), "One complete published merged table");
                        assertEquals(true, tables.getFirst().get("same_shape"));
                    }
                    entry.put("emitted_tables", tables);
                    final String observedText =
                            normalize(String.join(" ", result.elements()
                                                               .stream()
                                                               .filter(e -> e.type() != ElementType.Image)
                                                               .map(e -> e.text())
                                                               .toList()));
                    entry.put("observed_characters", observedText.codePointCount(0, observedText.length()));
                    final int edits = distance(expectedText, observedText);
                    entry.put("row_major_character_edits", edits < 0 ? "work_limit" : edits);
                    entry.put("token_overlap", overlap(expectedText, observedText));
                    entry.put("source_images",
                            result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
                    assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
                } catch (final PartitionException failure) {
                    assertTrue(List.of(PartitionException.Code.TABLE_LIMIT, PartitionException.Code.OCR_LIMIT)
                                       .contains(failure.code()),
                            failure.code().name());
                    entry.put("typed_failure", failure.code().name());
                }
                orientation.describe(entry);
                results.add(entry);
                System.out.println("PDF table corpus " + tableMode + ": " + name + " (" + results.size() + "/20)");
            }
            assertEquals(20, results.size());
            assertEquals(1380, annotatedCells);
            final var description = new LinkedHashMap<String, Object>();
            description.put("runtime", Runtime.version().toString());
            description.put("commit", manifest.get("commit").asString());
            description.put("scope",
                    "All20 published train examples embedded whole at150DPI; no oracle crop/repair; diagnostic, not general or held-out-model accuracy");
            description.put("render_dpi", dpi);
            description.put("cell_segmentation", cellMode.name());
            description.put("page_segmentation", pageMode.name());
            description.put("cell_borders", borders.name());
            description.put("table_detection", tableMode.name());
            if (Boolean.getBoolean("docweave.pdf.pubtabnet.preferRuled")) {
                description.put("prefer_ruled", true);
            }
            if (System.getProperty("docweave.pdf.pubtabnet.layoutMergeConflicts") != null) {
                description.put(
                        "layout_merge_conflicts", System.getProperty("docweave.pdf.pubtabnet.layoutMergeConflicts"));
            }
            if (cellContext > 0) {
                description.put("cell_context_pixels", cellContext);
            }
            final String pageModelHash = hash(Files.readAllBytes(Path.of(data).resolve("eng.traineddata")));
            description.put("model_sha256", pageModelHash);
            description.put("executable_sha256", hash(Files.readAllBytes(Path.of(executable))));
            if (cells != null) {
                description.put("cell_engine_strategy", "independent_line_provider");
                description.put("cell_model_sha256", cellModelHash == null ? pageModelHash : cellModelHash);
            }
            if (cellScale != 1) {
                description.put("cell_scale_factor", cellScale);
            }
            description.put("results", results);
            Files.write(Path.of(report), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(description),
                    StandardOpenOption.CREATE_NEW);
        }
    }

    /** Builds the same explicit engine policies for PAGE and optional independent CELL model directories. */
    private static TesseractOptions engineOptions(final Path executable, final Path data,
            final TesseractOptions.PageSegmentation pageMode, final TesseractOptions.CellSegmentation cellMode,
            final TesseractOptions.CellBorders borders) {
        final var defaults = TesseractOptions.defaults();
        return new TesseractOptions(executable, data, List.of("eng"), defaults.timeout(), 1, defaults.maxImagePixels(),
                defaults.maxPngBytes(), defaults.maxOutputBytes(), defaults.maxLines(), defaults.maxCharacters())
                .withCellSegmentation(cellMode)
                .withPageSegmentation(pageMode)
                .withCellBorders(borders);
    }

    @Test
    void publishedSpanParsingAndTextMetricsHaveIndependentAnalyticalControls() {
        final var grid = grid("<table><tr><td rowspan='2'>A</td><td>B</td></tr><tr><td>C</td></tr></table>", null);
        assertEquals(new Grid(2, 2,
                             List.of(new Cell(0, 0, 2, 1, "A"), new Cell(0, 1, 1, 1, "B"), new Cell(1, 1, 1, 1, "C"))),
                grid);
        assertEquals(1, distance("αβ", "αγ"));
        assertEquals(1, distance("a😀", "a"));
        assertEquals(2, overlap("A A B", "B A C").get("matched"));
        assertEquals(3, overlap("A A B", "B A C").get("reference"));
        assertEquals(-1, distance("x".repeat(6000), "y".repeat(6000)));
        assertThrows(
                IllegalArgumentException.class, () -> grid("<table><tr><td rowspan='2000'>x</td></tr></table>", null));
    }

    private static boolean samePosition(final Cell left, final Cell right) {
        return left.row() == right.row() && left.column() == right.column() && left.rowSpan() == right.rowSpan()
                && left.columnSpan() == right.columnSpan();
    }

    private static Map<String, Integer> centers(final Grid expected, final JsonNode labels,
            final Map<String, Object> metadata, final int width, final int height) {
        final var extension = (Map<?, ?>)metadata.get("pdf_table_cells");
        assertNotNull(extension);
        int labeled = 0;
        int matched = 0;
        int invalid = 0;
        for (int index = 0; index < expected.cells().size(); index++) {
            final var bbox = labels.get(index).get("bbox");
            if (bbox == null) {
                continue;
            }
            final var reference = expected.cells().get(index);
            final double x = (bbox.get(0).asDouble() + bbox.get(2).asDouble()) / 2;
            final double y = (bbox.get(1).asDouble() + bbox.get(3).asDouble()) / 2;
            if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > width || y < 0 || y > height) {
                invalid++;
                continue;
            }
            labeled++;
            for (final var value : (List<?>) extension.get("cells")) {
                final var cell = (Map<?, ?>)value;
                if (((Number) cell.get("row_index")).intValue() != reference.row()
                        || ((Number) cell.get("column_index")).intValue() != reference.column()
                        || ((Number) cell.get("row_span")).intValue() != reference.rowSpan()
                        || ((Number) cell.get("column_span")).intValue() != reference.columnSpan()) {
                    continue;
                }
                final var coordinates = (Map<?, ?>)cell.get("coordinates");
                final double scaleX = width / ((Number) coordinates.get("layout_width")).doubleValue();
                final double scaleY = height / ((Number) coordinates.get("layout_height")).doubleValue();
                double left = Double.POSITIVE_INFINITY;
                double top = Double.POSITIVE_INFINITY;
                double right = Double.NEGATIVE_INFINITY;
                double bottom = Double.NEGATIVE_INFINITY;
                for (final var point : (List<?>) coordinates.get("points")) {
                    final var pair = (List<?>) point;
                    final double px = ((Number) pair.get(0)).doubleValue() * scaleX;
                    final double py = ((Number) pair.get(1)).doubleValue() * scaleY;
                    left = Math.min(left, px);
                    right = Math.max(right, px);
                    top = Math.min(top, py);
                    bottom = Math.max(bottom, py);
                }
                if (x >= left - 1 && x <= right + 1 && y >= top - 1 && y <= bottom + 1) {
                    matched++;
                }
                break;
            }
        }
        return Map.of("labeled", labeled, "matched", matched, "invalid_annotations", invalid);
    }

    private static Grid grid(final String html, final JsonNode labels) {
        final var rows = Jsoup.parseBodyFragment(html).select(
                "table > thead > tr, table > tbody > tr, table > tfoot > tr, table > tr");
        if (rows.isEmpty() || rows.size() > 1000) {
            throw new IllegalArgumentException("Unsupported annotation row count");
        }
        final var occupied = new HashMap<Integer, Integer>();
        final var cells = new ArrayList<Cell>();
        int columns = 0;
        for (int row = 0; row < rows.size(); row++) {
            int column = 0;
            for (final var element : rows.get(row).children()) {
                if (!element.normalName().equals("td") && !element.normalName().equals("th")) {
                    continue;
                }
                while (occupied.getOrDefault(column, 0) > row) {
                    column++;
                }
                final int rowSpan = element.hasAttr("rowspan") ? Integer.parseInt(element.attr("rowspan")) : 1;
                final int columnSpan = element.hasAttr("colspan") ? Integer.parseInt(element.attr("colspan")) : 1;
                if (rowSpan < 1 || columnSpan < 1 || row + rowSpan > rows.size() || column + columnSpan > 1000) {
                    throw new IllegalArgumentException("Invalid published cell span");
                }
                for (int index = column; index < column + columnSpan; index++) {
                    if (occupied.getOrDefault(index, 0) > row) {
                        throw new IllegalArgumentException("Overlapping published cell span");
                    }
                    occupied.put(index, row + rowSpan);
                }
                final String text =
                        labels == null ? element.text()
                                       : Jsoup.parseBodyFragment(tokens(labels.get(cells.size()).get("tokens"))).text();
                cells.add(new Cell(row, column, rowSpan, columnSpan, normalize(text)));
                column += columnSpan;
                columns = Math.max(columns, column);
            }
        }
        if (labels != null && labels.size() != cells.size()) {
            throw new IllegalArgumentException("Published cells/structure disagree");
        }
        return new Grid(rows.size(), columns, List.copyOf(cells));
    }

    private static String tokens(final JsonNode values) {
        final var text = new StringBuilder();
        for (final var value : values) {
            text.append(value.asString());
        }
        return text.toString();
    }

    private static String normalize(final String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private static Map<String, Integer> overlap(final String reference, final String observed) {
        final var expected = counts(reference);
        final var actual = counts(observed);
        int matched = 0;
        for (final var entry : actual.entrySet()) {
            matched += Math.min(entry.getValue(), expected.getOrDefault(entry.getKey(), 0));
        }
        return Map.of("matched", matched, "reference", expected.values().stream().mapToInt(Integer::intValue).sum(),
                "observed", actual.values().stream().mapToInt(Integer::intValue).sum());
    }

    private static Map<String, Integer> counts(final String text) {
        final var result = new HashMap<String, Integer>();
        if (!text.isBlank()) {
            for (final String word : text.split(" ")) {
                result.merge(word, 1, Integer::sum);
            }
        }
        return result;
    }

    private static int distance(final String reference, final String observed) {
        final var left = reference.codePoints().toArray();
        final var right = observed.codePoints().toArray();
        if ((long) left.length * right.length > 25_000_000) {
            return -1;
        }
        var previous = java.util.stream.IntStream.rangeClosed(0, right.length).toArray();
        var current = new int[right.length + 1];
        for (int row = 1; row <= left.length; row++) {
            current[0] = row;
            for (int column = 1; column <= right.length; column++) {
                current[column] = Math.min(Math.min(previous[column] + 1, current[column - 1] + 1),
                        previous[column - 1] + (left[row - 1] == right[column - 1] ? 0 : 1));
            }
            final var old = previous;
            previous = current;
            current = old;
        }
        return previous[right.length];
    }

    private static RasterPdf pdf(final byte[] png, final String filename) throws Exception {
        final var image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(image.getWidth() * 72f / 150, image.getHeight() * 72f / 150));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, page.getMediaBox().getWidth(),
                        page.getMediaBox().getHeight());
            }
            document.save(output);
            final byte[] encoded = output.toByteArray();
            return new RasterPdf(
                    DocumentSource.bytes(encoded, filename + ".pdf"), image.getWidth(), image.getHeight(), encoded);
        } finally {
            image.flush();
        }
    }

    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
