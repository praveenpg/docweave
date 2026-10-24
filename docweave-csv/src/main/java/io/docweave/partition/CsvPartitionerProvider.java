package io.docweave.partition;

/**
 * JDK service-discovery bridge for the CSV module; applications use {@link PartitionerRegistry}.
 * Public construction is required by ServiceLoader; the returned parser implementation is hidden.
 */
public final class CsvPartitionerProvider implements PartitionerProvider {
    /** Creates a stateless service-discovery bridge. */
    public CsvPartitionerProvider() {
    }

    /**
     * Returns the module key.
     * @return {@link DocumentFormat#CSV}
     */
    public DocumentFormat format() {
        return DocumentFormat.CSV;
    }

    /**
     * Creates a hidden thread-safe parser strategy.
     * @return reusable CSV strategy
     */
    public Partitioner create() {
        return new CsvPartitioner();
    }
}
