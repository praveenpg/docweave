package io.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Root-local validated immutable descriptors, charged against one document-wide budget. */
final class NestedTableDescriptors {
    /** Shared document-wide nested-table preservation budget. */
    private final NestedTableBudget budget;
    /** Total Unicode code-point count of the root table's plain text. */
    private final int rootPoints;
    /** Ordered list of validated immutable nested-table descriptor maps. */
    private final List<Map<String, Object>> descriptors = new ArrayList<>();
    /** Set of already-registered cell coordinate paths used to reject duplicates. */
    private final Set<List<Map<String, Object>>> paths = new HashSet<>();

    /**
     * Creates a descriptor accumulator for a single root table.
     *
     * @param budget document-wide nested-table preservation budget
     * @param rootPoints Unicode code-point length of the root table's plain text
     */
    NestedTableDescriptors(final NestedTableBudget budget, final int rootPoints) {
        this.budget = Objects.requireNonNull(budget, "budget");
        if (rootPoints < 0) {
            throw new IllegalArgumentException("Negative root text length");
        }
        this.rootPoints = rootPoints;
    }

    /**
     * Validates before reserving or copying; every offset has an explicit root or child coordinate space.
     *
     * @param parent 0-based index of the immediate parent nested table, or {@code null} for direct children of the root
     * @param path ordered list of owning cell coordinate steps from the root table
     * @param start inclusive start code-point offset in the root table text
     * @param end exclusive end code-point offset in the root table text
     * @param text child-local flattened plain text
     * @param html child-local compact HTML table markup
     * @param links child-local hyperlink descriptors
     * @param captions child-local table caption strings
     * @throws IOException if nested-table budgets are exceeded or the thread is interrupted
     */
    void add(final Integer parent, final List<Map<String, Object>> path, final int start, final int end,
            final String text, final String html, final List<Map<String, Object>> links, final List<String> captions)
            throws IOException {
        add(parent, path, start, end, text, html, links, captions, Map.of());
    }

    /**
     * DOCX semantic snapshots are whitelisted and remain in child-local coordinates.
     *
     * @param parent 0-based index of the immediate parent nested table, or {@code null} for direct children of the root
     * @param path ordered list of owning cell coordinate steps from the root table
     * @param start inclusive start code-point offset in the root table text
     * @param end exclusive end code-point offset in the root table text
     * @param text child-local flattened plain text
     * @param html child-local compact HTML table markup
     * @param links child-local hyperlink descriptors
     * @param captions child-local table caption strings
     * @param source optional format-specific semantic metadata map
     * @throws IOException if nested-table budgets are exceeded or the thread is interrupted
     */
    void add(final Integer parent, final List<Map<String, Object>> path, final int start, final int end,
            final String text, final String html, final List<Map<String, Object>> links, final List<String> captions,
            final Map<String, Object> source) throws IOException {
        ElementCollector.checkInterrupted();
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(html, "html");
        for (final String caption : captions) {
            Objects.requireNonNull(caption, "caption");
        }
        if (path.isEmpty() || start < 0 || end < start || end > rootPoints || paths.contains(path)) {
            throw new IllegalArgumentException("Invalid nested-table path or root interval");
        }
        for (final var step : path) {
            final var keys = step.keySet();
            if (!keys.equals(Set.of("row", "column", "table_index"))
                    && !keys.equals(Set.of("row", "column", "table_index", "owner_row", "owner_column"))) {
                throw new IllegalArgumentException("Invalid nested-table path step");
            }
            for (final var value : step.values()) {
                if (!(value instanceof final Integer number) || number < 0) {
                    throw new IllegalArgumentException("Invalid nested-table cell coordinate");
                }
            }
        }
        if (parent == null) {
            if (path.size() != 1) {
                throw new IllegalArgumentException("Missing nested-table parent");
            }
        } else {
            if (parent < 0 || parent >= descriptors.size()) {
                throw new IllegalArgumentException("Invalid nested-table parent");
            }
            final var owner = descriptors.get(parent);
            if (!path.subList(0, path.size() - 1).equals(owner.get("path"))
                    || start < (Integer) owner.get("start_index") || end > (Integer) owner.get("end_index")) {
                throw new IllegalArgumentException("Nested-table child is outside its parent");
            }
        }
        final int points = text.codePointCount(0, text.length());
        for (final var link : links) {
            if (!link.keySet().equals(Set.of("start_index", "text", "url"))) {
                throw new IllegalArgumentException("Invalid child-local nested-table link fields");
            }
            if (!(link.get("start_index") instanceof final Integer offset) || offset < 0 || offset > points
                    || !(link.get("text") instanceof final String label) || !(link.get("url") instanceof String)
                    || !text.startsWith(label, text.offsetByCodePoints(0, offset))) {
                throw new IllegalArgumentException("Invalid child-local nested-table link");
            }
        }
        validateSource(source, points);
        final var value = new LinkedHashMap<String, Object>();
        value.put("index", descriptors.size());
        if (parent != null) {
            value.put("parent_index", parent);
        }
        value.put("path", path);
        value.put("start_index", start);
        value.put("end_index", end);
        value.put("text", text);
        value.put("text_as_html", html);
        value.put("links", links);
        value.put("captions", captions);
        if (!source.isEmpty()) {
            value.put("source_metadata", source);
        }
        budget.checkCount(1, path.size());
        final long payload = measure(value, 0, budget.remainingCodePoints());
        budget.reserve(path.size(), payload);
        final var frozenPath = path.stream().map(Map::copyOf).toList();
        value.put("path", frozenPath);
        value.put("links", links.stream().map(Map::copyOf).toList());
        value.put("captions", List.copyOf(captions));
        if (!source.isEmpty()) {
            value.put("source_metadata", freeze(source));
        }
        descriptors.add(Map.copyOf(value));
        paths.add(frozenPath);
    }

    /**
     * Validates that optional DOCX semantic source metadata entries conform to allowed keys and child-local bounds.
     *
     * @param source candidate source metadata map
     * @param points total Unicode code points in the child table's plain text
     * @throws IOException if the thread is interrupted
     */
    private void validateSource(final Map<String, Object> source, final int points) throws IOException {
        if (source.isEmpty()) {
            return;
        }
        final var ranges = Set.of("docx_list_items", "docx_table_style_paragraphs", "docx_references", "docx_revisions",
                "docx_supplement_anchors");
        final var strings =
                Set.of("docx_field_policy", "docx_merge_content_policy", "docx_table_style", "docx_table_style_source");
        final var flags = Set.of("docx_table_style_unresolved", "docx_table_style_conditions_omitted");
        for (final var entry : source.entrySet()) {
            ElementCollector.checkInterrupted();
            final String key = entry.getKey();
            final Object value = entry.getValue();
            if (ranges.contains(key)) {
                if (!(value instanceof final List<?> list)) {
                    throw new IllegalArgumentException("Invalid DOCX semantic ranges");
                }
                for (final var item : list) {
                    ElementCollector.checkInterrupted();
                    if (!(item instanceof final Map<?, ?> range) || !(range.get("start_index") instanceof final Integer start)
                            || start < 0 || start > points || range.containsKey("end_index")
                            && (!(range.get("end_index") instanceof final Integer end) || end < start || end > points)) {
                        throw new IllegalArgumentException("Invalid child-local DOCX semantic range");
                    }
                }
            } else if (key.equals("docx_field_instructions")) {
                if (!(value instanceof final List<?> list)
                        || list.stream().anyMatch(item -> !(item instanceof String))) {
                    throw new IllegalArgumentException("Invalid cached DOCX field instructions");
                }
            } else if (strings.contains(key) && value instanceof String
                       || flags.contains(key) && value instanceof Boolean) {
                // Validated scalar semantic property.
            } else {
                throw new IllegalArgumentException("Unsupported DOCX semantic metadata");
            }
        }
    }

    /**
     * Called only after measuring/validating supported immutable scalar types and bounded container depth.
     *
     * @param value validated metadata value to copy immutably
     * @return deeply immutable copy of {@code value}
     */
    private Object freeze(final Object value) {
        if (value instanceof final Map<?, ?> map) {
            final var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put((String) key, freeze(item)));
            return Map.copyOf(result);
        }
        if (value instanceof final List<?> list) {
            return list.stream().map(this::freeze).toList();
        }
        return value;
    }

    /**
     * Returns the finalized root-table metadata entries for all captured nested tables.
     *
     * @return immutable map with {@code nested_tables_version} and {@code nested_tables}, or an empty map
     */
    Map<String, Object> metadata() {
        return descriptors.isEmpty() ? Map.of()
                                     : Map.of("nested_tables_version", 1, "nested_tables", List.copyOf(descriptors));
    }

    /**
     * Counts stored strings/keys by code point, plus one unit per scalar/container, before immutable copying.
     *
     * @param value candidate metadata value to measure
     * @param depth current container nesting depth
     * @param remaining remaining allowed metadata code-point budget
     * @return measured code-point cost of {@code value}
     * @throws IOException if the metadata budget or nesting depth is exceeded or the thread is interrupted
     */
    static long measure(final Object value, final int depth, final long remaining) throws IOException {
        ElementCollector.checkInterrupted();
        if (remaining < 0 || depth > 100) {
            throw new PartitionException(PartitionException.Code.TABLE_LIMIT, "Nested-table metadata budget exceeded");
        }
        long result = 1;
        if (value instanceof final String string) {
            result = 0;
            for (int offset = 0; offset < string.length();) {
                if ((offset & 1023) == 0) {
                    ElementCollector.checkInterrupted();
                }
                offset += Character.charCount(string.codePointAt(offset));
                if (++result > remaining) {
                    throw new PartitionException(
                            PartitionException.Code.TABLE_LIMIT, "Nested-table metadata budget exceeded");
                }
            }
        } else if (value instanceof final Map<?, ?> map) {
            for (final var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String) || entry.getKey().equals("nested_tables")
                        || entry.getKey().equals("nested_tables_version")) {
                    throw new IllegalArgumentException("Invalid nested-table metadata key");
                }
                result += measure(entry.getKey(), depth + 1, remaining - result);
                result += measure(entry.getValue(), depth + 1, remaining - result);
            }
        } else if (value instanceof final List<?> list) {
            for (final var item : list) {
                result += measure(item, depth + 1, remaining - result);
            }
        }
        if (!(value instanceof String || value instanceof Map<?, ?> || value instanceof List<?>
                || value instanceof Integer || value instanceof Long || value instanceof Boolean)) {
            throw new IllegalArgumentException("Unsupported nested-table metadata value");
        }
        if (result > remaining) {
            throw new PartitionException(PartitionException.Code.TABLE_LIMIT, "Nested-table metadata budget exceeded");
        }
        return result;
    }
}
