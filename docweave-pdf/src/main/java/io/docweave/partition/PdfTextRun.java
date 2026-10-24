package io.docweave.partition;

/**
 * One nonblank native text run after baseline grouping and large-gap splitting.
 * @param text extracted text
 * @param left crop-relative left edge
 * @param top approximate glyph top
 * @param right right edge
 * @param bottom baseline/bottom
 * @param rotation clockwise displayed text rotation; nonzero text is a recognition barrier
 */
record PdfTextRun(String text, float left, float top, float right, float bottom, int rotation) {
    /**
     * Retains horizontal-run construction for internal callers.
     * @param text native text
     * @param left left edge
     * @param top top edge
     * @param right right edge
     * @param bottom baseline/bottom
     */
    PdfTextRun(final String text, final float left, final float top, final float right, final float bottom) {
        this(text, left, top, right, bottom, 0);
    }
}
