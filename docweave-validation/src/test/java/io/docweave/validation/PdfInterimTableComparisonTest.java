package io.docweave.validation;

import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

/** Paired current whole-document table diagnostics; independently frozen publisher labels stay unchanged. */
class PdfInterimTableComparisonTest {
    /** Creates independent owned controls and opt-in paired scoring. */
    PdfInterimTableComparisonTest() {
    }

    /**
     * Preserves merged anchors, physical hyphens, Unicode and entity text without inventing covered-slot cells.
     * @throws Exception if canonical XML/publisher comparison fails
     */
    @Test
    void htmlAnchorsPreserveSpansAndCharacters() throws Exception {
        final var structure = UpstreamTableCapture.structure(
                "<table><tr><th rowspan='2'>A&amp;B</th><td>left- right</td></tr><tr><td>μ\u00a0😀</td></tr></table>");
        final var cells = (Map<?, ?>) structure.get("pdf_table_cells");
        assertEquals(2, cells.get("row_count"));
        assertEquals(2, cells.get("column_count"));
        final var anchors = (List<?>) cells.get("cells");
        assertEquals(3, anchors.size());
        assertEquals(Map.of("row_index", 0, "column_index", 0, "row_span", 2, "column_span", 1), anchors.getFirst());
        assertEquals(Map.of("row_index", 1, "column_index", 1, "row_span", 1, "column_span", 1), anchors.getLast());
        final var xml =
                ScientificPublisherXml.parse(structure.get("text_as_html").toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("A&Bleft- rightμ\u00a0😀", xml.getDocumentElement().getTextContent());
        for (final String invalid : List.of("", "<table/>", "<table><tr><td rowspan='0'>A</td></tr></table>",
                     "<table><tr><td rowspan='2'>A</td></tr></table>",
                     "<table><tr><td>A</td><td rowspan='2'>B</td></tr><tr><td colspan='2'>overlap</td></tr></table>",
                     "<table><tr><td colspan='1025'>A</td></tr></table>",
                     "<table><tr><td><table/></td></tr></table>")) {
            assertThrows(IllegalArgumentException.class, () -> UpstreamTableCapture.structure(invalid));
        }
    }

    /** Actual raster dimensions define independent x/y scaling; duplicate and invalid-HTML tables remain counted. */
    @Test
    void pixelFramesUseActualRasterAndRetainInvalidPredictions() {
        final var mapper = JsonMapper.builder().build();
        final var valid = table("<table><tr><td>A</td></tr></table>");
        final var invalid = table("<table><tr><td rowspan='0'>bad</td></tr></table>");
        final var raw = mapper.valueToTree(Map.of("outcome", "SUCCESS", "elements", List.of(valid, valid, invalid)));
        final var adapted = UpstreamTableCapture.page(raw, 1, 100, 100, mapper);
        assertEquals(3, adapted.get("tables").size());
        final var points = adapted.get("tables").get(0).get("coordinates").get("points");
        assertEquals(10, points.get(0).get(0).asDouble());
        assertEquals(20, points.get(0).get(1).asDouble());
        assertEquals(60, points.get(2).get(0).asDouble());
        assertEquals(80, points.get(2).get(1).asDouble());
        assertFalse(adapted.get("tables").get(2).get("upstream_structure_valid").asBoolean());
        assertEquals(0, adapted.get("tables").get(2).get("pdf_table_cells").get("cells").size());
        assertEquals(3, raw.get("elements").size());
        assertEquals(0, UpstreamTableCapture.page(raw, 2, 100, 100, mapper).get("tables").size());
        assertThrows(IllegalArgumentException.class,
                ()
                        -> UpstreamTableCapture.page(
                                mapper.valueToTree(Map.of("outcome", "RuntimeFailure", "elements", List.of(valid))), 1,
                                100, 100, mapper));
        assertThrows(IllegalArgumentException.class, () -> UpstreamTableCapture.page(raw, 1, Double.NaN, 100, mapper));
    }

    /**
     * Missing/failed captures keep every publisher anchor and every issuer row in the original denominator.
     * @throws Exception if independently authored references or common scoring fail
     */
    @Test
    void failedCapturesRetainCompleteIndependentDenominators() throws Exception {
        final var mapper = JsonMapper.builder().build();
        final var capture = mapper.valueToTree(Map.of("outcome", "RuntimeFailure", "tables", List.of()));
        final var publisher = mapper.valueToTree(Map.of("source_page", 1, "roi_left_top_right_bottom",
                List.of(0, 0, 100, 100), "table_id", "owned", "expected_anchors", 2, "expected_rows", 2,
                "expected_columns", 1, "publisher_header_rows", 1, "expected_header_anchors", 1));
        final byte[] xml =
                "<article><table-wrap id='owned'><table><thead><tr><th>Head</th></tr></thead><tbody><tr><td>Body</td></tr></tbody></table></table-wrap></article>"
                        .getBytes(StandardCharsets.UTF_8);
        final var temporary = Files.createTempFile("docweave-interim-owned", ".json");
        try {
            Files.writeString(temporary, mapper.writeValueAsString(capture));
            final var result = PdfScientificSpanTest.scorePublisherCapture(publisher, capture, temporary, mapper, xml);
            assertEquals(2, result.get("expected_anchors"));
            assertEquals(2, result.get("missing_reference_keys"));
            assertEquals(0, result.get("exact_span_text_anchors"));
            assertEquals(false, result.get("complete_span_text"));
        } finally {
            Files.delete(temporary);
        }
        final byte[] html =
                "<table><tr><td>Alpha</td></tr><tr><td>Beta</td></tr></table>".getBytes(StandardCharsets.UTF_8);
        final var issuer = mapper.valueToTree(Map.of("page", 1, "roi_left_top_right_bottom", List.of(0, 0, 100, 100),
                "html_table_index", 0, "reference_rows", 2, "reference_rows_sha256",
                hash("Alpha\nBeta".getBytes(StandardCharsets.UTF_8))));
        final var business = PdfCurrentBusinessRowsTest.scoreCapturedRows(issuer, capture, html, mapper);
        assertEquals(2, business.get("reference_content_rows"));
        assertEquals(2, business.get("missing_row_positions"));
        assertEquals(0, business.get("exact_ordinal_rows"));
    }

    /**
     * Scores all frozen regions in both libraries with unchanged independent source labels and common geometry.
     * @throws Exception if opt-in configuration, identities, original source frames or fresh report requirements fail
     */
    @Test
    void optionalWholeOriginalPairedTables() throws Exception {
        final String cache = System.getProperty("docweave.pdf.interim.tables.cache");
        final String nativeRoot = System.getProperty("docweave.pdf.interim.tables.native");
        final String upstream = System.getProperty("docweave.pdf.interim.tables.upstream");
        final String output = System.getProperty("docweave.pdf.interim.tables.report");
        if (cache == null && nativeRoot == null && upstream == null && output == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(nativeRoot);
        assertNotNull(upstream);
        assertNotNull(output);
        final var mapper = JsonMapper.builder().build();
        final var manifestPath = Path.of("benchmarks/review/pdf-interim-tables/manifest.json");
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        final var nativeRuntime = mapper.readTree(Files.readAllBytes(Path.of(nativeRoot, "runtime.json")));
        for (final String policy : List.of("native_first", "tagged_first", "segmented_rows")) {
            assertTrue(nativeRuntime.get(policy).asBoolean());
        }
        assertEquals("SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE", nativeRuntime.get("cell_text_join").asString());
        for (final String model : List.of("detector_sha256", "structure_sha256")) {
            assertEquals(manifest.get("docweave_profile").get(model), nativeRuntime.get(model));
        }
        final var upstreamRuntime = mapper.readTree(Files.readAllBytes(Path.of(upstream, "runtime.json")));
        final var executedBytes = Files.readAllBytes(Path.of(manifest.get("capture_manifest").asString()));
        assertEquals(manifest.get("capture_manifest_sha256").asString(), hash(executedBytes));
        assertEquals(hash(executedBytes), upstreamRuntime.get("manifest_sha256").asString());
        final var executed = mapper.readTree(executedBytes);
        for (final String identity : List.of("cases", "resources", "upstream_profile", "document_timeout_seconds")) {
            assertEquals(executed.get(identity), manifest.get(identity));
        }
        assertEquals(executed.get("docweave_profile").get("minimum_table_cells"),
                manifest.get("docweave_profile").get("max_table_cells"));
        final var results = new ArrayList<Map<String, Object>>();
        for (final var document : manifest.get("cases")) {
            final var referencePath = Path.of(document.get("reference_manifest").asString());
            assertEquals(document.get("reference_manifest_sha256").asString(), hash(Files.readAllBytes(referencePath)));
            final var references = mapper.readTree(Files.readAllBytes(referencePath));
            final String name = document.get("id").asString();
            final byte[] pdf = Files.readAllBytes(Path.of(cache, document.get("pdf").asString()));
            assertEquals(document.get("bytes").asLong(), pdf.length);
            assertEquals(document.get("sha256").asString(), hash(pdf));
            final var upstreamPath = Path.of(upstream, name + ".json");
            final var raw = mapper.readTree(Files.readAllBytes(upstreamPath));
            assertEquals(document.get("sha256"), raw.get("source_sha256"));
            final boolean business = name.equals("business");
            final byte[] labels =
                    Files.readAllBytes(Path.of(cache, business ? "amazon-q4-2025-sec.html" : name + ".xml"));
            final var labelPin = references.get("inputs").get(1);
            assertEquals(labelPin.get("bytes").asLong(), labels.length);
            assertEquals(labelPin.get("sha256").asString(), hash(labels));
            assertEquals(references.get("cases"), document.get("regions"));
            try (final var original = Loader.loadPDF(pdf)) {
                for (final var selected : document.get("regions")) {
                    final String id = selected.get("id").asString();
                    final int page = selected.get(business ? "page" : "source_page").asInt();
                    final var sourcePage = original.getPage(page - 1);
                    assertEquals(0, sourcePage.getRotation());
                    assertEquals(1, sourcePage.getUserUnit());
                    assertEquals(0, sourcePage.getCropBox().getLowerLeftX());
                    assertEquals(0, sourcePage.getCropBox().getLowerLeftY());
                    final var nativePath = Path.of(nativeRoot, id, "native-first.json");
                    final var nativeCapture = mapper.readTree(Files.readAllBytes(nativePath));
                    final var frame = mapper.readTree(Files.readAllBytes(Path.of(nativeRoot, id, "source.json")));
                    assertEquals(document.get("sha256"), frame.get("pdf_sha256"));
                    assertEquals(page, frame.get("source_page").asInt());
                    assertEquals(sourcePage.getCropBox().getWidth(), frame.get("page_width").asDouble());
                    assertEquals(sourcePage.getCropBox().getHeight(), frame.get("page_height").asDouble());
                    final var adapted = UpstreamTableCapture.page(
                            raw, page, frame.get("page_width").asDouble(), frame.get("page_height").asDouble(), mapper);
                    for (final String profile :
                            List.of("DOCWEAVE_CONFIGURED_NATIVE", "UNSTRUCTURED_CONFIGURED_HI_RES")) {
                        final boolean nativeProfile = profile.startsWith("DOCWEAVE");
                        final var capture = nativeProfile ? nativeCapture : adapted;
                        final var path = nativeProfile ? nativePath : upstreamPath;
                        final var result = business ? PdfCurrentBusinessRowsTest.scoreCapturedRows(
                                                              selected, capture, labels, mapper)
                                                    : PdfScientificSpanTest.scorePublisherCapture(
                                                              selected, capture, path, mapper, labels);
                        result.put("id", id);
                        result.put("profile", profile);
                        result.put("capture_sha256", hash(Files.readAllBytes(path)));
                        result.put("invalid_structure_tables",
                                capture.get("tables")
                                        .valueStream()
                                        .filter(table
                                                -> table.has("upstream_structure_valid")
                                                           && !table.get("upstream_structure_valid").asBoolean())
                                        .count());
                        result.put("unassociated_tables_on_page",
                                capture.get("tables").size() - (Boolean.TRUE.equals(result.get("associated")) ? 1 : 0));
                        results.add(result);
                    }
                }
            }
        }
        assertEquals(10, results.size());
        Files.writeString(Path.of(output),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("runtime", Runtime.version().toString(), "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Creates a fixed upstream pixel-frame table fixture with no source-dependent values.
     * @param html owned predicted structure
     * @return raw upstream element fixture
     */
    private static Map<String, Object> table(final String html) {
        return Map.of("type", "Table", "metadata",
                Map.of("page_number", 1, "text_as_html", html, "coordinates",
                        Map.of("system", "PixelSpace", "layout_width", 1000, "layout_height", 2000, "points",
                                List.of(List.of(100, 400), List.of(100, 1600), List.of(600, 1600),
                                        List.of(600, 400)))));
    }

    /**
     * Computes a source/label/capture identity without changing its bytes.
     * @param bytes original local bytes
     * @return lowercase SHA256
     * @throws Exception if mandatory JDK hash support is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
