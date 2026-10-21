package dev.docweave.chunking;

import dev.docweave.core.Element;
import dev.docweave.json.ElementJson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Encodes source provenance once per pre-chunk, avoiding repeated oversized singleton payloads. */
final class ChunkProvenance {
    /** Strict greater-than threshold matching the reviewed upstream split-source guard, in code points. */
    private static final long MAX_REPEATED_SOURCE_CHARACTERS = 1_000_000;

    /** Prevents utility construction. */
    private ChunkProvenance() {
    }

    /**
     * Adds provenance only after actual fragment multiplicity is known and before emitting chunks.
     * The raw source remains caller-owned; source text, fragment geometry and continuation metadata are unchanged.
     * Nested original provenance is excluded from encoding as before. Omission markers are recomputed per group.
     * @param metadata mutable consolidated destination, never a caller's source map
     * @param sources immutable pre-chunk sources
     * @param enabled whether original embedding is requested
     * @param split whether this group actually produces more than one chunk
     * @param json shared stateless bounded codec
     * @throws IllegalArgumentException if retained provenance exceeds the existing codec limits
     */
    static void attach(final Map<String, Object> metadata, final List<Element> sources, final boolean enabled,
            final boolean split, final ElementJson json) {
        ChunkCancellation.check();
        if (!enabled) {
            return;
        }
        if (split && sources.size() == 1) {
            final var source = sources.getFirst();
            final long size = characters(source.text()) + characters(source.metadata().get("text_as_html"))
                              + characters(source.metadata().get("image_base64"));
            if (size > MAX_REPEATED_SOURCE_CHARACTERS) {
                metadata.put("orig_elements_omitted", "oversized_split_source");
                metadata.put("orig_elements_source_characters", size);
                return;
            }
        }
        final var originals =
                sources.stream()
                        .map(element -> {
                            ChunkCancellation.check();
                            final var clean = new LinkedHashMap<>(element.metadata());
                            clean.remove("orig_elements");
                            return new Element(element.elementId(), element.type(), element.text(), clean);
                        })
                        .toList();
        metadata.put("orig_elements", json.encodeOriginals(originals));
    }

    /**
     * Counts only source string fields under the explicit repeated-payload policy.
     * @param value text or optional metadata string
     * @return Unicode code-point count; non-string metadata contributes zero
     */
    private static long characters(final Object value) {
        ChunkCancellation.check();
        return value instanceof final String text ? text.codePointCount(0, text.length()) : 0;
    }
}
