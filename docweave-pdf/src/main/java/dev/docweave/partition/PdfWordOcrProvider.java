package dev.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Optional OCR geometry capability supplying actual provider word boxes with their source lines.
 * Implementations preserve the borrowed-image, concurrency and cancellation contract of PdfOcrProvider.
 * This capability never synthesizes word positions from a line string. Line-only providers remain valid.
 * Default recognition projects the immutable detailed result into the existing line API.
 */
@FunctionalInterface
public interface PdfWordOcrProvider extends PdfOcrProvider {
    /**
     * Adds explicit integer nearest-neighbor magnification while retaining actual provider word boxes.
     * Source/enlarged raster and raw line/UTF-16 limits apply before provider output is inverted. Every
     * line/word remains in provider order with unchanged text; positions divide by the explicit scale,
     * without inferring tokens or altering table-detection pixels. Purpose, borrowed-source ownership,
     * cancellation and caller-owned engine lifecycle remain. Magnification adds no information and
     * does not guarantee better recognition. Construction allocates no raster and executes no provider.
     * @param provider nonnull thread-safe actual-word strategy
     * @param multiplier integer scale from 1 through 8
     * @param limits nonnull per-call source/enlarged pixel and raw-output limits
     * @return hidden reusable actual-word decorator
     * @throws NullPointerException if provider or limits is null
     * @throws IllegalArgumentException if multiplier is outside 1 through 8
     */
    static PdfWordOcrProvider upscaled(
            final PdfWordOcrProvider provider, final int multiplier, final PdfOcrOptions limits) {
        return new UpscaledPdfWordOcrProvider(provider, multiplier, limits);
    }

    /**
     * Applies a fixed explicit OCR-only correction while retaining actual provider tokens.
     * The owned corrected canvas is bounded before allocation; line/character limits apply to raw
     * output before clipping. Token polygons are inverted/clipped to the borrowed source raster,
     * white-margin tokens are discarded and retained line text is rebuilt from actual retained words.
     * Table-detection pixels/topology are not rotated by this decorator; whole-pipeline quarter turns
     * use PdfPartitioner.createOriented instead. Existing line-only factories keep their capability contract.
     * @param provider caller-owned thread-safe actual-word strategy
     * @param clockwiseDegrees finite explicit source-to-corrected angle
     * @param limits per-call source/expanded raster and raw-output limits
     * @return hidden thread-safe word-capable decorator
     * @throws NullPointerException if provider or limits is null
     * @throws IllegalArgumentException if the angle is not finite
     */
    static PdfWordOcrProvider rotated(
            final PdfWordOcrProvider provider, final double clockwiseDegrees, final PdfOcrOptions limits) {
        return new RotatedPdfWordOcrProvider(provider, clockwiseDegrees, limits);
    }

    /**
     * Adds conservative small-angle PAGE deskew while retaining actual-word capability.
     * Uncertain evidence leaves correction zero. TABLE_CELL calls pass directly to the supplied
     * provider with its own limits; no cell raster/table topology correction is inferred.
     * Projection scores and axis-aligned inverse bounds do not establish semantic or OCR accuracy.
     * @param provider caller-owned thread-safe actual-word strategy
     * @param limits PAGE source/expanded raster and raw-output policy
     * @return hidden word-capable decorator without external discovery
     * @throws NullPointerException if provider or limits is null
     */
    static PdfWordOcrProvider deskewed(final PdfWordOcrProvider provider, final PdfOcrOptions limits) {
        return new DeskewedPdfWordOcrProvider(provider, limits);
    }

    /**
     * Recognizes a borrowed raster once, retaining provider line/word encounter order.
     * Implementations enforce their own line/character/process limits before returning this list;
     * nonblank words bound word count by returned character count. Each word must fit its source line,
     * and returned line geometry must fit the raster. Integration validates raster containment separately.
     * @param image borrowed nonnull RGB raster, never modified/flushed/retained
     * @param kind explicit page or padded-cell purpose
     * @return stable nonnull immutable detailed lines in input-pixel coordinates
     * @throws IOException if recognition, resource limits or cancellation prevent output
     */
    List<WordLine> recognizeWords(BufferedImage image, InputKind kind) throws IOException;

    @Override
    default List<Line> recognize(final BufferedImage image) throws IOException {
        return recognize(image, InputKind.PAGE);
    }

    @Override
    default List<Line> recognize(final BufferedImage image, final InputKind kind) throws IOException {
        return recognizeWords(image, Objects.requireNonNull(kind, "kind")).stream().map(WordLine::line).toList();
    }

    /**
     * One actual provider token; geometry is a positive top-left pixel rectangle, not an estimated glyph outline.
     * @param text nonblank recognized token, with case/punctuation unchanged
     * @param left left pixel edge
     * @param top top pixel edge
     * @param right right pixel edge
     * @param bottom bottom pixel edge
     */
    record Word(String text, double left, double top, double right, double bottom) {
        /**
         * Validates token text and positive finite geometry.
         * @throws NullPointerException if text is null
         * @throws IllegalArgumentException if text is blank or bounds are invalid
         */
        public Word {
            Objects.requireNonNull(text, "text");
            if (text.isBlank() || !Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(right)
                    || !Double.isFinite(bottom) || left < 0 || top < 0 || right <= left || bottom <= top) {
                throw new IllegalArgumentException("Invalid OCR word");
            }
        }
    }

    /**
     * Immutable source line and its complete ordered word decomposition. Text equals word texts joined
     * with one space, making later ownership splitting explicit and lossless at the provider-token level.
     * Empty words are legal only for an empty line string. This is not linguistic tokenization or confidence.
     * @param line provider's aggregate line text and enclosing pixel bounds
     * @param words complete provider words in encounter order; defensively copied
     */
    record WordLine(Line line, List<Word> words) {
        /**
         * Snapshots tokens and validates text correspondence and source-envelope containment.
         * @throws NullPointerException if a component or word is null
         * @throws IllegalArgumentException if text or token/line geometry disagrees
         */
        public WordLine {
            Objects.requireNonNull(line, "line");
            words = List.copyOf(words);
            if (!line.text().equals(words.stream().map(Word::text).collect(Collectors.joining(" ")))) {
                throw new IllegalArgumentException("OCR word texts do not reconstruct the source line");
            }
            for (final var word : words) {
                if (word.left() < line.left() || word.top() < line.top() || word.right() > line.right()
                        || word.bottom() > line.bottom()) {
                    throw new IllegalArgumentException("OCR word is outside its source line");
                }
            }
        }
    }
}
