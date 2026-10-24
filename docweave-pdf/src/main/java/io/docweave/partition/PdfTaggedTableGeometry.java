package io.docweave.partition;

/** Explicit geometry requirements for source-authored logical PDF tables. */
public enum PdfTaggedTableGeometry {
    /** Requires independently supported axes for every empty source cell; preserves historical behavior. */
    SUPPORTED_CELL_AXES,
    /**
     * Retains the authored logical grid using only the enclosing nonempty source text bounds.
     * Empty rows and columns receive no invented geometry. Requires cell coordinates to be disabled,
     * upright source text and exclusive ownership of all nonblank glyph centers inside the envelope.
     * This describes a logical source table, not physical cell boundaries or semantic reading order.
     */
    LOGICAL_TEXT_ENVELOPE,
    /**
     * Retains authored logical cells while allowing unclaimed text only in gaps between their real text envelopes.
     * Such text remains ordinary output and is never attached to or deleted from the table. Every nonblank
     * glyph center inside a nonempty cell envelope must still belong to this table. Requires disabled cell
     * coordinates and upright source-owned text; empty slots receive no invented rectangles. Parent bounds
     * enclose the table's source cells and may also enclose separately emitted ordinary text in those gaps.
     * This is source logical ownership, not a rendered visibility or semantic reading-order guarantee.
     */
    LOGICAL_CELL_TEXT_ENVELOPES
}
