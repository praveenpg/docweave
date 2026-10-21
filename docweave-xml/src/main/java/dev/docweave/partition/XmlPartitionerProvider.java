package dev.docweave.partition;

/**
 * JDK service-discovery bridge for the XML module; applications use {@link PartitionerRegistry}.
 * Public construction is required by ServiceLoader; the returned parser implementation is hidden.
 */
public final class XmlPartitionerProvider implements PartitionerProvider {
    /** Creates a stateless service-discovery bridge. */
    public XmlPartitionerProvider() {
    }

    /**
     * Returns the module key.
     * @return {@link DocumentFormat#XML}
     */
    public DocumentFormat format() {
        return DocumentFormat.XML;
    }

    /**
     * Creates a hidden thread-safe parser strategy.
     * @return reusable XML strategy
     */
    public Partitioner create() {
        return new XmlPartitioner();
    }
}
