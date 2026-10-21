package dev.docweave.chunking;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consolidates element-level metadata across grouped source elements according to field-specific merge rules.
 */
final class MetadataConsolidator {
    /** Metadata keys whose first non-null value in document order is retained. */
    private static final Set<String> FIRST =
            Set.of("attached_to_filename", "cc_recipient", "bcc_recipient", "data_source", "file_directory", "filename",
                    "filetype", "last_modified", "email_message_id", "page_name", "page_number", "sent_from", "sent_to",
                    "signature", "subject", "table_as_cells", "table_extraction_method", "url");
    /** List-valued metadata keys concatenated in document order across grouped elements. */
    private static final Set<String> CONCAT =
            Set.of("emphasized_text_contents", "emphasized_text_tags", "link_texts", "link_urls");
    /** Metadata keys dropped when consolidating or emitting chunked elements. */
    private static final Set<String> DROP = Set.of("category_depth", "coordinates", "detection_class_prob",
            "detection_origin", "header_footer_type", "image_url", "image_path", "image_base64", "image_mime_type",
            "is_continuation", "is_extracted", "link_start_indexes", "links", "max_characters", "orig_elements",
            "parent_id", "routing", "routing_score", "table_id", "chunk_index", "num_carried_over_header_rows",
            "docx_references", "docx_revisions", "docx_list_items", "docx_table_style_paragraphs",
            "docx_field_instructions", "docx_field_policy", "nested_tables", "nested_tables_version",
            "segment_start_seconds", "segment_end_seconds", "key_value_pairs", "pdf_table_continuation",
            "pdf_reading_index", "pdf_table_multiline_cells");

    /**
     * Filters single-table metadata by removing keys listed in {@link #DROP}.
     *
     * @param table single table or table-chunk element
     * @return mutable metadata map with dropped keys removed
     */
    static Map<String, Object> tableMetadata(final Element table) {
        final var result = new LinkedHashMap<>(table.metadata());
        result.keySet().removeAll(DROP);
        // Structural chunking normalizes cell whitespace. The source policy belongs in original-element provenance.
        if ("native_line_breaks_v1".equals(result.get("pdf_table_cell_text_policy"))) {
            result.remove("pdf_table_cell_text_policy");
        }
        return result;
    }

    /**
     * Consolidates metadata across multiple grouped elements into a single chunk metadata map.
     *
     * @param elements ordered source elements in the pre-chunk
     * @return consolidated mutable metadata map
     */
    static Map<String, Object> consolidate(final List<Element> elements) {
        final var result = new LinkedHashMap<String, Object>();
        // Merge element metadata using per-field policies: first-value retention, list concatenation, set
        // deduplication, or space-joined HTML.
        for (final var element : elements) {
            ChunkCancellation.check();
            element.metadata().forEach((key, value) -> {
                if (FIRST.contains(key)) {
                    result.putIfAbsent(key, value);
                } else if (CONCAT.contains(key)) {
                    final var combined = new ArrayList<Object>((List<?>) result.getOrDefault(key, List.of()));
                    combined.addAll(requireList(key, value));
                    result.put(key, combined);
                } else if (key.equals("languages")) {
                    final var unique = new LinkedHashSet<Object>((List<?>) result.getOrDefault(key, List.of()));
                    unique.addAll(requireList(key, value));
                    result.put(key, new ArrayList<>(unique));
                } else if (key.equals("text_as_html")) {
                    if (!(value instanceof final String html)) {
                        throw new IllegalArgumentException("text_as_html must be a string");
                    }
                    result.merge(key, UnicodeText.strip(html), (a, b) -> a + " " + b);
                } else if (key.equals("enrichment_origins")) {
                    mergeOrigins(result, value);
                }
                // Upstream drops unrecognized extension metadata from composite chunks.
            });
        }
        if (elements.size() > 1
                && elements.stream().anyMatch(
                        element -> element.type() == ElementType.Table || element.type() == ElementType.TableChunk)) {
            // Source table geometry cannot describe combined prose/tables. Keep it in provenance.
            result.keySet().removeAll(Set.of("text_as_html", "table_as_cells", "table_extraction_method"));
        }
        return result;
    }

    /**
     * Verifies that {@code value} is a {@link List} and returns it.
     *
     * @param key metadata field name for diagnostics
     * @param value candidate metadata value
     * @return {@code value} cast to {@link List}
     */
    private static List<?> requireList(final String key, final Object value) {
        if (value instanceof final List<?> list) {
            return list;
        }
        throw new IllegalArgumentException(key + " must be an array");
    }

    /**
     * Merges an {@code enrichment_origins} map into {@code result} while deduplicating per-origin record lists.
     *
     * @param result target consolidated metadata map
     * @param value candidate {@code enrichment_origins} map value
     */
    private static void mergeOrigins(final Map<String, Object> result, final Object value) {
        if (!(value instanceof final Map<?, ?> source)) {
            throw new IllegalArgumentException("enrichment_origins must be an object");
        }
        final var merged = new LinkedHashMap<String, Object>();
        if (result.get("enrichment_origins") instanceof final Map<?, ?> existing)
            {
            existing.forEach((k, v) -> merged.put((String) k, v));
        }
        source.forEach((key, records) -> {
            if (!(key instanceof final String name)) {
                throw new IllegalArgumentException("enrichment origin key must be a string");
            }
            final var unique = new LinkedHashSet<Object>((List<?>) merged.getOrDefault(name, List.of()));
            unique.addAll(requireList(name, records));
            merged.put(name, new ArrayList<>(unique));
        });
        result.put("enrichment_origins", merged);
    }
}
