package io.docweave.partition;

/** JDK ServiceLoader discovery bridge; applications use the registry or {@link PdfPartitioner} interface. */
public final class PdfPartitionerProvider implements PartitionerProvider {
    /** Creates a stateless discovery bridge. */
    public PdfPartitionerProvider() {
    }

    /**
     * Returns the installed format.
     * @return PDF
     */
    @Override
    public DocumentFormat format() {
        return DocumentFormat.PDF;
    }

    /**
     * Creates a bounded native strategy.
     * @return reusable hidden implementation
     */
    @Override
    public Partitioner create() {
        return PdfPartitioner.create(PdfOptions.defaults());
    }
}
