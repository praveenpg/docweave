package dev.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** PAGE-only projection deskew preserving actual-word capability; isolated CELL inputs pass through. */
final class DeskewedPdfWordOcrProvider implements PdfWordOcrProvider {
    /** Caller-owned thread-safe actual-word strategy. */
    private final PdfWordOcrProvider provider;
    /** Bounded PAGE rotation/output policy; CELL limits remain the underlying provider's responsibility. */
    private final PdfOcrOptions limits;
    /** Stateless bounded projection analyzer. */
    private final PdfDeskewEstimator estimator;

    /**
     * Captures explicit provider and budgets without examining an image or launching recognition.
     * @param provider nonnull actual-word strategy
     * @param limits nonnull PAGE raster/output budgets
     */
    DeskewedPdfWordOcrProvider(final PdfWordOcrProvider provider, final PdfOcrOptions limits) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.limits = Objects.requireNonNull(limits, "limits");
        estimator = PdfDeskewEstimator.create(limits.maxRenderPixels());
    }

    /**
     * Estimates small PAGE skew and delegates one actual-word call through the explicit inverse frame.
     * @param image borrowed raster
     * @param kind explicit purpose; CELL calls retain their original image and underlying budgets
     * @return original-frame actual-word output
     * @throws IOException if estimation, recognition, correction or cancellation fails
     */
    @Override
    public List<WordLine> recognizeWords(final BufferedImage image, final InputKind kind) throws IOException {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(kind, "kind");
        ElementCollector.checkInterrupted();
        if (kind == InputKind.TABLE_CELL) {
            return provider.recognizeWords(image, kind);
        }
        final var estimate = estimator.estimate(image);
        return PdfWordOcrProvider.rotated(provider, estimate.clockwiseDegrees(), limits).recognizeWords(image, kind);
    }
}
