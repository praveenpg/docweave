package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.chunking.ChunkOptions;
import io.docweave.chunking.Chunker;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownNestedTableTest {
    private final DocWeave processor = DocWeave.create();
    private static final String NESTED =
            "<table><tr><td>😀 Root<table><tr><td>Child</td></tr></table>After</td></tr></table>";

    private PartitionOptions options(final NestedTableOptions nested, final TableLimits limits) {
        return PartitionOptions.builder()
                .format(DocumentFormat.MARKDOWN)
                .baseUri(URI.create("https://example.com/docs/"))
                .markdownOptions(new MarkdownOptions(true, 128, MarkdownOptions.RawHtml.EXTRACT_DOM, limits))
                .nestedTableOptions(nested)
                .build();
    }

    private PartitionResult parse(final String markdown, final NestedTableOptions nested, final TableLimits limits)
            throws Exception {
        return processor.partition(DocumentSource.text(markdown, "nested.md"), options(nested, limits));
    }

    private PartitionResult parse(final String markdown) throws Exception {
        return parse(markdown, NestedTableOptions.preserveMetadata(), TableLimits.defaults());
    }

    private List<Element> tables(final PartitionResult result) {
        return result.elements().stream().filter(element -> element.type() == ElementType.Table).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> children(final Element table) {
        return (List<Map<String, Object>>) table.metadata().get("nested_tables");
    }

    @Test
    void combinedDomPreservesNestedStructureWithoutChangingDefaultElementsOrWarnings() throws Exception {
        final String nested = Files.readString(Path.of("benchmarks/review/nested-tables/nested.html"));
        final String markdown = "# Report\n\n| Pipe | Other |\n| --- | --- |\n| A | B |\n\n" + nested
                                + "\n\nAfter.\n\n```html\n" + NESTED + "\n```\n";
        final var baseline = parse(markdown, NestedTableOptions.defaults(), TableLimits.defaults());
        final var preserved = parse(markdown);
        assertEquals(baseline.warnings(), preserved.warnings());
        assertEquals(baseline.elements(), preserved.elements()
                                                  .stream()
                                                  .map(element -> {
                                                      final var metadata = new LinkedHashMap<>(element.metadata());
                                                      metadata.remove("nested_tables");
                                                      metadata.remove("nested_tables_version");
                                                      return new Element(element.elementId(), element.type(),
                                                              element.text(), metadata);
                                                  })
                                                  .toList());
        final var tables = tables(preserved);
        assertEquals(2, tables.size());
        assertFalse(tables.getFirst().metadata().containsKey("nested_tables"));
        final var source = tables.getLast();
        assertEquals(1, source.metadata().get("nested_tables_version"));
        final var children = children(source);
        assertEquals(3, children.size());
        assertEquals(0, children.get(1).get("parent_index"));
        assertEquals(List.of(Map.of("row", 0, "column", 0, "table_index", 1)), children.get(2).get("path"));
        assertEquals(List.of("Child caption"), children.getFirst().get("captions"));
        assertTrue(((String) children.getFirst().get("text_as_html")).contains("colspan=\"2\""));
        assertEquals(
                "Grandchild", source.text().substring(
                                      source.text().offsetByCodePoints(0, (Integer) children.get(1).get("start_index")),
                                      source.text().offsetByCodePoints(0, (Integer) children.get(1).get("end_index"))));
        assertEquals(List.of(Map.of("start_index", 14, "text", "Repeated", "url", "https://example.com/item")),
                children.getFirst().get("links"));
        assertEquals("Report", preserved.elements().getFirst().text());
        assertEquals(preserved.elements().getFirst().elementId(), source.metadata().get("parent_id"));
        assertEquals(preserved.elements(), ElementJson.create().read(ElementJson.create().write(preserved.elements())));
    }

    @Test
    void rawHtmlUsesSharedFilteringAndChildLocalUnicodeLinkOffsets() throws Exception {
        final var result =
                parse("# Heading\n\n<table><tr><td>Root<table><tr><td>😀 "
                        + "<a href='../guide'>Link</a><script>hidden</script><span hidden>hidden</span>"
                        + "</td></tr></table><table hidden><tr><td>Hidden child</td></tr></table></td></tr></table>\n");
        final var table = tables(result).getFirst();
        final var children = children(table);
        assertEquals(1, children.size());
        assertEquals("😀 Link", children.getFirst().get("text"));
        assertEquals(List.of(Map.of("start_index", 2, "text", "Link", "url", "https://example.com/guide")),
                children.getFirst().get("links"));
        assertFalse(table.text().contains("hidden"));
    }

    @Test
    void customTableLimitsApplyToEmbeddedTablesAndLogicalSpanOrigins() throws Exception {
        final String span = "<table><tr><td colspan='3'>Root<table><tr><td>X</td></tr></table></td></tr></table>";
        assertLimit(span, NestedTableOptions.preserveMetadata(), new TableLimits(10, 2, 100, 100));
        assertEquals(1, children(tables(parse(span)).getFirst()).size());
        assertLimit(NESTED, NestedTableOptions.preserveMetadata(), new TableLimits(10, 10, 100, 3));
        assertLimit("<table><tr><td>Root<table><tr><td>A</td></tr><tr><td>B</td></tr></table></td></tr></table>",
                NestedTableOptions.preserveMetadata(), new TableLimits(1, 10, 100, 100));
    }

    @Test
    void preservationCountDepthAndPayloadRemainDocumentWideInDomMode() throws Exception {
        assertLimit(NESTED + "\n\n" + NESTED,
                new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 1, 4, 10000), TableLimits.defaults());
        assertLimit(NESTED, new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 10, 4, 100),
                TableLimits.defaults());
        assertLimit("<table><tr><td>Root<table><tr><td>Child<table></table></td></tr></table></td></tr></table>",
                new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 10, 1, 10000),
                TableLimits.defaults());
        assertEquals(1, children(tables(parse(NESTED)).getFirst()).size());
    }

    @Test
    void bothMarkdownAndHtmlDepthPoliciesStillApply() {
        final var markdownLimit = PartitionOptions.builder()
                                          .format(DocumentFormat.MARKDOWN)
                                          .markdownOptions(new MarkdownOptions(
                                                  true, 1, MarkdownOptions.RawHtml.EXTRACT_DOM, TableLimits.defaults()))
                                          .nestedTableOptions(NestedTableOptions.preserveMetadata())
                                          .build();
        assertEquals(PartitionException.Code.MARKDOWN_DEPTH_LIMIT,
                assertThrows(PartitionException.class,
                        () -> processor.partition(DocumentSource.text("> > deeply nested", "a.md"), markdownLimit))
                        .code());
        final var htmlLimit = PartitionOptions.builder()
                                      .format(DocumentFormat.MARKDOWN)
                                      .maxHtmlDepth(2)
                                      .markdownOptions(new MarkdownOptions(
                                              true, 128, MarkdownOptions.RawHtml.EXTRACT_DOM, TableLimits.defaults()))
                                      .nestedTableOptions(NestedTableOptions.preserveMetadata())
                                      .build();
        assertEquals(PartitionException.Code.HTML_DEPTH_LIMIT,
                assertThrows(PartitionException.class,
                        () -> processor.partition(DocumentSource.text(NESTED, "a.md"), htmlLimit))
                        .code());
    }

    @Test
    void rewrittenMarkdownTablesKeepDescriptorsOnlyInRequestedOriginalProvenance() throws Exception {
        final var source = tables(parse(NESTED)).getFirst();
        final var codec = ElementJson.create();
        for (final int max : new int[] {1000, 12}) {
            for (final boolean provenance : new boolean[] {true, false}) {
                for (final var chunk : Chunker.create().basic(List.of(source),
                             ChunkOptions.builder().maxCharacters(max).includeOriginalElements(provenance).build())) {
                    assertFalse(chunk.metadata().containsKey("nested_tables"));
                    assertFalse(chunk.metadata().containsKey("nested_tables_version"));
                    if (provenance) {
                        assertEquals(
                                List.of(source), codec.decodeOriginals((String) chunk.metadata().get("orig_elements")));
                    } else {
                        assertFalse(chunk.metadata().containsKey("orig_elements"));
                    }
                }
            }
        }
        assertSame(source,
                Chunker.create()
                        .basic(List.of(source), ChunkOptions.builder().maxCharacters(1).skipTableChunking(true).build())
                        .getFirst());
    }

    private void assertLimit(final String markdown, final NestedTableOptions nested, final TableLimits limits) {
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> parse(markdown, nested, limits)).code());
    }
}
