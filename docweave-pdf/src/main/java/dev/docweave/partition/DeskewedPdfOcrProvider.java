package dev.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Opt-in PAGE-only projection correction; cell segmentation and table topology stay caller-controlled. */
final class DeskewedPdfOcrProvider implements PdfOcrProvider {
    /** Explicit caller-owned recognition strategy. */
    private final PdfOcrProvider provider;
    /** Immutable per-call raster/output policy. */
    private final PdfOcrOptions limits;
    /** Stateless bounded projection analyzer. */
    private final PdfDeskewEstimator estimator;

    /**
     * Captures policy without analyzing an image or invoking an engine.
     * @param provider caller-owned strategy
     * @param limits positive raster/output limits
     */
    DeskewedPdfOcrProvider(final PdfOcrProvider provider, final PdfOcrOptions limits) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.limits = Objects.requireNonNull(limits, "limits");
        estimator = PdfDeskewEstimator.create(limits.maxRenderPixels());
    }

    @Override
    public List<Line> recognize(final BufferedImage image) throws IOException {
        return recognize(image, InputKind.PAGE);
    }

    @Override
    public List<Line> recognize(final BufferedImage image, final InputKind kind) throws IOException {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(kind, "kind");
        ElementCollector.checkInterrupted();
        if (kind == InputKind.TABLE_CELL) {
            return provider.recognize(image, kind);
        }
        final var estimate = estimator.estimate(image);
        return PdfOcrProvider.rotated(provider, estimate.clockwiseDegrees(), limits).recognize(image, kind);
    }
}
