package io.docweave.partition;

import java.io.IOException;

/** Stateless RFC-style comma-delimited strategy using the shared quoted-record extractor. */
final class CsvPartitioner implements Partitioner {
    /**
     * Identifies the comma-separated values format handled by this strategy.
     * @return {@link DocumentFormat#CSV}
     */
    public DocumentFormat format() {
        return DocumentFormat.CSV;
    }

    /**
     * Partitions a comma-delimited document into a structured table element.
     * @param context synchronous document context
     * @throws IOException if parsing, table limits, unsupported options or interruption fails
     */
    public void partition(final PartitionContext context) throws IOException {
        NestedTableSupport.check(this, context.options());
        DelimitedTablePartitioner.partition(context, ',');
    }
}
