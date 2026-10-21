package dev.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fixed, explicit OCR-only correction with inverse geometry and borrowed-raster ownership. */
final class RotatedPdfOcrProvider implements PdfOcrProvider {
    /** Caller-owned recognition strategy. */
    private final PdfOcrProvider provider;
    /** Normalized clockwise correction in the top-left raster frame. */
    private final double degrees;
    /** Per-call output/raster limits; page aggregation remains the integration's responsibility. */
    private final PdfOcrOptions limits;

    /**
     * Captures immutable correction policy without allocating a raster or starting recognition.
     * @param provider underlying synchronous strategy
     * @param degrees finite clockwise correction
     * @param limits raster/line/character policy
     */
    RotatedPdfOcrProvider(final PdfOcrProvider provider, final double degrees, final PdfOcrOptions limits) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.limits = Objects.requireNonNull(limits, "limits");
        if (!Double.isFinite(degrees)) {
            throw new IllegalArgumentException("OCR correction angle must be finite");
        }
        this.degrees = Math.IEEEremainder(degrees, 360);
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
        try (final var rotation = new PdfOcrRotation(image, degrees, limits)) {
            final var corrected = rotation.image();
            final var lines = provider.recognize(corrected, kind);
            ElementCollector.checkInterrupted();
            if (lines == null) {
                throw failure("OCR correction provider returned null");
            }
            if (lines.size() > limits.maxLines()) {
                throw limit("OCR correction exceeds maxLines");
            }
            long characters = 0;
            final var output = new ArrayList<Line>(lines.size());
            for (final var line : lines) {
                ElementCollector.checkInterrupted();
                if (line == null || line.right() > corrected.getWidth() || line.bottom() > corrected.getHeight()) {
                    throw failure("OCR correction provider bounds exceed corrected raster");
                }
                characters += line.text().length();
                if (characters > limits.maxCharacters()) {
                    throw limit("OCR correction exceeds maxCharacters");
                }
                final var restored = rotation.toSource(line);
                if (restored != null) {
                    output.add(restored);
                }
            }
            return List.copyOf(output);
        }
    }

    /**
     * Creates a stable resource diagnostic without provider text.
     * @param message diagnostic
     * @return typed resource failure
     */
    private static PartitionException limit(final String message) {
        return new PartitionException(PartitionException.Code.OCR_LIMIT, message);
    }

    /**
     * Creates a stable provider-contract diagnostic without recognized text.
     * @param message diagnostic
     * @return typed recognition failure
     */
    private static PartitionException failure(final String message) {
        return new PartitionException(PartitionException.Code.OCR_FAILURE, message);
    }
}
