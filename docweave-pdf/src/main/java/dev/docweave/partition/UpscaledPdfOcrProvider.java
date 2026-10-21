package dev.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stateless line-only magnification; recognition and coordinates retain their explicit purpose. */
final class UpscaledPdfOcrProvider implements PdfOcrProvider {
    /** Caller-owned thread-safe line strategy. */
    private final PdfOcrProvider provider;
    /** Validated immutable integer ratio. */
    private final int multiplier;
    /** Per-call raster/raw-output limits, independent of page aggregation. */
    private final PdfOcrOptions limits;

    /**
     * Captures dependencies without raster allocation or external execution.
     * @param provider nonnull line strategy
     * @param multiplier explicit supported integer ratio
     * @param limits nonnull per-call budget
     */
    UpscaledPdfOcrProvider(final PdfOcrProvider provider, final int multiplier, final PdfOcrOptions limits) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.limits = Objects.requireNonNull(limits, "limits");
        PdfOcrScaling.checkMultiplier(multiplier);
        this.multiplier = multiplier;
    }

    /**
     * Recognizes a PAGE with the same bounded ownership contract as contextual recognition.
     * @param image borrowed source raster
     * @return immutable source-frame lines
     * @throws IOException if preparation, provider output, limits or cancellation fail
     */
    @Override
    public List<Line> recognize(final BufferedImage image) throws IOException {
        return recognize(image, InputKind.PAGE);
    }

    /**
     * Invokes the selected purpose once and restores validated raw output into original pixels.
     * @param image borrowed source raster
     * @param kind nonnull original PAGE/CELL purpose
     * @return immutable source-frame lines, preserving order and text
     * @throws IOException if preparation, output geometry, raw limits or cancellation fail
     */
    @Override
    public List<Line> recognize(final BufferedImage image, final InputKind kind) throws IOException {
        Objects.requireNonNull(kind, "kind");
        try (final var scaling = new PdfOcrScaling(image, multiplier, limits)) {
            final var lines = new PdfOcrBudget(limits).recognize(provider, scaling.image(), kind);
            final var output = new ArrayList<Line>(lines.size());
            for (final var line : lines) {
                ElementCollector.checkInterrupted();
                output.add(scaling.toSource(line));
            }
            return List.copyOf(output);
        }
    }
}
