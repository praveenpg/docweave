package io.docweave.partition;

import java.io.IOException;

/** Stateless tab-delimited strategy supporting quoted tabs/newlines and doubled quote escapes. */
final class TsvPartitioner implements Partitioner {
    /**
     * Identifies the tab-separated values format handled by this strategy.
     * @return {@link DocumentFormat#TSV}
     */
    public DocumentFormat format() {
        return DocumentFormat.TSV;
    }

    /**
     * Partitions a tab-delimited document into a structured table element.
     * @param context synchronous document context
     * @throws IOException if parsing, table limits, unsupported options or interruption fails
     */
    public void partition(final PartitionContext context) throws IOException {
        NestedTableSupport.check(this, context.options());
        DelimitedTablePartitioner.partition(context, '\t');
    }
}
