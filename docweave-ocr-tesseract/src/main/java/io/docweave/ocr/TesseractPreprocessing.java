package io.docweave.ocr;

/** Explicit same-size page-raster preparation, independent of engine thresholding and never selected automatically. */
public enum TesseractPreprocessing {
    /** Keeps historical page and cell raster preparation. */
    NONE,
    /**
     * Normalizes page luminance against bilinearly interpolated local95th-percentile whitepoints.
     * Tiles span64 pixels at300DPI, scaled by explicit source resolution. Alpha composites over white;
     * output is grayscale. Dimensions and returned OCR coordinates are unchanged. TABLE_CELL calls
     * retain their existing preparation. May help uneven illumination, worsen recognition, erase
     * legitimate gray fields and remove color; use only when these tradeoffs suit the supplied pages.
     */
    LOCAL_WHITEPOINT
}
