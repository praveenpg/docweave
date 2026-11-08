package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.chunking.ChunkOptions;
import io.docweave.chunking.Chunker;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Footnotes, definition lists and front matter across the raw-HTML modes (#59). */
class MarkdownExtensionModesTest {
    private static final EnumSet<MarkdownOptions.Extension> ALL = EnumSet.allOf(MarkdownOptions.Extension.class);
    private static final String SOURCE = """
            ---
            title: Modes
            ---
            # Modes

            Prose with <b>raw</b> markup[^n].

            Term <i>raw</i>
            : Definition with <span>inline HTML</span>.

            [^n]: Note with <em>markup</em>.
            """;

    private final DocWeave processor = DocWeave.create();

    private PartitionResult partition(final String source, final MarkdownOptions.RawHtml mode) throws Exception {
        final var markdown = new MarkdownOptions(true, 128, mode, TableLimits.defaults(), ALL);
        return processor.partition(DocumentSource.text(source, "modes.md"),
                PartitionOptions.builder().markdownOptions(markdown).build());
    }

    private static List<String> texts(final List<Element> elements) {
        return elements.stream().map(Element::text).toList();
    }

    @Test
    void eachModeKeepsTextOrderAndFrontMatter() throws Exception {
        final var preserve = partition(SOURCE, MarkdownOptions.RawHtml.PRESERVE_TEXT).elements();
        assertEquals(List.of("Modes", "Prose with <b>raw</b> markup[1].", "Term <i>raw</i>",
                             "Definition with <span>inline HTML</span>.", "Note with <em>markup</em>."),
                texts(preserve));
        final var skip = partition(SOURCE, MarkdownOptions.RawHtml.SKIP).elements();
        assertEquals(List.of("Modes", "Prose with raw markup[1].", "Term raw", "Definition with inline HTML.",
                             "Note with markup."),
                texts(skip));
        assertEquals("term", skip.get(2).metadata().get("definition_list_role"));
        assertEquals(1, skip.get(4).metadata().get("footnote_number"));
        final var dom = partition(SOURCE, MarkdownOptions.RawHtml.EXTRACT_DOM).elements();
        assertEquals(List.of("Modes", "Prose with raw markup1.", "Term raw", "Definition with inline HTML.",
                             "Note with markup. ↩"),
                texts(dom));
        for (final var elements : List.of(preserve, skip, dom)) {
            assertTrue(elements.stream().allMatch(
                    element -> Map.of("title", "Modes").equals(element.metadata().get("front_matter"))));
        }
    }

    @Test
    void chunksKeepFrontMatterAndDropElementLocalStructure() throws Exception {
        final var elements = partition(SOURCE, MarkdownOptions.RawHtml.PRESERVE_TEXT).elements();
        final var chunks = Chunker.create().byTitle(elements, ChunkOptions.defaults());
        assertEquals(1, chunks.size());
        assertEquals(Map.of("title", "Modes"), chunks.getFirst().metadata().get("front_matter"));
        assertTrue(chunks.getFirst().metadata().keySet().stream().noneMatch(
                key -> key.startsWith("footnote_") || key.startsWith("definition_")));
    }

    @Test
    void malformedFrontMatterPrecedesTheBodyInEveryMode() throws Exception {
        for (final var mode : MarkdownOptions.RawHtml.values()) {
            final var result = partition("---\nkey: !tag value\n---\n# Body\n", mode);
            assertEquals(List.of("key: !tag value", "Body"), texts(result.elements()), mode.name());
            assertEquals(ElementType.UncategorizedText, result.elements().getFirst().type(), mode.name());
            assertEquals(PartitionResult.WarningCode.MARKDOWN_FRONT_MATTER_INVALID,
                    result.warnings().getLast().code(), mode.name());
        }
    }

    @Test
    void definitionListsInsideListItemsRenderInDomMode() throws Exception {
        final String source = """
                - Item

                  Term
                  : Definition.
                """;
        assertEquals(List.of("Item", "Term", "Definition."),
                texts(partition(source, MarkdownOptions.RawHtml.PRESERVE_TEXT).elements()));
        assertEquals(List.of("Item", "Term", "Definition."),
                texts(partition(source, MarkdownOptions.RawHtml.EXTRACT_DOM).elements()));
    }

    @Test
    void domModeKeepsTheTextOfFootnotesTheRendererWouldDrop() throws Exception {
        final String source = """
                Cited[^a].

                [^a]: Referenced note cites[^c].

                [^b]: Unreferenced note.

                [^A]: Duplicate of a.

                [^c]: Reached only through a.

                [^d]: Unreferenced, cites[^e].

                [^e]: Reached only through d.
                """;
        // Notes the renderer omits (b, the duplicate, d and e, which only d cites) keep their text in place; a
        // reference to an omitted note stays literal, in both modes.
        assertEquals(List.of("Cited[1].", "Referenced note cites[2].", "Unreferenced note.", "Duplicate of a.",
                             "Reached only through a.", "Unreferenced, cites[^e].", "Reached only through d."),
                texts(partition(source, MarkdownOptions.RawHtml.SKIP).elements()));
        // The renderer moves rendered notes to the end; omitted ones keep their place and text.
        assertEquals(List.of("Cited1.", "Unreferenced note.", "Duplicate of a.", "Unreferenced, cites[^e].",
                             "Reached only through d.", "Referenced note cites2. ↩", "Reached only through a. ↩"),
                texts(partition(source, MarkdownOptions.RawHtml.EXTRACT_DOM).elements()));
    }

    @Test
    void aNoteDefinedInsideAnotherNoteStaysLiteralButKeepsItsText() throws Exception {
        // commonmark-java's renderer never renders a definition nested in another note, so its reference stays
        // literal in both modes and its blocks remain part of the outer note.
        final String source = "Ref[^o] and[^i].\n\n[^o]: Outer.\n\n    [^i]: Inner nested.\n";
        assertEquals(List.of("Ref[1] and[^i].", "Outer.", "Inner nested."),
                texts(partition(source, MarkdownOptions.RawHtml.SKIP).elements()));
        assertEquals(List.of("Ref1 and[^i].", "Outer.", "Inner nested. ↩"),
                texts(partition(source, MarkdownOptions.RawHtml.EXTRACT_DOM).elements()));
    }

    @Test
    void unreferencedNotesThatCiteEachOtherKeepTheirTextAndStayLinear() throws Exception {
        final String pair = "Body.\n\n[^a]: First [^b].\n\n[^b]: Second [^a].\n\n[^s]: Self [^s] ref.\n";
        for (final var mode : MarkdownOptions.RawHtml.values()) {
            final var elements = partition(pair, mode).elements();
            assertEquals(List.of("Body.", "First [^b].", "Second [^a].", "Self [^s] ref."), texts(elements),
                    mode.name());
            assertTrue(elements.stream().noneMatch(element -> element.metadata().containsKey("footnote_number")),
                    mode.name());
        }
        // Many such pairs: the analysis takes a bounded number of linear passes, not one per definition.
        final var many = new StringBuilder("Body.\n\n");
        for (int i = 0; i < 5_000; i++) {
            many.append("[^a").append(i).append("]: First [^b").append(i).append("].\n\n[^b").append(i)
                    .append("]: Second [^a").append(i).append("].\n\n");
        }
        final long started = System.nanoTime();
        assertEquals(10_001, partition(many.toString(), MarkdownOptions.RawHtml.EXTRACT_DOM).elements().size());
        assertEquals(10_001, partition(many.toString(), MarkdownOptions.RawHtml.SKIP).elements().size());
        assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(20),
                "10,000 unreferenced notes must not take quadratic time");
    }

    @Test
    void malformedFootnoteDefinitionsKeepTheirTextInEveryMode() throws Exception {
        final String source = "Body [^x].\n\n[^a b]: malformed\n\n[^]: empty\n";
        for (final var mode : MarkdownOptions.RawHtml.values()) {
            assertEquals(List.of("Body [^x].", "[^a b]: malformed", "[^]: empty"),
                    texts(partition(source, mode).elements()), mode.name());
        }
    }

    @Test
    void aCommentOnlyLeadingBlockIsOrdinaryMarkdown() throws Exception {
        final String source = "---\n# Intro\n---\nBody\n";
        for (final var mode : MarkdownOptions.RawHtml.values()) {
            final var result = partition(source, mode);
            assertEquals(List.of("Intro", "Body"), texts(result.elements()), mode.name());
            assertTrue(result.warnings().isEmpty(), mode.name());
        }
    }

    @Test
    void titleChunkingFollowsTheElementSequenceWhileParentsKeepTheOuterOutline() throws Exception {
        final String source = "# Main\n\nBefore[^n].\n\n[^n]: ## Note heading\n\n    Note content.\n\nAfter the note.\n";
        final var elements = partition(source, MarkdownOptions.RawHtml.PRESERVE_TEXT).elements();
        assertEquals(elements.getFirst().elementId(), elements.getLast().metadata().get("parent_id"),
                "text after the note stays under the outer heading");
        // byTitle follows the element sequence, as Unstructured's by_title does, and does not consult parent_id;
        // with the default small-section combining these short sections form one chunk in document order.
        final var chunks = Chunker.create().byTitle(elements, ChunkOptions.defaults());
        assertEquals(1, chunks.size());
        final String text = chunks.getFirst().text();
        assertTrue(text.indexOf("Note heading") < text.indexOf("After the note."), text);
    }
}
