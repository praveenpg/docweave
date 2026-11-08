package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** AST-mode emphasis, strikethrough and task-list metadata, and the opt-in extension contract. */
class MarkdownInlineMetadataTest {
    private static final List<String> SPAN_KEYS =
            List.of("emphasized_text_contents", "emphasized_text_tags", "emphasized_text_start_indexes",
                    "strikethrough_texts", "strikethrough_start_indexes", "task_list_item_checked");

    private final DocWeave processor = DocWeave.create();

    private List<Element> parse(final String source, final Set<MarkdownOptions.Extension> extensions) throws Exception {
        final var options = PartitionOptions.builder()
                                    .markdownOptions(MarkdownOptions.defaults().withExtensions(extensions))
                                    .build();
        return processor.partition(DocumentSource.text(source, "inline.md"), options).elements();
    }

    private List<Element> parse(final String source) throws Exception {
        return parse(source, Set.of());
    }

    private static Object meta(final Element element, final String key) {
        return element.metadata().get(key);
    }

    /** Every reported span must name exactly the code points it points at. */
    private static void assertSpansAddressText(final Element element) {
        for (final var keys : List.of(List.of("emphasized_text_contents", "emphasized_text_start_indexes"),
                     List.of("strikethrough_texts", "strikethrough_start_indexes"),
                     List.of("link_texts", "link_start_indexes"))) {
            final var texts = (List<?>) meta(element, keys.get(0));
            final var starts = (List<?>) meta(element, keys.get(1));
            if (texts == null) {
                assertNull(starts);
                continue;
            }
            assertEquals(texts.size(), starts.size());
            for (int i = 0; i < texts.size(); i++) {
                final String text = element.text();
                final int from = text.offsetByCodePoints(0, (Integer) starts.get(i));
                assertTrue(text.startsWith((String) texts.get(i), from), keys + " " + i + " in " + text);
            }
        }
    }

    @Test
    void emphasisRunsSplitAtEveryBoundaryAndCombineNestedTags() throws Exception {
        final var paragraph =
                parse("Plain ***both*** and **outer *inner* tail** and _under_ __score__ **a** **b**.").getFirst();
        assertEquals("Plain both and outer inner tail and under score a b.", paragraph.text());
        assertEquals(List.of("both", "outer", "inner", "tail", "under", "score", "a", "b"),
                meta(paragraph, "emphasized_text_contents"));
        assertEquals(List.of("bi", "b", "bi", "b", "i", "b", "b", "b"), meta(paragraph, "emphasized_text_tags"));
        assertEquals(List.of(6, 15, 21, 27, 36, 42, 48, 50), meta(paragraph, "emphasized_text_start_indexes"));
        assertSpansAddressText(paragraph);
    }

    @Test
    void documentedExampleProducesTheDocumentedSpans() throws Exception {
        final var paragraph = parse("Plain **outer *inner* tail** 🧵 *é*.").getFirst();
        assertEquals("Plain outer inner tail 🧵 é.", paragraph.text());
        assertEquals(List.of("outer", "inner", "tail", "é"), meta(paragraph, "emphasized_text_contents"));
        assertEquals(List.of("b", "bi", "b", "i"), meta(paragraph, "emphasized_text_tags"));
        assertEquals(List.of(6, 12, 18, 25), meta(paragraph, "emphasized_text_start_indexes"));
    }

    @Test
    void codeEscapesAndIntrawordUnderscoresAreNotEmphasisWhileCodeInsideEmphasisIsPartOfTheRun() throws Exception {
        final var elements = parse("Keep `*literal*` and \\*escaped\\* and snake_case_name.\n\n*see `code` here*\n");
        assertEquals("Keep *literal* and *escaped* and snake_case_name.", elements.getFirst().text());
        assertTrue(elements.getFirst().metadata().keySet().stream().noneMatch(SPAN_KEYS::contains));
        assertEquals(List.of("see code here"), meta(elements.getLast(), "emphasized_text_contents"));
        assertEquals(List.of(0), meta(elements.getLast(), "emphasized_text_start_indexes"));
    }

    @Test
    void unicodeOffsetsCountCodePointsNotUtf16Units() throws Exception {
        final var paragraph = parse("🧵 𝔘 *é 👩‍🔬 x* and **東京**").getFirst();
        assertEquals("🧵 𝔘 é 👩‍🔬 x and 東京", paragraph.text());
        assertEquals(List.of("é 👩‍🔬 x", "東京"), meta(paragraph, "emphasized_text_contents"));
        // 🧵 and 𝔘 are one code point each but two UTF-16 units; the ZWJ sequence is three code points.
        assertEquals(List.of(4, 16), meta(paragraph, "emphasized_text_start_indexes"));
        assertSpansAddressText(paragraph);
    }

    @Test
    void linksAndEmphasisNestInEitherDirectionWithIndependentMetadata() throws Exception {
        final var paragraph =
                parse("A [**bold link**](https://e.org/a) and *see [the docs](https://e.org/b)*.").getFirst();
        assertEquals("A bold link and see the docs.", paragraph.text());
        assertEquals(List.of("bold link", "see the docs"), meta(paragraph, "emphasized_text_contents"));
        assertEquals(List.of("b", "i"), meta(paragraph, "emphasized_text_tags"));
        assertEquals(List.of(2, 20), meta(paragraph, "link_start_indexes"));
        assertSpansAddressText(paragraph);
    }

    @Test
    void headingsListsAndTableCellsCarrySpansWithoutChangingTextOrderOrHierarchy() throws Exception {
        final String source = "# Title *em*\n\n- item **one**\n  - child _two_\n\n| H *a* | B |\n| --- | --- |\n"
                              + "| **c** | 🧵 *d* |\n";
        final var elements = parse(source);
        assertEquals(List.of("Title em", "item one", "child two", "H a\tB\nc\t🧵 d"),
                elements.stream().map(Element::text).toList());
        assertEquals(List.of(ElementType.Title, ElementType.ListItem, ElementType.ListItem, ElementType.Table),
                elements.stream().map(Element::type).toList());
        assertEquals(elements.get(1).elementId(), meta(elements.get(2), "parent_id"));
        assertEquals(List.of("em"), meta(elements.getFirst(), "emphasized_text_contents"));
        assertEquals(List.of(5), meta(elements.get(1), "emphasized_text_start_indexes"));
        final var table = elements.getLast();
        assertEquals(List.of("a", "c", "d"), meta(table, "emphasized_text_contents"));
        assertEquals(List.of("i", "b", "i"), meta(table, "emphasized_text_tags"));
        assertEquals(List.of(2, 6, 10), meta(table, "emphasized_text_start_indexes"));
        elements.forEach(MarkdownInlineMetadataTest::assertSpansAddressText);
    }

    @Test
    void listItemContinuationBlocksKeepTheItemAsParent() throws Exception {
        final var elements =
                parse("1. first\n\n   ```\n   code\n   ```\n\n   after\n\n   - child\n\n   tail\n2. second\n");
        assertEquals(List.of("first", "code\n", "after", "child", "tail", "second"),
                elements.stream().map(Element::text).toList());
        final String first = elements.getFirst().elementId();
        for (final int index : List.of(1, 2, 3, 4)) {
            assertEquals(first, meta(elements.get(index), "parent_id"), "element " + index);
        }
        assertNull(meta(elements.getLast(), "parent_id"));
    }

    @Test
    void extensionSyntaxStaysLiteralByDefault() throws Exception {
        final var elements = parse("- [ ] open\n\nA ~~gone~~ https://e.org/x www.e.org a@e.org\n");
        assertEquals(List.of("[ ] open", "A ~~gone~~ https://e.org/x www.e.org a@e.org"),
                elements.stream().map(Element::text).toList());
        assertTrue(elements.stream().allMatch(element
                -> element.metadata().keySet().stream().noneMatch(SPAN_KEYS::contains)
                           && !element.metadata().containsKey("links")));
    }

    @Test
    void taskListItemsRecordStateOnTheItemsFirstElement() throws Exception {
        final var elements = parse("- [ ] open\n- [x] done\n- [X] upper\n- plain\n- [ ]\n\n1. [x] ordered\n\n   more\n",
                EnumSet.of(MarkdownOptions.Extension.TASK_LIST_ITEMS));
        assertEquals(List.of("open", "done", "upper", "plain", "[ ]", "ordered", "more"),
                elements.stream().map(Element::text).toList());
        assertEquals(false, meta(elements.getFirst(), "task_list_item_checked"));
        assertEquals(true, meta(elements.get(1), "task_list_item_checked"));
        assertEquals(true, meta(elements.get(2), "task_list_item_checked"));
        assertNull(meta(elements.get(3), "task_list_item_checked"));
        assertNull(meta(elements.get(4), "task_list_item_checked"), "a marker without content is literal text");
        assertEquals(true, meta(elements.get(5), "task_list_item_checked"));
        assertNull(meta(elements.getLast(), "task_list_item_checked"));
    }

    @Test
    void strikethroughRequiresDoubleTildesAndReportsSpansInProseAndCells() throws Exception {
        final var elements = parse("A ~~gone *now*~~ and ~kept~.\n\n| H |\n| --- |\n| x ~~🧵y~~ |\n",
                EnumSet.of(MarkdownOptions.Extension.STRIKETHROUGH));
        final var paragraph = elements.getFirst();
        assertEquals("A gone now and ~kept~.", paragraph.text());
        assertEquals(List.of("gone now"), meta(paragraph, "strikethrough_texts"));
        assertEquals(List.of(2), meta(paragraph, "strikethrough_start_indexes"));
        assertEquals(List.of("now"), meta(paragraph, "emphasized_text_contents"));
        final var table = elements.getLast();
        assertEquals("H\nx 🧵y", table.text());
        assertEquals(List.of(4), meta(table, "strikethrough_start_indexes"));
        elements.forEach(MarkdownInlineMetadataTest::assertSpansAddressText);
    }

    @Test
    void autolinksAddLinkMetadataWithoutChangingTextOrTouchingCodeAndExistingLinks() throws Exception {
        final var paragraph = parse("See https://e.org/a, www.e.org/b and team@e.org; `https://e.org/code` and "
                                            + "[label](https://e.org/c) <https://e.org/d>.",
                EnumSet.of(MarkdownOptions.Extension.AUTOLINKS))
                                      .getFirst();
        assertEquals("See https://e.org/a, www.e.org/b and team@e.org; https://e.org/code and label "
                             + "https://e.org/d.",
                paragraph.text());
        assertEquals(List.of("https://e.org/a", "www.e.org/b", "team@e.org", "label", "https://e.org/d"),
                meta(paragraph, "link_texts"));
        assertEquals(List.of("https://e.org/a", "http://www.e.org/b", "mailto:team@e.org", "https://e.org/c",
                             "https://e.org/d"),
                meta(paragraph, "link_urls"));
        assertSpansAddressText(paragraph);
    }

    @Test
    void optionsKeepCompatibleConstructorAndCopyExtensionsDeterministically() {
        final var legacy =
                new MarkdownOptions(true, 128, MarkdownOptions.RawHtml.PRESERVE_TEXT, TableLimits.defaults());
        assertEquals(MarkdownOptions.defaults(), legacy);
        assertEquals(Set.of(), legacy.extensions());
        final var selected = EnumSet.of(MarkdownOptions.Extension.AUTOLINKS, MarkdownOptions.Extension.STRIKETHROUGH);
        final var options = legacy.withExtensions(selected);
        selected.clear();
        assertEquals(List.of(MarkdownOptions.Extension.STRIKETHROUGH, MarkdownOptions.Extension.AUTOLINKS),
                List.copyOf(options.extensions()));
        assertThrows(UnsupportedOperationException.class,
                () -> options.extensions().add(MarkdownOptions.Extension.TASK_LIST_ITEMS));
        assertEquals(options, legacy.withExtensions(Set.of(
                                      MarkdownOptions.Extension.AUTOLINKS, MarkdownOptions.Extension.STRIKETHROUGH)));
        assertThrows(NullPointerException.class, () -> legacy.withExtensions(null));
        final var withNull = new java.util.HashSet<MarkdownOptions.Extension>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> legacy.withExtensions(withNull));
    }
}
