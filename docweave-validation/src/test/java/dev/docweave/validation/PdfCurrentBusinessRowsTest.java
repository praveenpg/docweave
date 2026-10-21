package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.layout.OnnxTableLayout;
import dev.docweave.layout.OnnxTableOptions;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionOptions;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfNativeLayoutOptions;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in current-business diagnostics against issuer-authored HTML row content.
 * HTML presentation columns/spans are deliberately not treated as PDF topology labels.
 */
class PdfCurrentBusinessRowsTest {
    /** Creates isolated publisher-reference and capture controls. */
    PdfCurrentBusinessRowsTest() {
    }

    /**
     * Whole-document outcome without partial results or source text copies on typed failure.
     * @param outcome SUCCESS or the explicit partition failure code
     * @param elements complete immutable output, empty on failure
     * @param warnings content-free warning codes
     */
    private record Capture(String outcome, List<Element> elements, List<String> warnings) {}

    /**
     * Exact nonblank row-position comparison; no shifting, fuzzy matching or semantic alignment.
     * @param expected reference row count
     * @param observed emitted row count
     * @param exact matches at the same original nonblank row index
     */
    private record Rows(int expected, int observed, int exact) {}

    /**
     * One independently selected original-page region; never supplied to extraction.
     * @param page positive original page number
     * @param bounds finite nonnegative left/top/right/bottom with positive area
     */
    private record Region(int page, List<Double> bounds) {
        /** Validates and snapshots the fixed source frame. */
        Region {
            bounds = List.copyOf(bounds);
            if (page < 1 || bounds.size() != 4
                    || bounds.stream().anyMatch(value -> !Double.isFinite(value) || value < 0)
                    || bounds.get(0) >= bounds.get(2) || bounds.get(1) >= bounds.get(3)) {
                throw new IllegalArgumentException("Invalid original-page region");
            }
        }
    }

    /**
     * Complete page-fragment association, including unmatched original regions.
     * @param observed concatenated emitted rows in fixed page order, without boundary repair
     * @param regions content-free per-region observations
     * @param associated number of regions with a qualifying table
     */
    private record Fragments(List<String> observed, List<Map<String, Object>> regions, int associated) {}

    /** Geometry fixes order and stable ties independently of matching reference text. */
    @Test
    void multiplePageAssociationRetainsSourceOrderAndNeverSelectsByText() {
        final var first = new Region(2, List.of(0.0, 0.0, 100.0, 100.0));
        final var second = new Region(3, first.bounds());
        final var output =
                fragments(List.of(fragmentTable(3, "B", 0), fragmentTable(2, "A", 0)), List.of(first, second));
        assertEquals(List.of("A", "B"), output.observed());
        assertEquals(2, output.associated());
        assertEquals(List.of(2, 3), output.regions().stream().map(row -> row.get("source_page")).toList());
        final var tied = fragments(List.of(fragmentTable(2, "Wrong", 0), fragmentTable(2, "A", 0)), List.of(first));
        assertEquals(List.of("Wrong"), tied.observed());
        final var outside = fragments(List.of(fragmentTable(2, "A", 200)), List.of(first));
        assertEquals(0, outside.associated());
        assertTrue(outside.observed().isEmpty());
    }

    /**
     * Partial captures and repeated boundary rows remain visible and cannot satisfy a complete statement.
     * @throws Exception if owned protocol decoding fails
     */
    @Test
    void missingFragmentsAndRepeatedRowsCannotDisappearFromScores() throws Exception {
        final var selected = multiplePageSelection();
        final var partial = multiplePages(selected, List.of("A"),
                new Capture("SUCCESS", List.of(fragmentTable(2, "A", 0)), List.of()), "owned", 3);
        assertEquals(1, partial.get("exact_ordinal_rows"));
        assertEquals(1, partial.get("associated_regions"));
        assertEquals(false, partial.get("complete_row_content"));
        assertEquals(2, ((List<?>) partial.get("regions")).size());
        final var repeated = multiplePages(selected, List.of("A"),
                new Capture("SUCCESS", List.of(fragmentTable(2, "A", 0), fragmentTable(3, "A", 0)), List.of()), "owned",
                3);
        assertEquals(2, repeated.get("observed_content_rows"));
        assertEquals(1, repeated.get("extra_row_positions"));
        assertEquals(false, repeated.get("complete_row_content"));
    }

    /**
     * Whole-document typed failure keeps every original page and the complete reference denominator.
     * @throws Exception if owned protocol decoding fails
     */
    @Test
    void failedMultiplePageCaptureRetainsEveryReferenceAndRegion() throws Exception {
        final var result = multiplePages(multiplePageSelection(), List.of("A", "B"),
                new Capture("TABLE_LIMIT", List.of(), List.of()), "owned", 3);
        assertEquals("TABLE_LIMIT", result.get("outcome"));
        assertEquals(2, result.get("reference_content_rows"));
        assertEquals(2, result.get("missing_row_positions"));
        assertEquals(0, result.get("observed_content_rows"));
        assertEquals(0, result.get("associated_regions"));
        assertEquals(List.of(2, 3), result.get("source_pages"));
        assertEquals(false, result.get("complete_row_content"));
        assertThrows(AssertionError.class,
                ()
                        -> multiplePages(multiplePageSelection(), List.of("A"),
                                new Capture("SUCCESS", List.of(), List.of()), "owned", 2));
    }

    /** Invalid, repeated and reordered page regions fail rather than changing the scoring population. */
    @Test
    void invalidMultiplePageRegionsRejectWithoutRepair() {
        final var bounds = new ArrayList<>(List.of(0.0, 0.0, 100.0, 100.0));
        final var region = new Region(2, bounds);
        bounds.set(0, 90.0);
        assertEquals(0.0, region.bounds().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> region.bounds().set(0, 90.0));
        assertThrows(IllegalArgumentException.class, () -> new Region(0, region.bounds()));
        for (final var invalid :
                List.of(List.of(0.0, 0.0, 0.0, 100.0), List.of(0.0, 0.0, 100.0), List.of(-1.0, 0.0, 100.0, 100.0),
                        List.of(0.0, 0.0, Double.NaN, 100.0), List.of(0.0, 0.0, Double.POSITIVE_INFINITY, 100.0))) {
            assertThrows(IllegalArgumentException.class, () -> new Region(2, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> fragments(List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> fragments(List.of(), List.of(region, region)));
        assertThrows(IllegalArgumentException.class,
                () -> fragments(List.of(), List.of(new Region(3, region.bounds()), region)));
        assertThrows(
                IllegalArgumentException.class, () -> fragments(List.of(), java.util.Collections.nCopies(33, region)));
    }

    /**
     * Builds original-format table output without deriving geometry from reference text.
     * @param page original page
     * @param text one emitted source row
     * @param left source x offset
     * @return immutable owned extraction element
     */
    private static Element fragmentTable(final int page, final String text, final double left) {
        return new Element("owned-" + page + "-" + left + "-" + text, ElementType.Table, text,
                Map.of("page_number", page, "text_as_html", "<table><tr><td>" + text + "</td></tr></table>",
                        "coordinates",
                        Map.of("system", "PDFUserSpace", "points",
                                List.of(List.of(left, 0.0), List.of(left + 100, 0.0), List.of(left + 100, 100.0),
                                        List.of(left, 100.0)))));
    }

    /**
     * Supplies a fixed two-page protocol independently of extraction and reference matching.
     * @return owned original-page selection
     * @throws Exception if JSON decoding fails
     */
    private static tools.jackson.databind.JsonNode multiplePageSelection() throws Exception {
        return JsonMapper.builder().build().readTree("""
                {"id":"owned", "regions":[
                    {"page":2,"roi_left_top_right_bottom":[0,0,100,100]},
                    {"page":3,"roi_left_top_right_bottom":[0,0,100,100]}]}
                """);
    }

    /**
     * Runs opt-in whole-document native policies against unchanged issuer row references.
     * @throws Exception if verified sources, extraction or fresh report writing fail
     */
    @Test
    void optionalCurrentBusinessRows() throws Exception {
        final String cache = System.getProperty("docweave.pdf.current.business.cache");
        final String report = System.getProperty("docweave.pdf.current.business.report");
        final String manifestPath = System.getProperty("docweave.pdf.current.business.manifest");
        if (cache == null && report == null && manifestPath == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(report);
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readString(Path.of(
                manifestPath == null ? "benchmarks/review/pdf-current-business-rows/manifest.json" : manifestPath)));
        for (final var input : manifest.get("inputs")) {
            final var bytes = Files.readAllBytes(Path.of(cache, input.get("filename").asString()));
            assertEquals(input.get("bytes").asLong(), bytes.length);
            assertEquals(input.get("sha256").asString(), digest(bytes));
        }
        // Parse strings only: Jsoup does not execute scripts or fetch referenced assets here.
        final var html = Jsoup.parse(Files.readString(Path.of(cache, manifest.get("source_html").asString())));
        final var references = new ArrayList<List<String>>();
        for (final var selected : manifest.get("cases")) {
            final var rows = rows(html.select("table").get(selected.get("html_table_index").asInt()));
            assertEquals(selected.get("reference_rows").asInt(), rows.size());
            assertEquals(selected.get("reference_rows_sha256").asString(),
                    digest(String.join("\n", rows).getBytes(StandardCharsets.UTF_8)));
            references.add(rows);
        }
        final var source =
                DocumentSource.bytes(Files.readAllBytes(Path.of(cache, manifest.get("source_pdf").asString())),
                        manifest.get("source_pdf").asString());
        final var reports = new ArrayList<Map<String, Object>>();
        final var partitionOptions =
                manifest.has("max_input_bytes")
                        ? PartitionOptions.builder().maxInputBytes(manifest.get("max_input_bytes").asInt()).build()
                        : PartitionOptions.defaults();
        for (final var profile : manifest.get("profiles")) {
            final String mode = profile.asString();
            final var capture = capture(source, mode, partitionOptions);
            int caseIndex = 0;
            for (final var selected : manifest.get("cases")) {
                if (selected.has("regions")) {
                    reports.add(multiplePages(selected, references.get(caseIndex++), capture, mode,
                            manifest.get("source_pages").asInt()));
                    continue;
                }
                final int page = selected.get("page").asInt();
                final var elements =
                        capture.elements()
                                .stream()
                                .filter(element -> ((Number) element.metadata().get("page_number")).intValue() == page)
                                .toList();
                final var tables = elements.stream().filter(element -> element.type() == ElementType.Table).toList();
                final double[] roi = new double[4];
                for (int index = 0; index < roi.length; index++) {
                    roi[index] = selected.get("roi_left_top_right_bottom").get(index).asDouble();
                }
                Element associated = null;
                double best = 0;
                for (final var table : tables) {
                    final double overlap = iou(roi, rectangle((Map<?, ?>)table.metadata().get("coordinates")));
                    if (overlap >= 0.5 && overlap > best) {
                        associated = table;
                        best = overlap;
                    }
                }
                final var observed = associated == null
                                             ? List.<String>of()
                                             : rows(Jsoup.parse(associated.metadata().get("text_as_html").toString())
                                                               .selectFirst("table"));
                final var comparison = compare(references.get(caseIndex++), observed);
                final var entry = new LinkedHashMap<String, Object>();
                entry.put("case", selected.get("id").asString());
                entry.put("source_page", page);
                entry.put("mode", mode);
                entry.put("outcome", capture.outcome());
                entry.put("document_elements", capture.elements().size());
                entry.put("selected_page_elements", elements.size());
                entry.put("page_text_nonblank", elements.stream().anyMatch(element -> !element.text().isBlank()));
                entry.put("page_text_characters", elements.stream().mapToInt(element -> element.text().length()).sum());
                entry.put("tables_on_page", tables.size());
                entry.put("associated", associated != null);
                entry.put("association_iou", best);
                entry.put("reference_content_rows", comparison.expected());
                entry.put("observed_content_rows", comparison.observed());
                entry.put("exact_ordinal_rows", comparison.exact());
                entry.put("missing_row_positions", Math.max(0, comparison.expected() - comparison.observed()));
                entry.put("extra_row_positions", Math.max(0, comparison.observed() - comparison.expected()));
                entry.put("complete_row_content",
                        comparison.expected() == comparison.observed() && comparison.exact() == comparison.expected());
                entry.put("warning_codes", capture.warnings());
                reports.add(entry);
            }
        }
        Files.write(Path.of(report),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("runtime",
                        Runtime.version().toString(), "scope", manifest.get("scope").asString(), "reference_kind",
                        "Issuer HTML ordered nonblank row content; not PDF physical cell/span labels", "results",
                        reports)),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Scores all declared page fragments against the complete original issuer table.
     * @param selected frozen complete-statement selection
     * @param expected unchanged complete issuer rows
     * @param capture complete-document extraction or typed failure
     * @param mode uniformly applied profile
     * @param pages declared original page count
     * @return content-free statement metrics retaining partial and failed associations
     */
    private static Map<String, Object> multiplePages(final tools.jackson.databind.JsonNode selected,
            final List<String> expected, final Capture capture, final String mode, final int pages) {
        assertTrue(selected.get("regions").isArray());
        assertTrue(selected.get("regions").size() >= 2 && selected.get("regions").size() <= 32);
        final var regions = new ArrayList<Region>();
        for (final var region : selected.get("regions")) {
            assertTrue(region.get("page").isIntegralNumber());
            final var bounds = new ArrayList<Double>();
            assertTrue(region.get("roi_left_top_right_bottom").isArray());
            for (final var edge : region.get("roi_left_top_right_bottom")) {
                assertTrue(edge.isNumber());
                bounds.add(edge.asDouble());
            }
            final int page = region.get("page").asInt();
            assertTrue(page <= pages);
            regions.add(new Region(page, bounds));
        }
        final var fragments = fragments(capture.elements(), regions);
        final var compared = compare(expected, fragments.observed());
        final var result = new LinkedHashMap<String, Object>();
        result.put("case", selected.get("id").asString());
        result.put("mode", mode);
        result.put("outcome", capture.outcome());
        result.put("document_elements", capture.elements().size());
        result.put("source_pages", regions.stream().map(Region::page).toList());
        result.put("regions", fragments.regions());
        result.put("associated_regions", fragments.associated());
        result.put("all_regions_associated", fragments.associated() == regions.size());
        result.put("reference_content_rows", compared.expected());
        result.put("observed_content_rows", compared.observed());
        result.put("exact_ordinal_rows", compared.exact());
        result.put("missing_row_positions", Math.max(0, compared.expected() - compared.observed()));
        result.put("extra_row_positions", Math.max(0, compared.observed() - compared.expected()));
        result.put("complete_row_content", fragments.associated() == regions.size()
                                                   && compared.expected() == compared.observed()
                                                   && compared.exact() == compared.expected());
        result.put("warning_codes", capture.warnings());
        return result;
    }

    /**
     * Associates one table per fixed original-page region using geometry alone, retaining missing fragments.
     * @param elements complete original extraction output
     * @param regions strictly increasing original pages, at most32
     * @return original row concatenation and every region's association diagnostics
     */
    private static Fragments fragments(final List<Element> elements, final List<Region> regions) {
        if (regions.isEmpty() || regions.size() > 32) {
            throw new IllegalArgumentException("Invalid region count");
        }
        final var observed = new ArrayList<String>();
        final var details = new ArrayList<Map<String, Object>>();
        int previous = 0;
        int associated = 0;
        for (final var region : regions) {
            if (region.page() <= previous) {
                throw new IllegalArgumentException("Original pages must increase without duplicates");
            }
            previous = region.page();
            final var page =
                    elements.stream()
                            .filter(element
                                    -> ((Number) element.metadata().get("page_number")).intValue() == region.page())
                            .toList();
            final var tables = page.stream().filter(element -> element.type() == ElementType.Table).toList();
            final double[] roi = region.bounds().stream().mapToDouble(Double::doubleValue).toArray();
            Element chosen = null;
            double best = 0;
            for (final var table : tables) {
                final double overlap = iou(roi, rectangle((Map<?, ?>)table.metadata().get("coordinates")));
                if (overlap >= 0.5 && overlap > best) {
                    best = overlap;
                    chosen = table;
                }
            }
            final var emitted =
                    chosen == null
                            ? List.<String>of()
                            : rows(Jsoup.parse(chosen.metadata().get("text_as_html").toString()).selectFirst("table"));
            observed.addAll(emitted);
            if (chosen != null) {
                associated++;
            }
            details.add(Map.of("source_page", region.page(), "selected_page_elements", page.size(), "tables_on_page",
                    tables.size(), "associated", chosen != null, "association_iou", best, "observed_content_rows",
                    emitted.size(), "page_text_nonblank",
                    page.stream().anyMatch(element -> !element.text().isBlank())));
        }
        return new Fragments(List.copyOf(observed), List.copyOf(details), associated);
    }

    /**
     * Scores explicit local OCR captures against the unchanged issuer rows, without invoking an engine.
     * The OCR launcher uses selected-page raster derivatives rather than whole original PDFs.
     * An explicit experimental flag instead scores offline frozen-anchor native-glyph projection.
     * @throws Exception if pinned inputs, source frames or report output are invalid
     */
    @Test
    void optionalCurrentBusinessModelRows() throws Exception {
        final String captureRoot = System.getProperty("docweave.pdf.current.business.model.captures");
        final String report = System.getProperty("docweave.pdf.current.business.model.report");
        if (captureRoot == null && report == null) {
            return;
        }
        assertNotNull(captureRoot);
        assertNotNull(report);
        final String cache = System.getProperty("docweave.pdf.current.business.cache");
        assertNotNull(cache);
        final var mapper = JsonMapper.builder().build();
        final var manifest =
                mapper.readTree(Files.readString(Path.of("benchmarks/review/pdf-current-business-rows/manifest.json")));
        for (final var input : manifest.get("inputs")) {
            final var bytes = Files.readAllBytes(Path.of(cache, input.get("filename").asString()));
            assertEquals(input.get("bytes").asLong(), bytes.length);
            assertEquals(input.get("sha256").asString(), digest(bytes));
        }
        final var protocol = mapper.readTree(
                Files.readString(Path.of("benchmarks/review/pdf-current-business-model/manifest.json")));
        assertEquals(protocol.get("reference_manifest_sha256").asString(),
                digest(Files.readAllBytes(Path.of("benchmarks/review/pdf-current-business-rows/manifest.json"))));
        final boolean nativeProbe = Boolean.getBoolean("docweave.pdf.current.business.model.native.probe");
        final boolean nativeFirst = Boolean.getBoolean("docweave.pdf.current.business.model.native.first");
        final boolean nativeLive = nativeFirst || Boolean.getBoolean("docweave.pdf.current.business.model.native.live");
        assertFalse(nativeProbe && nativeLive, "Select one capture representation");
        if (!nativeProbe && !nativeLive) {
            final var runtime = mapper.readTree(Files.readString(Path.of(captureRoot, "runtime.json")));
            for (final String identity : List.of("executable_sha256", "english_model_sha256")) {
                assertEquals(protocol.get(identity).asString(), runtime.get(identity).asString());
            }
        }
        if (nativeLive) {
            final var runtime = mapper.readTree(Files.readString(Path.of(captureRoot, "runtime.json")));
            if (nativeFirst) {
                assertTrue(runtime.get("native_first").asBoolean());
            }
            for (final String identity : List.of("detector_sha256", "structure_sha256")) {
                assertEquals(protocol.get(identity).asString(), runtime.get(identity).asString());
            }
        }
        final var html = Jsoup.parse(Files.readString(Path.of(cache, manifest.get("source_html").asString())));
        final var reports = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final var directory = Path.of(captureRoot, selected.get("id").asString());
            final var source = mapper.readTree(Files.readString(directory.resolve("source.json")));
            assertEquals(selected.get("page").asInt(), source.get("source_page").asInt());
            assertEquals(manifest.get("inputs").get(0).get("sha256").asString(), source.get("pdf_sha256").asString());
            assertEquals(0, source.get("source_rotation").asInt());
            assertEquals(612.0, source.get("page_width").asDouble());
            assertEquals(792.0, source.get("page_height").asDouble());
            assertEquals(150, source.get("dpi").asInt());
            if (!nativeLive) {
                assertEquals(1275, source.get("render_width").asInt());
                assertEquals(1649, source.get("render_height").asInt());
            }
            final var expected = rows(html.select("table").get(selected.get("html_table_index").asInt()));
            assertEquals(selected.get("reference_rows").asInt(), expected.size());
            assertEquals(selected.get("reference_rows_sha256").asString(),
                    digest(String.join("\n", expected).getBytes(StandardCharsets.UTF_8)));
            final double[] roi = new double[4];
            for (int index = 0; index < 4; index++) {
                roi[index] = selected.get("roi_left_top_right_bottom").get(index).asDouble();
            }
            for (final String profile : (nativeFirst   ? List.of("native-first")
                                         : nativeLive  ? List.of("native-live")
                                         : nativeProbe ? List.of("native")
                                                       : List.of("page", "cells"))) {
                final var capture = mapper.readTree(Files.readString(directory.resolve(profile + ".json")));
                assertEquals(nativeFirst   ? "native_first_disjoint_v1"
                             : nativeLive  ? "provider_layout_native_glyphs_v1"
                             : nativeProbe ? "frozen_model_native_glyph_probe"
                                           : "onnx_detection_structure",
                        capture.get("layout_strategy").asString());
                if (nativeProbe) {
                    final var identities = mapper.readTree(
                            Files.readString(Path.of("benchmarks/review/pdf-current-business-model/identities.json")));
                    final String key = selected.get("id").asString() + "/page.json";
                    final String geometryHash = capture.get("geometry_capture_sha256").asString();
                    assertTrue(geometryHash.equals(
                                       identities.get("local_capture_identities").get("21").get(key).asString())
                               || geometryHash.equals(
                                       identities.get("local_capture_identities").get("25").get(key).asString()));
                }
                final var tables = capture.get("tables");
                tools.jackson.databind.JsonNode associated = null;
                double best = 0;
                for (final var table : tables) {
                    // Raster/probe captures use derivative page one; live captures preserve the original page.
                    assertEquals(nativeLive ? selected.get("page").asInt() : 1, table.get("page_number").asInt());
                    final var coordinates = mapper.convertValue(table.get("coordinates"), Map.class);
                    final double overlap = iou(roi, rectangle(coordinates));
                    if (overlap >= 0.5 && overlap > best) {
                        associated = table;
                        best = overlap;
                    }
                }
                final var observed =
                        associated == null
                                ? List.<String>of()
                                : rows(Jsoup.parse(associated.get("text_as_html").asString()).selectFirst("table"));
                final var comparison = compare(expected, observed);
                final var entry = new LinkedHashMap<String, Object>();
                entry.put("capture_sha256", digest(Files.readAllBytes(directory.resolve(profile + ".json"))));
                entry.put("case", selected.get("id").asString());
                entry.put("source_page", selected.get("page").asInt());
                entry.put("profile", profile);
                entry.put("outcome", capture.get("outcome").asString());
                entry.put("tables", tables.size());
                entry.put("association_iou", best);
                entry.put("reference_content_rows", comparison.expected());
                entry.put("observed_content_rows", comparison.observed());
                entry.put("exact_ordinal_rows", comparison.exact());
                entry.put("missing_row_positions", Math.max(0, comparison.expected() - comparison.observed()));
                entry.put("extra_row_positions", Math.max(0, comparison.observed() - comparison.expected()));
                for (final String field : List.of("page_calls", "cell_calls", "layout_calls", "layout_tables")) {
                    entry.put(field, capture.get(field).asInt());
                }
                reports.add(entry);
            }
        }
        Files.write(Path.of(report),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("runtime",
                        Runtime.version().toString(), "representation",
                        nativeLive ? "Complete original document; live model geometry and native glyph text"
                        : nativeProbe
                                ? "Offline frozen model anchors with original native glyphs; not a production partitioner"
                                : "Selected-page 150DPI image-only derivative; issuer HTML ordinal row content",
                        "results", reports)),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Preserves display content, numeric spelling and Unicode without expanding HTML presentation spans.
     */
    @Test
    void htmlRowsPreserveContentAndDoNotExpandPresentationSpans() {
        final var table =
                Jsoup.parse("<table><tr><td>A&amp;B</td><td>&nbsp;</td><td>001</td></tr>"
                             + "<tr><td>&nbsp;</td></tr><tr><td colspan='2'>Net</td><td>(2)</td></tr></table>")
                        .selectFirst("table");
        assertEquals(List.of("A&B 001", "Net (2)"), rows(table));
        assertEquals("\u2003001\u2003", normalize("\u2003001\u2003"));
        assertNotEquals(normalize("001"), normalize("1"));
        assertNotEquals(normalize("Net"), normalize("net"));
        assertNotEquals(normalize("(2)"), normalize("-2"));
        assertNotEquals(normalize("$ 2"), normalize("$2"));
    }

    /**
     * Retains original row positions, empty-output denominators and independent geometric association.
     */
    @Test
    void ordinalComparisonDoesNotShiftRowsOrRewardMissingOutput() {
        assertEquals(new Rows(2, 2, 2), compare(List.of("A 001", "B (2)"), List.of("A 001", "B (2)")));
        assertEquals(new Rows(2, 0, 0), compare(List.of("A 001", "B (2)"), List.of()));
        assertEquals(new Rows(2, 3, 0), compare(List.of("A 001", "B (2)"), List.of("Title", "A 001", "B (2)")));
        assertEquals(1.0, iou(new double[] {1, 2, 3, 4}, new double[] {1, 2, 3, 4}));
        assertEquals(0.0, iou(new double[] {1, 2, 3, 4}, new double[] {5, 6, 7, 8}));
    }

    /**
     * Applies one predeclared input budget uniformly to every policy on the original complete PDF.
     * @param source unchanged complete original source
     * @param mode explicit native enum name or source-tagged geometry profile
     * @param options caller limits frozen before extraction; defaults remain unchanged
     * @return complete source result or typed failure retaining every selected reference case
     * @throws IOException if unexpected source I/O fails
     */
    private static Capture capture(final DocumentSource source, final String mode, final PartitionOptions options)
            throws IOException {
        if (mode.equals("NATIVE_FIRST_ONNX_TABLES")) {
            try (final var layout = OnnxTableLayout.create(modelOptions("detector"), modelOptions("structure"))) {
                return capture(source,
                        PdfPartitioner.createWithNativeTablesFirst(
                                PdfOptions.defaults().withTableCellCoordinates(true).withTableDetection(
                                        PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT),
                                new PdfNativeLayoutOptions(150, 16_000_000, 1024), layout),
                        options);
            }
        }
        final var pdfOptions = PdfOptions.defaults().withTableCellCoordinates(true);
        final PdfPartitioner partitioner = switch (mode) {
            case "SOURCE_TAGGED_TABLES" -> PdfPartitioner.createWithTaggedTables(pdfOptions);
            case "SOURCE_TAGGED_LOGICAL_TABLES" ->
                PdfPartitioner.createWithTaggedTables(pdfOptions.withTableCellCoordinates(false))
                        .withTaggedTableGeometry(dev.docweave.partition.PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE);
            case "SOURCE_TAGGED_PAINTED_TABLES" ->
                PdfPartitioner.createWithTaggedTables(
                        pdfOptions.withTextRendering(PdfOptions.TextRendering.PAINTED_MODES));
            case "SOURCE_TAGGED_PAINTED_LOGICAL_TABLES" ->
                PdfPartitioner
                        .createWithTaggedTables(pdfOptions.withTableCellCoordinates(false).withTextRendering(
                                PdfOptions.TextRendering.PAINTED_MODES))
                        .withTaggedTableGeometry(dev.docweave.partition.PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE);
            case "SOURCE_TAGGED_CELL_ENVELOPE_TABLES" ->
                PdfPartitioner.createWithTaggedTables(pdfOptions.withTableCellCoordinates(false))
                        .withTaggedTableGeometry(
                                dev.docweave.partition.PdfTaggedTableGeometry.LOGICAL_CELL_TEXT_ENVELOPES);
            default -> PdfPartitioner.create(pdfOptions.withTableDetection(PdfOptions.TableDetection.valueOf(mode)));
        };
        return capture(source, partitioner, options);
    }

    /**
     * Requires explicitly supplied local model paths and identities; never downloads or discovers graphs.
     * @param kind detector or structure graph property suffix
     * @return fixed four-thread graph configuration using the earlier native-first experiment's policies
     */
    private static OnnxTableOptions modelOptions(final String kind) {
        final String prefix = "docweave.pdf.current.business." + kind;
        final var path = Path.of(java.util.Objects.requireNonNull(System.getProperty(prefix), prefix));
        final String hash =
                java.util.Objects.requireNonNull(System.getProperty(prefix + ".sha256"), prefix + ".sha256");
        final var defaults = OnnxTableOptions.defaults(path, hash);
        return new OnnxTableOptions(path, hash, defaults.timeout(), defaults.maxConcurrentCalls(),
                defaults.maxModelBytes(), defaults.maxInputPixels(), defaults.maxQueries(), defaults.tableLimits(),
                defaults.minimumScore(), 4, OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
    }

    /**
     * Executes one already configured strategy, preserving typed whole-document failure semantics.
     * @param source complete original source
     * @param partitioner explicit caller-owned configured service
     * @param options original frozen input limits
     * @return complete output or typed failure with no partial elements
     * @throws IOException if unexpected source or provider I/O fails
     */
    private static Capture capture(final DocumentSource source, final PdfPartitioner partitioner,
            final PartitionOptions options) throws IOException {
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)));
        try {
            final var result = processor.partition(source, options);
            return new Capture(
                    "SUCCESS", result.elements(), result.warnings().stream().map(w -> w.code().name()).toList());
        } catch (final PartitionException failure) {
            return new Capture(failure.code().name(), List.of(), List.of());
        }
    }

    /**
     * Scores another pipeline with the unchanged issuer row labels and geometry-only association.
     * @param selected original frozen business region
     * @param capture selected-page common table metadata, including whole-document failures
     * @param html unchanged verified issuer HTML bytes
     * @param mapper local diagnostic converter
     * @return numeric-only row metrics with missing/extras retained
     * @throws Exception if source labels or capture geometry are inconsistent
     */
    static Map<String, Object> scoreCapturedRows(final tools.jackson.databind.JsonNode selected,
            final tools.jackson.databind.JsonNode capture, final byte[] html, final JsonMapper mapper)
            throws Exception {
        final var expected = rows(Jsoup.parse(new String(html, StandardCharsets.UTF_8))
                        .select("table")
                        .get(selected.get("html_table_index").asInt()));
        assertEquals(selected.get("reference_rows").asInt(), expected.size());
        assertEquals(selected.get("reference_rows_sha256").asString(),
                digest(String.join("\n", expected).getBytes(StandardCharsets.UTF_8)));
        final double[] roi = new double[4];
        for (int index = 0; index < roi.length; index++) {
            roi[index] = selected.get("roi_left_top_right_bottom").get(index).asDouble();
        }
        tools.jackson.databind.JsonNode associated = null;
        double best = 0;
        for (final var table : capture.get("tables")) {
            assertEquals(selected.get("page").asInt(), table.get("page_number").asInt());
            final var metadata = mapper.convertValue(table, Map.class);
            final double overlap = iou(roi, rectangle((Map<?, ?>)metadata.get("coordinates")));
            if (overlap >= 0.5 && overlap > best) {
                best = overlap;
                associated = table;
            }
        }
        final var observed =
                associated == null ? List.<String>of()
                                   : rows(Jsoup.parse(associated.get("text_as_html").asString()).selectFirst("table"));
        final var compared = compare(expected, observed);
        final var result = new LinkedHashMap<String, Object>();
        result.put("outcome", capture.get("outcome").asString());
        result.put("associated", associated != null);
        result.put("association_iou", best);
        result.put("tables_on_page", capture.get("tables").size());
        result.put("unassociated_tables_on_page", capture.get("tables").size() - (associated == null ? 0 : 1));
        result.put("reference_content_rows", compared.expected());
        result.put("observed_content_rows", compared.observed());
        result.put("exact_ordinal_rows", compared.exact());
        result.put("missing_row_positions", Math.max(0, compared.expected() - compared.observed()));
        result.put("extra_row_positions", Math.max(0, compared.observed() - compared.expected()));
        result.put("complete_row_content",
                compared.expected() == compared.observed() && compared.exact() == compared.expected());
        return result;
    }

    /**
     * Extracts direct cell text for nonblank rows belonging to this table, preserving author order.
     * @param table source HTML table, never a PDF topology oracle
     * @return immutable normalized row strings; used locally, never written into metric reports
     */
    private static List<String> rows(final org.jsoup.nodes.Element table) {
        assertNotNull(table);
        final var rows = new ArrayList<String>();
        for (final var row : table.select("tr")) {
            if (row.closest("table") != table) {
                continue;
            }
            final var cells = row.children()
                                      .stream()
                                      .filter(cell -> cell.tagName().equals("td") || cell.tagName().equals("th"))
                                      .map(org.jsoup.nodes.Element::wholeText)
                                      .toList();
            final var text = normalize(String.join(" ", cells));
            if (!text.isEmpty()) {
                rows.add(text);
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Compares original nonblank row indices, without searching or repositioning either sequence.
     * @param expected independently fixed publisher rows
     * @param observed associated table rows, empty when no table qualifies
     * @return exact ordinal counts retaining the full reference denominator
     */
    private static Rows compare(final List<String> expected, final List<String> observed) {
        int exact = 0;
        for (int row = 0; row < Math.min(expected.size(), observed.size()); row++) {
            if (expected.get(row).equals(observed.get(row))) {
                exact++;
            }
        }
        return new Rows(expected.size(), observed.size(), exact);
    }

    /**
     * Applies the declared display-space policy only: NBSP to space and ASCII whitespace collapse/trim.
     * @param value author or emitted text
     * @return comparison-only text, with case/punctuation/numeric formatting and other Unicode preserved
     */
    private static String normalize(final String value) {
        return value.replace('\u00a0', ' ').replaceAll("[ \\t\\r\\n\\f\\u000B]+", " ").replaceAll("^ | $", "");
    }

    /**
     * Computes the exact byte identity of a cached input or reference-row sequence.
     * @param bytes bounded local source content
     * @return lowercase SHA256
     * @throws Exception if SHA256 is unavailable
     */
    private static String digest(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Reads original displayed parent extents without text-based adjustment.
     * @param coordinates PDFUserSpace coordinate map
     * @return left/top/right/bottom
     */
    private static double[] rectangle(final Map<?, ?> coordinates) {
        assertEquals("PDFUserSpace", coordinates.get("system"));
        final var points = (List<?>) coordinates.get("points");
        final var first = (List<?>) points.getFirst();
        final var opposite = (List<?>) points.get(2);
        return new double[] {((Number) first.getFirst()).doubleValue(), ((Number) first.get(1)).doubleValue(),
                ((Number) opposite.getFirst()).doubleValue(), ((Number) opposite.get(1)).doubleValue()};
    }

    /**
     * Computes source-frame intersection over union for geometry-only table association.
     * @param first fixed source ROI
     * @param second emitted parent bounds
     * @return zero through one
     */
    private static double iou(final double[] first, final double[] second) {
        final double intersection = Math.max(0, Math.min(first[2], second[2]) - Math.max(first[0], second[0]))
                                    * Math.max(0, Math.min(first[3], second[3]) - Math.max(first[1], second[1]));
        final double union = (first[2] - first[0]) * (first[3] - first[1])
                             + (second[2] - second[0]) * (second[3] - second[1]) - intersection;
        return union > 0 ? intersection / union : 0;
    }
}
