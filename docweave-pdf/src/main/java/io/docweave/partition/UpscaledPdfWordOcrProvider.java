package io.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stateless actual-word magnification without inferred tokens or a changed source frame. */
final class UpscaledPdfWordOcrProvider implements PdfWordOcrProvider {
    /** Caller-owned actual-word strategy. */
    private final PdfWordOcrProvider provider;
    /** Validated immutable integer ratio. */
    private final int multiplier;
    /** Per-call raster/raw-output limits; integration retains its own aggregate budget. */
    private final PdfOcrOptions limits;

    /**
     * Validates configuration without allocating an image or invoking a recognizer.
     * @param provider nonnull actual-word strategy
     * @param multiplier supported integer ratio
     * @param limits nonnull per-call policies
     */
    UpscaledPdfWordOcrProvider(final PdfWordOcrProvider provider, final int multiplier, final PdfOcrOptions limits) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.limits = Objects.requireNonNull(limits, "limits");
        PdfOcrScaling.checkMultiplier(multiplier);
        this.multiplier = multiplier;
    }

    /**
     * Validates detailed recognition in the enlarged frame and inverts only actual provider words.
     * @param image borrowed source raster
     * @param kind nonnull original purpose, forwarded unchanged
     * @return immutable source-frame lines/words with unchanged text and encounter order
     * @throws IOException if recognition, raw limits, geometry or cancellation fail
     */
    @Override
    public List<WordLine> recognizeWords(final BufferedImage image, final InputKind kind) throws IOException {
        Objects.requireNonNull(kind, "kind");
        try (final var scaling = new PdfOcrScaling(image, multiplier, limits)) {
            final var enlarged = scaling.image();
            final var detailed = new PdfOcrBudget(limits).recognizeWords(provider, enlarged, kind);
            final var output = new ArrayList<WordLine>(detailed.size());
            for (final var value : detailed) {
                ElementCollector.checkInterrupted();
                final var words = new ArrayList<Word>(value.words().size());
                for (final var word : value.words()) {
                    ElementCollector.checkInterrupted();
                    if (word.right() > enlarged.getWidth() || word.bottom() > enlarged.getHeight()) {
                        throw new PartitionException(PartitionException.Code.OCR_FAILURE,
                                "Magnified OCR word exceeds the recognition raster");
                    }
                    final var region = scaling.toSource(
                            new Line(word.text(), word.left(), word.top(), word.right(), word.bottom()));
                    words.add(new Word(region.text(), region.left(), region.top(), region.right(), region.bottom()));
                }
                try {
                    output.add(new WordLine(scaling.toSource(value.line()), words));
                } catch (final IllegalArgumentException failure) {
                    throw new PartitionException(PartitionException.Code.OCR_FAILURE,
                            "Magnified OCR words cannot be represented inside their source line", failure);
                }
            }
            return List.copyOf(output);
        }
    }
}
