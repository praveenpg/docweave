package dev.docweave.partition;

/**
 * JDK service-discovery bridge for the DOCX module; applications use {@link PartitionerRegistry}.
 * Public construction is required by ServiceLoader; the returned parser implementation is hidden.
 */
public final class DocxPartitionerProvider implements PartitionerProvider {
    /** Creates a stateless service-discovery bridge. */
    public DocxPartitionerProvider() {
    }

    /**
     * Returns the module key.
     * @return {@link DocumentFormat#DOCX}
     */
    public DocumentFormat format() {
        return DocumentFormat.DOCX;
    }

    /**
     * Creates a hidden thread-safe parser strategy.
     * @return reusable DOCX strategy
     */
    public Partitioner create() {
        return new DocxPartitioner();
    }
}
