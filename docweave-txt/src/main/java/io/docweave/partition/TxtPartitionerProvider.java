package io.docweave.partition;

/**
 * JDK service-discovery bridge for the TXT module; applications use {@link PartitionerRegistry}.
 * Public construction is required by ServiceLoader; the returned parser implementation is hidden.
 */
public final class TxtPartitionerProvider implements PartitionerProvider {
    /** Creates a stateless service-discovery bridge. */
    public TxtPartitionerProvider() {
    }

    /**
     * Returns the module key.
     * @return {@link DocumentFormat#TXT}
     */
    public DocumentFormat format() {
        return DocumentFormat.TXT;
    }

    /**
     * Creates a hidden thread-safe parser strategy.
     * @return reusable TXT strategy
     */
    public Partitioner create() {
        return new TxtPartitioner();
    }
}
