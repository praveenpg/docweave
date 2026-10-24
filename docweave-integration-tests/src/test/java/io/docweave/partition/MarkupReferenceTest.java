package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.json.ElementJson;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Reviewed native contracts, retaining classification/structure differences explicitly. */
class MarkupReferenceTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ElementJson json = ElementJson.create();
    private final DocWeave processor = DocWeave.create();

    private List<Element> reference(final String filename) throws Exception {
        try (final var stream = getClass().getResourceAsStream("/markup-native-reference.json")) {
            final var saved = mapper.readValue(stream, Map.class);
            final var provenance = (Map<?, ?>) saved.get("reference");
            assertEquals("347fbbfd3e84e228017f125e5c5dbdc326ef7de2", provenance.get("upstream_commit"));
            assertEquals("3.13.16", provenance.get("python"));
            assertEquals(true, ((Map<?, ?>) provenance.get("model")).get("installed_package_verified"));
            final var selected = ((List<?>) saved.get("cases")).stream().map(value -> (Map<?, ?>) value)
                    .filter(value -> filename.equals(value.get("input"))).findFirst().orElseThrow();
            final String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    Files.readAllBytes(Path.of("benchmarks/fixtures", filename))));
            assertEquals(selected.get("input_sha256"), digest);
            assertEquals("v1", ((Map<?, ?>) selected.get("upstream_options")).get("html_parser_version"));
            return json.read(mapper.writeValueAsString(selected.get("upstream_output")));
        }
    }

    private PartitionResult candidate(final String filename, final MarkdownOptions.RawHtml mode) throws Exception {
        return processor.partition(DocumentSource.of(Path.of("benchmarks/fixtures", filename)),
                PartitionOptions.builder()
                        .markdownOptions(new MarkdownOptions(true, 128, mode, TableLimits.defaults()))
                        .build());
    }

    private List<String> texts(final List<Element> elements) {
        return elements.stream().map(Element::text).toList();
    }

    private List<Integer> parents(final List<Element> elements) {
        final var ids = elements.stream().map(Element::elementId).toList();
        return elements.stream()
                .map(element -> {
                    final var parent = element.metadata().get("parent_id");
                    final int index = parent == null ? -1 : ids.indexOf(parent);
                    assertTrue(parent == null || index >= 0);
                    return index;
                })
                .toList();
    }

    private void sharedLinks(final Element reference, final Element actual) {
        assertEquals(reference.metadata().get("link_texts"), actual.metadata().get("link_texts"));
        assertEquals(reference.metadata().get("link_urls"), actual.metadata().get("link_urls"));
        assertFalse(reference.metadata().containsKey("link_start_indexes"));
        final String label = ((List<?>) actual.metadata().get("link_texts")).getFirst().toString();
        assertEquals(List.of(actual.text().codePointCount(0, actual.text().indexOf(label))),
                actual.metadata().get("link_start_indexes"));
    }

    private void sharedTable(final Element reference, final Element actual) {
        assertEquals(reference.text().replaceAll("\\s+", " "), actual.text().replaceAll("\\s+", " "));
        assertNotEquals(reference.text(), actual.text(), "Java preserves row/cell text separators");
        final var ref = Jsoup.parse((String) reference.metadata().get("text_as_html"));
        final var out = Jsoup.parse((String) actual.metadata().get("text_as_html"));
        assertEquals(ref.select("td, th").eachText(), out.select("td, th").eachText());
        assertEquals(ref.select("tr").size(), out.select("tr").size());
        assertEquals(ref.select("th").size(), out.select("th").size());
    }

    private void sourceMetadata(final List<Element> actual, final String filename, final String filetype) {
        assertTrue(actual.stream().allMatch(
                e -> filename.equals(e.metadata().get("filename")) && filetype.equals(e.metadata().get("filetype"))));
    }

    @Test
    void structuredHtmlMatchesSharedTextTypesHierarchyLinksAndCells() throws Exception {
        final var reference = reference("structured.html");
        final var result = candidate("structured.html", MarkdownOptions.RawHtml.PRESERVE_TEXT);
        final var actual = result.elements();
        assertTrue(result.warnings().isEmpty());
        assertEquals(6, actual.size());
        assertEquals(reference.stream().map(Element::type).toList(), actual.stream().map(Element::type).toList());
        assertEquals(texts(reference).subList(0, 5), texts(actual).subList(0, 5));
        assertEquals(parents(reference), parents(actual));
        sharedLinks(reference.get(1), actual.get(1));
        sharedTable(reference.getLast(), actual.getLast());
        for (final int index : List.of(3, 4)) {
            assertEquals(1, reference.get(index).metadata().get("category_depth"));
            assertEquals(0, actual.get(index).metadata().get("category_depth"));
        }
        sourceMetadata(actual, "structured.html", "text/html");
        assertEquals(actual, json.read(json.write(actual)));
    }

    @Test
    void markdownMatchesSharedSemanticsWithExplicitNestingCodeAndMetadataDifferences() throws Exception {
        final var reference = reference("structured.md");
        final var result = candidate("structured.md", MarkdownOptions.RawHtml.PRESERVE_TEXT);
        final var actual = result.elements();
        assertTrue(result.warnings().isEmpty());
        assertEquals(8, actual.size());
        assertEquals(reference.stream().map(Element::type).toList(), actual.stream().map(Element::type).toList());
        assertEquals(texts(reference).subList(0, 6), texts(actual).subList(0, 6));
        sharedLinks(reference.get(1), actual.get(1));
        assertEquals(2, parents(reference).get(4), "Python-Markdown keeps this two-space item at top list level");
        assertEquals(3, parents(actual).get(4), "CommonMark interprets the item as nested");
        assertFalse(reference.get(6).metadata().containsKey("parent_id"));
        assertEquals(actual.get(2).elementId(), actual.get(6).metadata().get("parent_id"));
        assertEquals(reference.get(6).text() + "\n", actual.get(6).text());
        assertEquals("java", actual.get(6).metadata().get("code_language"));
        assertFalse(reference.get(6).metadata().containsKey("code_language"));
        sharedTable(reference.getLast(), actual.getLast());
        assertTrue(actual.getLast().text().contains("00100"));
        assertEquals(List.of("left", "right"), actual.getLast().metadata().get("table_column_alignments"));
        sourceMetadata(actual, "structured.md", "text/markdown");
    }

    @Test
    void mixedHtmlRecordsPreserveSkipAndDomContractsWithoutHidingUpstreamVisibilityDifferences() throws Exception {
        final var reference = reference("mixed-html.md");
        assertEquals(List.of("Mixed report", "Text 🧵 linked.", "Embedded section", "Raw content.", "Hidden content.",
                             "After paragraph."),
                texts(reference));
        final var preserved = candidate("mixed-html.md", MarkdownOptions.RawHtml.PRESERVE_TEXT);
        assertEquals(4, preserved.elements().size());
        assertTrue(preserved.elements().get(1).text().contains("<a href="));
        final String rawBlock = Files.readString(Path.of("benchmarks/fixtures/mixed-html.md"))
                                        .lines()
                                        .filter(line -> line.startsWith("<div>"))
                                        .findFirst()
                                        .orElseThrow();
        assertEquals(rawBlock, preserved.elements().get(2).text());
        assertEquals(ElementType.UncategorizedText, preserved.elements().get(2).type());
        assertEquals(1, preserved.warnings().size());
        assertEquals(PartitionResult.WarningCode.RAW_HTML_PRESERVED, preserved.warnings().getFirst().code());
        final var skipped = candidate("mixed-html.md", MarkdownOptions.RawHtml.SKIP);
        assertEquals(List.of("Mixed report", "Text 🧵 linked.", "After paragraph."), texts(skipped.elements()));
        assertFalse(skipped.elements().get(1).metadata().containsKey("links"));
        assertTrue(skipped.warnings().isEmpty());
        final var dom = candidate("mixed-html.md", MarkdownOptions.RawHtml.EXTRACT_DOM);
        assertTrue(dom.warnings().isEmpty());
        assertEquals(List.of("Mixed report", "Text 🧵 linked.", "Embedded section", "Raw content.", "After paragraph."),
                texts(dom.elements()));
        assertFalse(texts(dom.elements()).contains("Hidden content."));
        sharedLinks(reference.get(1), dom.elements().get(1));
        assertEquals(List.of(7), dom.elements().get(1).metadata().get("link_start_indexes"));
        assertEquals(ElementType.UncategorizedText, reference.get(3).type());
        assertEquals(ElementType.NarrativeText, dom.elements().get(3).type());
        assertEquals(dom.elements().get(2).elementId(), dom.elements().getLast().metadata().get("parent_id"));
        sourceMetadata(dom.elements(), "mixed-html.md", "text/markdown");
    }
}
