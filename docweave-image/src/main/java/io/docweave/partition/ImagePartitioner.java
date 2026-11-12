package io.docweave.partition;

import java.util.List;
import java.util.Objects;

/**
 * Standalone PNG, JPEG and TIFF partitioning: each page is decoded, oriented upright and recognized with an
 * explicitly configured OCR provider, producing one {@code UncategorizedText} element per recognized line.
 *
 * <p>Instances found through {@link java.util.ServiceLoader} have no OCR provider: they detect and route images, and
 * partitioning then fails with {@link PartitionException.Code#UNSUPPORTED_OPTION}. Register instances from
 * {@link #create(DocumentFormat, ImageOptions, PdfOcrProvider)} or {@link #createAll(ImageOptions, PdfOcrProvider)}
 * to extract text. Nothing is downloaded and no model is called implicitly.</p>
 *
 * <pre>{@code
 * var ocr = TesseractOcr.create(TesseractOptions.defaults());
 * var registry = PartitionerRegistry.builder();
 * ImagePartitioner.createAll(ImageOptions.defaults(), ocr).forEach(registry::register);
 * var result = DocWeave.create(registry.build()).partition(Path.of("scan.tiff"));
 * }</pre>
 */
public interface ImagePartitioner extends Partitioner {
    /**
     * Creates an image partitioner of one format.
     * @param format {@link DocumentFormat#PNG}, {@link DocumentFormat#JPEG} or {@link DocumentFormat#TIFF}
     * @param options resource limits
     * @param ocr caller-owned recognition strategy, shared with PDF OCR where configured
     * @return partitioner
     * @throws IllegalArgumentException if the format is not an image format
     */
    static ImagePartitioner create(final DocumentFormat format, final ImageOptions options, final PdfOcrProvider ocr) {
        return new DefaultImagePartitioner(
                format, Objects.requireNonNull(options, "options"), Objects.requireNonNull(ocr, "ocr"));
    }

    /**
     * Creates PNG, JPEG and TIFF partitioners sharing limits and an OCR provider.
     * @param options resource limits
     * @param ocr caller-owned recognition strategy
     * @return partitioners in PNG, JPEG, TIFF order
     */
    static List<Partitioner> createAll(final ImageOptions options, final PdfOcrProvider ocr) {
        return List.of(create(DocumentFormat.PNG, options, ocr), create(DocumentFormat.JPEG, options, ocr),
                create(DocumentFormat.TIFF, options, ocr));
    }

    /**
     * Returns this partitioner's resource limits.
     * @return limits
     */
    ImageOptions options();
}
