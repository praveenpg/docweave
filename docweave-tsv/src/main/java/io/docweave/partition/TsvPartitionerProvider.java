package io.docweave.partition;

/**
 * JDK service-discovery bridge for the TSV module; applications use {@link PartitionerRegistry}.
 * Public construction is required by ServiceLoader; the returned parser implementation is hidden.
 */
public final class TsvPartitionerProvider implements PartitionerProvider {
    /** Creates a stateless service-discovery bridge. */
    public TsvPartitionerProvider() {
    }

    /**
     * Returns the module key.
     * @return {@link DocumentFormat#TSV}
     */
    public DocumentFormat format() {
        return DocumentFormat.TSV;
    }

    /**
     * Creates a hidden thread-safe parser strategy.
     * @return reusable TSV strategy
     */
    public Partitioner create() {
        return new TsvPartitioner();
    }
}
