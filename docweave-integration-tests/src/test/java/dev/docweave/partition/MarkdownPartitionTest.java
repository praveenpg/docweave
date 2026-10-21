package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.batch.BatchProcessor;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownPartitionTest {
    private final DocWeave processor = DocWeave.create();

    private PartitionResult parse(final String source) throws Exception {
        return processor.partition(DocumentSource.text(source, "report.md"));
    }

    private List<String> texts(final PartitionResult result) {
        return result.elements().stream().map(Element::text).toList();
    }

    private PartitionOptions policies(final MarkdownOptions options) {
        return PartitionOptions.builder().markdownOptions(options).build();
    }

    @Test
    void preservesBlockOrderAndHeadingHierarchyFromTheAst() throws Exception {
        final var result =
                parse("# Report\n\nA **bold** paragraph.\n\n## Details\n\n> Quoted text.\n\n---\n\nLast paragraph.");
        assertEquals(
                List.of("Report", "A bold paragraph.", "Details", "Quoted text.", "Last paragraph."), texts(result));
        assertEquals(List.of(ElementType.Title, ElementType.NarrativeText, ElementType.Title, ElementType.NarrativeText,
                             ElementType.NarrativeText),
                result.elements().stream().map(Element::type).toList());
        assertEquals(result.elements().getFirst().elementId(), result.elements().get(2).metadata().get("parent_id"));
        assertEquals(result.elements().get(2).elementId(), result.elements().getLast().metadata().get("parent_id"));
        assertEquals(DocumentFormat.MARKDOWN, result.format());
        assertEquals("text/markdown", result.elements().getFirst().metadata().get("filetype"));
    }

    @Test
    void handlesSetextHeadingsEscapesAndReferenceLinksWithUnicodeOffsets() throws Exception {
        final String source = "Heading\n=======\n\n🧵 [reference][id] and \\*literal\\*.\n\n[id]: ../guide \"Guide\"\n";
        final var options = PartitionOptions.builder().baseUri(URI.create("https://example.com/docs/page")).build();
        final var result = processor.partition(DocumentSource.text(source, "input.markdown"), options);
        assertEquals(List.of("Heading", "🧵 reference and *literal*."), texts(result));
        final var paragraph = result.elements().getLast();
        assertEquals(List.of(2), paragraph.metadata().get("link_start_indexes"));
        assertEquals(List.of("reference"), paragraph.metadata().get("link_texts"));
        assertEquals(List.of("https://example.com/guide"), paragraph.metadata().get("link_urls"));
    }

    @Test
    void nestedAndLooseListsKeepParentReferencesWithoutDuplicateText() throws Exception {
        final var result = parse("# Tasks\n\n- Parent\n\n  Continuation.\n\n  - Child\n- Sibling\n\n3. Ordered "
                                 + "first\n4. Ordered second\n");
        assertEquals(List.of("Tasks", "Parent", "Continuation.", "Child", "Sibling", "Ordered first", "Ordered second"),
                texts(result));
        assertEquals(result.elements().get(1).elementId(), result.elements().get(3).metadata().get("parent_id"));
        assertEquals(1, result.elements().get(3).metadata().get("category_depth"));
        assertTrue(result.elements()
                        .subList(1, result.elements().size())
                        .stream()
                        .allMatch(element -> element.type() == ElementType.ListItem));
    }

    @Test
    void fencedAndIndentedCodeKeepLiteralWhitespaceAndInfo() throws Exception {
        final var result = parse("```java linenos\n  int x = 1;\n    next();\n```\n\n    indented();\n");
        assertEquals(List.of("  int x = 1;\n    next();\n", "indented();\n"), texts(result));
        assertEquals("java", result.elements().getFirst().metadata().get("code_language"));
        assertEquals("java linenos", result.elements().getFirst().metadata().get("code_info"));
        assertTrue(result.elements().stream().allMatch(element -> element.type() == ElementType.CodeSnippet));
    }

    @Test
    void inlineCodeKeepsLiteralSpacesWhileSoftAndHardBreaksNormalize() throws Exception {
        final var result = parse("Use `a  b` here\nwrapped  \nagain.");
        assertEquals(List.of("Use a  b here wrapped again."), texts(result));
    }

    @Test
    void tablesKeepEmptyCellsEscapedPipesHeadersAndAlignment() throws Exception {
        final var result = parse("| Name | Count |\n| :--- | ---: |\n| A\\|B | 00123 |\n| Empty | |\n");
        final var table = result.elements().getFirst();
        assertEquals(ElementType.Table, table.type());
        assertEquals("Name\tCount\nA|B\t00123\nEmpty\t", table.text());
        assertEquals(3, table.metadata().get("table_row_count"));
        assertEquals(2, table.metadata().get("table_column_count"));
        assertEquals(1, table.metadata().get("table_header_rows"));
        assertEquals(List.of("left", "right"), table.metadata().get("table_column_alignments"));
        assertTrue(((String) table.metadata().get("text_as_html")).contains("<td></td>"));
    }

    @Test
    void tableLinksUseOffsetsInTheActualTableText() throws Exception {
        final var table = parse("| H |\n| --- |\n| 🧵 [go](/guide) |\n").elements().getFirst();
        assertEquals("H\n🧵 go", table.text());
        assertEquals(List.of(4), table.metadata().get("link_start_indexes"));
        assertEquals(List.of("/guide"), table.metadata().get("link_urls"));
    }

    @Test
    void tableExtractionCanBeDisabled() throws Exception {
        final var options = policies(
                new MarkdownOptions(false, 128, MarkdownOptions.RawHtml.PRESERVE_TEXT, TableLimits.defaults()));
        final var result =
                processor.partition(DocumentSource.text("| A | B |\n| --- | --- |\n| C | D |", "no-table.md"), options);
        assertTrue(result.elements().stream().noneMatch(element -> element.type() == ElementType.Table));
        assertTrue(result.elements().getFirst().text().contains("|"));
    }

    @Test
    void rawHtmlIsExplicitlyPreservedOrSkipped() throws Exception {
        final String source = "<div>raw block</div>\n\nText <span>inline</span>.\n";
        final var kept = parse(source);
        assertEquals(ElementType.UncategorizedText, kept.elements().getFirst().type());
        assertTrue(kept.elements().getFirst().text().contains("<div>"));
        assertEquals("Text <span>inline</span>.", kept.elements().getLast().text());
        assertEquals(1, kept.warnings().size());
        assertEquals(PartitionResult.WarningCode.RAW_HTML_PRESERVED, kept.warnings().getFirst().code());
        final var skipped = processor.partition(DocumentSource.text(source, "input.md"),
                policies(new MarkdownOptions(true, 128, MarkdownOptions.RawHtml.SKIP, TableLimits.defaults())));
        assertEquals(List.of("Text inline."), texts(skipped));
        assertTrue(skipped.warnings().isEmpty());
    }

    @Test
    void imagesRetainReferencesIncludingEmptyAltTextWithoutFetchingAssets() throws Exception {
        final var result = parse("![Diagram](/diagram.png)\n\n![](/empty.png)\n\nInline ![icon](/icon.png) text.");
        assertEquals(List.of(ElementType.Image, ElementType.Image, ElementType.NarrativeText),
                result.elements().stream().map(Element::type).toList());
        assertEquals(List.of("Diagram", "", "Inline icon text."), texts(result));
        assertEquals("/empty.png", result.elements().get(1).metadata().get("image_url"));
        assertEquals(List.of("/icon.png"), result.elements().getLast().metadata().get("image_urls"));
    }

    @Test
    void depthAndTableBudgetsFailWithTypedErrors() {
        final var depth =
                policies(new MarkdownOptions(true, 2, MarkdownOptions.RawHtml.PRESERVE_TEXT, TableLimits.defaults()));
        final var deep = assertThrows(PartitionException.class,
                () -> processor.partition(DocumentSource.text("> > > nested", "deep.md"), depth));
        assertEquals(PartitionException.Code.MARKDOWN_DEPTH_LIMIT, deep.code());
        final var table = policies(
                new MarkdownOptions(true, 128, MarkdownOptions.RawHtml.PRESERVE_TEXT, new TableLimits(10, 10, 3, 100)));
        final var big = assertThrows(PartitionException.class,
                ()
                        -> processor.partition(
                                DocumentSource.text("| A | B |\n| --- | --- |\n| C | D |", "limit.md"), table));
        assertEquals(PartitionException.Code.TABLE_LIMIT, big.code());
    }

    @Test
    void explicitRoutingBomRoundTripAndChunkingUseExistingContracts() throws Exception {
        final var options = PartitionOptions.builder().format(DocumentFormat.MARKDOWN).build();
        final var result = processor.partition(
                DocumentSource.bytes("\ufeff# Heading\n\nBody.".getBytes(StandardCharsets.UTF_8), null), options);
        assertEquals(List.of("Heading", "Body."), texts(result));
        final var json = ElementJson.create();
        assertEquals(result.elements(), json.read(json.write(result.elements())));
        final var chunks = Chunker.create().byTitle(result.elements(), ChunkOptions.defaults());
        assertEquals("Heading\n\nBody.", chunks.getFirst().text());
        assertEquals(
                result.elements(), json.decodeOriginals((String) chunks.getFirst().metadata().get("orig_elements")));
    }

    @Test
    void commonMarkTreatsUnclosedFencesAsLiteralCodeAndBlankInputsAsEmpty() throws Exception {
        assertEquals(List.of(), parse("\n\n").elements());
        assertEquals(List.of("body\n"), texts(parse("```\nbody\n")));
    }

    @Test
    void sharedMarkdownStrategyKeepsConcurrentDocumentStateIsolated() throws Exception {
        final var results = BatchProcessor.create().mapOrdered(IntStream.range(0, 25).boxed().toList(), 5,
                index
                -> processor.partition(
                        DocumentSource.text("# Heading " + index + "\n\nBody " + index + ".", "doc-" + index + ".md")));
        for (int i = 0; i < results.size(); i++) {
            assertEquals("Heading " + i, results.get(i).elements().getFirst().text());
            assertEquals(results.get(i).elements().getFirst().elementId(),
                    results.get(i).elements().getLast().metadata().get("parent_id"));
        }
    }

    private PartitionOptions domOptions(final TableLimits limits) {
        return policies(new MarkdownOptions(true, 128, MarkdownOptions.RawHtml.EXTRACT_DOM, limits));
    }

    @Test
    void domModeUnifiesMarkdownAndHtmlHeadingsLinksAndLists() throws Exception {
        final var options = PartitionOptions.builder()
                                    .baseUri(URI.create("https://example.com/docs/"))
                                    .markdownOptions(new MarkdownOptions(
                                            true, 128, MarkdownOptions.RawHtml.EXTRACT_DOM, TableLimits.defaults()))
                                    .build();
        final var result = processor.partition(DocumentSource.text("""
                # Report

                <h2>Details</h2>

                🧵 <a href="../guide">**go**</a> and [next](next).

                - Parent
                  - Child
                """, "mixed.md"), options);
        assertEquals(List.of("Report", "Details", "🧵 go and next.", "Parent", "Child"), texts(result));
        final var elements = result.elements();
        assertEquals(elements.getFirst().elementId(), elements.get(1).metadata().get("parent_id"));
        assertEquals(elements.get(1).elementId(), elements.get(2).metadata().get("parent_id"));
        assertEquals(elements.get(3).elementId(), elements.get(4).metadata().get("parent_id"));
        assertEquals(List.of(2, 9), elements.get(2).metadata().get("link_start_indexes"));
        assertEquals(List.of("https://example.com/guide", "https://example.com/docs/next"),
                elements.get(2).metadata().get("link_urls"));
        assertTrue(elements.stream().allMatch(element -> element.metadata().get("filetype").equals("text/markdown")));
        assertTrue(result.warnings().stream().noneMatch(
                warning -> warning.code() == PartitionResult.WarningCode.RAW_HTML_PRESERVED));
    }

    @Test
    void domModeFiltersInvisibleHtmlAndKeepsCodeLiteral() throws Exception {
        final var result =
                processor.partition(DocumentSource.text("""
                <div><p>Visible.</p><p hidden>Hidden.</p><script>bad()</script><style>bad</style></div>

                ```html
                <span>literal</span>
                ```

                <span aria-hidden="true">secret</span> Public.
                """, "visible.md"), domOptions(TableLimits.defaults()));
        assertEquals(List.of("Visible.", "<span>literal</span>\n", "Public."), texts(result));
        assertEquals(ElementType.CodeSnippet, result.elements().get(1).type());
    }

    @Test
    void domModeExtractsBothTableSyntaxesAndPreservesChunkProvenance() throws Exception {
        final var result =
                processor.partition(DocumentSource.text("""
                | H |
                | --- |
                | Pipe |

                <table><tr><th>Head</th></tr><tr><td>🧵 <a href="/x">go</a></td></tr></table>
                """, "tables.md"), domOptions(TableLimits.defaults()));
        assertEquals(List.of("H\nPipe", "Head\n🧵 go"), texts(result));
        assertTrue(result.elements().stream().allMatch(element -> element.type() == ElementType.Table));
        assertEquals(List.of(7), result.elements().getLast().metadata().get("link_start_indexes"));
        final var json = ElementJson.create();
        assertEquals(result.elements(), json.read(json.write(result.elements())));
        final var chunks = Chunker.create().basic(result.elements(), ChunkOptions.defaults());
        assertEquals(result.elements().getFirst(),
                json.decodeOriginals((String) chunks.getFirst().metadata().get("orig_elements")).getFirst());
    }

    @Test
    void domModeEnforcesHtmlDepthAndAllTableBudgets() {
        final var depth = PartitionOptions.builder()
                                  .maxHtmlDepth(2)
                                  .markdownOptions(new MarkdownOptions(
                                          true, 128, MarkdownOptions.RawHtml.EXTRACT_DOM, TableLimits.defaults()))
                                  .build();
        assertEquals(PartitionException.Code.HTML_DEPTH_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> processor.partition(
                                        DocumentSource.text("<div><div><p>deep</p></div></div>", "deep.md"), depth))
                        .code());
        final var sources = List.of("<table><tr><td>A</td></tr><tr><td>B</td></tr></table>",
                "<table><tr><td>A</td><td>B</td></tr></table>",
                "<table><tr><td>A</td><td>B</td></tr><tr><td>C</td><td>D</td></tr></table>",
                "<table><tr><td>🧵ab</td></tr></table>", "| H |\n| --- |\n| abc |");
        final var limits = List.of(new TableLimits(1, 10, 100, 100), new TableLimits(10, 1, 100, 100),
                new TableLimits(10, 10, 3, 100), new TableLimits(10, 10, 100, 2), new TableLimits(10, 10, 100, 2));
        for (int i = 0; i < sources.size(); i++) {
            final var source = DocumentSource.text(sources.get(i), "limit.md");
            final var options = domOptions(limits.get(i));
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class, () -> processor.partition(source, options)).code());
        }
    }

    @Test
    void domModeKeepsConcurrentDocumentHierarchiesIsolated() throws Exception {
        final var options = domOptions(TableLimits.defaults());
        final var results = BatchProcessor.create().mapOrdered(IntStream.range(0, 20).boxed().toList(), 4,
                index
                -> processor.partition(DocumentSource.text("# Heading " + index + "\n\n<p>Body " + index + ".</p>",
                                               "dom-" + index + ".md"),
                        options));
        for (int i = 0; i < results.size(); i++) {
            assertEquals(List.of("Heading " + i, "Body " + i + "."), texts(results.get(i)));
            assertEquals(results.get(i).elements().getFirst().elementId(),
                    results.get(i).elements().getLast().metadata().get("parent_id"));
        }
    }

    @Test
    void domModeRetainsAstLimitsAndHonorsDisabledPipeTablesAndHtmlPolicies() throws Exception {
        final var shallow =
                policies(new MarkdownOptions(true, 2, MarkdownOptions.RawHtml.EXTRACT_DOM, TableLimits.defaults()));
        assertEquals(PartitionException.Code.MARKDOWN_DEPTH_LIMIT,
                assertThrows(PartitionException.class,
                        () -> processor.partition(DocumentSource.text("> > > nested", "deep.md"), shallow))
                        .code());
        final var options = PartitionOptions.builder()
                                    .skipHeadersAndFooters(true)
                                    .markdownOptions(new MarkdownOptions(
                                            false, 128, MarkdownOptions.RawHtml.EXTRACT_DOM, TableLimits.defaults()))
                                    .build();
        final var result = processor.partition(DocumentSource.text("""
                <header>Navigation</header>

                | H |
                | --- |
                | Pipe |

                <table><tr><td>🧵ab</td></tr></table>

                <footer>Footer</footer>
                """, "policies.md"), options);
        assertEquals(2, result.elements().size());
        assertTrue(result.elements().getFirst().text().contains("|"));
        assertEquals(ElementType.Table, result.elements().getLast().type());
        final var exact =
                processor.partition(DocumentSource.text("<table><tr><td>🧵ab</td></tr></table>", "unicode.md"),
                        domOptions(new TableLimits(1, 1, 1, 3)));
        assertEquals("🧵ab", exact.elements().getFirst().text());
    }

    @Test
    void domModeAppliesRowBudgetsToNestedTablesBeforeFlattening() {
        final var source = DocumentSource.text("<table><tr><td><table><tr><td>A</td></tr>"
                                                       + "<tr><td>B</td></tr></table></td></tr></table>",
                "nested.md");
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> processor.partition(source, domOptions(new TableLimits(1, 10, 100, 100))))
                        .code());
    }
}
