package io.docweave.partition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared code-point offset composition for cell paragraphs, nested tables and notes. */
final class DocxTextAccumulator {
    /** Accumulated text buffer. */
    private final StringBuilder text = new StringBuilder();
    /** Accumulated hyperlink descriptors with shifted offsets. */
    private final List<Map<String, Object>> links = new ArrayList<>();
    /** Accumulated inline annotations keyed by metadata attribute name. */
    private final Map<String, List<Object>> annotations = new LinkedHashMap<>();
    /** Accumulated supplements with shifted anchor offsets. */
    private final List<DocxSupplement> supplements = new ArrayList<>();
    /** Accumulated nested-table entries with shifted offsets, or {@code null}. */
    private List<DocxNestedTables.Entry> nested;
    /** Total Unicode code points appended to {@link #text}. */
    private int points;

    /**
     * Appends rendered content when non-empty, inserting the separator only between non-empty text segments.
     *
     * @param value rendered content to append
     * @param separator separator inserted between non-empty text segments
     */
    void append(final DocxParagraphText.Rendered value, final String separator) {
        append(value, separator, false);
    }

    /**
     * Unconditionally appends the separator and rendered content, preserving empty cells and rows in table grids.
     *
     * @param value rendered content to append
     * @param separator separator to prepend before the content
     */
    void appendExact(final DocxParagraphText.Rendered value, final String separator) {
        append(value, separator, true);
    }

    /**
     * Appends rendered content and shifts all link, annotation, supplement, and nested-table offsets.
     *
     * @param value rendered content to append
     * @param separator separator string
     * @param exact {@code true} to always append the separator even when text is empty
     */
    private void append(final DocxParagraphText.Rendered value, final String separator, final boolean exact) {
        if (!exact && value.text().isEmpty() && value.supplements().isEmpty() && value.annotations().isEmpty()
                && value.nested().isEmpty()) {
            return;
        }
        // Insert the separator before the new segment when in exact grid mode or when joining two non-empty segments.
        if (exact || !text.isEmpty() && !value.text().isEmpty()) {
            text.append(separator);
            points += separator.codePointCount(0, separator.length());
        }
        // Shift all relative code-point offsets in nested tables, links, annotations, and supplements by the current
        // text length.
        final int offset = points;
        if (!value.nested().isEmpty()) {
            if (nested == null) {
                nested = new ArrayList<>();
            }
            for (final var entry : value.nested()) {
                nested.add(entry.shifted(offset));
            }
        }
        for (final var link : value.links()) {
            links.add(shift(link, offset));
        }
        value.annotations().forEach((key, item) -> {
            if (item instanceof final List<?> list) {
                for (final var entry : list) { annotations.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry instanceof final Map<?, ?> map ? shift(map, offset) : entry);
                }
            }
        });
        for (final var supplement : value.supplements()) {
            supplements.add(supplement.shifted(offset));
        }
        text.append(value.text());
        points += value.text().codePointCount(0, value.text().length());
    }

    /**
     * Updates only this table's containing-cell owner after horizontal/vertical append-to-origin.
     *
     * @param row 0-based owner row index
     * @param column 0-based owner column index
     */
    void nestedOwner(final int row, final int column) {
        if (nested != null) {
            nested = new ArrayList<>(nested.stream().map(entry -> entry.owned(row, column)).toList());
        }
    }

    /**
     * Returns the total number of Unicode code points accumulated so far.
     *
     * @return accumulated code-point count
     */
    int size() {
        return points;
    }

    /**
     * Builds an immutable rendered snapshot of all accumulated text, links, annotations, supplements, and nested
     * tables.
     *
     * @return rendered snapshot
     */
    DocxParagraphText.Rendered rendered() {
        final var metadata = new LinkedHashMap<String, Object>();
        annotations.forEach((key, value) -> metadata.put(key, List.copyOf(value)));
        if (metadata.containsKey("docx_field_instructions")) {
            metadata.put("docx_field_policy", "cached_results");
        }
        return new DocxParagraphText.Rendered(text.toString(), List.copyOf(links), Map.copyOf(metadata),
                List.copyOf(supplements), nested == null ? List.of() : List.copyOf(nested));
    }

    /**
     * Shifts {@code start_index} and {@code end_index} entries in a link or annotation map by the given code-point
     * offset.
     *
     * @param value source link or annotation map
     * @param offset code-point offset delta
     * @return shifted immutable map
     */
    private static Map<String, Object> shift(final Map<?, ?> value, final int offset) {
        final var copy = new LinkedHashMap<String, Object>();
        value.forEach((key, item) -> copy.put((String) key, item));
        for (final String key : List.of("start_index", "end_index")) {
            if (copy.get(key) instanceof final Integer point) {
                copy.put(key, point + offset);
            }
        }
        return Map.copyOf(copy);
    }
}
