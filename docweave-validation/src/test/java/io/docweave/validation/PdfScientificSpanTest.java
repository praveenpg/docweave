package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
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
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in original scientific PDF evaluation against published rectangular span/text labels, without downloads. */
class PdfScientificSpanTest {
    /** Creates isolated publisher-reference and capture controls. */
    PdfScientificSpanTest() {
    }

    /**
     * Exact positional rectangular anchor identity, independent of source text.
     * @param row zero-based origin row
     * @param column zero-based origin column
     * @param rows positive row span
     * @param columns positive column span
     */
    private record Key(int row, int column, int rows, int columns) {}

    /**
     * Runs opt-in native scientific extraction against frozen logical span/text labels.
     * @throws Exception if source identity, annotation validation or report writing fail
     */
    @Test
    void optionalPublishedScientificSpans() throws Exception {
        final String cache = System.getProperty("docweave.pdf.scientific.cache");
        final String report = System.getProperty("docweave.pdf.scientific.report");
        if (cache == null && report == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(report);
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(
                Files.readString(Path.of("benchmarks/review/pdf-scientific-native-spans/manifest.json")));
        verifyInputs(cache, manifest);
        final var selected = manifest.get("case");
        final int page = selected.get("source_page").asInt();
        final var region = region(parse(Files.readAllBytes(Path.of(cache, selected.get("cells").asString()))), page,
                selected.get("region_id").asString());
        final var expected = labels(region);
        assertEquals(selected.get("expected_anchors").asInt(), expected.size());
        final int rows = expected.keySet().stream().mapToInt(key -> key.row() + key.rows()).max().orElseThrow();
        final int columns =
                expected.keySet().stream().mapToInt(key -> key.column() + key.columns()).max().orElseThrow();
        assertEquals(selected.get("expected_rows").asInt(), rows);
        assertEquals(selected.get("expected_columns").asInt(), columns);
        assertEquals(rows * columns, expected.keySet().stream().mapToInt(key -> key.rows() * key.columns()).sum());
        final var original = region(parse(Files.readAllBytes(Path.of(cache, selected.get("regions").asString()))), page,
                selected.get("region_id").asString());
        final var bounds = (org.w3c.dom.Element) original.getElementsByTagName("bounding-box").item(0);
        final double height = selected.get("source_page_height").asDouble();
        final double[] roi = {Double.parseDouble(bounds.getAttribute("x1")),
                height - Double.parseDouble(bounds.getAttribute("y2")), Double.parseDouble(bounds.getAttribute("x2")),
                height - Double.parseDouble(bounds.getAttribute("y1"))};
        for (int index = 0; index < roi.length; index++) {
            assertEquals(selected.get("roi_left_top_right_bottom").get(index).asDouble(), roi[index]);
        }
        final var source = DocumentSource.bytes(
                Files.readAllBytes(Path.of(cache, selected.get("pdf").asString())), selected.get("pdf").asString());
        final var reports = new ArrayList<Map<String, Object>>();
        for (final var policy : manifest.get("protocol").get("profiles")) {
            final var mode = PdfOptions.TableDetection.valueOf(policy.asString());
            final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                    PdfOptions.defaults().withTableCellCoordinates(true).withTableDetection(mode)))));
            final var result = processor.partition(source);
            final var tables =
                    result.elements()
                            .stream()
                            .filter(element
                                    -> element.type() == ElementType.Table
                                               && ((Number) element.metadata().get("page_number")).intValue() == page)
                            .toList();
            Element associated = null;
            double best = 0;
            for (final var table : tables) {
                final double overlap = iou(roi, rectangle((Map<?, ?>)table.metadata().get("coordinates")));
                if (overlap >= 0.5 && overlap > best) {
                    associated = table;
                    best = overlap;
                }
            }
            final var observed = associated == null ? Map.<Key, String>of() : anchors(associated);
            final int matchingKeys = (int) expected.keySet().stream().filter(observed::containsKey).count();
            final int exact = (int) expected.entrySet()
                                      .stream()
                                      .filter(entry -> entry.getValue().equals(observed.get(entry.getKey())))
                                      .count();
            final boolean topology = expected.keySet().equals(observed.keySet());
            final var entry = new LinkedHashMap<String, Object>();
            entry.put("mode", mode.name());
            entry.put("tables_on_page", tables.size());
            entry.put("associated", associated != null);
            entry.put("association_iou", best);
            entry.put("expected_anchors", expected.size());
            entry.put("observed_anchors", observed.size());
            entry.put("matching_span_keys", matchingKeys);
            entry.put("exact_span_text_anchors", exact);
            entry.put("missing_reference_keys", expected.size() - matchingKeys);
            entry.put("extra_prediction_keys", observed.size() - matchingKeys);
            entry.put("complete_topology", topology);
            entry.put("complete_span_text", topology && exact == expected.size());
            entry.put("warning_codes", result.warnings().stream().map(w -> w.code().name()).toList());
            reports.add(entry);
        }
        Files.write(Path.of(report),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                        Map.of("runtime", Runtime.version().toString(), "source_page", page, "expected_rows", rows,
                                "expected_columns", columns, "results", reports)),
                StandardOpenOption.CREATE_NEW);
        // Preserve every profile's diagnostic record before enforcing this externally labelled regression.
        for (final var entry : reports) {
            assertEquals(true, entry.get("complete_span_text"), () -> "Scientific span/text regression: " + entry);
        }
    }

    /**
     * Scores explicit live-model captures against the same frozen scientific span/text labels.
     * This diagnostic keeps failures and misses; it never invokes a model or requires a positive result.
     * @throws Exception if pinned source/capture identities, topology or fresh output checks fail
     */
    @Test
    void optionalCapturedNativeModelSpans() throws Exception {
        final String captures = System.getProperty("docweave.pdf.scientific.model.captures");
        final String report = System.getProperty("docweave.pdf.scientific.model.report");
        if (captures == null && report == null) {
            return;
        }
        assertNotNull(captures);
        assertNotNull(report);
        final String cache = System.getProperty("docweave.pdf.scientific.cache");
        assertNotNull(cache);
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(
                Files.readString(Path.of("benchmarks/review/pdf-scientific-native-spans/manifest.json")));
        verifyInputs(cache, manifest);
        final var selected = manifest.get("case");
        final int page = selected.get("source_page").asInt();
        final var expected = labels(region(parse(Files.readAllBytes(Path.of(cache, selected.get("cells").asString()))),
                page, selected.get("region_id").asString()));
        assertEquals(selected.get("expected_anchors").asInt(), expected.size());
        final var directory = Path.of(captures, selected.get("id").asString());
        final var source = mapper.readTree(Files.readString(directory.resolve("source.json")));
        assertEquals(page, source.get("source_page").asInt());
        assertEquals(manifest.get("inputs").get(0).get("sha256").asString(), source.get("pdf_sha256").asString());
        assertEquals(792.0, source.get("page_height").asDouble());
        final boolean nativeFirst = Boolean.getBoolean("docweave.pdf.scientific.model.native.first");
        final var capturePath = directory.resolve(nativeFirst ? "native-first.json" : "native-live.json");
        final var capture = mapper.readTree(Files.readString(capturePath));
        assertEquals(nativeFirst ? nativeCaptureStrategy() : "provider_layout_native_glyphs_v1",
                capture.get("layout_strategy").asString());
        final var runtime = mapper.readTree(Files.readString(Path.of(captures, "runtime.json")));
        if (nativeFirst) {
            assertTrue(runtime.get("native_first").asBoolean());
            requireNativePolicy(runtime);
            if (Boolean.getBoolean("docweave.pdf.scientific.tagged.first")) {
                assertTrue(runtime.get("tagged_first").asBoolean());
            }
        }
        final var modelProtocol = mapper.readTree(
                Files.readString(Path.of("benchmarks/review/pdf-current-business-model/manifest.json")));
        for (final String identity : List.of("detector_sha256", "structure_sha256")) {
            assertEquals(modelProtocol.get(identity).asString(), runtime.get(identity).asString());
        }
        final var result = capturedResult(selected, capture, capturePath, mapper, expected);
        Files.write(Path.of(report), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Scores all frozen independent regions without invoking engines, dropping failures or requiring accuracy.
     * An explicit independent manifest may select another pre-frozen corpus; the historical default is unchanged.
     * @throws Exception if source labels, capture identities or fresh report checks fail
     */
    @Test
    void optionalIndependentNativeFirstSpans() throws Exception {
        final String captures = System.getProperty("docweave.pdf.independent.captures");
        final String report = System.getProperty("docweave.pdf.independent.report");
        final String explicitManifest = System.getProperty("docweave.pdf.independent.manifest");
        if (captures == null && report == null && explicitManifest == null) {
            return;
        }
        assertNotNull(captures);
        assertNotNull(report);
        final String cache = System.getProperty("docweave.pdf.independent.cache");
        assertNotNull(cache);
        final var mapper = JsonMapper.builder().build();
        final var manifestPath =
                Path.of(explicitManifest == null ? "benchmarks/review/pdf-native-first-independent/manifest.json"
                                                 : explicitManifest);
        final var manifest = mapper.readTree(Files.readString(manifestPath));
        verifyInputs(cache, manifest);
        final var runtime = mapper.readTree(Files.readString(Path.of(captures, "runtime.json")));
        assertTrue(runtime.get("native_first").asBoolean());
        requireNativePolicy(runtime);
        if (Boolean.getBoolean("docweave.pdf.scientific.tagged.first")) {
            assertTrue(runtime.get("tagged_first").asBoolean());
        }
        final var protocol = mapper.readTree(
                Files.readString(Path.of("benchmarks/review/pdf-current-business-model/manifest.json")));
        for (final String identity : List.of("detector_sha256", "structure_sha256")) {
            assertEquals(protocol.get(identity).asString(), runtime.get(identity).asString());
        }
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final var directory = Path.of(captures, selected.get("id").asString());
            final var source = mapper.readTree(Files.readString(directory.resolve("source.json")));
            assertEquals(selected.get("source_page").asInt(), source.get("source_page").asInt());
            assertEquals(selected.get("source_page_height").asDouble(), source.get("page_height").asDouble());
            for (final var input : manifest.get("inputs")) {
                if (input.get("filename").asString().equals(selected.get("pdf").asString())) {
                    assertEquals(input.get("sha256").asString(), source.get("pdf_sha256").asString());
                }
            }
            final var capturePath = directory.resolve("native-first.json");
            final var capture = mapper.readTree(Files.readString(capturePath));
            assertEquals(nativeCaptureStrategy(), capture.get("layout_strategy").asString());
            final var expected =
                    labels(region(parse(Files.readAllBytes(Path.of(cache, selected.get("cells").asString()))),
                            selected.get("source_page").asInt(), selected.get("region_id").asString()));
            assertEquals(selected.get("expected_anchors").asInt(), expected.size());
            assertEquals(selected.get("expected_rows").asInt(),
                    expected.keySet().stream().mapToInt(key -> key.row() + key.rows()).max().orElseThrow());
            assertEquals(selected.get("expected_columns").asInt(),
                    expected.keySet().stream().mapToInt(key -> key.column() + key.columns()).max().orElseThrow());
            final var original = region(parse(Files.readAllBytes(Path.of(cache, selected.get("regions").asString()))),
                    selected.get("source_page").asInt(), selected.get("region_id").asString());
            final var bounds = (org.w3c.dom.Element) original.getElementsByTagName("bounding-box").item(0);
            final double height = selected.get("source_page_height").asDouble();
            final double[] roi = {Double.parseDouble(bounds.getAttribute("x1")),
                    height - Double.parseDouble(bounds.getAttribute("y2")),
                    Double.parseDouble(bounds.getAttribute("x2")),
                    height - Double.parseDouble(bounds.getAttribute("y1"))};
            for (int index = 0; index < roi.length; index++) {
                assertEquals(selected.get("roi_left_top_right_bottom").get(index).asDouble(), roi[index]);
            }
            final var result = capturedResult(selected, capture, capturePath, mapper, expected);
            result.put("id", selected.get("id").asString());
            results.add(result);
        }
        Files.write(Path.of(report),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                        Map.of("runtime", Runtime.version().toString(), "results", results)),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Evaluates frozen publisher tables against independent JATS logical headers and body cells.
     * The explicit publisher.manifest property selects an additional frozen manifest; the historical default stays.
     * Publisher logical spans are not silently promoted to physical PDF border annotations.
     * @throws Exception if opt-in input identities, source frames, annotations or output checks fail
     */
    @Test
    void optionalRecentPublisherHeaders() throws Exception {
        final String cache = System.getProperty("docweave.pdf.publisher.cache");
        final String captures = System.getProperty("docweave.pdf.publisher.captures");
        final String report = System.getProperty("docweave.pdf.publisher.report");
        if (cache == null && captures == null && report == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(captures);
        assertNotNull(report);
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(System.getProperty(
                "docweave.pdf.publisher.manifest", "benchmarks/review/pdf-recent-scientific-headers/manifest.json"))));
        verifyInputs(cache, manifest);
        final var runtime = mapper.readTree(Files.readAllBytes(Path.of(captures, "runtime.json")));
        assertTrue(runtime.get("native_first").asBoolean());
        requireNativePolicy(runtime);
        if (Boolean.getBoolean("docweave.pdf.scientific.tagged.first")) {
            assertTrue(runtime.get("tagged_first").asBoolean());
        }
        final var modelPins = mapper.readTree(
                Files.readAllBytes(Path.of("benchmarks/review/pdf-current-business-model/manifest.json")));
        for (final String identity : List.of("detector_sha256", "structure_sha256")) {
            assertEquals(modelPins.get(identity).asString(), runtime.get(identity).asString());
        }
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final var source = mapper.readTree(
                    Files.readAllBytes(Path.of(captures, selected.get("id").asString(), "source.json")));
            assertEquals(selected.get("source_page").asInt(), source.get("source_page").asInt());
            assertEquals(selected.get("source_page_height").asDouble(), source.get("page_height").asDouble());
            for (final var input : manifest.get("inputs")) {
                if (input.get("filename").asString().equals(selected.get("pdf").asString())) {
                    assertEquals(input.get("sha256").asString(), source.get("pdf_sha256").asString());
                }
            }
            final var document = publisherXml(Files.readAllBytes(Path.of(cache, selected.get("jats").asString())));
            final var tables = document.getElementsByTagName("table-wrap");
            org.w3c.dom.Element chosen = null;
            for (int i = 0; i < tables.getLength(); i++) {
                final var table = (org.w3c.dom.Element) tables.item(i);
                if (table.getAttribute("id").equals(selected.get("table_id").asString())) {
                    assertNull(chosen, "Duplicate publisher table identifier");
                    chosen = table;
                }
            }
            assertNotNull(chosen);
            final var expected = publisherLabels(chosen);
            assertEquals(selected.get("expected_anchors").asInt(), expected.size());
            assertEquals(selected.get("expected_rows").asInt(),
                    expected.keySet().stream().mapToInt(key -> key.row() + key.rows()).max().orElseThrow());
            assertEquals(selected.get("expected_columns").asInt(),
                    expected.keySet().stream().mapToInt(key -> key.column() + key.columns()).max().orElseThrow());
            assertEquals(1, chosen.getElementsByTagName("thead").getLength());
            assertEquals(selected.get("publisher_header_rows").asInt(),
                    ((org.w3c.dom.Element) chosen.getElementsByTagName("thead").item(0))
                            .getElementsByTagName("tr")
                            .getLength());
            final int headerRows = selected.get("publisher_header_rows").asInt();
            assertEquals(selected.get("expected_header_anchors").asLong(),
                    expected.keySet().stream().filter(key -> key.row() < headerRows).count());
            final var capturePath = Path.of(captures, selected.get("id").asString(), "native-first.json");
            final var capture = mapper.readTree(Files.readAllBytes(capturePath));
            assertEquals(nativeCaptureStrategy(), capture.get("layout_strategy").asString());
            final var result = capturedResult(selected, capture, capturePath, mapper, expected);
            result.put("id", selected.get("id").asString());
            result.put("reference_kind", "publisher_jats_logical_table");
            results.add(result);
        }
        Files.write(Path.of(report),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                        Map.of("runtime", Runtime.version().toString(), "results", results)),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Scores an independently captured pipeline using the unchanged publisher labels and geometry association.
     * @param selected original frozen region and denominators
     * @param capture selected-page tables in the common metadata representation, including failures
     * @param capturePath original raw capture identity
     * @param mapper local diagnostic converter
     * @param xml unchanged verified publisher XML bytes
     * @return mutable numeric-only result with all missing/extra keys retained
     * @throws Exception if reference identity or emitted representation is invalid
     */
    static Map<String, Object> scorePublisherCapture(final JsonNode selected, final JsonNode capture,
            final Path capturePath, final JsonMapper mapper, final byte[] xml) throws Exception {
        final var document = publisherXml(xml);
        final var wraps = document.getElementsByTagName("table-wrap");
        org.w3c.dom.Element chosen = null;
        for (int index = 0; index < wraps.getLength(); index++) {
            final var table = (org.w3c.dom.Element) wraps.item(index);
            if (table.getAttribute("id").equals(selected.get("table_id").asString())) {
                assertNull(chosen);
                chosen = table;
            }
        }
        assertNotNull(chosen);
        final var expected = publisherLabels(chosen);
        assertEquals(selected.get("expected_anchors").asInt(), expected.size());
        assertEquals(selected.get("expected_rows").asInt(),
                expected.keySet().stream().mapToInt(key -> key.row() + key.rows()).max().orElseThrow());
        assertEquals(selected.get("expected_columns").asInt(),
                expected.keySet().stream().mapToInt(key -> key.column() + key.columns()).max().orElseThrow());
        assertEquals(1, chosen.getElementsByTagName("thead").getLength());
        final int headerRows = ((org.w3c.dom.Element) chosen.getElementsByTagName("thead").item(0))
                                       .getElementsByTagName("tr")
                                       .getLength();
        assertEquals(selected.get("publisher_header_rows").asInt(), headerRows);
        assertEquals(selected.get("expected_header_anchors").asLong(),
                expected.keySet().stream().filter(key -> key.row() < headerRows).count());
        return capturedResult(selected, capture, capturePath, mapper, expected);
    }

    /**
     * Parses publisher XML while permitting its inert external DOCTYPE declaration, without fetching DTDs or entities.
     * @param bytes original bounded source bytes
     * @return parsed original document
     * @throws Exception if XML is malformed, oversized or declares internal entities
     */
    private static Document publisherXml(final byte[] bytes) throws Exception {
        return ScientificPublisherXml.parse(bytes);
    }

    /**
     * Reads publisher logical cells, retaining declared row/column spans and their full normalized content.
     * @param tableWrap original selected JATS table wrapper
     * @return immutable independently authored logical anchor map
     */
    private static Map<Key, String> publisherLabels(final org.w3c.dom.Element tableWrap) {
        final var tables = tableWrap.getElementsByTagName("table");
        assertEquals(1, tables.getLength());
        final var rows = ((org.w3c.dom.Element) tables.item(0)).getElementsByTagName("tr");
        assertTrue(rows.getLength() > 0 && rows.getLength() <= 1024);
        final var occupied = new HashSet<String>();
        final var values = new HashMap<Key, String>();
        for (int row = 0; row < rows.getLength(); row++) {
            int column = 0;
            for (var child = rows.item(row).getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof final org.w3c.dom.Element cell)
                        || !(cell.getTagName().equals("td") || cell.getTagName().equals("th"))) {
                    continue;
                }
                while (occupied.contains(row + ":" + column)) {
                    column++;
                }
                final int rs = cell.hasAttribute("rowspan") ? Integer.parseInt(cell.getAttribute("rowspan")) : 1;
                final int cs = cell.hasAttribute("colspan") ? Integer.parseInt(cell.getAttribute("colspan")) : 1;
                assertTrue(rs > 0 && cs > 0 && rs <= rows.getLength() - row && cs <= 1024 && column + cs <= 1024
                           && (long) rs * cs <= 1024);
                for (int r = row; r < row + rs; r++) {
                    for (int c = column; c < column + cs; c++) {
                        assertTrue(occupied.size() < 16384);
                        assertTrue(occupied.add(r + ":" + c), "Overlapping publisher cells");
                    }
                }
                // Clone before replacing explicit line breaks, preserving the original XML tree and inline text.
                final var copy = (org.w3c.dom.Element) cell.cloneNode(true);
                final var breaks = copy.getElementsByTagName("break");
                while (breaks.getLength() > 0) {
                    final var lineBreak = breaks.item(0);
                    lineBreak.getParentNode().replaceChild(copy.getOwnerDocument().createTextNode(" "), lineBreak);
                }
                assertNull(values.put(new Key(row, column, rs, cs), normalize(copy.getTextContent())));
                column += cs;
            }
        }
        return Map.copyOf(values);
    }

    /**
     * Checks inert public DTD handling, entity refusal, inline text, header breaks and body rowspan ownership.
     * @throws Exception if owned publisher XML cannot be parsed
     */
    @Test
    void publisherReferencesPreserveAuthoredCellsWithoutExternalResolution() throws Exception {
        final String xml =
                "<!DOCTYPE article SYSTEM 'file:///unavailable'><article><table-wrap><table><thead><tr><th>A<break/>B</th><th>C</th></tr></thead><tbody><tr><td rowspan='2'>X<italic>Y</italic></td><td>1</td></tr><tr><td>2</td></tr></tbody></table></table-wrap></article>";
        final var document = publisherXml(xml.getBytes(StandardCharsets.UTF_8));
        final var labels = publisherLabels((org.w3c.dom.Element) document.getElementsByTagName("table-wrap").item(0));
        assertEquals("A B", labels.get(new Key(0, 0, 1, 1)));
        assertEquals("XY", labels.get(new Key(1, 0, 2, 1)));
        assertEquals("2", labels.get(new Key(2, 1, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> publisherXml("<!DOCTYPE article SYSTEM 'file:///unavailable'><article>&unresolved;</article>"
                                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> publisherXml(
                                "<!DOCTYPE article [<!ENTITY x SYSTEM 'file:///unavailable'>]><article>&x;</article>"
                                        .getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Source header and data cells retain their shared row-major order in diagnostic scoring.
     * @throws Exception if owned emitted table metadata cannot be parsed
     */
    @Test
    void taggedHeaderAnchorsKeepSourceOrder() throws Exception {
        final var cells = List.of(Map.of("row_index", 0, "column_index", 0, "row_span", 1, "column_span", 2),
                Map.of("row_index", 1, "column_index", 0, "row_span", 1, "column_span", 1),
                Map.of("row_index", 1, "column_index", 1, "row_span", 1, "column_span", 1));
        final var observed = anchors(Map.of("text_as_html",
                "<table><tbody><tr><th colspan='2'>Header</th></tr><tr><td>Body</td><th>Side</th></tr></tbody></table>",
                "pdf_table_cells", Map.of("cells", cells)));
        assertEquals(Map.of(new Key(0, 0, 1, 2), "Header", new Key(1, 0, 1, 1), "Body", new Key(1, 1, 1, 1), "Side"),
                observed);
    }

    /**
     * Compares one independently labelled region using geometry association and exact span/text identities.
     * @param selected frozen source region
     * @param capture complete selected-page capture, including any failure
     * @param capturePath original local capture for byte identity
     * @param mapper diagnostic map converter
     * @param expected independently parsed reference anchors
     * @return mutable numeric-only report retaining missing and extra keys
     * @throws Exception if emitted geometry or HTML is invalid
     */
    private static Map<String, Object> capturedResult(final tools.jackson.databind.JsonNode selected,
            final tools.jackson.databind.JsonNode capture, final Path capturePath, final JsonMapper mapper,
            final Map<Key, String> expected) throws Exception {
        final int page = selected.get("source_page").asInt();
        final double[] roi = new double[4];
        for (int index = 0; index < 4; index++) {
            roi[index] = selected.get("roi_left_top_right_bottom").get(index).asDouble();
        }
        Map<?, ?> associated = null;
        double best = 0;
        for (final var table : capture.get("tables")) {
            assertEquals(page, table.get("page_number").asInt());
            final Map<?, ?> metadata = mapper.convertValue(table, Map.class);
            final double overlap = iou(roi, rectangle((Map<?, ?>)metadata.get("coordinates")));
            if (overlap >= 0.5 && overlap > best) {
                best = overlap;
                associated = metadata;
            }
        }
        final var observed = associated == null ? Map.<Key, String>of() : anchors(associated);
        final int matching = (int) expected.keySet().stream().filter(observed::containsKey).count();
        final int exact = (int) expected.entrySet()
                                  .stream()
                                  .filter(entry -> entry.getValue().equals(observed.get(entry.getKey())))
                                  .count();
        final var result = new LinkedHashMap<String, Object>();
        result.put("runtime", Runtime.version().toString());
        result.put("outcome", capture.get("outcome").asString());
        result.put("tables_on_page", capture.get("tables").size());
        result.put("associated", associated != null);
        result.put("association_iou", best);
        result.put("expected_anchors", expected.size());
        result.put("observed_anchors", observed.size());
        result.put("expected_rows", selected.get("expected_rows").asInt());
        result.put("expected_columns", selected.get("expected_columns").asInt());
        if (associated != null) {
            final var geometry = (Map<?, ?>) associated.get("pdf_table_cells");
            result.put("observed_rows", geometry.get("row_count"));
            result.put("observed_columns", geometry.get("column_count"));
            result.put("extraction_method", associated.get("pdf_table_method"));
            if (associated.containsKey("pdf_table_joined_soft_hyphens")) {
                result.put("joined_soft_hyphens", associated.get("pdf_table_joined_soft_hyphens"));
            }
            if (associated.containsKey("pdf_table_joined_iso_dates")) {
                result.put("joined_iso_dates", associated.get("pdf_table_joined_iso_dates"));
                result.put("cell_text_policy", associated.get("pdf_table_cell_text_policy"));
            }
        }
        result.put("matching_span_keys", matching);
        result.put("exact_span_text_anchors", exact);
        result.put("missing_reference_keys", expected.size() - matching);
        result.put("extra_prediction_keys", observed.size() - matching);
        result.put("complete_topology", expected.keySet().equals(observed.keySet()));
        result.put("complete_span_text", expected.keySet().equals(observed.keySet()) && exact == expected.size());
        if (selected.has("leading_row_window")) {
            result.put("leading_row_window",
                    leadingWindow(expected, observed, selected.get("leading_row_window").asInt()));
        }
        result.put("capture_sha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(capturePath))));
        return result;
    }

    /**
     * Scores a fixed positional leading-row window without inventing semantic header roles or clipping spans.
     * @param expected published original anchors
     * @param observed geometrically associated predictions, empty on a miss
     * @param rows fixed positive number of leading logical rows
     * @return numeric counts retaining missing anchors, text mismatches and extra predictions
     */
    private static Map<String, Object> leadingWindow(
            final Map<Key, String> expected, final Map<Key, String> observed, final int rows) {
        assertTrue(rows > 0 && rows <= 1024);
        final var reference = new HashMap<Key, String>();
        final var prediction = new HashMap<Key, String>();
        expected.forEach((key, value) -> {
            if (key.row() < rows) {
                reference.put(key, value);
            }
        });
        observed.forEach((key, value) -> {
            if (key.row() < rows) {
                prediction.put(key, value);
            }
        });
        final long matching = reference.keySet().stream().filter(prediction::containsKey).count();
        final long exact = reference.entrySet()
                                   .stream()
                                   .filter(entry -> entry.getValue().equals(prediction.get(entry.getKey())))
                                   .count();
        return Map.of("rows", rows, "expected_anchors", reference.size(), "observed_anchors", prediction.size(),
                "matching_span_keys", matching, "exact_span_text_anchors", exact, "extra_prediction_keys",
                prediction.size() - matching, "complete_span_text", reference.equals(prediction));
    }

    /** Checks whole-span window ownership, wrong-text matches, extra predictions and missing-table denominators. */
    @Test
    void leadingWindowsRetainCrossingSpansAndNegativeCases() {
        final var crossing = new Key(0, 0, 3, 1);
        final var lower = new Key(1, 1, 1, 1);
        final var body = new Key(2, 1, 1, 1);
        final var expected = Map.of(crossing, "Wrapped", lower, "Stacked", body, "Body");
        assertEquals(true, leadingWindow(expected, expected, 2).get("complete_span_text"));
        final var wrongText = leadingWindow(expected, Map.of(crossing, "Different", lower, "Stacked"), 2);
        assertEquals(2L, wrongText.get("matching_span_keys"));
        assertEquals(1L, wrongText.get("exact_span_text_anchors"));
        assertEquals(false, wrongText.get("complete_span_text"));
        final var extra = new HashMap<>(expected);
        extra.put(new Key(1, 2, 1, 1), "Extra");
        assertEquals(1L, leadingWindow(expected, extra, 2).get("extra_prediction_keys"));
        assertEquals(false, leadingWindow(expected, extra, 2).get("complete_span_text"));
        final var missing = leadingWindow(expected, Map.of(), 2);
        assertEquals(2, missing.get("expected_anchors"));
        assertEquals(0L, missing.get("exact_span_text_anchors"));
        assertThrows(AssertionError.class, () -> leadingWindow(expected, expected, 0));
    }

    /**
     * Checks original PDF/XML byte and repository-blob identities for both diagnostic representations.
     * @param cache explicit fetch-only source directory
     * @param manifest frozen scientific source identities
     * @throws Exception if a source is missing, altered or cannot be hashed
     */
    private static void verifyInputs(final String cache, final tools.jackson.databind.JsonNode manifest)
            throws Exception {
        for (final var input : manifest.get("inputs")) {
            final byte[] bytes = Files.readAllBytes(Path.of(cache, input.get("filename").asString()));
            assertEquals(input.get("bytes").asLong(), bytes.length);
            assertEquals(input.get("sha256").asString(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            final var blob = MessageDigest.getInstance("SHA-1");
            blob.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            assertEquals(input.get("git_blob_sha1").asString(), HexFormat.of().formatHex(blob.digest(bytes)));
        }
    }

    /**
     * Rejects overlapping source anchors and external entities while retaining authored spans.
     * @throws Exception if owned source annotation parsing fails
     */
    @Test
    void annotationSpansRejectOverlapAndExternalEntities() throws Exception {
        assertThrows(Exception.class,
                () -> parse("<!DOCTYPE a SYSTEM 'file:///unavailable'><a/>".getBytes(StandardCharsets.UTF_8)));
        final var valid = "<document><region page='2' id='1'><cell start-row='1' end-row='2' start-col='1' "
                          + "end-col='1'><content>001\n A</content></cell></region></document>";
        final var expected = labels(region(parse(valid.getBytes(StandardCharsets.UTF_8)), 2, "1"));
        assertEquals(Map.of(new Key(0, 0, 2, 1), "001 A"), expected);
        final var unit = "<document><region page='1' id='1'><cell start-row='0' start-col='0'>"
                         + "<content>A</content></cell></region></document>";
        assertEquals(
                Map.of(new Key(0, 0, 1, 1), "A"), labels(region(parse(unit.getBytes(StandardCharsets.UTF_8)), 1, "1")));
        final var overlap = valid.replace("</region>", "<cell start-row='2' end-row='2' start-col='1' end-col='1'>"
                                                               + "<content>B</content></cell></region>");
        assertThrows(
                AssertionError.class, () -> labels(region(parse(overlap.getBytes(StandardCharsets.UTF_8)), 2, "1")));
        assertEquals("\u2003001\u2003", normalize("\u2003001\u2003"));
        assertNotEquals(normalize("001"), normalize("1"));
        assertNotEquals(normalize("Label"), normalize("label"));
        assertEquals(1.0, iou(new double[] {1, 2, 3, 4}, new double[] {1, 2, 3, 4}));
        assertEquals(0.0, iou(new double[] {1, 2, 3, 4}, new double[] {5, 6, 7, 8}));
    }

    /**
     * Reads every original anchor after common origin conversion, validating nonoverlapping rectangular coverage.
     * Omitted XML end indices denote a unit span; unlabelled slots are never fabricated.
     * @param region original source region
     * @return immutable normalized text keyed by exact positional spans
     */
    private static Map<Key, String> labels(final org.w3c.dom.Element region) {
        final var cells = region.getElementsByTagName("cell");
        int firstRow = Integer.MAX_VALUE;
        int firstColumn = Integer.MAX_VALUE;
        for (int index = 0; index < cells.getLength(); index++) {
            final var cell = (org.w3c.dom.Element) cells.item(index);
            firstRow = Math.min(firstRow, Integer.parseInt(cell.getAttribute("start-row")));
            firstColumn = Math.min(firstColumn, Integer.parseInt(cell.getAttribute("start-col")));
        }
        final var expected = new HashMap<Key, String>();
        final var occupied = new HashSet<String>();
        for (int index = 0; index < cells.getLength(); index++) {
            final var cell = (org.w3c.dom.Element) cells.item(index);
            final int row = Integer.parseInt(cell.getAttribute("start-row"));
            final int column = Integer.parseInt(cell.getAttribute("start-col"));
            final var key = new Key(row - firstRow, column - firstColumn,
                    (cell.hasAttribute("end-row") ? Integer.parseInt(cell.getAttribute("end-row")) : row) - row + 1,
                    (cell.hasAttribute("end-col") ? Integer.parseInt(cell.getAttribute("end-col")) : column) - column
                            + 1);
            assertTrue(key.rows() > 0 && key.columns() > 0);
            for (int r = key.row(); r < key.row() + key.rows(); r++) {
                for (int c = key.column(); c < key.column() + key.columns(); c++) {
                    assertTrue(occupied.add(r + ":" + c), "Overlapping published anchors");
                }
            }
            assertNull(expected.put(key, normalize(cell.getElementsByTagName("content").item(0).getTextContent())));
        }
        return Map.copyOf(expected);
    }

    /**
     * Matches emitted row-major HTML anchors with metadata, without filling uncovered positions or joining fragments.
     * @param table actual source-associated table
     * @return immutable exact span keys and normalized strings
     * @throws Exception if generated HTML is invalid
     */
    private static Map<Key, String> anchors(final Element table) throws Exception {
        return anchors(table.metadata());
    }

    /**
     * Prevents a changed native inference policy from being scored under an unselected historical profile.
     * Missing fields in historical captures retain the original false default.
     * @param runtime explicit capture runtime and strategy metadata
     */
    private static void requireNativePolicy(final JsonNode runtime) {
        final var joining = runtime.get("cell_text_join");
        assertEquals(Boolean.getBoolean("docweave.pdf.scientific.source.soft.hyphens"),
                joining != null && joining.asString().equals("SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE"),
                "Source discretionary joining must be selected explicitly");
        final var selected = runtime.get("segmented_rows");
        assertEquals(Boolean.getBoolean("docweave.pdf.scientific.segmented.rows"),
                selected != null && selected.asBoolean(), "Native segmented-row profile must be selected explicitly");
    }

    /**
     * Requires an explicit diagnostic opt-in for source-tag precedence; historical captures retain their strategy.
     * @return expected explicitly selected capture strategy
     */
    private static String nativeCaptureStrategy() {
        return Boolean.getBoolean("docweave.pdf.scientific.tagged.first") ? "tagged_native_first_disjoint_v1"
                                                                          : "native_first_disjoint_v1";
    }

    /**
     * Reads exact positional span/text keys from either live output or an explicit local capture.
     * @param metadata unchanged generated HTML and anchor coordinates
     * @return original anchor keys and strictly normalized cell text, without covered-slot fabrication
     * @throws Exception if generated HTML or anchor alignment is invalid
     */
    private static Map<Key, String> anchors(final Map<?, ?> metadata) throws Exception {
        final var html = parse(metadata.get("text_as_html").toString().getBytes(StandardCharsets.UTF_8));
        final var nodes = html.getElementsByTagName("*");
        final var texts = new ArrayList<org.w3c.dom.Node>();
        for (int index = 0; index < nodes.getLength(); index++) {
            final var node = nodes.item(index);
            if (node.getNodeName().equals("td") || node.getNodeName().equals("th")) {
                texts.add(node);
            }
        }
        final var geometry = (Map<?, ?>)metadata.get("pdf_table_cells");
        final var cells = (List<?>) geometry.get("cells");
        assertEquals(texts.size(), cells.size());
        final var values = new HashMap<Key, String>();
        for (int index = 0; index < cells.size(); index++) {
            final var cell = (Map<?, ?>)cells.get(index);
            final var key =
                    new Key(((Number) cell.get("row_index")).intValue(), ((Number) cell.get("column_index")).intValue(),
                            ((Number) cell.get("row_span")).intValue(), ((Number) cell.get("column_span")).intValue());
            assertNull(values.put(key, normalize(texts.get(index).getTextContent())));
        }
        return Map.copyOf(values);
    }

    /**
     * Selects exactly one original page/region annotation independently of extraction results.
     * @param document pinned annotation document
     * @param page one-based page
     * @param id original region identifier
     * @return unique source region
     */
    private static org.w3c.dom.Element region(final Document document, final int page, final String id) {
        final var regions = document.getElementsByTagName("region");
        org.w3c.dom.Element match = null;
        for (int index = 0; index < regions.getLength(); index++) {
            final var region = (org.w3c.dom.Element) regions.item(index);
            if (region.getAttribute("page").equals(Integer.toString(page)) && region.getAttribute("id").equals(id)) {
                assertNull(match, "Duplicate original region");
                match = region;
            }
        }
        assertNotNull(match, "Missing original region");
        return match;
    }

    /**
     * Reads pinned XML/generated HTML without allowing external entities, DTDs or schemas.
     * @param bytes input XML
     * @return parsed local document
     * @throws Exception if XML is invalid or forbidden
     */
    private static Document parse(final byte[] bytes) throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    /**
     * Extracts displayed rectangular extents from a source parent's coordinate map.
     * @param coordinates source PDFUserSpace coordinates
     * @return left/top/right/bottom without text-based adjustment
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
     * Computes intersection over union without source/content normalization.
     * @param first original region bounds
     * @param second emitted parent bounds
     * @return zero through one, with zero for nonpositive union
     */
    private static double iou(final double[] first, final double[] second) {
        final double intersection = Math.max(0, Math.min(first[2], second[2]) - Math.max(first[0], second[0]))
                                    * Math.max(0, Math.min(first[3], second[3]) - Math.max(first[1], second[1]));
        final double union = (first[2] - first[0]) * (first[3] - first[1])
                             + (second[2] - second[0]) * (second[3] - second[1]) - intersection;
        return union > 0 ? intersection / union : 0;
    }

    /**
     * Collapses ASCII whitespace only, retaining numeric formatting, case, punctuation and Unicode.
     * @param value original or emitted cell text
     * @return comparison-only normalized string
     */
    private static String normalize(final String value) {
        return value.replaceAll("[ \\t\\r\\n\\f\\u000B]+", " ").replaceAll("^ | $", "");
    }
}
