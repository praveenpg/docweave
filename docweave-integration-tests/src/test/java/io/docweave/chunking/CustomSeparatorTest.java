package io.docweave.chunking;

import io.docweave.batch.BatchProcessor;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CustomSeparatorTest {
    private final Chunker chunker = Chunker.create();

    private Element text(final String value) {
        return new Element("source", ElementType.NarrativeText, value, Map.of());
    }

    private List<String> texts(final List<Element> elements) {
        return elements.stream().map(Element::text).toList();
    }

    private ChunkOptions options(final int max, final String... separators) {
        return ChunkOptions.builder().maxCharacters(max).textSplittingSeparators(List.of(separators)).build();
    }

    @Test
    void literalPunctuationIsRetainedAndPriorityWinsOverLaterBoundaries() {
        final var input = List.of(text("aa||bb::cc::dd"));
        assertEquals(List.of("aa||", "bb::cc::", "dd"), texts(chunker.basic(input, options(8, "||", "::"))));
        assertEquals(List.of("aa||bb::", "cc::dd"), texts(chunker.basic(input, options(8, "::", "||"))));
    }

    @Test
    void regexMetacharactersAreLiteralAndWholeElementsKeepTheirJoiningSeparator() {
        assertEquals(
                List.of("aa.*", "bb.*", "cc"), texts(chunker.basic(List.of(text("aa.*bb.*cc")), options(5, ".*"))));
        assertEquals(List.of("a||b\n\nc"), texts(chunker.basic(List.of(text("a||b"), text("c")), options(20, "||"))));
    }

    @Test
    void emptyListAndAbsentOrOversizedDelimitersFallBackToCodePoints() {
        final var input = List.of(text("🧵abcde"));
        for (final var options : List.of(options(3), options(3, "missing"), options(3, "🧵abc"))) {
            assertEquals(List.of("🧵ab", "cde"), texts(chunker.basic(input, options)));
        }
    }

    @Test
    void supplementaryDelimitersStayWholeAndMustFitTheHardBudget() {
        assertEquals(
                List.of("ab🧵", "cd🧵", "ef"), texts(chunker.basic(List.of(text("ab🧵cd🧵ef")), options(3, "🧵"))));
        assertEquals(List.of("ab🧵", "cd"), texts(chunker.basic(List.of(text("ab🧵cd")), options(3, "🧵c"))));
    }

    @Test
    void multiCharacterWhitespaceDelimitersWorkWithLiteralCodeFormatting() {
        final var code = new Element("code", ElementType.CodeSnippet, "aa\n\nbb\nccdd", Map.of());
        assertEquals(List.of("aa", "bb\nccd", "d"), texts(chunker.basic(List.of(code), options(6, "\n\n"))));
    }

    @Test
    void overlapRemainsBoundedAndContinuationAndProvenanceSurvive() {
        final var input = List.of(text("aa||bb||cc||dd"));
        final var options =
                ChunkOptions.builder().maxCharacters(6).overlap(2).textSplittingSeparators(List.of("||")).build();
        final var chunks = chunker.basic(input, options);
        assertEquals("aa||", chunks.getFirst().text());
        final var json = ElementJson.create();
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).text().codePointCount(0, chunks.get(i).text().length()) <= 6);
            assertEquals(input, json.decodeOriginals((String) chunks.get(i).metadata().get("orig_elements")));
            if (i > 0) {
                assertEquals(true, chunks.get(i).metadata().get("is_continuation"));
            }
        }
        assertEquals(chunks, json.read(json.write(chunks)));
    }

    @Test
    void titleAndTextOnlyTableSplittingHonorSeparators() {
        final var input = List.of(text("aa||bb||cc"));
        assertEquals(List.of("aa||", "bb||cc"), texts(chunker.byTitle(input, options(6, "||"))));
        final var table = new Element("table", ElementType.Table, "aa||bb||cc", Map.of());
        final var chunks = chunker.basic(List.of(table), options(6, "||"));
        assertEquals(List.of("aa||", "bb||cc"), texts(chunks));
        assertTrue(chunks.stream().allMatch(element -> element.type() == ElementType.TableChunk));
        assertEquals(chunks.getFirst().metadata().get("table_id"), chunks.getLast().metadata().get("table_id"));
    }

    @Test
    void structuralTablesAndPassThroughIgnoreNarrativeSeparators() {
        final var table = new Element("table", ElementType.Table, "aa||bb||cc".repeat(12),
                Map.of("text_as_html", "<table><tr><td>"
                                               + "aa||bb||cc".repeat(12) + "</td></tr></table>"));
        final var defaults = chunker.basic(
                List.of(table), ChunkOptions.builder().maxCharacters(70).includeOriginalElements(false).build());
        final var custom = chunker.basic(List.of(table), ChunkOptions.builder()
                                                                 .maxCharacters(70)
                                                                 .includeOriginalElements(false)
                                                                 .textSplittingSeparators(List.of("||"))
                                                                 .build());
        assertEquals(texts(defaults), texts(custom));
        assertEquals(defaults.stream().map(element -> element.metadata().get("text_as_html")).toList(),
                custom.stream().map(element -> element.metadata().get("text_as_html")).toList());
        assertSame(table, chunker.basic(List.of(table), ChunkOptions.builder()
                                                                .maxCharacters(3)
                                                                .skipTableChunking(true)
                                                                .textSplittingSeparators(List.of("||"))
                                                                .build())
                                  .getFirst());
    }

    @Test
    void configurationSnapshotsListsAndRetainsLegacyConstructor() {
        final var separators = new ArrayList<>(List.of("||"));
        final var builder = ChunkOptions.builder().textSplittingSeparators(separators);
        separators.clear();
        final var first = builder.build();
        builder.textSplittingSeparators(List.of("::"));
        assertEquals(List.of("||"), first.textSplittingSeparators());
        assertThrows(UnsupportedOperationException.class, () -> first.textSplittingSeparators().clear());
        final var d = ChunkOptions.defaults();
        assertEquals(d, new ChunkOptions(d.maxCharacters(), d.newAfterCharacters(), d.overlap(), d.overlapAll(),
                                d.multipageSections(), d.combineTextUnderCharacters(), d.includeOriginalElements(),
                                d.repeatTableHeaders(), d.skipTableChunking()));
        assertThrows(NullPointerException.class, () -> ChunkOptions.builder().textSplittingSeparators(null));
        assertThrows(NullPointerException.class,
                () -> ChunkOptions.builder().textSplittingSeparators(Arrays.asList(" ", null)));
        for (final String invalid : List.of("", "\ud83e", "\uddf5")) {
            assertThrows(IllegalArgumentException.class, () -> options(10, invalid));
        }
    }

    @Test
    void seededLiteralSplittingPreservesAllVisibleCharactersWithinBudget() {
        final var random = new Random(5617);
        final String[] alphabet = {"🧵", "a", "|", ":", "日", "."};
        for (int trial = 0; trial < 100; trial++) {
            final var source = new StringBuilder();
            for (int i = 0; i < 120; i++) {
                source.append(alphabet[random.nextInt(alphabet.length)]);
            }
            final int max = 1 + random.nextInt(20);
            final var chunks = chunker.basic(List.of(text(source.toString())), options(max, "||", "🧵:", "."));
            assertEquals(source.toString(), String.join("", texts(chunks)));
            for (final var chunk : chunks) {
                assertFalse(chunk.text().isEmpty());
                assertTrue(chunk.text().codePointCount(0, chunk.text().length()) <= max);
                assertFalse(Character.isLowSurrogate(chunk.text().charAt(0)));
                assertFalse(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1)));
            }
        }
    }

    @Test
    void sharedOptionsWorkAcrossConcurrentChunkingCalls() throws Exception {
        final var options = options(6, "||");
        final var results = BatchProcessor.create().mapOrdered(IntStream.range(0, 20).boxed().toList(), 4,
                index -> chunker.basic(List.of(text("aa||bb||cc")), options));
        for (final var result : results) {
            assertEquals(List.of("aa||", "bb||cc"), texts(result));
        }
    }

    @Test
    void nearMaximumOverlapAndRepeatedDelimitersStillMakeProgress() {
        final var options =
                ChunkOptions.builder().maxCharacters(5).overlap(4).textSplittingSeparators(List.of("||", "|")).build();
        final var chunks = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2), () -> chunker.basic(List.of(text("||||||||||||||||||||")), options));
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() <= 20);
        assertTrue(chunks.stream().allMatch(element -> element.text().length() <= 5));
    }
}
