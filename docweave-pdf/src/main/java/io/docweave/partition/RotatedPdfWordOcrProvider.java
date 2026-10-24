package io.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Explicit bounded actual-word correction with inverse source-polygon clipping and immutable output. */
final class RotatedPdfWordOcrProvider implements PdfWordOcrProvider {
    /** Caller-owned actual-word strategy. */
    private final PdfWordOcrProvider provider;
    /** Finite normalized clockwise correction. */
    private final double degrees;
    /** Raw line/character and source/expanded raster budgets. */
    private final PdfOcrOptions limits;

    /**
     * Captures policy without image allocation, model discovery or external execution.
     * @param provider nonnull thread-safe actual-word strategy
     * @param degrees finite clockwise angle
     * @param limits nonnull per-call policies
     */
    RotatedPdfWordOcrProvider(final PdfWordOcrProvider provider, final double degrees, final PdfOcrOptions limits) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.limits = Objects.requireNonNull(limits, "limits");
        if (!Double.isFinite(degrees)) {
            throw new IllegalArgumentException("OCR correction angle must be finite");
        }
        this.degrees = Math.IEEEremainder(degrees, 360);
    }

    /**
     * Recognizes once and restores only actual provider tokens, preserving their encounter order.
     * Raw limits apply before white-margin tokens/lines are discarded. Retained text is rebuilt
     * from retained token strings; no word boxes are inferred from line text.
     * @param image borrowed RGB input
     * @param kind explicit PAGE or TABLE_CELL purpose, forwarded unchanged
     * @return immutable original-frame detailed lines
     * @throws IOException if recognition, budgets, contracts or cancellation fail
     */
    @Override
    public List<WordLine> recognizeWords(final BufferedImage image, final InputKind kind) throws IOException {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(kind, "kind");
        try (final var rotation = new PdfOcrRotation(image, degrees, limits)) {
            final var corrected = rotation.image();
            final var detailed = provider.recognizeWords(corrected, kind);
            ElementCollector.checkInterrupted();
            if (detailed == null) {
                throw failure("OCR word correction provider returned null");
            }
            if (detailed.size() > limits.maxLines()) {
                throw new PartitionException(PartitionException.Code.OCR_LIMIT, "OCR word correction exceeds maxLines");
            }
            long characters = 0;
            final var output = new ArrayList<WordLine>(detailed.size());
            for (final var value : detailed) {
                ElementCollector.checkInterrupted();
                if (value == null || value.line().right() > corrected.getWidth()
                        || value.line().bottom() > corrected.getHeight()) {
                    throw failure("OCR word correction bounds exceed corrected raster");
                }
                characters += value.line().text().length();
                if (characters > limits.maxCharacters()) {
                    throw new PartitionException(
                            PartitionException.Code.OCR_LIMIT, "OCR word correction exceeds maxCharacters");
                }
                final var line = rotation.toSource(value.line());
                if (line == null) {
                    continue;
                }
                final var words = new ArrayList<Word>();
                double left = line.left();
                double top = line.top();
                double right = line.right();
                double bottom = line.bottom();
                for (final var word : value.words()) {
                    final var restored = rotation.toSource(
                            new Line(word.text(), word.left(), word.top(), word.right(), word.bottom()));
                    if (restored != null) {
                        // Separate polygon clipping can differ at a shared edge by floating-point roundoff.
                        // The line envelope conservatively includes its actual restored words.
                        left = Math.min(left, restored.left());
                        top = Math.min(top, restored.top());
                        right = Math.max(right, restored.right());
                        bottom = Math.max(bottom, restored.bottom());
                        words.add(new Word(
                                restored.text(), restored.left(), restored.top(), restored.right(), restored.bottom()));
                    }
                }
                if (!words.isEmpty() || value.words().isEmpty()) {
                    final var text = words.stream().map(Word::text).collect(Collectors.joining(" "));
                    try {
                        output.add(new WordLine(new Line(text, left, top, right, bottom), words));
                    } catch (final IllegalArgumentException failure) {
                        throw new PartitionException(PartitionException.Code.OCR_FAILURE,
                                "Restored OCR words cannot be represented inside their source line", failure);
                    }
                }
            }
            return List.copyOf(output);
        }
    }

    /**
     * Constructs a provider-contract failure without exposing recognized content.
     * @param message content-free diagnostic
     * @return typed recognition failure
     */
    private static PartitionException failure(final String message) {
        return new PartitionException(PartitionException.Code.OCR_FAILURE, message);
    }
}
