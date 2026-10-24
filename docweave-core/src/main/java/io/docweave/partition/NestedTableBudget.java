package io.docweave.partition;

import java.io.IOException;
import java.util.Objects;

/**
 * Document-confined reservation accounting shared by preservation adapters.
 * Check before constructing/copying a descriptor. Callers calculate actual stored payload,
 * including repeated text/HTML/annotation copies; this counter does not inspect or retain content.
 * Failed validation, interruption and reservations do not consume budget.
 */
final class NestedTableBudget {
    /** Nested-table preservation options defining per-document table, depth, and code-point caps. */
    private final NestedTableOptions options;
    /** Number of nested tables reserved so far in the current document. */
    private int tables;
    /** Total metadata Unicode code points reserved so far across nested tables in the current document. */
    private long codePoints;

    /**
     * Creates a preservation budget tracker for a single document.
     *
     * @param options nested-table options configured in {@link NestedTableOptions.Mode#PRESERVE_METADATA} mode
     */
    NestedTableBudget(final NestedTableOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (options.mode() != NestedTableOptions.Mode.PRESERVE_METADATA) {
            throw new IllegalArgumentException("Preservation accounting requires PRESERVE_METADATA");
        }
    }

    /**
     * Checks a prospective batch before allocating child capture state, without reserving it.
     *
     * @param additional number of additional nested tables to check
     * @param depth 1-based nesting depth relative to the root table
     * @throws IOException if the table count or depth budget is exceeded or the thread is interrupted
     */
    void checkCount(final int additional, final int depth) throws IOException {
        ElementCollector.checkInterrupted();
        if (additional < 0 || depth < 1) {
            throw new IllegalArgumentException("Invalid nested-table count check");
        }
        if (additional > options.maxNestedTables() - tables || depth > options.maxNestedDepth()) {
            throw new PartitionException(
                    PartitionException.Code.TABLE_LIMIT, "Nested-table preservation budget exceeded");
        }
    }

    /**
     * Remaining payload capacity; per-adapter bounded renderers use this before building strings.
     *
     * @return remaining available metadata Unicode code points
     */
    long remainingCodePoints() {
        return options.maxMetadataCodePoints() - codePoints;
    }

    /**
     * Reserves one child atomically; depth one is a direct child and empty payloads still count.
     *
     * @param depth 1-based nesting depth relative to the root table
     * @param payloadCodePoints number of metadata Unicode code points consumed by the descriptor
     * @throws IOException if table count, depth, or metadata code-point budgets are exceeded or interrupted
     */
    void reserve(final int depth, final long payloadCodePoints) throws IOException {
        ElementCollector.checkInterrupted();
        if (depth < 1 || payloadCodePoints < 0) {
            throw new IllegalArgumentException("Invalid nested-table reservation");
        }
        if (tables >= options.maxNestedTables() || depth > options.maxNestedDepth()
                || payloadCodePoints > options.maxMetadataCodePoints() - codePoints) {
            throw new PartitionException(
                    PartitionException.Code.TABLE_LIMIT, "Nested-table preservation budget exceeded");
        }
        tables++;
        codePoints += payloadCodePoints;
    }
}
