package io.docweave.chunking;

import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChunkerTest {
    private final Chunker chunker = Chunker.create();

    private static Element text(final String value) {
        return element(ElementType.NarrativeText, value, Map.of());
    }

    private static Element title(final String value) {
        return element(ElementType.Title, value, Map.of());
    }

    private static Element element(final ElementType type, final String value, final Map<String, Object> metadata) {
        return new Element("source-" + value, type, value, metadata);
    }

    private static List<String> texts(final List<Element> chunks) {
        return chunks.stream().map(Element::text).toList();
    }

    @Test
    void fillsWholeElementsWithBlankLineSeparators() {
        final var options = ChunkOptions.builder().maxCharacters(10).includeOriginalElements(false).build();
        assertEquals(List.of("abc\n\ndef", "ghij"),
                texts(chunker.basic(List.of(text("abc"), text("def"), text("ghij")), options)));
    }

    @Test
    void softLimitUsesStrictGreaterThanAndNeverSplitsOrdinaryElements() {
        final var options = ChunkOptions.builder().maxCharacters(20).newAfterCharacters(3).build();
        assertEquals(List.of("abc\n\ndef", "g"),
                texts(chunker.basic(List.of(text("abc"), text("def"), text("g")), options)));
    }

    @Test
    void splitsSupplementaryCharactersWithoutBreakingSurrogates() {
        final var options = ChunkOptions.builder().maxCharacters(3).overlap(1).build();
        final var chunks = chunker.basic(List.of(text("🧵𐐀abcd")), options);
        assertEquals(List.of("🧵𐐀a", "abc", "cd"), texts(chunks));
        assertFalse(chunks.getFirst().metadata().containsKey("is_continuation"));
        assertEquals(true, chunks.get(1).metadata().get("is_continuation"));
    }

    @Test
    void wordBoundaryOverlapIncludesSeparatorBudget() {
        final var options = ChunkOptions.builder().maxCharacters(8).overlap(3).build();
        assertEquals(List.of("one two", "wo three", "ee four"),
                texts(chunker.basic(List.of(text("one two three four")), options)));
    }

    @Test
    void recordsSplitterOverlapInCodePoints() {
        final var words = chunker.basic(
                List.of(text("one two three four")), ChunkOptions.builder().maxCharacters(8).overlap(3).build());
        assertEquals(List.of("one two", "wo three", "ee four"), texts(words));
        assertFalse(words.getFirst().metadata().containsKey(Chunker.OVERLAP_PREFIX_CHARACTERS));
        // "wo" and "ee" are repeated with the joining space.
        assertEquals(3, words.get(1).metadata().get(Chunker.OVERLAP_PREFIX_CHARACTERS));
        assertEquals(3, words.get(2).metadata().get(Chunker.OVERLAP_PREFIX_CHARACTERS));
        final var hardCut =
                chunker.basic(List.of(text("🧵𐐀abcd")), ChunkOptions.builder().maxCharacters(3).overlap(1).build());
        assertEquals(List.of("🧵𐐀a", "abc", "cd"), texts(hardCut));
        assertEquals(1, hardCut.get(1).metadata().get(Chunker.OVERLAP_PREFIX_CHARACTERS));
        final var supplementary =
                chunker.basic(List.of(text("𐐀𐐀𐐀𐐀𐐀𐐀")), ChunkOptions.builder().maxCharacters(4).overlap(2).build());
        assertEquals(List.of("𐐀𐐀𐐀𐐀", "𐐀𐐀𐐀𐐀"), texts(supplementary));
        assertEquals(2, supplementary.get(1).metadata().get(Chunker.OVERLAP_PREFIX_CHARACTERS));
    }

    @Test
    void recordsOverlapAllPrefixWithItsSeparator() {
        final var options = ChunkOptions.builder().maxCharacters(10).overlap(2).overlapAll(true).build();
        final var chunks = chunker.basic(List.of(text("abcdef"), text("ghijkl")), options);
        assertEquals(List.of("abcdef", "ef\n\nghijkl"), texts(chunks));
        assertEquals(4, chunks.get(1).metadata().get(Chunker.OVERLAP_PREFIX_CHARACTERS));
    }

    @Test
    void omitsOverlapAllPrefixThatSplittingRejoinedDifferently() {
        final var options = ChunkOptions.builder()
                                    .maxCharacters(17)
                                    .overlap(14)
                                    .overlapAll(true)
                                    .combineTextUnderCharacters(0)
                                    .build();
        final var chunks = chunker.basic(List.of(text("a a a dcd d dd"), text("c c c"), text("d b")), options);
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).metadata().get(Chunker.OVERLAP_PREFIX_CHARACTERS) instanceof final Integer prefix) {
                final String repeated =
                        chunks.get(i).text().substring(0, chunks.get(i).text().offsetByCodePoints(0, prefix)).strip();
                assertTrue(chunks.get(i - 1).text().endsWith(repeated), chunks.get(i - 1).text() + " / " + repeated);
            }
        }
        final int rejoined = texts(chunks).indexOf("cd d dd\n\nc c c");
        assertTrue(rejoined > 0, texts(chunks).toString());
        assertEquals("dcd d dd c c c", chunks.get(rejoined - 1).text());
        assertFalse(chunks.get(rejoined).metadata().containsKey(Chunker.OVERLAP_PREFIX_CHARACTERS));
    }

    @Test
    void everyRecordedOverlapPrefixEndsThePrecedingChunk() {
        final var random = new java.util.Random(181);
        final String[] words = {"a", "b", "cd", "dd", "c", "e😀", "ff", "g\u00a0h", "ijk"};
        int recorded = 0;
        for (int round = 0; round < 2_000; round++) {
            final var inputs = new java.util.ArrayList<Element>();
            for (int element = 0, count = 1 + random.nextInt(4); element < count; element++) {
                final var text = new StringBuilder();
                for (int word = 0, n = 1 + random.nextInt(6); word < n; word++) {
                    text.append(word == 0 ? "" : " ").append(words[random.nextInt(words.length)]);
                }
                inputs.add(random.nextInt(5) == 0 ? title(text.toString()) : text(text.toString()));
            }
            final int max = 3 + random.nextInt(18);
            final var options = ChunkOptions.builder()
                                        .maxCharacters(max)
                                        .overlap(random.nextInt(max))
                                        .overlapAll(random.nextBoolean())
                                        .combineTextUnderCharacters(random.nextBoolean() ? 0 : max)
                                        .build();
            final var chunks = random.nextBoolean() ? chunker.basic(inputs, options) : chunker.byTitle(inputs, options);
            for (int i = 0; i < chunks.size(); i++) {
                if (chunks.get(i).metadata().get(Chunker.OVERLAP_PREFIX_CHARACTERS) instanceof final Integer prefix) {
                    recorded++;
                    final String text = chunks.get(i).text();
                    assertTrue(i > 0 && prefix > 0 && prefix <= text.codePointCount(0, text.length()));
                    final String repeated = text.substring(0, text.offsetByCodePoints(0, prefix)).stripTrailing();
                    assertFalse(repeated.isEmpty());
                    assertTrue(chunks.get(i - 1).text().endsWith(repeated),
                            "round " + round + ": '" + repeated + "' does not end '" + chunks.get(i - 1).text() + "'");
                }
            }
        }
        assertTrue(recorded > 100, "too few overlaps exercised: " + recorded);
    }

    @Test
    void omitsOverlapMetadataWithoutOverlapAndKeepsIntentionalRepetition() {
        final var rows = List.of(text("Total 100"), text("Total 100"), title("Notes"), text("Total 100"));
        final var chunks = chunker.basic(rows, ChunkOptions.builder().maxCharacters(12).build());
        assertEquals(List.of("Total 100", "Total 100", "Notes", "Total 100"), texts(chunks));
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.metadata().containsKey(Chunker.OVERLAP_PREFIX_CHARACTERS)));
        final var titled = chunker.byTitle(List.of(title("Revenue"), text("Up"), title("Revenue"), text("Up")),
                ChunkOptions.builder().maxCharacters(50).combineTextUnderCharacters(0).build());
        assertEquals(List.of("Revenue\n\nUp", "Revenue\n\nUp"), texts(titled));
        assertTrue(titled.stream().noneMatch(chunk -> chunk.metadata().containsKey(Chunker.OVERLAP_PREFIX_CHARACTERS)));
    }

    @Test
    void rechunkingDropsInheritedOverlapMetadata() {
        final var first = chunker.basic(
                List.of(text("one two three four")), ChunkOptions.builder().maxCharacters(8).overlap(3).build());
        final var again = chunker.basic(first, ChunkOptions.builder().maxCharacters(100).build());
        assertEquals(1, again.size());
        assertFalse(again.getFirst().metadata().containsKey(Chunker.OVERLAP_PREFIX_CHARACTERS));
    }

    @Test
    void preservesCodeFormattingButNormalizesNarrativeWhitespace() {
        final var options = ChunkOptions.defaults();
        assertEquals(List.of("a b 🧵"), texts(chunker.basic(List.of(text(" a\n\tb\u00a0🧵 ")), options)));
        final var code = element(ElementType.CodeSnippet, "  a\n    b", Map.of());
        assertEquals(List.of("  a\n    b"), texts(chunker.basic(List.of(code), options)));
    }

    @Test
    void titleBoundariesCanBePreservedOrSmallSectionsCombined() {
        final var inputs = List.of(title("A"), text("body"), title("B"), text("more"));
        final var strict = ChunkOptions.builder().combineTextUnderCharacters(0).build();
        assertEquals(List.of("A\n\nbody", "B\n\nmore"), texts(chunker.byTitle(inputs, strict)));
        assertEquals(List.of("A\n\nbody\n\nB\n\nmore"), texts(chunker.byTitle(inputs, ChunkOptions.defaults())));
    }

    @Test
    void pageTrackingUpdatesEvenWhenTitleBoundaryCoincides() {
        final var inputs = List.of(element(ElementType.Title, "A", Map.of("page_number", 8)),
                element(ElementType.Title, "B", Map.of("page_number", 9)), text("body"),
                element(ElementType.NarrativeText, "previous", Map.of("page_number", 8)));
        final var options = ChunkOptions.builder().multipageSections(false).combineTextUnderCharacters(0).build();
        assertEquals(List.of("A", "B\n\nbody", "previous"), texts(chunker.byTitle(inputs, options)));
    }

    @Test
    void overlapAllDoesNotCrossIsolatedTables() {
        final var table = element(ElementType.Table, "table", Map.of());
        final var options = ChunkOptions.builder().maxCharacters(10).overlap(2).overlapAll(true).build();
        final var chunks = chunker.basic(List.of(text("abcdef"), text("ghijkl"), table, text("mnopqr")), options);
        assertEquals(List.of("abcdef", "ef\n\nghijkl", "table", "mnopqr"), texts(chunks));
        assertEquals(ElementType.Table, chunks.get(2).type());
    }

    @Test
    void consolidatesMetadataAndPreservesOriginals() {
        final var inputs = List.of(element(ElementType.NarrativeText, "A",
                                           Map.of("page_number", 1, "coordinates", Map.of("system", "PointSpace"),
                                                   "languages", List.of("eng"), "parent_id", "heading")),
                element(ElementType.NarrativeText, "B", Map.of("page_number", 2, "languages", List.of("eng", "deu"))));
        final var chunk = chunker.basic(inputs, ChunkOptions.defaults()).getFirst();
        assertEquals(1, chunk.metadata().get("page_number"));
        assertEquals(List.of("eng", "deu"), chunk.metadata().get("languages"));
        assertFalse(chunk.metadata().containsKey("coordinates"));
        assertFalse(chunk.metadata().containsKey("parent_id"));
        assertEquals(inputs, ElementJson.create().decodeOriginals((String) chunk.metadata().get("orig_elements")));
    }

    @Test
    void originalProvenanceDoesNotNestWhenRechunking() {
        final var prior = chunker.basic(List.of(text("body")), ChunkOptions.defaults());
        final var next = chunker.basic(prior, ChunkOptions.defaults());
        final var originals =
                ElementJson.create().decodeOriginals((String) next.getFirst().metadata().get("orig_elements"));
        assertFalse(originals.getFirst().metadata().containsKey("orig_elements"));
        assertTrue(prior.getFirst().metadata().containsKey("orig_elements"));
    }

    @Test
    void textOnlyTableChunksCarryReconstructionMetadata() {
        final var options = ChunkOptions.builder().maxCharacters(4).build();
        final var chunks =
                chunker.basic(List.of(element(ElementType.Table, "abcdefgh", Map.of("parent_id", "p"))), options);
        assertEquals(List.of("abcd", "efgh"), texts(chunks));
        assertEquals(ElementType.TableChunk, chunks.getFirst().type());
        assertEquals(chunks.getFirst().metadata().get("table_id"), chunks.getLast().metadata().get("table_id"));
        assertEquals(1, chunks.getLast().metadata().get("chunk_index"));
        assertFalse(chunks.getFirst().metadata().containsKey("parent_id"));
    }

    @Test
    void tinyHtmlWindowsUseTextFallbackWithProvenance() {
        final var table = element(
                ElementType.Table, "abcdefgh", Map.of("text_as_html", "<table><tr><td>abcdefgh</td></tr></table>"));
        final var chunks = chunker.basic(List.of(table), ChunkOptions.builder().maxCharacters(4).build());
        assertEquals(List.of("abcd", "efgh"), texts(chunks));
        assertTrue(chunks.stream().allMatch(
                e -> e.type() == ElementType.TableChunk && !e.metadata().containsKey("text_as_html")));
        assertEquals(List.of(table),
                ElementJson.create().decodeOriginals((String) chunks.getFirst().metadata().get("orig_elements")));
        assertEquals(ElementType.TableChunk,
                chunker.basic(List.of(table), ChunkOptions.builder().maxCharacters(10).build()).getFirst().type());
    }

    @Test
    void emptyInputAndPageBreakProduceNoTextChunks() {
        assertEquals(List.of(), chunker.basic(List.of(), ChunkOptions.defaults()));
        assertEquals(List.of(),
                chunker.basic(List.of(element(ElementType.PageBreak, "", Map.of())), ChunkOptions.defaults()));
    }

    @Test
    void validatesOptionsAndClampsSoftMaximum() {
        assertThrows(IllegalArgumentException.class, () -> ChunkOptions.builder().maxCharacters(0).build());
        assertThrows(IllegalArgumentException.class, () -> ChunkOptions.builder().overlap(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ChunkOptions.builder().maxCharacters(3).overlap(3).build());
        assertThrows(IllegalArgumentException.class, () -> ChunkOptions.builder().newAfterCharacters(-1).build());
        assertThrows(
                IllegalArgumentException.class, () -> ChunkOptions.builder().combineTextUnderCharacters(-1).build());
        assertEquals(3, ChunkOptions.builder().maxCharacters(3).newAfterCharacters(5).build().newAfterCharacters());
    }
}
