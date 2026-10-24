package io.docweave.partition;

/**
 * Convenience PDF routing presets, independent of engine installation and resource ownership.
 * Names describe DocWeave contracts, not output parity with similarly named upstream strategies.
 * Existing low-level factories remain available for custom ownership, orientation and table policies.
 */
public enum PdfStrategy {
    /** Native text/tables and source images only; never renders pages for OCR or invokes supplied providers. */
    FAST,
    /**
     * Prefers native text per page and uses a supplied OCR provider only on textless pages.
     * Without a provider this retains native extraction and textless-page diagnostics.
     * Does not assess native text quality, infer language, discover models or retry failures.
     */
    AUTO,
    /**
     * Recognizes every page with actual word OCR and explicit provider table geometry.
     * Defaults to300DPI unless OCR options are supplied; native text is not emitted separately.
     * Requires both a PdfWordOcrProvider and PdfTableLayoutProvider at construction.
     * This is table-layout assistance, not semantic figure/title/caption classification.
     */
    HI_RES,
    /**
     * Recognizes every rendered page, replacing native text/table emission while retaining source images.
     * Requires an explicit OCR provider; default resolution is150DPI and ordinary OCR emits lines.
     * Recognition failures propagate without falling back to native text.
     */
    OCR_ONLY
}
