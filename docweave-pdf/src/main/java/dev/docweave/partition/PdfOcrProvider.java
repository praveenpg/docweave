package dev.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/**
 * Optional synchronous OCR strategy for rendered PDF pages or explicitly masked image regions.
 * Implementations shared by a partitioner must support concurrent calls, honor interruption and
 * enforce their own process, timeout, language and allocation policies. The caller owns the image:
 * do not modify, flush or retain it after returning. No provider is discovered or executed by default.
 * The returned list must remain stable after return. Returned lines use top-left pixel coordinates in the supplied
 * image and provider reading order. PDF integration validates output before emitting it; this cannot bound allocations
 * inside a provider.
 */
@FunctionalInterface
public interface PdfOcrProvider {
    /**
     * Adds explicit integer nearest-neighbor magnification before line OCR and restores source coordinates.
     * Both borrowed source and enlarged raster must fit the per-call pixel ceiling before allocation or
     * recognition. Raw line/UTF-16 budgets apply before inversion; PAGE/CELL purpose is forwarded unchanged.
     * Source pixels remain unchanged, and the owned enlarged raster is released after the call. Multiplier 1
     * borrows the original raster. This factory exposes line capability only; use the corresponding
     * PdfWordOcrProvider factory when actual word geometry is required. Resampling adds no source detail
     * and can worsen recognition. No engine/model is discovered or executed during construction.
     * @param provider nonnull caller-owned thread-safe line strategy
     * @param multiplier integer scale from 1 through 8
     * @param limits nonnull per-call pixel/line/character limits; other OCR settings are unused here
     * @return hidden reusable line-only decorator
     * @throws NullPointerException if provider or limits is null
     * @throws IllegalArgumentException if multiplier is outside 1 through 8
     */
    static PdfOcrProvider upscaled(final PdfOcrProvider provider, final int multiplier, final PdfOcrOptions limits) {
        return new UpscaledPdfOcrProvider(provider, multiplier, limits);
    }

    /**
     * Wraps an explicitly selected provider with a fixed clockwise raster correction. Quarter turns
     * are exact; other angles resample onto an expanded white canvas. Returned envelopes are mapped
     * back into the original borrowed raster, so existing PDF crop/rotation/cell ownership stays valid.
     * The correction applies to PAGE and TABLE_CELL calls and does not rotate table-detection geometry.
     * It does not estimate skew/orientation, discover models or invoke a provider until recognition.
     *
     * @param provider caller-owned thread-safe provider
     * @param clockwiseDegrees finite correction in degrees; normalized modulo360
     * @param limits explicit per-call raster/line/character ceilings; other OCR options are unused here
     * @return stateless decorator, safe for concurrent calls if the underlying provider is safe
     * @throws NullPointerException if provider or limits is null
     * @throws IllegalArgumentException if angle is not finite
     */
    static PdfOcrProvider rotated(
            final PdfOcrProvider provider, final double clockwiseDegrees, final PdfOcrOptions limits) {
        return new RotatedPdfOcrProvider(provider, clockwiseDegrees, limits);
    }

    /**
     * Adds conservative automatic small-angle projection correction to PAGE calls. TABLE_CELL calls
     * retain the existing cell frame and go directly to the supplied provider. Accepted corrections
     * use the same inverse geometry/ownership contract as {@link #rotated}; ambiguous/insufficient
     * evidence leaves the borrowed input frame unchanged. This does not estimate quarter-turn
     * orientation or deskew scanned table-detection geometry. Inspect {@link PdfDeskewEstimator}
     * separately when application-level measurement provenance is required.
     * @param provider explicitly selected thread-safe provider
     * @param limits per-call raster and output policy
     * @return stateless, opt-in decorator without automatic provider/model discovery
     * @throws NullPointerException if provider or limits is null
     */
    static PdfOcrProvider deskewed(final PdfOcrProvider provider, final PdfOcrOptions limits) {
        return new DeskewedPdfOcrProvider(provider, limits);
    }

    /**
     * Recognizes a borrowed page raster.
     * @param image nonnull RGB raster, valid only during this call
     * @return nonnull lines, with nonnull entries; an empty list represents no recognized text
     * @throws IOException if recognition fails; interruption should use InterruptedIOException
     */
    List<Line> recognize(BufferedImage image) throws IOException;

    /**
     * Recognizes a borrowed raster with an explicit layout purpose. The default delegates to
     * {@link #recognize(BufferedImage)}, preserving existing providers and lambda implementations.
     * Overrides may select segmentation per call; they must retain the same ownership, concurrency,
     * output-coordinate and interruption contract. No mutable per-call configuration is shared.
     * @param image nonnull RGB raster, valid only during this call
     * @param kind nonnull purpose; cell rasters have white padding, and provider-layout crops may retain boundary rules
     * @return stable nonnull recognized lines in the supplied raster's pixel frame
     * @throws IOException if recognition fails; interruption should use InterruptedIOException
     * @throws NullPointerException if kind is null
     */
    default List<Line> recognize(final BufferedImage image, final InputKind kind) throws IOException {
        java.util.Objects.requireNonNull(kind, "kind");
        return recognize(image);
    }

    /** Purpose supplied by PDF integration, without imposing an OCR engine or segmentation algorithm. */
    enum InputKind {
        /** Whole page or native-masked image regions in a full-page coordinate frame. */
        PAGE,
        /** One padded source-cell crop, possibly with boundary rules, multiple text lines or no text. */
        TABLE_CELL
    }

    /**
     * Immutable recognized line. Coordinates must describe a positive rectangle inside the input raster.
     * @param text nonnull Unicode text, with no inferred table semantics
     * @param left left pixel edge
     * @param top top pixel edge
     * @param right right pixel edge
     * @param bottom bottom pixel edge
     */
    record Line(String text, double left, double top, double right, double bottom) {
        /**
         * Validates text and finite, positive geometry; raster containment is checked by the caller.
         * @throws NullPointerException if text is null
         * @throws IllegalArgumentException if coordinates are invalid
         */
        public Line {
            java.util.Objects.requireNonNull(text, "text");
            if (!Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(right) || !Double.isFinite(bottom)
                    || left < 0 || top < 0 || right <= left || bottom <= top) {
                throw new IllegalArgumentException("Invalid OCR line bounds");
            }
        }
    }
}
