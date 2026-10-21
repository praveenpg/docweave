package dev.docweave.partition;

import java.io.IOException;

/** Stateless HTML strategy; DOM traversal and heading/list state belong to each invocation. */
final class HtmlPartitioner implements Partitioner {
    /**
     * Returns the HTML document format handled by this partitioner.
     *
     * @return {@link DocumentFormat#HTML}
     */
    public DocumentFormat format() {
        return DocumentFormat.HTML;
    }

    /**
     * Indicates that HTML partitioning supports nested-table metadata preservation.
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsNestedTablePreservation() {
        return true;
    }

    /**
     * Partitions the HTML document in {@code context} using a fresh {@link HtmlDomWalker}.
     *
     * @param context active partition context
     * @throws IOException if nested-table options are unsupported or HTML partitioning fails
     */
    public void partition(final PartitionContext context) throws IOException {
        NestedTableSupport.check(this, context.options());
        new HtmlDomWalker().partition(context);
    }
}
