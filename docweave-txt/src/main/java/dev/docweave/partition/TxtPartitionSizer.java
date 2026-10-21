package dev.docweave.partition;

import dev.docweave.core.ElementType;

import java.io.IOException;
import java.util.Map;

/** Document-local size stage; holds at most one pending fragment and preserves source categories. */
final class TxtPartitionSizer {
    /** Active partition context receiving emitted text elements. */
    private final PartitionContext context;
    /** Minimum and maximum code-point partition limits for plain text. */
    private final TxtOptions limits;
    /** Pending text fragment awaiting potential merge with subsequent short prose. */
    private String pending;
    /** Code-point length of {@link #pending}. */
    private int pendingPoints;
    /** Effective element type assigned to {@link #pending}. */
    private ElementType pendingType;
    /** Whether {@link #pending} is prose eligible for minimum-partition merging. */
    private boolean pendingProse;

    /**
     * Creates a size stage bound to the given partition context.
     * @param context active document partition context
     */
    TxtPartitionSizer(final PartitionContext context) {
        this.context = context;
        limits = context.options().txtOptions();
    }

    /**
     * Classifies, merges or splits a grouped paragraph and emits completed fragments.
     * @param paragraph normalized grouped paragraph text
     * @throws IOException if element emission or interruption fails
     */
    void accept(final String paragraph) throws IOException {
        ElementCollector.checkInterrupted();
        final var category = TextSupport.classify(paragraph);
        final boolean classify = context.options().classifyText();
        final ElementType type = classify ? category : ElementType.UncategorizedText;
        final String value =
                classify && category == ElementType.ListItem ? TextSupport.cleanBullet(paragraph) : paragraph;
        final boolean prose = category != ElementType.Title && category != ElementType.ListItem;
        int points = TextSupport.points(value, 0, value.length());
        // Merge consecutive prose paragraphs of the same type when the pending fragment is shorter than minPartition
        // and the combined length fits maxPartition.
        if (pending != null && pendingProse && prose && pendingType == type && pendingPoints < limits.minPartition()
                && (long) pendingPoints + 1 + points <= limits.maxPartition()) {
            pending += " " + value;
            pendingPoints += 1 + points;
            return;
        }
        finish();
        // Split oversized paragraphs into chunks of at most maxPartition code points, preferring word boundaries when
        // available.
        int start = 0;
        while (points > limits.maxPartition()) {
            ElementCollector.checkInterrupted();
            final int end = TextSupport.advance(value, start, limits.maxPartition());
            int split = end;
            while (split > start && value.charAt(split) != ' ') {
                if ((split & 1023) == 0) {
                    ElementCollector.checkInterrupted();
                }
                split--;
            }
            if (split == start) {
                split = end;
            }
            context.emit(type, value.substring(start, split), Map.of());
            int next = split;
            while (next < value.length() && value.charAt(next) == ' ') {
                if ((next & 1023) == 0) {
                    ElementCollector.checkInterrupted();
                }
                next++;
            }
            points -= TextSupport.points(value, start, next);
            start = next;
        }
        if (start < value.length()) {
            pending = value.substring(start);
            pendingPoints = points;
            pendingType = type;
            pendingProse = prose;
        }
    }

    /**
     * Emits any remaining buffered text fragment at the end of the document or before a non-mergeable paragraph.
     * @throws IOException if element emission or interruption fails
     */
    void finish() throws IOException {
        if (pending != null) {
            context.emit(pendingType, pending, Map.of());
        }
        pending = null;
        pendingPoints = 0;
    }
}
