package io.docweave.partition;

import java.util.Arrays;
import java.util.Objects;

/**
 * Lossless PNG crop of a final rendered PDF page region. The raster is the renderer's composited
 * page appearance inside the rectangle: it can include text, vector paint, background, neighboring
 * images and later overpainting. It is not a source image payload, an isolated image asset or proof
 * that any source image is visible; a fully hidden source image can yield a valid background-only crop.
 * Instances are immutable: PNG bytes are copied on construction and on access.
 * @param request original caller request
 * @param png lossless PNG bytes, copied defensively
 * @param width crop width in pixels
 * @param height crop height in pixels
 * @param pixelLeft crop's left pixel column within the complete rendered page raster
 * @param pixelTop crop's top pixel row within the complete rendered page raster
 * @param pageWidthPixels complete rendered page raster width
 * @param pageHeightPixels complete rendered page raster height
 * @param left actual rendered left edge in the request's displayed units
 * @param top actual rendered top edge in the request's displayed units
 * @param right actual rendered right edge in the request's displayed units
 * @param bottom actual rendered bottom edge in the request's displayed units
 * @param clipped whether the request extended beyond the rendered page raster
 * @param pixelsPerUnit raster pixels per unscaled displayed user-space unit, including UserUnit
 * @param dpi physical rendering resolution
 * @param pageRotation normalized clockwise page rotation applied by the renderer
 * @param userUnit physical points per unscaled user-space unit
 * @param background raster background policy
 * @param sampling rendered-pixel sampling policy that produced each output pixel
 * @param renderer renderer identity used for this derivative
 */
public record PdfPageCrop(PdfPageCropRequest request, byte[] png, int width, int height, int pixelLeft, int pixelTop,
        int pageWidthPixels, int pageHeightPixels, double left, double top, double right, double bottom,
        boolean clipped, double pixelsPerUnit, int dpi, int pageRotation, double userUnit,
        PdfPageCropOptions.Background background, PdfPageCropOptions.Sampling sampling, String renderer) {
    /** Stable label for the derivative contract, distinct from source-image payload policies. */
    public static final String POLICY = "rendered_page_region_crop_v1";

    /**
     * Copies PNG bytes and validates internally consistent extents.
     * @throws NullPointerException if a reference component is null
     * @throws IllegalArgumentException if a dimension or extent is inconsistent
     */
    public PdfPageCrop {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(sampling, "sampling");
        Objects.requireNonNull(renderer, "renderer");
        png = Objects.requireNonNull(png, "png").clone();
        if (width < 1 || height < 1 || pixelLeft < 0 || pixelTop < 0 || pageWidthPixels < 1 || pageHeightPixels < 1
                || (long) pixelLeft + width > pageWidthPixels || (long) pixelTop + height > pageHeightPixels
                || !(right > left) || !(bottom > top) || !(pixelsPerUnit > 0) || !Double.isFinite(pixelsPerUnit)
                || !(userUnit > 0) || !Double.isFinite(userUnit) || png.length == 0) {
            throw new IllegalArgumentException("Inconsistent rendered PDF page crop");
        }
    }

    /**
     * Copies the encoded crop for the caller.
     * @return independent PNG bytes
     */
    @Override
    public byte[] png() {
        return png.clone();
    }

    /**
     * Returns the encoded size without copying the payload.
     * @return PNG byte count
     */
    public int pngSize() {
        return png.length;
    }

    /**
     * Returns the payload format.
     * @return {@code image/png}
     */
    public String mimeType() {
        return "image/png";
    }

    /**
     * Returns the derivative contract label.
     * @return {@link #POLICY}
     */
    public String policy() {
        return POLICY;
    }

    /**
     * Compares all components, including PNG content rather than array identity.
     * @param other candidate value
     * @return whether every component is equal
     */
    @Override
    public boolean equals(final Object other) {
        return other instanceof final PdfPageCrop crop && request.equals(crop.request) && Arrays.equals(png, crop.png)
                && width == crop.width && height == crop.height && pixelLeft == crop.pixelLeft
                && pixelTop == crop.pixelTop && pageWidthPixels == crop.pageWidthPixels
                && pageHeightPixels == crop.pageHeightPixels && Double.compare(left, crop.left) == 0
                && Double.compare(top, crop.top) == 0 && Double.compare(right, crop.right) == 0
                && Double.compare(bottom, crop.bottom) == 0 && clipped == crop.clipped
                && Double.compare(pixelsPerUnit, crop.pixelsPerUnit) == 0 && dpi == crop.dpi
                && pageRotation == crop.pageRotation && Double.compare(userUnit, crop.userUnit) == 0
                && background == crop.background && sampling == crop.sampling && renderer.equals(crop.renderer);
    }

    /**
     * Hashes all components, including PNG content.
     * @return content-based hash
     */
    @Override
    public int hashCode() {
        return 31
                * Objects.hash(request, width, height, pixelLeft, pixelTop, pageWidthPixels, pageHeightPixels, left,
                        top, right, bottom, clipped, pixelsPerUnit, dpi, pageRotation, userUnit, background, sampling,
                        renderer)
                + Arrays.hashCode(png);
    }

    /**
     * Describes the crop without printing binary payload content.
     * @return diagnostic string with geometry and PNG size
     */
    @Override
    public String toString() {
        return "PdfPageCrop[request=" + request + ", pngBytes=" + png.length + ", width=" + width + ", height=" + height
                + ", pixelLeft=" + pixelLeft + ", pixelTop=" + pixelTop + ", page=" + pageWidthPixels + "x"
                + pageHeightPixels + ", extent=[" + left + ", " + top + ", " + right + ", " + bottom
                + "], clipped=" + clipped + ", pixelsPerUnit=" + pixelsPerUnit + ", dpi=" + dpi
                + ", rotation=" + pageRotation + ", userUnit=" + userUnit + ", background=" + background
                + ", sampling=" + sampling + ", renderer=" + renderer + "]";
    }
}
