package dev.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Document-confined preservation accounting and immutable sidecars; numbering and extraction are never replayed. */
final class DocxNestedTables {
    /** Metadata keys copied into a nested table's {@code source_metadata} sidecar. */
    private static final Set<String> SOURCE_KEYS =
            Set.of("docx_list_items", "docx_table_style_paragraphs", "docx_references", "docx_revisions",
                    "docx_field_instructions", "docx_field_policy", "docx_merge_content_policy", "docx_table_style",
                    "docx_table_style_source", "docx_table_style_unresolved", "docx_table_style_conditions_omitted");
    /** Document-wide nested-table preservation budget. */
    private final NestedTableBudget budget;
    /** Number of nested tables captured in the current root table. */
    private int pending;
    /** Preorder sequence generator for nested table entries. */
    private int sequence;
    /** Accumulated code-point payload in the current root table before descriptor finalization. */
    private long pendingPayload;

    /**
     * Root-local ranges and paths travel with content; source snapshots always retain child-local coordinates.
     *
     * @param id preorder entry identifier
     * @param parent parent entry identifier, or {@code -1} for direct children of the root table
     * @param path cell-step path from the root table to this nested table
     * @param start inclusive start code-point offset in the enclosing text
     * @param end exclusive end code-point offset in the enclosing text
     * @param snapshot child-local rendered table snapshot
     * @param captions extracted table caption strings
     */
    record Entry(int id, int parent, List<Map<String, Object>> path, int start, int end,
            DocxTableExtractor.Rendered snapshot, List<String> captions) {
        /**
         * Returns a copy of this entry with its code-point range shifted by the given offset.
         *
         * @param offset code-point offset delta
         * @return shifted nested-table entry
         */
        Entry shifted(final int offset) {
            return new Entry(id, parent, path, start + offset, end + offset, snapshot, captions);
        }

        /**
         * Returns a copy of this entry recording the merged owner cell coordinates when they differ from the source
         * cell.
         *
         * @param row 0-based owner row index
         * @param column 0-based owner column index
         * @return nested-table entry with updated root path step
         */
        Entry owned(final int row, final int column) {
            final var step = new LinkedHashMap<>(path.getFirst());
            if ((Integer) step.get("row") != row || (Integer) step.get("column") != column) {
                step.put("owner_row", row);
                step.put("owner_column", column);
            }
            final var result = new ArrayList<>(path);
            result.set(0, Map.copyOf(step));
            return new Entry(id, parent, List.copyOf(result), start, end, snapshot, captions);
        }
    }

    /**
     * Creates a nested-table preservation helper configured with the given options.
     *
     * @param options nested-table preservation options
     */
    DocxNestedTables(final NestedTableOptions options) {
        budget = new NestedTableBudget(options);
    }

    /**
     * Checks count/depth before descending into another child or allocating its preservation sidecar.
     *
     * @param depth 1-based nesting depth of the child table
     * @return preorder identifier assigned to the child table
     * @throws IOException if the thread is interrupted
     */
    int child(final int depth) throws IOException {
        if (pending == Integer.MAX_VALUE) {
            throw new PartitionException(PartitionException.Code.TABLE_LIMIT, "Nested-table count budget exceeded");
        }
        budget.checkCount(pending + 1, depth);
        pending++;
        return sequence++;
    }

    /**
     * Wraps a direct child and prefixes its already-extracted descendants with the source-cell step.
     *
     * @param id preorder identifier of the direct child table
     * @param parent preorder identifier of the parent table, or {@code -1}
     * @param row 0-based source cell row index
     * @param column 0-based source cell column index
     * @param ordinal 0-based table ordinal within the source cell
     * @param value rendered child table result
     * @param captions extracted table captions
     * @return immutable list of captured entries for the child and its descendants
     * @throws IOException if the thread is interrupted
     */
    List<Entry> capture(final int id, final int parent, final int row, final int column, final int ordinal,
            final DocxTableExtractor.Rendered value, final List<String> captions) throws IOException {
        final Map<String, Object> step = Map.of("row", row, "column", column, "table_index", ordinal);
        final var payload = Map.of("text", value.text(), "text_as_html", value.metadata().get("text_as_html"), "links",
                value.content().links(), "captions", captions, "source_metadata", source(value));
        pendingPayload += NestedTableDescriptors.measure(payload, 0, budget.remainingCodePoints() - pendingPayload);
        final var result = new ArrayList<Entry>();
        // Do not retain descendant sidecars recursively through the snapshot.
        final var content = value.content();
        final var snapshot =
                new DocxTableExtractor.Rendered(new DocxParagraphText.Rendered(content.text(), content.links(),
                                                        content.annotations(), content.supplements()),
                        value.metadata());
        result.add(new Entry(id, parent, List.of(step), 0, content.text().codePointCount(0, content.text().length()),
                snapshot, captions));
        // Prefix each descendant's cell-step path with the current enclosing cell step.
        for (final var child : content.nested()) {
            ElementCollector.checkInterrupted();
            final var path = new ArrayList<Map<String, Object>>();
            path.add(step);
            path.addAll(child.path());
            result.add(new Entry(child.id(), child.parent(), List.copyOf(path), child.start(), child.end(),
                    child.snapshot(), child.captions()));
        }
        return List.copyOf(result);
    }

    /**
     * Extracts the format-specific source metadata map for a nested table snapshot.
     *
     * @param snapshot rendered nested table snapshot
     * @return source metadata map
     */
    private Map<String, Object> source(final DocxTableExtractor.Rendered snapshot) {
        final var source = new LinkedHashMap<String, Object>();
        snapshot.metadata().forEach((key, value) -> {
            if (SOURCE_KEYS.contains(key)) {
                source.put(key, value);
            }
        });
        final var anchors =
                snapshot.content()
                        .supplements()
                        .stream()
                        .map(value -> Map.<String, Object>of("kind", value.kind(), "start_index", value.offset()))
                        .toList();
        if (!anchors.isEmpty()) {
            source.put("docx_supplement_anchors", anchors);
        }
        return source;
    }

    /**
     * Restores source preorder even when merge rendering moves continuation content to an earlier cell.
     *
     * @param content rendered root table content carrying captured nested-table entries
     * @return nested-table metadata map to merge into the root table element
     * @throws IOException if the thread is interrupted
     */
    Map<String, Object> metadata(final DocxParagraphText.Rendered content) throws IOException {
        final var output =
                new NestedTableDescriptors(budget, content.text().codePointCount(0, content.text().length()));
        final var indices = new HashMap<Integer, Integer>();
        // Sort captured entries by preorder ID so parent indices always precede their children even after cell merges.
        for (final var entry : content.nested().stream().sorted(Comparator.comparingInt(Entry::id)).toList()) {
            ElementCollector.checkInterrupted();
            final var snapshot = entry.snapshot();
            final var source = source(snapshot);
            final Integer parent = entry.parent() < 0 ? null : indices.get(entry.parent());
            if (entry.parent() >= 0 && parent == null) {
                throw new IllegalArgumentException("Missing DOCX nested-table parent");
            }
            output.add(parent, entry.path(), entry.start(), entry.end(), snapshot.text(),
                    (String) snapshot.metadata().get("text_as_html"), snapshot.content().links(), entry.captions(),
                    source);
            indices.put(entry.id(), indices.size());
        }
        pending = 0;
        pendingPayload = 0;
        return output.metadata();
    }
}
