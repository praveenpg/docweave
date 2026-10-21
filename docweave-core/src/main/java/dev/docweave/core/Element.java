package dev.docweave.core;

import java.util.Map;
import java.util.Objects;

/**
 * An ordered semantic document unit such as a heading, paragraph, table or chunk.
 * Text may be empty for structural markers. IDs are supplied by the creator: partitioners produce
 * deterministic hashes and chunkers produce UUIDs. Parent relationships live in metadata.
 * Metadata is deeply copied into immutable maps/lists; top-level null values are omitted and
 * nested nulls are retained. This record is safe to share across threads after construction.
 *
 * @param elementId nonblank identity, serialized as {@code element_id}
 * @param type supported semantic category, serialized with its enum spelling
 * @param text content, including meaningful code whitespace
 * @param metadata JSON-compatible metadata and vendor extensions
 */
public record Element(String elementId, ElementType type, String text, Map<String, Object> metadata) {
    /**
     * Validates identity/content and takes a deep immutable metadata snapshot.
     * @param elementId nonblank element identity
     * @param type nonnull category
     * @param text nonnull text
     * @param metadata nonnull JSON-compatible metadata
     * @throws NullPointerException if any component is null
     * @throws IllegalArgumentException if the ID is blank or metadata contains unsupported values
     */
    public Element {
        Objects.requireNonNull(elementId, "elementId");
        if (elementId.isBlank()) {
            throw new IllegalArgumentException("elementId is blank");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        metadata = JsonValues.snapshot(Objects.requireNonNull(metadata, "metadata"));
    }
}
