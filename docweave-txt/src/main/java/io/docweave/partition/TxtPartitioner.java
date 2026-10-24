package io.docweave.partition;

import java.io.IOException;

/** Stateless TXT strategy; grouping and size/emission state are isolated to each document. */
final class TxtPartitioner implements Partitioner {
    /**
     * Identifies the plain-text format handled by this strategy.
     * @return {@link DocumentFormat#TXT}
     */
    public DocumentFormat format() {
        return DocumentFormat.TXT;
    }

    /**
     * Partitions decoded plain text into grouped and size-bounded elements.
     * @param context synchronous document context
     * @throws IOException if text grouping, sizing, unsupported options or interruption fails
     */
    public void partition(final PartitionContext context) throws IOException {
        NestedTableSupport.check(this, context.options());
        new TxtParagraphGrouper(context.options(), new TxtPartitionSizer(context)).partition(context.text());
    }
}
