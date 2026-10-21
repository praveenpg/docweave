package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.batch.DocumentBatch;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DelimitedPartitionTest {
    private final DocWeave processor = DocWeave.create();

    private PartitionResult csv(final String source) throws Exception {
        return processor.partition(DocumentSource.text(source, "input.csv"));
    }

    private PartitionOptions policies(final DelimitedOptions options) {
        return PartitionOptions.builder().delimitedOptions(options).build();
    }

    @Test
    void preservesQuotedCommasQuotesNewlinesEmptyCellsAndStringNumbers() throws Exception {
        final var result = csv("name,id,note,empty\r\n\"Doe, Jane\",00123,\"First\nsecond \"\"quoted\"\"\",\r\n");
        final var table = result.elements().getFirst();
        assertEquals(ElementType.Table, table.type());
        assertEquals("name\tid\tnote\tempty\nDoe, Jane\t00123\tFirst\nsecond \"quoted\"\t", table.text());
        assertEquals(2, table.metadata().get("table_row_count"));
        assertEquals(4, table.metadata().get("table_column_count"));
        assertEquals(1, table.metadata().get("table_header_rows"));
        assertEquals("text/csv", table.metadata().get("filetype"));
        assertTrue(((String) table.metadata().get("text_as_html")).contains("<td>00123</td>"));
    }

    @Test
    void tsvSupportsQuotedTabsAndTrailingEmptyFields() throws Exception {
        final var result =
                processor.partition(DocumentSource.text("name\tvalue\tempty\n\"A\tB\"\t42\t\n", "input.tsv"));
        assertEquals(DocumentFormat.TSV, result.format());
        assertEquals("name\tvalue\tempty\nA\tB\t42\t", result.elements().getFirst().text());
        assertEquals(3, result.elements().getFirst().metadata().get("table_column_count"));
    }

    @Test
    void headerPolicyCanRetainEveryRecordAsData() throws Exception {
        final var result = processor.partition(DocumentSource.text("A,B\nC,D", "headerless.csv"),
                policies(new DelimitedOptions(DelimitedOptions.Header.NONE, true, TableLimits.defaults())));
        assertEquals(0, result.elements().getFirst().metadata().get("table_header_rows"));
        assertEquals("<table><tr><td>A</td><td>B</td></tr><tr><td>C</td><td>D</td></tr></table>",
                result.elements().getFirst().metadata().get("text_as_html"));
    }

    @Test
    void blankAndDuplicateHeaderLabelsArePreserved() throws Exception {
        final var table = csv(",same,same\n1,2,3").elements().getFirst();
        assertTrue(((String) table.metadata().get("text_as_html"))
                        .startsWith("<table><tr><th></th><th>same</th><th>same</th>"));
    }

    @Test
    void raggedRowsRequireAnExplicitPermissivePolicy() throws Exception {
        final var error = assertThrows(PartitionException.class, () -> csv("A,B\n1\n2,3,4"));
        assertEquals(PartitionException.Code.MALFORMED_DOCUMENT, error.code());
        final var relaxed = processor.partition(DocumentSource.text("A,B\n1\n2,3,4", "ragged.csv"),
                policies(new DelimitedOptions(DelimitedOptions.Header.FIRST_ROW, false, TableLimits.defaults())));
        assertEquals("A\tB\n1\n2\t3\t4", relaxed.elements().getFirst().text());
        assertEquals(3, relaxed.elements().getFirst().metadata().get("table_column_count"));
        assertEquals(1, relaxed.warnings().size());
        assertEquals(PartitionResult.WarningCode.RAGGED_TABLE, relaxed.warnings().getFirst().code());
    }

    @Test
    void malformedQuotedRecordsFailWithoutReturningPartialTables() {
        for (final String source : List.of("A,B\n\"unterminated,B", "A,B\n\"quoted\"trailing,B")) {
            final var error = assertThrows(PartitionException.class, () -> csv(source));
            assertEquals(PartitionException.Code.MALFORMED_DOCUMENT, error.code());
        }
    }

    @Test
    void emptyPhysicalLinesAreIgnoredButQuotedEmptyRecordsRemain() throws Exception {
        assertEquals(List.of(), csv("\r\n\n").elements());
        final var table = csv("\"\"\nnext").elements().getFirst();
        assertEquals("\nnext", table.text());
        assertEquals(2, table.metadata().get("table_row_count"));
        assertTrue(((String) table.metadata().get("text_as_html")).contains("<th></th>"));
    }

    @Test
    void valuesKeepWhitespaceAndHtmlMarkupIsEscapedRatherThanChangingRouting() throws Exception {
        final var table = csv("<script>value</script>,other\n  spaced  ,<b>&\n").elements().getFirst();
        assertEquals("<script>value</script>\tother\n  spaced  \t<b>&", table.text());
        final var html = (String) table.metadata().get("text_as_html");
        assertTrue(html.contains("&lt;script&gt;value&lt;/script&gt;"));
        assertTrue(html.contains("<td>  spaced  </td>"));
        assertTrue(html.contains("&lt;b&gt;&amp;"));
    }

    @Test
    void eachTableBudgetFailsWithTheTypedLimitCode() {
        for (final var limits : List.of(new TableLimits(1, 10, 100, 100), new TableLimits(10, 1, 100, 100),
                     new TableLimits(10, 10, 3, 100), new TableLimits(10, 10, 100, 1))) {
            final var error = assertThrows(PartitionException.class,
                    ()
                            -> processor.partition(DocumentSource.text("AA,B\n1,2", "limit.csv"),
                                    policies(new DelimitedOptions(DelimitedOptions.Header.FIRST_ROW, true, limits))));
            assertEquals(PartitionException.Code.TABLE_LIMIT, error.code());
        }
    }

    @Test
    void unicodeCellBudgetsCountCodePointsRatherThanUtf16Units() throws Exception {
        final var options =
                policies(new DelimitedOptions(DelimitedOptions.Header.NONE, true, new TableLimits(10, 10, 100, 1)));
        final var table =
                processor.partition(DocumentSource.text("🧵,𐐀", "unicode.csv"), options).elements().getFirst();
        assertEquals("🧵\t𐐀", table.text());
    }

    @Test
    void explicitAnonymousRoutingAndBomUseSharedDecoding() throws Exception {
        final var result =
                processor.partition(DocumentSource.bytes("\ufeffA,B\n1,2".getBytes(StandardCharsets.UTF_8), null),
                        PartitionOptions.builder().format(DocumentFormat.CSV).build());
        assertEquals(DocumentFormat.CSV, result.format());
        assertEquals("A\tB\n1\t2", result.elements().getFirst().text());
    }

    @Test
    void tableMetadataRoundTripsAndSmallTablesChunk() throws Exception {
        final var result = csv("A,B\n1,2");
        final var json = ElementJson.create();
        assertEquals(result.elements(), json.read(json.write(result.elements())));
        final var chunks = Chunker.create().basic(result.elements(), ChunkOptions.defaults());
        assertEquals(ElementType.Table, chunks.getFirst().type());
        assertEquals(
                result.elements(), json.decodeOriginals((String) chunks.getFirst().metadata().get("orig_elements")));
    }

    @Test
    void rawFileBatchSupportsMarkdownCsvAndTsv(final @TempDir Path directory) throws Exception {
        final var md = directory.resolve("first.md");
        final var csv = directory.resolve("second.csv");
        final var tsv = directory.resolve("third.tsv");
        Files.writeString(md, "# Heading\n\nBody.");
        Files.writeString(csv, "A,B\n1,2");
        Files.writeString(tsv, "A\tB\n3\t4");
        final var results = DocumentBatch.create().partitionAndChunk(
                List.of(md, csv, tsv), PartitionOptions.defaults(), Chunker.Strategy.BASIC, ChunkOptions.defaults(), 3);
        assertEquals(List.of(DocumentFormat.MARKDOWN, DocumentFormat.CSV, DocumentFormat.TSV),
                results.stream().map(result -> result.partition().format()).toList());
        assertEquals(List.of(md, csv, tsv), results.stream().map(DocumentBatch.Result::source).toList());
    }

    @Test
    void formatSpecificPoliciesRejectInvalidValuesAndNulls() {
        assertThrows(IllegalArgumentException.class, () -> new TableLimits(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new MarkdownOptions(true, 257, MarkdownOptions.RawHtml.SKIP, TableLimits.defaults()));
        assertThrows(NullPointerException.class, () -> PartitionOptions.builder().markdownOptions(null).build());
        assertThrows(NullPointerException.class, () -> PartitionOptions.builder().delimitedOptions(null).build());
    }
}
