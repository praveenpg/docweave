package dev.docweave.partition;

/**
 * JDK service-discovery bridge for the MARKDOWN module; applications use {@link PartitionerRegistry}.
 * Public construction is required by ServiceLoader; the returned parser implementation is hidden.
 */
public final class MarkdownPartitionerProvider implements PartitionerProvider {
    /** Creates a stateless service-discovery bridge. */
    public MarkdownPartitionerProvider() {
    }

    /**
     * Returns the module key.
     * @return {@link DocumentFormat#MARKDOWN}
     */
    public DocumentFormat format() {
        return DocumentFormat.MARKDOWN;
    }

    /**
     * Creates a hidden thread-safe parser strategy.
     * @return reusable MARKDOWN strategy
     */
    public Partitioner create() {
        return new MarkdownPartitioner();
    }
}
