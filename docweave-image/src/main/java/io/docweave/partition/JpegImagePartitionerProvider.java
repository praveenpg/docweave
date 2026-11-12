package io.docweave.partition;

/** Service provider of the JPEG image partitioner without OCR (see {@link ImagePartitioner}). */
public final class JpegImagePartitionerProvider implements PartitionerProvider {
    /** Creates the provider. */
    public JpegImagePartitionerProvider() {
    }

    @Override
    public DocumentFormat format() {
        return DocumentFormat.JPEG;
    }

    @Override
    public Partitioner create() {
        return new DefaultImagePartitioner(DocumentFormat.JPEG, ImageOptions.defaults(), null);
    }
}
