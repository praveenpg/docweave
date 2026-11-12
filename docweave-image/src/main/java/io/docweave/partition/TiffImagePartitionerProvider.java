package io.docweave.partition;

/** Service provider of the TIFF image partitioner without OCR (see {@link ImagePartitioner}). */
public final class TiffImagePartitionerProvider implements PartitionerProvider {
    /** Creates the provider. */
    public TiffImagePartitionerProvider() {
    }

    @Override
    public DocumentFormat format() {
        return DocumentFormat.TIFF;
    }

    @Override
    public Partitioner create() {
        return new DefaultImagePartitioner(DocumentFormat.TIFF, ImageOptions.defaults(), null);
    }
}
