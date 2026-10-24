package io.docweave.partition;

import java.util.Objects;

/**
 * One explicitly requested rectangle of a final rendered PDF page.
 * Coordinates use the same displayed frame as partition element coordinates: crop-box-relative,
 * top-left origin, x rightward and y downward, with declared page quarter-turn rotation already applied.
 * A rectangle partly outside the displayed page is intersected with the rendered page raster;
 * a rectangle with no rendered pixels is rejected during rendering. The request carries no element
 * identity and does not select, isolate or reconstruct a source image.
 * @param pageNumber one-based physical page number
 * @param left displayed left edge
 * @param top displayed top edge
 * @param right displayed right edge, strictly greater than left
 * @param bottom displayed bottom edge, strictly greater than top
 * @param units coordinate scale of the four edges
 */
public record PdfPageCropRequest(int pageNumber, double left, double top, double right, double bottom, Units units) {
    /** Supported displayed-frame scales; physical pixels are deliberately not accepted as input. */
    public enum Units {
        /**
         * Unscaled PDF user-space units, as reported by the {@code PDFUserSpace} coordinate system.
         * The page's UserUnit is not applied to these values.
         */
        USER_SPACE,
        /**
         * Physical points (1/72 inch), as reported by the {@code PDFPoints} coordinate system.
         * Values are divided by the page's UserUnit before mapping to the rendered raster.
         */
        POINTS
    }

    /**
     * Validates a finite, positively sized rectangle without opening a document.
     * @throws NullPointerException if units is null
     * @throws IllegalArgumentException if the page is not positive, an edge is NaN or infinite,
     *                                  or the rectangle is empty or reversed
     */
    public PdfPageCropRequest {
        Objects.requireNonNull(units, "units");
        if (pageNumber < 1) {
            throw new IllegalArgumentException("PDF crop page number must be positive");
        }
        if (!Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(right) || !Double.isFinite(bottom)) {
            throw new IllegalArgumentException("PDF crop edges must be finite");
        }
        if (right <= left || bottom <= top) {
            throw new IllegalArgumentException("PDF crop rectangle must have positive width and height");
        }
    }

    /**
     * Requests a rectangle in unscaled displayed user space.
     * @param pageNumber one-based physical page number
     * @param left displayed left edge
     * @param top displayed top edge
     * @param right displayed right edge
     * @param bottom displayed bottom edge
     * @throws IllegalArgumentException if the page or rectangle is invalid
     */
    public PdfPageCropRequest(
            final int pageNumber, final double left, final double top, final double right, final double bottom) {
        this(pageNumber, left, top, right, bottom, Units.USER_SPACE);
    }
}
