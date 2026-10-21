package dev.docweave.chunking;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Actual chunk production controls for repeated-source size, Unicode boundaries and explicit omissions. */
class ChunkProvenanceTest {
    /** Creates the owned control suite. */
    ChunkProvenanceTest() {
    }

    /** Large split text retains every character while omitting repeated full-source provenance. */
    @Test
    void largeNarrativeIsNotRepeatedInEachChunk() {
        final var source = element(ElementType.NarrativeText, "x".repeat(1_000_001), Map.of());
        for (final var strategy : Chunker.Strategy.values()) {
            final var chunks = Chunker.create().chunk(List.of(source), strategy, options(500_000, true));
            assertEquals(source.text(), String.join("", chunks.stream().map(Element::text).toList()));
            assertEquals(3, chunks.size());
            for (final var chunk : chunks) {
                omitted(chunk, 1_000_001L);
            }
            assertEquals(true, chunks.get(1).metadata().get("is_continuation"));
        }
        assertTrue(source.metadata().isEmpty());
    }

    /** The exact threshold counts code points, with supplementary Unicode retained and no off-by-one omission. */
    @Test
    void thresholdIsStrictAndCountsUnicodeCodePoints() {
        final var source = element(ElementType.NarrativeText, "😀".repeat(500_000) + "a".repeat(500_000), Map.of());
        final var chunks = Chunker.create().basic(List.of(source), options(750_000, true));
        assertEquals(2, chunks.size());
        for (final var chunk : chunks) {
            assertFalse(chunk.metadata().containsKey("orig_elements_omitted"));
            assertEquals(List.of(source),
                    ElementJson.create().decodeOriginals((String) chunk.metadata().get("orig_elements")));
        }
        final var larger = element(ElementType.NarrativeText, source.text() + "a", Map.of());
        for (final var chunk : Chunker.create().basic(List.of(larger), options(750_000, true))) {
            omitted(chunk, 1_000_001L);
        }
    }

    /** A large metadata payload is retained when a code separator consumes the only apparent remainder. */
    @Test
    void actualSingleChunkRetainsOriginals() {
        final var source = element(ElementType.CodeSnippet, "x".repeat(1_000_000) + "\n", Map.of());
        final var options = ChunkOptions.builder().maxCharacters(1_000_000).build();
        final var chunks = Chunker.create().basic(List.of(source), options);
        assertEquals(1, chunks.size());
        assertEquals(List.of(source),
                ElementJson.create().decodeOriginals((String) chunks.getFirst().metadata().get("orig_elements")));
    }

    /** Structured table splits count raw HTML/image payloads before serialization and preserve fragment content. */
    @Test
    void structuralTablesOmitOversizedRepeatedPayloadAndPreserveOwnership() {
        final String html = "<table><tr><td>alpha</td><td>beta</td></tr><tr><td>gamma</td><td>delta</td></tr></table>";
        final String payload = "A".repeat(1_000_001);
        final var source = element(
                ElementType.Table, "alpha beta gamma delta", Map.of("text_as_html", html, "image_base64", payload));
        final var chunks = Chunker.create().basic(List.of(source), options(60, true));
        final var plain = Chunker.create().basic(List.of(source), options(60, false));
        assertTrue(chunks.size() > 1);
        assertEquals(plain.stream().map(Element::text).toList(), chunks.stream().map(Element::text).toList());
        for (int i = 0; i < chunks.size(); i++) {
            final var chunk = chunks.get(i);
            omitted(chunk, 1_000_001L + html.codePointCount(0, html.length()) + source.text().length());
            for (final var key : List.of("text_as_html", "table_row_start", "table_row_end", "table_cell_fragments")) {
                assertEquals(plain.get(i).metadata().get(key), chunk.metadata().get(key));
            }
        }
        assertEquals(payload, source.metadata().get("image_base64"));
        final var unsplit = Chunker.create().basic(List.of(source), options(200, true));
        assertEquals(1, unsplit.size());
        assertTrue(unsplit.getFirst().metadata().containsKey("orig_elements"));
    }

    /** Multiple smaller originals remain available even when their combined payload exceeds the singleton limit. */
    @Test
    void groupedSourcesRetainTheirExistingProvenance() {
        final var first =
                element(ElementType.NarrativeText, "a".repeat(150), Map.of("image_base64", "A".repeat(600_000)));
        final var second =
                element(ElementType.NarrativeText, "b".repeat(150), Map.of("image_base64", "B".repeat(600_000)));
        // Direct attachment isolates the multi-source contract independently of normal staging's size boundaries.
        final var metadata = new LinkedHashMap<String, Object>();
        ChunkProvenance.attach(metadata, List.of(first, second), true, true, ElementJson.create());
        assertEquals(
                List.of(first, second), ElementJson.create().decodeOriginals((String) metadata.get("orig_elements")));
        assertFalse(metadata.containsKey("orig_elements_omitted"));
    }

    /** Text-only fallback obeys the guard; pass-through and disabled provenance keep their explicit contracts. */
    @Test
    void fallbackPassThroughAndDisabledEmbeddingRemainDistinct() {
        final var source = element(ElementType.Table, "word ".repeat(30),
                Map.of("text_as_html",
                        "<table><tr><td>"
                                + "word ".repeat(30) + "</td></tr></table>",
                        "image_base64", "A".repeat(1_000_001)));
        final var chunks = Chunker.create().basic(List.of(source), options(20, true));
        assertTrue(chunks.size() > 1);
        for (final var chunk : chunks) {
            assertEquals("oversized_split_source", chunk.metadata().get("orig_elements_omitted"));
        }
        final var bypass = ChunkOptions.builder().maxCharacters(20).skipTableChunking(true).build();
        assertSame(source, Chunker.create().basic(List.of(source), bypass).getFirst());
        final var marked = element(ElementType.NarrativeText, "one two three",
                Map.of("orig_elements_omitted", "old", "orig_elements_source_characters", 1_000_001));
        for (final var chunk : Chunker.create().basic(List.of(marked), options(5, false))) {
            assertFalse(chunk.metadata().containsKey("orig_elements"));
            assertFalse(chunk.metadata().containsKey("orig_elements_omitted"));
            assertFalse(chunk.metadata().containsKey("orig_elements_source_characters"));
        }
    }

    /**
     * Checks the externally visible omission reason and unchanged size accounting.
     * @param chunk actual emitted element
     * @param size full raw source code-point count
     */
    private static void omitted(final Element chunk, final long size) {
        assertFalse(chunk.metadata().containsKey("orig_elements"));
        assertEquals("oversized_split_source", chunk.metadata().get("orig_elements_omitted"));
        assertEquals(size, chunk.metadata().get("orig_elements_source_characters"));
    }

    /**
     * Creates explicit separator-free owned budgets.
     * @param max hard code-point limit
     * @param originals whether embedding is requested
     * @return immutable chunk policy
     */
    private static ChunkOptions options(final int max, final boolean originals) {
        return ChunkOptions.builder()
                .maxCharacters(max)
                .includeOriginalElements(originals)
                .textSplittingSeparators(List.of())
                .build();
    }

    /**
     * Builds an immutable owned source.
     * @param type element category
     * @param text raw source Unicode
     * @param metadata raw source properties
     * @return source element
     */
    private static Element element(final ElementType type, final String text, final Map<String, Object> metadata) {
        return new Element("source", type, text, metadata);
    }
}
