package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.*;

/** Published cell-position oracles, independent of candidate output; optional inputs are never downloaded. */
class PdfIndependentCellsTest {
    @Test
    void optionalPublishedNativeCells() throws Exception {
        final String cache = System.getProperty("docweave.pdf.independent.cells.cache");
        final String report = System.getProperty("docweave.pdf.independent.cells.report");
        if (cache == null && report == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(report);
        final var mapper = JsonMapper.builder().build();
        final var manifest =
                mapper.readTree(Files.readAllBytes(Path.of("benchmarks/review/pdf-independent-cells/manifest.json")));
        for (final var input : manifest.get("inputs")) {
            final byte[] bytes = Files.readAllBytes(Path.of(cache).resolve(input.get("filename").asString()));
            assertEquals(input.get("bytes").asLong(), bytes.length);
            assertEquals(input.get("sha256").asString(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            final var blob = MessageDigest.getInstance("SHA-1");
            blob.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            assertEquals(input.get("git_blob_sha1").asString(), HexFormat.of().formatHex(blob.digest(bytes)));
        }
        final var reports = new ArrayList<Map<String, Object>>();
        for (final String name : List.of("eu-010", "us-038", "eu-011")) {
            final var oracle = parse(Files.readAllBytes(Path.of(cache).resolve(name + "-str.xml")));
            final var region = (org.w3c.dom.Element) oracle.getElementsByTagName("region").item(0);
            assertEquals(1, oracle.getElementsByTagName("region").getLength());
            final int page = Integer.parseInt(region.getAttribute("page"));
            final var cells = region.getElementsByTagName("cell");
            final var expected = new HashMap<String, String>();
            int firstRow = Integer.MAX_VALUE;
            int firstColumn = Integer.MAX_VALUE;
            int lastRow = -1;
            int lastColumn = -1;
            int wrapped = 0;
            for (int index = 0; index < cells.getLength(); index++) {
                final var cell = (org.w3c.dom.Element) cells.item(index);
                final int row = Integer.parseInt(cell.getAttribute("start-row"));
                final int column = Integer.parseInt(cell.getAttribute("start-col"));
                assertEquals(row, Integer.parseInt(cell.getAttribute("end-row")));
                assertEquals(column, Integer.parseInt(cell.getAttribute("end-col")));
                firstRow = Math.min(firstRow, row);
                firstColumn = Math.min(firstColumn, column);
                lastRow = Math.max(lastRow, row);
                lastColumn = Math.max(lastColumn, column);
                final String text = cell.getElementsByTagName("content").item(0).getTextContent();
                if (text.strip().contains("\n")) {
                    wrapped++;
                }
                assertNull(expected.put(row + ":" + column, normalize(text)));
            }
            final int rows = lastRow - firstRow + 1;
            final int columns = lastColumn - firstColumn + 1;
            assertEquals(rows * columns, expected.size());
            if (!name.equals("eu-011")) {
                assertEquals(1, wrapped);
            }
            final var source =
                    DocumentSource.bytes(Files.readAllBytes(Path.of(cache).resolve(name + ".pdf")), name + ".pdf");
            for (final var mode : List.of(PdfOptions.TableDetection.RULED_ONLY,
                         PdfOptions.TableDetection.RULED_AND_MULTILINE_ALIGNED_TEXT,
                         PdfOptions.TableDetection.RULED_AND_DOUBLE_BORDERS,
                         PdfOptions.TableDetection.RULED_AND_HORIZONTAL_TEXT,
                         PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT)) {
                final var result = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                                                           PdfOptions.defaults().withTableDetection(mode)))))
                                           .partition(source);
                final var tables =
                        result.elements()
                                .stream()
                                .filter(e
                                        -> e.type() == ElementType.Table
                                                   && ((Number) e.metadata().get("page_number")).intValue() == page)
                                .toList();
                final var observedShapes = new ArrayList<List<Integer>>();
                final String pageText = normalize(result.elements()
                                .stream()
                                .filter(e -> ((Number) e.metadata().get("page_number")).intValue() == page)
                                .map(e -> e.text())
                                .collect(Collectors.joining(" ")));
                final long labelsPresent = expected.values().stream().filter(pageText::contains).count();
                int complete = 0;
                int best = 0;
                boolean shape = false;
                for (final var table : tables) {
                    final var html =
                            parse(table.metadata().get("text_as_html").toString().getBytes(StandardCharsets.UTF_8));
                    final var actualRows = html.getElementsByTagName("tr");
                    final var widths = new ArrayList<Integer>();
                    int matches = 0;
                    boolean candidateShape = actualRows.getLength() == rows;
                    for (int row = 0; row < actualRows.getLength(); row++) {
                        final var actualCells = ((org.w3c.dom.Element) actualRows.item(row)).getElementsByTagName("td");
                        candidateShape &= actualCells.getLength() == columns;
                        widths.add(actualCells.getLength());
                        for (int column = 0; column < actualCells.getLength(); column++) {
                            final var actual = (org.w3c.dom.Element) actualCells.item(column);
                            candidateShape &= !actual.hasAttribute("rowspan") && !actual.hasAttribute("colspan");
                            if (!actual.hasAttribute("rowspan") && !actual.hasAttribute("colspan")
                                    && normalize(actual.getTextContent())
                                            .equals(expected.get((row + firstRow) + ":" + (column + firstColumn)))) {
                                matches++;
                            }
                        }
                    }
                    observedShapes.add(List.copyOf(widths));
                    if (candidateShape && matches == expected.size()) {
                        complete++;
                    }
                    best = Math.max(best, matches);
                    shape |= candidateShape;
                }
                if (name.equals("eu-010")) {
                    assertEquals(1, complete, "Published wrapped-header table must reconstruct completely: " + mode);
                    assertEquals(22, best);
                }
                if (name.equals("us-038") && mode == PdfOptions.TableDetection.RULED_AND_DOUBLE_BORDERS) {
                    assertEquals(1, complete, "Published double-border table must reconstruct completely");
                    assertEquals(16, best);
                }
                if (name.equals("eu-011") && mode == PdfOptions.TableDetection.RULED_AND_HORIZONTAL_TEXT) {
                    assertEquals(1, complete, "Published horizontal-rule table must reconstruct completely");
                    assertEquals(30, best);
                }
                final var entry = new LinkedHashMap<String, Object>(Map.of("input", name, "mode", mode.name(), "page",
                        page, "expected_rows", rows, "expected_columns", columns, "expected_cells", expected.size(),
                        "published_multiline_cells", wrapped, "tables_on_page", tables.size(), "has_expected_shape",
                        shape, "best_position_text_matches", best));
                entry.put("observed_row_widths", observedShapes);
                entry.put("complete_oracle_matches", complete);
                entry.put("labels_present_in_page_text", labelsPresent);
                reports.add(entry);
            }
        }
        Files.write(Path.of(report),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("runtime",
                        Runtime.version().toString(), "scope",
                        "Whole native PDFs; published unmerged cell-position text oracle; diagnostic misses are retained",
                        "comparison", "Whitespace collapse only; no case, punctuation, numeric or label correction",
                        "results", reports)),
                StandardOpenOption.CREATE_NEW);
    }

    @Test
    void annotationParserRejectsExternalEntitiesAndNormalizationPreservesContent() {
        assertThrows(Exception.class,
                () -> parse("<!DOCTYPE a SYSTEM 'file:///unavailable'><a/>".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Signed TA (EURm)", normalize("Signed TA\n(EURm)"));
        assertNotEquals(normalize("001"), normalize("1"));
        assertNotEquals(normalize("Impact"), normalize("impact"));
    }

    /** Parses pinned XML or generated table HTML with all external resource access disabled. */
    private static Document parse(final byte[] bytes) throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    /** Collapses whitespace without changing labels, numbers, case or punctuation. */
    private static String normalize(final String value) {
        return value.replaceAll("\\s+", " ").strip();
    }
}
