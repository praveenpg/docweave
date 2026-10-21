package dev.docweave.chunking;

import dev.docweave.DocWeave;
import dev.docweave.batch.BatchProcessor;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import dev.docweave.partition.DocumentSource;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class StructuredTableChunkTest {
    private final Chunker chunker = Chunker.create();
    private final ElementJson json = ElementJson.create();

    private Element table(final String html) {
        return new Element("source-table", ElementType.Table, Jsoup.parseBodyFragment(html).text(),
                Map.of("text_as_html", html, "filename", "source.html"));
    }

    private ChunkOptions options(final int max) {
        return ChunkOptions.builder().maxCharacters(max).build();
    }

    private List<Element> split(final String html, final int max) {
        return chunker.basic(List.of(table(html)), options(max));
    }

    private String rows(final int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "<tr><td>value" + i + "</td><td>00" + i + "</td></tr>")
                .collect(java.util.stream.Collectors.joining());
    }

    private void assertBudgetsAndSynchronization(final List<Element> chunks, final int max) {
        for (final var chunk : chunks) {
            assertTrue(chunk.text().codePointCount(0, chunk.text().length()) <= max, chunk.text());
            final var html = (String) chunk.metadata().get("text_as_html");
            assertNotNull(html);
            assertTrue(html.codePointCount(0, html.length()) <= max, html);
            final var parsed = Jsoup.parseBodyFragment(html);
            assertEquals(1, parsed.select("table").size());
            final var cells =
                    parsed.select("td, th").stream().map(c -> c.wholeText()).filter(s -> !s.isEmpty()).toList();
            assertEquals(String.join(" ", cells), chunk.text());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, String> reconstructCellTexts(final List<Element> chunks) {
        final var parts = new java.util.TreeMap<Integer, java.util.TreeMap<Integer, String>>();
        for (final var chunk : chunks) {
            final var htmlCells = Jsoup.parseBodyFragment((String) chunk.metadata().get("text_as_html")).select("tr");
            final int carried = ((Number) chunk.metadata().get("num_carried_over_header_rows")).intValue();
            final var cells = htmlCells.stream()
                                      .skip(carried)
                                      .flatMap(row -> row.children().stream())
                                      .filter(c -> List.of("td", "th").contains(c.normalName()))
                                      .toList();
            final var info = (List<Map<String, Object>>) chunk.metadata().get("table_cell_fragments");
            assertEquals(cells.size(), info.size());
            for (int i = 0; i < info.size(); i++) {
                final var item = info.get(i);
                if (Boolean.TRUE.equals(item.get("is_span_continuation"))) {
                    assertEquals("", cells.get(i).wholeText());
                    continue;
                }
                int index = ((Number) item.get("cell_index")).intValue();
                final int start = ((Number) item.get("text_start")).intValue();
                final int end = ((Number) item.get("text_end")).intValue();
                assertEquals(
                        end - start, cells.get(i).wholeText().codePointCount(0, cells.get(i).wholeText().length()));
                assertNull(parts.computeIfAbsent(index, ignored -> new java.util.TreeMap<>())
                                   .put(start, cells.get(i).wholeText()),
                        "Source cell data must not duplicate across fragments");
            }
        }
        final var result = new java.util.TreeMap<Integer, String>();
        parts.forEach((key, value) -> result.put(key, String.join("", value.values())));
        return result;
    }

    @Test
    void compactsSmallTablesAndSynchronizesTextFromAuthoritativeCells() {
        final var chunks =
                split("<table "
                                + "class='cosmetic'><thead><tr><th>A</th><th>B</th></tr></thead><tbody><tr><td>one</"
                                + "td><td>two</td></tr></tbody></table>",
                        500);
        assertEquals(1, chunks.size());
        assertEquals(ElementType.Table, chunks.getFirst().type());
        assertEquals("A B one two", chunks.getFirst().text());
        assertEquals("<table><tr><td>A</td><td>B</td></tr><tr><td>one</td><td>two</td></tr></table>",
                chunks.getFirst().metadata().get("text_as_html"));
    }

    @Test
    void splitsWholeRowsWithSequenceAndCompleteProvenance() {
        final var source = table("<table>" + rows(8) + "</table>");
        final var chunks = chunker.basic(List.of(source), options(110));
        assertTrue(chunks.size() > 1);
        assertBudgetsAndSynchronization(chunks, 110);
        final Object id = chunks.getFirst().metadata().get("table_id");
        for (int i = 0; i < chunks.size(); i++) {
            var c = chunks.get(i);
            assertEquals(ElementType.TableChunk, c.type());
            assertEquals(id, c.metadata().get("table_id"));
            assertEquals(i, c.metadata().get("chunk_index"));
            assertEquals(i > 0 ? true : null, c.metadata().get("is_continuation"));
            assertEquals(List.of(source), json.decodeOriginals((String) c.metadata().get("orig_elements")));
        }
        assertEquals(IntStream.range(0, 8)
                             .mapToObj(i -> "value" + i + " 00" + i)
                             .collect(java.util.stream.Collectors.joining(" ")),
                chunks.stream().map(Element::text).collect(java.util.stream.Collectors.joining(" ")));
    }

    @Test
    void repeatHeadersIsDefaultAndCanBeDisabled() {
        final String html =
                "<table><thead><tr><th>Name</th><th>Id</th></tr></thead><tbody>" + rows(8) + "</tbody></table>";
        final var chunks = split(html, 140);
        assertBudgetsAndSynchronization(chunks, 140);
        assertEquals(0, chunks.getFirst().metadata().get("num_carried_over_header_rows"));
        assertTrue(chunks.stream().skip(1).allMatch(c -> c.metadata().get("num_carried_over_header_rows").equals(1)));
        assertTrue(chunks.stream().skip(1).allMatch(c -> c.text().startsWith("Name Id ")));
        final var without = chunker.basic(
                List.of(table(html)), ChunkOptions.builder().maxCharacters(140).repeatTableHeaders(false).build());
        assertTrue(without.stream().allMatch(c -> c.metadata().get("num_carried_over_header_rows").equals(0)));
        assertEquals(reconstructCellTexts(without), reconstructCellTexts(chunks));
    }

    @Test
    void blankCornerAndMultipleLeadingHeadersAreRecognized() {
        final var chunks =
                split("<table><tr><td></td><th>H</th></tr><tr><th>I</th><th>J</th></tr>" + rows(10) + "</table>", 200);
        assertBudgetsAndSynchronization(chunks, 200);
        assertTrue(chunks.stream().skip(1).anyMatch(c -> c.metadata().get("num_carried_over_header_rows").equals(2)));
    }

    @Test
    void largeHeadersNeverOverflowOrForceEmptyProgress() {
        final var chunks = split("<table><tr><th>"
                                         + "header".repeat(30) + "</th></tr>" + rows(3) + "</table>",
                80);
        assertBudgetsAndSynchronization(chunks, 80);
        assertTrue(chunks.stream().allMatch(c -> c.metadata().get("num_carried_over_header_rows").equals(0)));
        assertEquals("header".repeat(30), reconstructCellTexts(chunks).get(0));
    }

    @Test
    void oversizedRowsSplitOnCellBoundariesBeforeSplittingCellText() {
        final String html = "<table><tr>"
                            + IntStream.range(0, 8)
                                      .mapToObj(i -> "<td>cell" + i + "</td>")
                                      .collect(java.util.stream.Collectors.joining())
                            + "</tr></table>";
        final var chunks = split(html, 70);
        assertBudgetsAndSynchronization(chunks, 70);
        assertEquals(8, reconstructCellTexts(chunks).size());
        for (int i = 0; i < 8; i++) {
            assertEquals("cell" + i, reconstructCellTexts(chunks).get(i));
        }
    }

    @Test
    void oversizedCellsRespectEscapingUnicodeAndContiguousTextOffsets() {
        final String text = "🧵 & < > \" ' words ".repeat(30).strip();
        final var chunks = split("<table><tr><td>" + TableFragment.escape(text) + "</td></tr></table>", 75);
        assertBudgetsAndSynchronization(chunks, 75);
        assertEquals(text, reconstructCellTexts(chunks).get(0));
        assertTrue(chunks.stream().noneMatch(c -> c.text().chars().anyMatch(ch -> ch == 0xfffd)));
    }

    @Test
    void repeatedHeadersReserveRoomWhenAnOversizedBodyCellIsSplit() {
        final var chunks = split("<table><tr><th>H</th></tr><tr><td>"
                                         + "large body ".repeat(40) + "</td></tr></table>",
                120);
        assertBudgetsAndSynchronization(chunks, 120);
        assertTrue(chunks.stream().skip(1).allMatch(c -> c.metadata().get("num_carried_over_header_rows").equals(1)));
        assertEquals("large body ".repeat(40).strip(), reconstructCellTexts(chunks).get(1));
    }

    @Test
    void compactedTablesKeepTheirHeaderPolicyWhenRechunked() {
        final var small = split("<table><tr><th>H</th></tr>" + rows(8) + "</table>", 1000);
        final var chunks = chunker.basic(small, options(130));
        assertBudgetsAndSynchronization(chunks, 130);
        assertTrue(chunks.stream().skip(1).anyMatch(c -> c.metadata().get("num_carried_over_header_rows").equals(1)));
    }

    @Test
    void randomizedMergedTablesPreserveCellOwnershipAndBoundEveryEmittedSpan() {
        final var random = new Random(43191);
        for (int trial = 0; trial < 30; trial++) {
            final boolean[][] occupied = new boolean[6][6];
            final var expected = new LinkedHashMap<Integer, String>();
            final var html = new StringBuilder("<table>");
            int index = 0;
            for (int r = 0; r < 6; r++) {
                html.append("<tr>");
                for (int c = 0; c < 6; c++) {
                    if (occupied[r][c]) {
                        continue;
                    }
                    int spanRows = random.nextInt(Math.min(3, 6 - r)) + 1;
                    int spanCols = random.nextInt(Math.min(2, 6 - c)) + 1;
                    boolean free = true;
                    for (int rr = r; rr < r + spanRows; rr++) {
                        for (int cc = c; cc < c + spanCols; cc++) {
                            free &= !occupied[rr][cc];
                        }
                    }
                    if (!free) {
                        spanRows = 1;
                        spanCols = 1;
                    }
                    for (int rr = r; rr < r + spanRows; rr++) {
                        for (int cc = c; cc < c + spanCols; cc++) {
                            occupied[rr][cc] = true;
                        }
                    }
                    final String text = ("🧵&" + r + c).repeat(random.nextInt(8) + 1);
                    expected.put(index++, text);
                    html.append("<td rowspan='")
                            .append(spanRows)
                            .append("' colspan='")
                            .append(spanCols)
                            .append("'>")
                            .append(TableFragment.escape(text))
                            .append("</td>");
                }
                html.append("</tr>");
            }
            html.append("</table>");
            final int max = random.nextInt(140) + 60;
            final var chunks = split(html.toString(), max);
            assertBudgetsAndSynchronization(chunks, max);
            assertEquals(expected, reconstructCellTexts(chunks));
            for (final var chunk : chunks) {
                final var parsedRows =
                        Jsoup.parseBodyFragment(chunk.metadata().get("text_as_html").toString()).select("tr");
                for (int r = 0; r < parsedRows.size(); r++) {
                    for (final var cell : parsedRows.get(r).children()) {
                        if (cell.hasAttr("rowspan")) {
                            assertTrue(Integer.parseInt(cell.attr("rowspan")) <= parsedRows.size() - r);
                        }
                    }
                }
            }
        }
    }

    @Test
    void rowspansStayAtomicWhenTheConnectedGroupFits() {
        final String html = "<table><tr><td rowspan='2' colspan='2'>Span</td><td>A</td></tr><tr><td>B</td></tr><tr><td>"
                            + "tail ".repeat(30) + "</td></tr></table>";
        final var chunks = split(html, 140);
        assertBudgetsAndSynchronization(chunks, 140);
        assertTrue(chunks.getFirst().metadata().get("text_as_html").toString().contains("rowspan=\"2\""));
        assertEquals(2, chunks.getFirst().metadata().get("table_row_end"));
    }

    @Test
    void oversizedSpanGroupsCarryGeometryWithoutDuplicatingOwnedText() {
        final String html = "<table><tr><td rowspan='3' colspan='2'>"
                            + "Long span ".repeat(15)
                            + "</td><td>row0</td></tr><tr><td>row1</td></tr><tr><td>row2</td></tr></table>";
        final var chunks = split(html, 100);
        assertBudgetsAndSynchronization(chunks, 100);
        final var values = reconstructCellTexts(chunks);
        assertEquals("Long span ".repeat(15).strip(), values.get(0));
        assertEquals(List.of("row0", "row1", "row2"), IntStream.range(1, 4).mapToObj(values::get).toList());
        assertTrue(chunks.stream().anyMatch(
                c -> c.metadata().get("table_cell_fragments").toString().contains("is_span_continuation=true")));
        assertTrue(
                chunks.stream().noneMatch(c -> c.metadata().get("text_as_html").toString().contains("rowspan=\"3\"")));
    }

    @Test
    void rowspanZeroStopsAtItsRowGroupAndOverreachingSpansAreClipped() {
        final var result = split(
                "<table><tbody><tr><td rowspan='0'>A</td><td>B</td></tr><tr><td>C</td></tr></tbody><tbody><tr><td "
                        + "rowspan='999'>D</td><td>E</td></tr></tbody></table>",
                500)
                                   .getFirst();
        final String html = result.metadata().get("text_as_html").toString();
        assertTrue(html.contains("rowspan=\"2\""));
        assertFalse(html.contains("999"));
        assertFalse(html.contains("rowspan=\"0\""));
    }

    @Test
    void emptyCellsAndFullySpanCoveredRowsRemainStructuralContent() {
        final String html = "<table><tr><td rowspan='2' colspan='2'>A</td></tr><tr></tr><tr><td></td><td>B</td></tr>"
                            + rows(4) + "</table>";
        final var chunks = split(html, 110);
        assertBudgetsAndSynchronization(chunks, 110);
        final var values = reconstructCellTexts(chunks);
        assertEquals("", values.get(1));
        assertEquals("B", values.get(2));
        assertTrue(chunks.stream().anyMatch(c -> c.metadata().get("text_as_html").toString().contains("<tr></tr>")));
        assertEquals("<table><tr><td/></tr></table>",
                split("<table><tr><td></td></tr></table>", 500).getFirst().metadata().get("text_as_html"));
    }

    @Test
    void skipTableChunkingPreservesIdentityAndAllMetadataRegardlessOfBudget() {
        final var source = new Element("original", ElementType.Table, "raw \n text",
                Map.of("text_as_html", "invalid", "orig_elements", "untouched", "parent_id", "heading"));
        final var options =
                ChunkOptions.builder().maxCharacters(1).skipTableChunking(true).includeOriginalElements(false).build();
        assertSame(source, chunker.basic(List.of(source), options).getFirst());
        assertSame(source, chunker.byTitle(List.of(source), options).getFirst());
    }

    @Test
    void malformedAndUnsupportedStructuresFailExplicitlyUnlessBypassed() {
        for (final String html :
                List.of("plain", "<table></table><table></table>", "<table><tr><td colspan='bad'>x</td></tr></table>",
                        "<table><tr><td colspan='10001'>x</td></tr></table>",
                        "<table><caption>Caption</caption><tr><td>x</td></tr></table>",
                        "<table><tr><td><table><tr><td>x</td></tr></table></td></tr></table>")) {
            assertThrows(IllegalArgumentException.class, () -> split(html, 100));
        }
        final var wrongType = new Element("bad", ElementType.Table, "text", Map.of("text_as_html", 1));
        assertThrows(IllegalArgumentException.class, () -> chunker.basic(List.of(wrongType), options(100)));
    }

    @Test
    void tinyFallbackDoesNotRetainStaleStructuralCoordinatesOnRechunking() {
        final var first = split("<table><tr><th>Header</th></tr>" + rows(8) + "</table>", 120);
        final var tiny = chunker.basic(first, options(4));
        assertTrue(tiny.stream().noneMatch(c
                -> c.metadata().containsKey("table_cell_fragments") || c.metadata().containsKey("table_row_start")
                           || c.metadata().containsKey("table_leading_header_rows")));
        assertTrue(tiny.stream().allMatch(c -> c.metadata().get("num_carried_over_header_rows").equals(0)));
    }

    @Test
    void impossibleEscapedSpanOverheadFailsWithoutLooping() {
        assertThrows(IllegalArgumentException.class,
                () -> split("<table><tr><td colspan='10000'>&quot;</td></tr></table>", 50));
    }

    @Test
    void tableBoundariesAndNarrativeOverlapStayIsolatedInBothStrategies() {
        final var before = new Element("before", ElementType.NarrativeText, "Before.", Map.of());
        final var after = new Element("after", ElementType.NarrativeText, "After.", Map.of());
        final var source = table("<table>" + rows(10) + "</table>");
        final var options = ChunkOptions.builder().maxCharacters(100).overlap(10).overlapAll(true).build();
        for (final var strategy : Chunker.Strategy.values()) {
            final var chunks = chunker.chunk(List.of(before, source, after), strategy, options);
            assertEquals("Before.", chunks.getFirst().text());
            assertEquals("After.", chunks.getLast().text());
            assertTrue(chunks.subList(1, chunks.size() - 1).stream().allMatch(e -> e.type() == ElementType.TableChunk));
        }
    }

    @Test
    void staleWholeTableLinksAndCellArraysAreNotCopiedToFragments() {
        final var metadata = new LinkedHashMap<String, Object>(table("<table>" + rows(8) + "</table>").metadata());
        metadata.put("link_texts", List.of("source"));
        metadata.put("link_urls", List.of("https://example.com"));
        metadata.put("table_as_cells", List.of(Map.of("cell", "source")));
        metadata.put("table_column_alignments", List.of("left"));
        final var source = new Element("source", ElementType.Table, "whole table", metadata);
        final var chunks = chunker.basic(List.of(source), options(100));
        assertTrue(chunks.stream().allMatch(
                c -> !c.metadata().containsKey("link_urls") && !c.metadata().containsKey("table_as_cells")));
        assertEquals(List.of(source), json.decodeOriginals((String) chunks.getFirst().metadata().get("orig_elements")));
    }

    @Test
    void optionalProvenanceAndRechunkingDoNotNestOriginals() {
        final var source = table("<table>" + rows(10) + "</table>");
        final var chunks = chunker.basic(List.of(source), options(140));
        assertTrue(
                chunker.basic(List.of(source),
                               ChunkOptions.builder().maxCharacters(140).includeOriginalElements(false).build())
                        .stream()
                        .noneMatch(c -> c.metadata().containsKey("orig_elements")));
        final var again = chunker.basic(chunks, options(80));
        for (final var chunk : again) {
            assertTrue(json.decodeOriginals((String) chunk.metadata().get("orig_elements"))
                            .stream()
                            .noneMatch(e -> e.metadata().containsKey("orig_elements")));
        }
    }

    @Test
    void nativeHtmlMarkdownCsvTsvAndDocxTablesUseTheSharedSplitter() throws Exception {
        final var processor = DocWeave.create();
        final var inputs = List.of(DocumentSource.text("<table>" + rows(8) + "</table>", "table.html"),
                DocumentSource.text("| Name | Id |\n| --- | --- |\n"
                                            + IntStream.range(0, 10)
                                                    .mapToObj(i -> "| value" + i + " | 007 |\n")
                                                    .collect(java.util.stream.Collectors.joining()),
                        "table.md"),
                DocumentSource.text("Name,Id\n"
                                            + IntStream.range(0, 10)
                                                    .mapToObj(i -> "value" + i + ",007\n")
                                                    .collect(java.util.stream.Collectors.joining()),
                        "table.csv"),
                DocumentSource.text("Name\tId\n"
                                            + IntStream.range(0, 10)
                                                    .mapToObj(i -> "value" + i + "\t007\n")
                                                    .collect(java.util.stream.Collectors.joining()),
                        "table.tsv"),
                DocumentSource.of(Path.of("benchmarks/fixtures/structured.docx")));
        for (final var input : inputs) {
            final var tables =
                    processor.partition(input).elements().stream().filter(e -> e.type() == ElementType.Table).toList();
            assertFalse(tables.isEmpty());
            final var chunks = chunker.byTitle(tables, options(90));
            assertBudgetsAndSynchronization(chunks, 90);
            assertTrue(chunks.stream().allMatch(c -> c.type() == ElementType.TableChunk));
        }
    }

    @Test
    void seededCorpusPreservesEveryCellAcrossDifferentBudgetsAndSpanGroups() {
        final var random = new Random(20261015);
        for (int trial = 0; trial < 40; trial++) {
            final var expected = new LinkedHashMap<Integer, String>();
            final var html = new StringBuilder("<table>");
            int index = 0;
            for (int r = 0; r < 5; r++) {
                html.append("<tr>");
                for (int c = 0; c < 3; c++) {
                    final String text = ("🧵&" + r + c).repeat(random.nextInt(20) + 1);
                    expected.put(index++, text);
                    html.append("<td>").append(TableFragment.escape(text)).append("</td>");
                }
                html.append("</tr>");
            }
            html.append("</table>");
            final int max = random.nextInt(100) + 50;
            final var chunks = split(html.toString(), max);
            assertBudgetsAndSynchronization(chunks, max);
            assertEquals(expected, reconstructCellTexts(chunks));
        }
    }

    @Test
    void concurrentCallsShareNoTableState() throws Exception {
        final var source = table("<table><tr><th>H</th></tr>" + rows(15) + "</table>");
        final var results = BatchProcessor.create().mapOrdered(
                IntStream.range(0, 20).boxed().toList(), 4, ignored -> chunker.basic(List.of(source), options(100)));
        final var expected = reconstructCellTexts(results.getFirst());
        for (final var result : results) {
            assertBudgetsAndSynchronization(result, 100);
            assertEquals(expected, reconstructCellTexts(result));
        }
        assertNotEquals(results.getFirst().getFirst().metadata().get("table_id"),
                results.getLast().getFirst().metadata().get("table_id"));
    }

    /**
     * Whole-table text-join and source-reference counts survive provenance without misdescribing fragments.
     */
    @Test
    void nativeTableCountsRemainScopedToOriginalWholeTables() {
        final var plain = table("<table><tbody>" + rows(12) + "</tbody></table>");
        final var metadata = new LinkedHashMap<>(plain.metadata());
        metadata.put("pdf_table_cell_text_policy", "wrapped_iso_date_v1");
        metadata.put("pdf_table_joined_iso_dates", 9);
        metadata.put("pdf_table_joined_soft_hyphens", 3);
        metadata.put("pdf_table_tagged_empty_cells", 4);
        metadata.put("pdf_table_caption_references", 2);
        metadata.put("pdf_table_tagged_nontext_references", 5);
        metadata.put("pdf_table_inline_script_glyphs", 6);
        metadata.put("pdf_table_retained_hyphen_wraps", 7);
        final var source = new Element(plain.elementId(), plain.type(), plain.text(), metadata);
        assertEquals(List.of(source), json.read(json.write(List.of(source))));
        final var whole = chunker.basic(List.of(source), options(10_000)).getFirst();
        assertEquals(9, whole.metadata().get("pdf_table_joined_iso_dates"));
        assertEquals(3, whole.metadata().get("pdf_table_joined_soft_hyphens"));
        assertEquals(4, whole.metadata().get("pdf_table_tagged_empty_cells"));
        assertEquals(2, whole.metadata().get("pdf_table_caption_references"));
        assertEquals(5, whole.metadata().get("pdf_table_tagged_nontext_references"));
        assertEquals(6, whole.metadata().get("pdf_table_inline_script_glyphs"));
        assertEquals(7, whole.metadata().get("pdf_table_retained_hyphen_wraps"));
        for (final int max : new int[] {80, 20}) {
            final var parts = chunker.basic(List.of(source), options(max));
            assertTrue(parts.size() > 1);
            for (final var part : parts) {
                assertFalse(part.metadata().containsKey("pdf_table_joined_iso_dates"));
                assertFalse(part.metadata().containsKey("pdf_table_joined_soft_hyphens"));
                assertFalse(part.metadata().containsKey("pdf_table_tagged_empty_cells"));
                assertFalse(part.metadata().containsKey("pdf_table_caption_references"));
                assertFalse(part.metadata().containsKey("pdf_table_tagged_nontext_references"));
                assertFalse(part.metadata().containsKey("pdf_table_inline_script_glyphs"));
                assertFalse(part.metadata().containsKey("pdf_table_retained_hyphen_wraps"));
                assertEquals(List.of(source), json.decodeOriginals((String) part.metadata().get("orig_elements")));
            }
        }
    }
}
