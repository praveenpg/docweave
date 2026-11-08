package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** AST-mode footnotes, definition lists and front matter (#59), all opt-in. */
class MarkdownExtensionSyntaxTest {
    private static final Set<MarkdownOptions.Extension> FOOTNOTES = EnumSet.of(MarkdownOptions.Extension.FOOTNOTES);
    private static final Set<MarkdownOptions.Extension> DEFINITIONS =
            EnumSet.of(MarkdownOptions.Extension.DEFINITION_LISTS);
    private static final Set<MarkdownOptions.Extension> FRONT_MATTER =
            EnumSet.of(MarkdownOptions.Extension.FRONT_MATTER);

    private final DocWeave processor = DocWeave.create();

    private PartitionResult result(final String source, final Set<MarkdownOptions.Extension> extensions,
            final int maxNesting) throws Exception {
        final var markdown = new MarkdownOptions(true, maxNesting, MarkdownOptions.RawHtml.PRESERVE_TEXT,
                TableLimits.defaults(), extensions);
        final var options = PartitionOptions.builder().markdownOptions(markdown).build();
        return processor.partition(DocumentSource.text(source, "notes.md"), options);
    }

    private PartitionResult result(final String source, final Set<MarkdownOptions.Extension> extensions)
            throws Exception {
        return result(source, extensions, 128);
    }

    private List<Element> parse(final String source, final Set<MarkdownOptions.Extension> extensions)
            throws Exception {
        return result(source, extensions).elements();
    }

    private static List<String> texts(final List<Element> elements) {
        return elements.stream().map(Element::text).toList();
    }

    private static Object meta(final Element element, final String key) {
        return element.metadata().get(key);
    }

    /** Strategy metadata without the central source keys. */
    private static Map<String, Object> strategy(final Element element) {
        final var metadata = new LinkedHashMap<>(element.metadata());
        metadata.keySet().removeAll(Set.of("filename", "filetype", "languages"));
        return metadata;
    }

    @Test
    void newSyntaxStaysLiteralWithoutItsOption() throws Exception {
        final String source = "---\ntitle: T\n---\n\nSee[^n].\n\n[^n]: Note.\n\nTerm\n: Def.\n";
        final var plain = parse(source, Set.of());
        // Plain CommonMark reads "[^n]: Note." as a link reference definition, so its text is lost and "[^n]" becomes
        // a link labelled "^n". The FOOTNOTES option keeps the note.
        assertEquals(List.of("title: T", "See^n.", "Term : Def."), texts(plain));
        assertEquals(ElementType.Title, plain.getFirst().type());
        // Each option leaves the other syntaxes exactly as they were.
        assertEquals(texts(plain).subList(1, 3), texts(parse(source, FRONT_MATTER)));
        assertEquals(List.of("title: T", "See[1].", "Note.", "Term : Def."), texts(parse(source, FOOTNOTES)));
        assertEquals(List.of("title: T", "See^n.", "Term", "Def."), texts(parse(source, DEFINITIONS)));
    }

    @Test
    void footnoteReferencesAreNumberedByFirstReferenceAndDefinitionsStayInPlace() throws Exception {
        final var elements = parse("""
                # Notes

                [^b]: Defined before use.

                Alpha[^a] then beta[^b] and alpha again[^A].

                [^a]: First body.

                    Second paragraph of the first note.

                [^unused]: Never referenced.
                """, FOOTNOTES);
        assertEquals(List.of("Notes", "Defined before use.", "Alpha[1] then beta[2] and alpha again[1].", "First body.",
                             "Second paragraph of the first note.", "Never referenced."),
                texts(elements));
        final var prose = elements.get(2);
        assertEquals(List.of("a", "b", "A"), meta(prose, "footnote_reference_labels"));
        assertEquals(List.of(1, 2, 1), meta(prose, "footnote_reference_numbers"));
        assertEquals(List.of(5, 18, 37), meta(prose, "footnote_reference_start_indexes"));
        for (int i = 0; i < 3; i++) {
            final int start = ((List<Integer>) meta(prose, "footnote_reference_start_indexes")).get(i);
            final int number = ((List<Integer>) meta(prose, "footnote_reference_numbers")).get(i);
            assertTrue(prose.text().startsWith("[" + number + "]", start));
        }
        final var before = elements.get(1);
        assertEquals("b", meta(before, "footnote_label"));
        assertEquals(2, meta(before, "footnote_number"));
        assertEquals(elements.getFirst().elementId(), meta(before, "parent_id"));
        final var first = elements.get(3);
        assertEquals(1, meta(first, "footnote_number"));
        assertEquals(first.elementId(), meta(elements.get(4), "parent_id"));
        assertNull(meta(elements.get(4), "footnote_label"));
        assertEquals("unused", meta(elements.get(5), "footnote_label"));
        assertNull(meta(elements.get(5), "footnote_number"));
        assertEquals(ElementType.NarrativeText, first.type());
    }

    @Test
    void undefinedDuplicateAndMalformedFootnotesLoseNoText() throws Exception {
        final var elements = parse("""
                Known[^k], unknown[^missing], spaced[^ k ] and empty[^].

                [^k]: First definition.

                [^K]: Duplicate definition.

                [^bad label]: Not a definition.
                """, FOOTNOTES);
        // Labels match as CommonMark link labels do: trimmed and case-insensitive.
        assertEquals(List.of("Known[1], unknown[^missing], spaced[1] and empty[^].", "First definition.",
                             "Duplicate definition.", "[^bad label]: Not a definition."),
                texts(elements));
        assertEquals(List.of("k", " k "), meta(elements.getFirst(), "footnote_reference_labels"));
        assertEquals(List.of(1, 1), meta(elements.getFirst(), "footnote_reference_numbers"));
        assertEquals(1, meta(elements.get(1), "footnote_number"));
        assertEquals("K", meta(elements.get(2), "footnote_label"));
        assertNull(meta(elements.get(2), "footnote_number"));
    }

    @Test
    void footnoteOffsetsCountCodePointsInProseHeadingsListsAndTables() throws Exception {
        final var elements = parse("""
                # 標題 🧵[^n]

                - item **é**[^n]

                | 🧵 | b[^n] |
                | --- | --- |
                | c | d |

                [^n]: Zażółć 🧵 note.
                """, FOOTNOTES);
        final var heading = elements.getFirst();
        assertEquals("標題 🧵[1]", heading.text());
        assertEquals(List.of(4), meta(heading, "footnote_reference_start_indexes"));
        final var item = elements.get(1);
        assertEquals("item é[1]", item.text());
        assertEquals(List.of(6), meta(item, "footnote_reference_start_indexes"));
        assertEquals(List.of("é"), meta(item, "emphasized_text_contents"));
        final var table = elements.get(2);
        assertEquals(ElementType.Table, table.type());
        final int start = ((List<Integer>) meta(table, "footnote_reference_start_indexes")).getFirst();
        assertEquals("[1]", table.text().substring(table.text().offsetByCodePoints(0, start)).substring(0, 3));
        assertEquals("Zażółć 🧵 note.", elements.get(3).text());
    }

    @Test
    void footnoteDefinitionsCanHoldListsAndCodeUnderTheirFirstElement() throws Exception {
        final var elements = parse("""
                Text[^x].

                [^x]: Intro.

                    - one
                    - two

                    ```sh
                    run
                    ```
                """, FOOTNOTES);
        assertEquals(List.of("Text[1].", "Intro.", "one", "two", "run\n"), texts(elements));
        final String note = elements.get(1).elementId();
        for (final var element : elements.subList(2, 5)) {
            assertEquals(note, meta(element, "parent_id"));
        }
        assertEquals(ElementType.CodeSnippet, elements.get(4).type());
    }

    @Test
    void definitionListsLinkTermsAndDefinitions() throws Exception {
        final var elements = parse("""
                # Glossary

                Apple
                Pomme
                : A **fruit**.
                : A company.

                Banana

                :   Yellow, loose form.

                    Second paragraph of the definition.
                """, DEFINITIONS);
        assertEquals(List.of("Glossary", "Apple", "Pomme", "A fruit.", "A company.", "Banana", "Yellow, loose form.",
                             "Second paragraph of the definition."),
                texts(elements));
        final String heading = elements.getFirst().elementId();
        final var apple = elements.get(1);
        final var pomme = elements.get(2);
        for (final var term : List.of(apple, pomme, elements.get(5))) {
            assertEquals(ElementType.ListItem, term.type());
            assertEquals("term", meta(term, "definition_list_role"));
            assertEquals(heading, meta(term, "parent_id"));
            assertEquals(0, meta(term, "category_depth"));
        }
        for (final var definition : List.of(elements.get(3), elements.get(4))) {
            assertEquals(ElementType.ListItem, definition.type());
            assertEquals("definition", meta(definition, "definition_list_role"));
            assertEquals(apple.elementId(), meta(definition, "parent_id"));
            assertEquals(List.of(apple.elementId(), pomme.elementId()), meta(definition, "definition_term_ids"));
            assertEquals(1, meta(definition, "category_depth"));
        }
        assertEquals(List.of("fruit"), meta(elements.get(3), "emphasized_text_contents"));
        final var yellow = elements.get(6);
        assertEquals(List.of(elements.get(5).elementId()), meta(yellow, "definition_term_ids"));
        assertEquals(yellow.elementId(), meta(elements.get(7), "parent_id"));
        assertNull(meta(elements.get(7), "definition_list_role"));
    }

    @Test
    void definitionsNestListsCodeAndDefinitionListsWithDepth() throws Exception {
        final var elements = parse("""
                Term
                : Intro.

                  - nested item

                  Inner
                  : Inner definition.
                """, DEFINITIONS);
        assertEquals(List.of("Term", "Intro.", "nested item", "Inner", "Inner definition."), texts(elements));
        final String intro = elements.get(1).elementId();
        assertEquals(intro, meta(elements.get(2), "parent_id"));
        assertEquals(intro, meta(elements.get(3), "parent_id"));
        assertEquals("term", meta(elements.get(3), "definition_list_role"));
        // Content of a definition (depth 1) is one level deeper: nested items and nested terms are at depth 2.
        assertEquals(2, meta(elements.get(2), "category_depth"));
        assertEquals(2, meta(elements.get(3), "category_depth"));
        assertEquals(elements.get(3).elementId(), meta(elements.get(4), "parent_id"));
        assertEquals(3, meta(elements.get(4), "category_depth"));
        final var limited = assertThrows(PartitionException.class, () -> result("""
                A
                : B

                  C
                  : D

                    E
                    : F
                """, DEFINITIONS, 4));
        assertEquals(PartitionException.Code.MARKDOWN_DEPTH_LIMIT, limited.code());
    }

    @Test
    void markersWithoutATermOrContentStayLiteral() throws Exception {
        final var elements = parse("""
                : starts the document.

                # Heading
                : after a heading.

                Colon:not a marker
                :

                    : indented code
                """, DEFINITIONS);
        assertEquals(List.of(": starts the document.", "Heading", ": after a heading.", "Colon:not a marker :",
                             ": indented code\n"),
                texts(elements));
        assertTrue(elements.stream().noneMatch(element -> meta(element, "definition_list_role") != null));
    }

    @Test
    void frontMatterValuesReachEveryElementAndTheBlockEmitsNothing() throws Exception {
        final var result = result("""
                ---
                title: "Site Report: Q3"
                author: Field Team   # trailing comment
                count: 3
                draft: true
                tags:
                  - field
                  - 'two words'
                aliases: [a, "b, c"]
                empty:
                # a comment
                summary: >
                  Folded line one
                  continues here.

                  New paragraph.
                notes: |-
                  Line one
                  Line two
                unicode: Zażółć 🧵
                ---
                # Site Report

                Body text.
                """, FRONT_MATTER);
        assertTrue(result.warnings().isEmpty());
        final var elements = result.elements();
        assertEquals(List.of("Site Report", "Body text."), texts(elements));
        final var expected = new LinkedHashMap<String, Object>();
        expected.put("title", "Site Report: Q3");
        expected.put("author", "Field Team");
        expected.put("count", "3");
        expected.put("draft", "true");
        expected.put("tags", List.of("field", "two words"));
        expected.put("aliases", List.of("a", "b, c"));
        expected.put("empty", "");
        expected.put("summary", "Folded line one continues here.\nNew paragraph.\n");
        expected.put("notes", "Line one\nLine two");
        expected.put("unicode", "Zażółć 🧵");
        for (final var element : elements) {
            assertEquals(expected, meta(element, "front_matter"));
            assertEquals(List.copyOf(expected.keySet()),
                    List.copyOf(((Map<?, ?>) meta(element, "front_matter")).keySet()));
        }
        assertEquals(ElementType.Title, elements.getFirst().type());
        assertEquals(0, meta(elements.getFirst(), "category_depth"));
    }

    @Test
    void malformedFrontMatterIsKeptAsTextWithAWarningAndNothingIsInterpreted() throws Exception {
        for (final String content : List.of("payload: !!python/object/apply:os.system [echo]", "base: &a x\nref: *a",
                     "outer:\n  inner: value", "key: one\nkey: two", "title: \"unterminated", "map: {a: 1}",
                     "list:\n  - - nested", "a: b\n\tc: tab", "esc: \"\\uD800\"", "esc: \"\\u12G4\"", "a: - x")) {
            final var result = result("---\n" + content + "\n---\nBody.\n", FRONT_MATTER);
            assertEquals(List.of(PartitionResult.WarningCode.MARKDOWN_FRONT_MATTER_INVALID),
                    result.warnings().stream().map(PartitionResult.Warning::code).toList(), content);
            assertFalse(result.warnings().getFirst().message().contains("python"), content);
            final var elements = result.elements();
            assertEquals(List.of(content, "Body."), texts(elements), content);
            assertEquals(ElementType.UncategorizedText, elements.getFirst().type());
            assertTrue(elements.stream().allMatch(element -> meta(element, "front_matter") == null), content);
        }
    }

    @Test
    void frontMatterLimitsDelimitersAndLineEndings() throws Exception {
        // Keys and sequence items count together towards the value limit.
        final var values = new StringBuilder("list:\n");
        for (int i = 1; i < MarkdownFrontMatter.MAX_VALUES; i++) {
            values.append("  - v").append(i).append('\n');
        }
        assertTrue(result("---\n" + values + "---\nBody.\n", FRONT_MATTER).warnings().isEmpty());
        values.append("  - one too many\n");
        assertEquals(PartitionResult.WarningCode.MARKDOWN_FRONT_MATTER_INVALID,
                result("---\n" + values + "---\nBody.\n", FRONT_MATTER).warnings().getFirst().code());
        // The closing delimiter must follow within the character limit; otherwise there is no front matter.
        final String large = "---\nbig: " + "x".repeat(MarkdownFrontMatter.MAX_CHARACTERS) + "\n---\nBody.\n";
        assertEquals(texts(parse(large, Set.of())), texts(parse(large, FRONT_MATTER)));
        assertTrue(result(large, FRONT_MATTER).warnings().isEmpty());
        final String fits = "---\nbig: " + "x".repeat(MarkdownFrontMatter.MAX_CHARACTERS - 10) + "\n---\nBody.\n";
        assertEquals(List.of("Body."), texts(parse(fits, FRONT_MATTER)));
        // A first content line that is neither a key nor a comment means the block is ordinary Markdown, so a
        // leading thematic break keeps its meaning.
        for (final String opening : List.of("just text", "\ttitle: tab", "%YAML 1.2", "? complex key", "", "  a: b")) {
            final String markdown = "---\n" + opening + "\n---\nBody.\n";
            assertEquals(texts(parse(markdown, Set.of())), texts(parse(markdown, FRONT_MATTER)), opening);
            assertTrue(result(markdown, FRONT_MATTER).warnings().isEmpty(), opening);
        }
        final var commented = parse("---\n# a comment\ntitle: T # trailing\n---\nBody.\n", FRONT_MATTER);
        assertEquals(Map.of("title", "T"), meta(commented.getFirst(), "front_matter"));
        assertEquals(Map.of("tag", "a#b"), meta(parse("---\ntag: a#b\n---\nBody.\n", FRONT_MATTER).getFirst(),
                "front_matter"), "a # without preceding whitespace is part of the value");
        // Without a closing delimiter there is no front matter: output equals the option being off.
        final String unclosed = "---\ntitle: T\n\n# Heading\n\nBody.\n";
        assertEquals(texts(parse(unclosed, Set.of())), texts(parse(unclosed, FRONT_MATTER)));
        assertTrue(result(unclosed, FRONT_MATTER).warnings().isEmpty());
        // Not on the first line: ordinary Markdown.
        assertEquals(texts(parse("\n---\na: b\n---\n", Set.of())), texts(parse("\n---\na: b\n---\n", FRONT_MATTER)));
        // CRLF, a byte-order mark, the "..." terminator and an empty block.
        final var crlf = parse("﻿---\r\nk: v\r\n...\r\nBody.\r\n", FRONT_MATTER);
        assertEquals(List.of("Body."), texts(crlf));
        assertEquals(Map.of("k", "v"), meta(crlf.getFirst(), "front_matter"));
        final var empty = parse("---\n---\nBody.\n", FRONT_MATTER);
        assertEquals(List.of("Body."), texts(empty));
        assertNull(meta(empty.getFirst(), "front_matter"));
    }

    @Test
    void footnotesInsideNotesAreNumberedAfterOuterReferencesAsTheHtmlRendererDoes() throws Exception {
        final var elements = parse("""
                Outer[^b] and outer[^c].

                [^a]: Cited only from b.

                [^b]: Note b cites[^a].

                [^c]: Note c.

                [^]: Kept.
                """, FOOTNOTES);
        assertEquals(List.of("Outer[1] and outer[2].", "Cited only from b.", "Note b cites[3].", "Note c.",
                             "[^]: Kept."), texts(elements));
        assertEquals(3, meta(elements.get(1), "footnote_number"));
        assertEquals(1, meta(elements.get(2), "footnote_number"));
        assertEquals(2, meta(elements.get(3), "footnote_number"));
        assertEquals(ElementType.UncategorizedText, elements.get(4).type(), "a malformed ^ label keeps its text");
    }

    @Test
    void termsSplitOnHardBreaksAndADefinitionStartingWithAListHasNoDefinitionElement() throws Exception {
        final var hard = parse("One  \nTwo\n: Shared.\n", DEFINITIONS);
        assertEquals(List.of("One", "Two", "Shared."), texts(hard));
        assertEquals(List.of(hard.get(0).elementId(), hard.get(1).elementId()),
                meta(hard.get(2), "definition_term_ids"));
        final var listed = parse("Term\n: - first\n  - second\n", DEFINITIONS);
        assertEquals(List.of("Term", "first", "second"), texts(listed));
        for (final Element item : listed.subList(1, 3)) {
            assertEquals(ElementType.ListItem, item.type());
            assertEquals(listed.getFirst().elementId(), meta(item, "parent_id"));
            assertNull(meta(item, "definition_list_role"));
        }
    }

    @Test
    void aNoteThatStartsWithADefinitionListKeepsBothFirstElementRoles() throws Exception {
        final var both = EnumSet.of(MarkdownOptions.Extension.FOOTNOTES, MarkdownOptions.Extension.DEFINITION_LISTS);
        final var elements = parse("Ref[^n].\n\n[^n]: Term\n    : Meaning.\n", both);
        assertEquals(List.of("Ref[1].", "Term", "Meaning."), texts(elements));
        assertEquals("n", meta(elements.get(1), "footnote_label"));
        assertEquals(1, meta(elements.get(1), "footnote_number"));
        assertEquals("term", meta(elements.get(1), "definition_list_role"));
        assertEquals("definition", meta(elements.get(2), "definition_list_role"));
        assertNull(meta(elements.get(2), "footnote_label"), "only the note's first element carries its label");
    }

    @Test
    void headingsInsideNotesAndDefinitionsDoNotParentTheTextAfterThem() throws Exception {
        final var both = EnumSet.of(MarkdownOptions.Extension.FOOTNOTES, MarkdownOptions.Extension.DEFINITION_LISTS);
        for (final var rawHtml : List.of(MarkdownOptions.RawHtml.PRESERVE_TEXT, MarkdownOptions.RawHtml.SKIP)) {
            final var options = PartitionOptions.builder().markdownOptions(
                    new MarkdownOptions(true, 128, rawHtml, TableLimits.defaults(), both)).build();
            final var elements = processor.partition(DocumentSource.text("""
                    # Main

                    Before[^n].

                    [^n]: ## Note heading

                        Note content.

                    After the note.

                    Term
                    : ## Definition heading

                      Definition content.

                    Later term
                    : Later definition.

                    After the definition.

                    ## Section

                    In the section.
                    """, "notes.md"), options).elements();
            final Map<String, String> ids = new java.util.HashMap<>();
            final Map<String, Object> parents = new java.util.HashMap<>();
            for (final Element element : elements) {
                ids.put(element.text(), element.elementId());
                parents.put(element.text(), meta(element, "parent_id"));
            }
            final String main = ids.get("Main");
            // Inside the containers, their own headings still parent the following content.
            assertEquals(ids.get("Note heading"), parents.get("Note content."), rawHtml.name());
            assertEquals(ids.get("Definition heading"), parents.get("Definition content."), rawHtml.name());
            // After them, the outer outline is unchanged.
            assertEquals(main, parents.get("After the note."), rawHtml.name());
            assertEquals(main, parents.get("Later term"), rawHtml.name());
            assertEquals(main, parents.get("After the definition."), rawHtml.name());
            assertEquals(main, parents.get("Section"), rawHtml.name());
            assertEquals(ids.get("Section"), parents.get("In the section."), rawHtml.name());
        }
    }

    @Test
    void allExtensionsCombineAndDefaultsAreUnchanged() throws Exception {
        final String source = """
                ---
                title: Combined
                ---
                - [x] Done~~ish~~ see www.example.org[^n]

                Term[^n]
                : Definition with ~~strike~~.

                [^n]: Note.
                """;
        final var elements = parse(source, EnumSet.allOf(MarkdownOptions.Extension.class));
        assertEquals(List.of("Doneish see www.example.org[1]", "Term[1]", "Definition with strike.", "Note."),
                texts(elements));
        assertEquals(true, meta(elements.getFirst(), "task_list_item_checked"));
        assertEquals(List.of("http://www.example.org"), meta(elements.getFirst(), "link_urls"));
        assertEquals(List.of(1), meta(elements.get(1), "footnote_reference_numbers"));
        assertEquals("term", meta(elements.get(1), "definition_list_role"));
        assertTrue(elements.stream().allMatch(element -> Map.of("title", "Combined").equals(meta(element,
                                                                 "front_matter"))));
        assertEquals(Set.of(), MarkdownOptions.defaults().extensions());
        assertEquals(List.of("parent_id"), List.copyOf(strategy(parse("# H\n\nP\n", Set.of()).get(1)).keySet()));
    }
}
