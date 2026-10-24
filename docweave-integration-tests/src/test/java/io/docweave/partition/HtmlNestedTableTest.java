package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.chunking.ChunkOptions;
import io.docweave.chunking.Chunker;
import io.docweave.core.Element;
import io.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises real HTML preservation, coordinate spaces, fail-closed limits and downstream ownership. */
class HtmlNestedTableTest {
    private final DocWeave processor = DocWeave.create();

    private PartitionOptions options(final NestedTableOptions nested) {
        return PartitionOptions.builder().format(DocumentFormat.HTML).nestedTableOptions(nested).build();
    }

    private PartitionResult parse(final String html, final NestedTableOptions nested) throws Exception {
        return processor.partition(DocumentSource.text(html, "nested.html"), options(nested));
    }

    private Element parse(final String html) throws Exception {
        return parse(html, NestedTableOptions.preserveMetadata()).elements().getFirst();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> children(final Element table) {
        assertEquals(1, table.metadata().get("nested_tables_version"));
        return (List<Map<String, Object>>) table.metadata().get("nested_tables");
    }

    private String interval(final Element table, final Map<String, Object> child) {
        return table.text().substring(table.text().offsetByCodePoints(0, (Integer) child.get("start_index")),
                table.text().offsetByCodePoints(0, (Integer) child.get("end_index")));
    }

    @Test
    void fixtureRetainsRootIdentityAndWarningsWhileCapturingPreorderChildren() throws Exception {
        final var source = DocumentSource.of(Path.of("benchmarks/review/nested-tables/nested.html"));
        final var baseline = processor.partition(source);
        final var preserved = processor.partition(source, options(NestedTableOptions.preserveMetadata()));
        assertEquals(baseline.warnings(), preserved.warnings());
        assertEquals(1, preserved.elements().size());
        final var table = preserved.elements().getFirst();
        final var metadata = new LinkedHashMap<>(table.metadata());
        metadata.remove("nested_tables");
        metadata.remove("nested_tables_version");
        assertEquals(
                baseline.elements().getFirst(), new Element(table.elementId(), table.type(), table.text(), metadata));
        final var children = children(table);
        assertEquals(3, children.size());
        assertEquals(List.of(0, 1, 2), children.stream().map(child -> child.get("index")).toList());
        assertFalse(children.get(0).containsKey("parent_index"));
        assertEquals(0, children.get(1).get("parent_index"));
        assertFalse(children.get(2).containsKey("parent_index"));
        final var firstStep = Map.of("row", 0, "column", 0, "table_index", 0);
        assertEquals(List.of(firstStep), children.get(0).get("path"));
        assertEquals(List.of(firstStep, Map.of("row", 1, "column", 0, "table_index", 0)), children.get(1).get("path"));
        assertEquals(List.of(Map.of("row", 0, "column", 0, "table_index", 1)), children.get(2).get("path"));
        assertEquals("Child caption Child heading Repeated Grandchild Right", interval(table, children.get(0)));
        assertEquals("Child heading\nRepeated Grandchild\tRight", children.get(0).get("text"));
        assertEquals(List.of("Child caption"), children.get(0).get("captions"));
        assertEquals("<table><tr><th colspan=\"2\">Child heading</th></tr>"
                             + "<tr><td>Repeated Grandchild</td><td>Right</td></tr></table>",
                children.get(0).get("text_as_html"));
        assertEquals(List.of(Map.of("start_index", 14, "text", "Repeated", "url", "https://example.com/item")),
                children.get(0).get("links"));
        assertEquals("Grandchild", interval(table, children.get(1)));
        assertEquals("Repeated", interval(table, children.get(2)));
        assertTrue((Integer) children.get(2).get("start_index") > table.text().indexOf("Repeated"));
        assertEquals(List.of(table), ElementJson.create().read(ElementJson.create().write(List.of(table))));
    }

    @Test
    void logicalOriginsRespectRowspanAndColspanWithoutChangingRootText() throws Exception {
        final var table = parse("<table><tr><td rowspan='2' colspan='2'>Held</td><td>Top</td></tr>"
                                + "<tr><td>😀<table><tr><td colspan='2'>Child</td></tr></table>End</td></tr></table>");
        final var child = children(table).getFirst();
        assertEquals(List.of(Map.of("row", 1, "column", 2, "table_index", 0)), child.get("path"));
        assertEquals("Held\tTop\n😀 Child End", table.text());
        assertEquals("Child", interval(table, child));
        assertTrue(((String) child.get("text_as_html")).contains("colspan=\"2\""));
    }

    @Test
    void emptyChildrenAndLeadingEmptyRowsHaveValidContainedIntervals() throws Exception {
        final var table = parse("<table><tr><td></td></tr><tr><td>Before<table><tr><td>"
                                + "<table></table>X<table></table></td></tr></table>After</td></tr></table>");
        final var children = children(table);
        assertEquals(3, children.size());
        assertEquals("X", interval(table, children.getFirst()));
        for (final var child : children.subList(1, 3)) {
            assertEquals("", interval(table, child));
            assertEquals("", child.get("text"));
            assertEquals("<table></table>", child.get("text_as_html"));
            assertEquals(0, child.get("parent_index"));
            assertTrue((Integer) child.get("start_index") >= (Integer) children.getFirst().get("start_index"));
            assertTrue((Integer) child.get("end_index") <= (Integer) children.getFirst().get("end_index"));
        }
    }

    @Test
    void snapshotsEscapeTextAndRetainChildLocalLinksAndCaptions() throws Exception {
        final var table = parse("<table><tr><td>Outer<table><caption>😀 Caption</caption><tr><td>"
                                + "😀 <a href='https://example.com/?a=1&amp;b=2'>&lt;&amp;😀</a>"
                                + "<script>hidden</script></td></tr></table>After</td></tr></table>");
        final var child = children(table).getFirst();
        assertEquals("😀 <&😀", child.get("text"));
        assertEquals("<table><tr><td>😀 &lt;&amp;😀</td></tr></table>", child.get("text_as_html"));
        assertEquals(List.of("😀 Caption"), child.get("captions"));
        assertEquals(List.of(Map.of("start_index", 2, "text", "<&😀", "url", "https://example.com/?a=1&b=2")),
                child.get("links"));
    }

    @Test
    void countAndDepthBudgetsAreDocumentWideAndEmptyChildrenCount() throws Exception {
        final String one = "<table><tr><td>Root<table></table></td></tr></table>";
        final var single = new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 1, 1, 10000);
        assertEquals(1, children(parse(one, single).elements().getFirst()).size());
        assertLimit(one + one, single);
        final String deep =
                "<table><tr><td>Root<table><tr><td>Child<table></table></td></tr></table></td></tr></table>";
        assertLimit(deep, new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 10, 1, 10000));
        assertEquals(2, children(parse(deep)).size());
    }

    @Test
    void metadataBudgetCountsRepeatedPayloadAcrossRootTablesAndEscapedHtml() throws Exception {
        final String html = "<table><tr><td>Root<table><tr><td>&amp;😀</td></tr></table></td></tr></table>";
        final var child = children(parse(html)).getFirst();
        final long payload = payload(child);
        final var exact = new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 10, 10, payload);
        assertEquals(child, children(parse(html, exact).elements().getFirst()).getFirst());
        assertLimit(html, new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 10, 10, payload - 1));
        assertLimit(html + html, exact);
        assertEquals(2, parse(html + html,
                                new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 10, 10, payload * 2))
                                .elements()
                                .size());
        assertLimit("<table><tr><td>Root<table><tr><td>"
                            + "&amp;".repeat(1000) + "</td></tr></table></td></tr></table>",
                new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 10, 10, 100));
    }

    @Test
    void unsafeSpanGeometryFailsOnlyInOptInPreservation() throws Exception {
        for (final String rows : List.of("<tr><td colspan='2147483647'>X<table></table></td></tr>",
                     "<tr><td rowspan='2147483647'>X<table></table></td></tr>")) {
            final String html = "<table>" + rows + "</table>";
            assertFalse(parse(html, NestedTableOptions.defaults()).elements().isEmpty());
            assertLimit(html, NestedTableOptions.preserveMetadata());
        }
        final String overlap = "<table><tr><td>A</td><td rowspan='2'>B</td></tr>"
                               + "<tr><td colspan='2'>C<table></table></td></tr></table>";
        assertEquals(PartitionException.Code.MALFORMED_DOCUMENT,
                assertThrows(PartitionException.class, () -> parse(overlap)).code());
    }

    @Test
    void tablesWithoutChildrenHaveNoDescriptorMetadataAndPreserveExactElements() throws Exception {
        final String html = "<table><tr><td>Plain</td></tr></table>";
        assertEquals(parse(html, NestedTableOptions.defaults()).elements(),
                parse(html, NestedTableOptions.preserveMetadata()).elements());
        assertTrue(parse("<table><tr><td><table></table></td></tr></table>", NestedTableOptions.preserveMetadata())
                        .elements()
                        .isEmpty());
        assertTrue(
                PartitionerRegistry.create().find(DocumentFormat.HTML).orElseThrow().supportsNestedTablePreservation());
        assertTrue(PartitionerRegistry.create()
                        .find(DocumentFormat.MARKDOWN)
                        .orElseThrow()
                        .supportsNestedTablePreservation());
    }

    @Test
    void realDescriptorsStayOnlyInOriginalProvenanceAfterRewriting() throws Exception {
        final var source = parse("<table><tr><td>Root<table><tr><td>😀 Child</td></tr></table>After</td></tr></table>");
        for (final int limit : new int[] {1000, 12}) {
            for (final var chunk :
                    Chunker.create().basic(List.of(source), ChunkOptions.builder().maxCharacters(limit).build())) {
                assertFalse(chunk.metadata().containsKey("nested_tables"));
                assertFalse(chunk.metadata().containsKey("nested_tables_version"));
                assertEquals(List.of(source),
                        ElementJson.create().decodeOriginals((String) chunk.metadata().get("orig_elements")));
            }
        }
        assertSame(source,
                Chunker.create()
                        .basic(List.of(source), ChunkOptions.builder().maxCharacters(1).skipTableChunking(true).build())
                        .getFirst());
    }

    @Test
    void failedDocumentDoesNotLeakBudgetIntoReuseOrConcurrentDocuments() throws Exception {
        final String html = "<table><tr><td>Root<table><tr><td>Child</td></tr></table></td></tr></table>";
        final var policy = new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 1, 1, 10000);
        assertLimit(html + html, policy);
        final var expected = parse(html, policy).elements();
        try (final var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 20)
                                      .mapToObj(index -> executor.submit(() -> parse(html, policy).elements()))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(expected, task.get());
            }
        }
    }

    private void assertLimit(final String html, final NestedTableOptions nested) {
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> parse(html, nested)).code());
    }

    private long payload(final Object value) {
        if (value instanceof final String text) {
            return text.codePointCount(0, text.length());
        }
        if (value instanceof final Map<?, ?> map) {
            return 1
                    + map.entrySet()
                              .stream()
                              .mapToLong(entry -> payload(entry.getKey()) + payload(entry.getValue()))
                              .sum();
        }
        if (value instanceof final List<?> list) {
            return 1 + list.stream().mapToLong(this::payload).sum();
        }
        return 1;
    }
}
