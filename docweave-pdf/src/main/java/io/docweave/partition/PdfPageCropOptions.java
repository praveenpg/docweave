package io.docweave.partition;

import java.util.Objects;

/**
 * Explicit resolution, background and resource ceilings for rendered PDF page-region crops.
 * Every limit is checked from page geometry before the corresponding raster is allocated, except
 * the encoded-byte budget, which is charged during bounded PNG encoding. These limits do not bound
 * PDFBox parser/font/decoder intermediates or interrupt an uncooperative renderer call. Unlike
 * {@link PdfOptions}, these options never change partition output or source-image payloads.
 * @param dpi physical rendering resolution, 36 through 600 dots per inch; PDF UserUnit is applied
 * @param background lossless raster background policy
 * @param maxInputBytes maximum encoded PDF source bytes read for one call
 * @param maxRequests maximum crop requests in one call
 * @param maxPages maximum distinct pages rendered in one call
 * @param maxPagePixels maximum pixels in one complete rendered page raster; PDFRenderer additionally refuses
 *                      rasters above {@link Integer#MAX_VALUE} pixels, which are rejected as IMAGE_LIMIT
 * @param maxTotalPagePixels maximum complete page-raster pixels rendered across one call
 * @param maxCropPixels maximum pixels in one returned crop
 * @param maxEncodedBytes maximum cumulative PNG bytes returned by one call
 * @param sampling how rendered page pixels become output pixels; page-pixel limits apply to the raster
 *                 actually rendered, including supersampled rasters
 */
public record PdfPageCropOptions(int dpi, Background background, int maxInputBytes, int maxRequests, int maxPages,
        long maxPagePixels, long maxTotalPagePixels, long maxCropPixels, int maxEncodedBytes, Sampling sampling) {
    /** Lossless page-raster backgrounds; both select PDFBox's own page compositing. */
    public enum Background {
        /**
         * Opaque RGB raster initialized to white, matching an ordinary paper-colored page view.
         * Unpainted and fully transparent page content therefore yields white pixels.
         */
        WHITE,
        /**
         * ARGB raster initialized to fully transparent black. Unpainted page areas keep alpha zero;
         * PDFBox composites page content over that transparent backdrop rather than over white.
         */
        TRANSPARENT
    }

    /**
     * Rendered-pixel sampling policies. Both keep the same output pixel grid, request rectangles,
     * reported extents and metadata; only how each output pixel's color is obtained differs.
     */
    public enum Sampling {
        /** One PDFRenderer pixel per output pixel at the requested resolution; the historical behavior. */
        RENDERER,
        /**
         * Renders the page at exactly four times the output scale and averages each 4x4 block, with
         * alpha-weighted color, into one output pixel. Java2D applies non-rectangular clip paths,
         * including glyph clips, without antialiasing, so their edges are hard at one sample per pixel;
         * averaging sixteen samples gives those edges fractional coverage. The rendered raster has
         * about sixteen times the pixels and is charged against the page-pixel limits. This is a fixed
         * box filter, not a renderer-independent coverage guarantee. For a page axis smaller than one
         * output pixel, samples beyond the actual rendered raster use the selected background; no
         * larger padded page raster is allocated and source edge colors are not repeated.
         */
        BOX_4X;

        /**
         * Returns the per-axis supersampling factor.
         * @return 1 for RENDERER, 4 for BOX_4X
         */
        int factor() {
            return this == BOX_4X ? 4 : 1;
        }
    }

    /**
     * Validates immutable bounds without opening or rendering a document.
     * @throws NullPointerException if background or sampling is null
     * @throws IllegalArgumentException if resolution is outside 36 through 600 or a limit is not positive,
     *                                  or a single page/crop limit exceeds its aggregate counterpart
     */
    public PdfPageCropOptions {
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(sampling, "sampling");
        if (dpi < 36 || dpi > 600 || maxInputBytes < 1 || maxInputBytes == Integer.MAX_VALUE || maxRequests < 1
                || maxPages < 1 || maxPagePixels < 1 || maxTotalPagePixels < maxPagePixels || maxCropPixels < 1
                || maxCropPixels > maxPagePixels || maxEncodedBytes < 1) {
            throw new IllegalArgumentException("Invalid PDF page crop policy");
        }
    }

    /**
     * Creates a policy with {@link Sampling#RENDERER} sampling.
     * @param dpi physical rendering resolution, 36 through 600
     * @param background raster background policy
     * @param maxInputBytes maximum encoded source bytes
     * @param maxRequests maximum requests per call
     * @param maxPages maximum distinct rendered pages per call
     * @param maxPagePixels maximum pixels in one rendered page raster
     * @param maxTotalPagePixels maximum rendered page pixels per call
     * @param maxCropPixels maximum pixels in one crop
     * @param maxEncodedBytes maximum cumulative PNG bytes per call
     * @throws NullPointerException if background is null
     * @throws IllegalArgumentException if a value is outside its documented range
     */
    public PdfPageCropOptions(final int dpi, final Background background, final int maxInputBytes,
            final int maxRequests, final int maxPages, final long maxPagePixels, final long maxTotalPagePixels,
            final long maxCropPixels, final int maxEncodedBytes) {
        this(dpi, background, maxInputBytes, maxRequests, maxPages, maxPagePixels, maxTotalPagePixels, maxCropPixels,
                maxEncodedBytes, Sampling.RENDERER);
    }

    /**
     * Returns bounded defaults: 150 DPI, white background, 10 MiB input, 64 requests, 16 pages,
     * 16M pixels per page, 64M rendered page pixels per call, 16M pixels per crop, 32 MiB of PNG output and
     * {@link Sampling#RENDERER} sampling.
     * @return immutable default policy; larger budgets require explicit selection
     */
    public static PdfPageCropOptions defaults() {
        return new PdfPageCropOptions(
                150, Background.WHITE, 10 * 1024 * 1024, 64, 16, 16_000_000, 64_000_000, 16_000_000, 32 * 1024 * 1024);
    }

    /**
     * Copies this policy with another physical resolution.
     * @param value 36 through 600 dots per inch
     * @return independent immutable policy
     * @throws IllegalArgumentException if the resolution is unsupported
     */
    public PdfPageCropOptions withDpi(final int value) {
        return new PdfPageCropOptions(value, background, maxInputBytes, maxRequests, maxPages, maxPagePixels,
                maxTotalPagePixels, maxCropPixels, maxEncodedBytes, sampling);
    }

    /**
     * Copies this policy with another rendered-pixel sampling policy.
     * @param value nonnull sampling policy
     * @return independent immutable policy
     * @throws NullPointerException if value is null
     */
    public PdfPageCropOptions withSampling(final Sampling value) {
        return new PdfPageCropOptions(dpi, background, maxInputBytes, maxRequests, maxPages, maxPagePixels,
                maxTotalPagePixels, maxCropPixels, maxEncodedBytes, value);
    }

    /**
     * Copies this policy with another raster background.
     * @param value nonnull background policy
     * @return independent immutable policy
     * @throws NullPointerException if value is null
     */
    public PdfPageCropOptions withBackground(final Background value) {
        return new PdfPageCropOptions(dpi, value, maxInputBytes, maxRequests, maxPages, maxPagePixels,
                maxTotalPagePixels, maxCropPixels, maxEncodedBytes, sampling);
    }
}
