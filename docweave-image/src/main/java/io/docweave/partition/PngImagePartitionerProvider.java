package io.docweave.partition;

/** Service provider of the PNG image partitioner without OCR (see {@link ImagePartitioner}). */
public final class PngImagePartitionerProvider implements PartitionerProvider {
    /** Creates the provider. */
    public PngImagePartitionerProvider() {
    }

    @Override
    public DocumentFormat format() {
        return DocumentFormat.PNG;
    }

    @Override
    public Partitioner create() {
        return new DefaultImagePartitioner(DocumentFormat.PNG, ImageOptions.defaults(), null);
    }
}
