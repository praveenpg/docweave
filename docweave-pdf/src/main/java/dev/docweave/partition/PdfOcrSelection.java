package dev.docweave.partition;

import java.io.IOException;

/** Pixel-frame ownership strategy shared by ordinary and explicitly oriented OCR/table stages. */
interface PdfOcrSelection {
    /**
     * Tests whether a whole table/token envelope belongs to an eligible mixed-page image region.
     * @param line candidate bounds in the stage's input frame
     * @return whether the complete candidate is owned
     * @throws IOException if cancellation or region-work limits fail
     */
    boolean owns(PdfOcrProvider.Line line) throws IOException;

    /**
     * Filters ordinary OCR text against mixed-page image/native exclusions.
     * @param line source text envelope in the stage's input frame
     * @return whether the line may be emitted
     * @throws IOException if cancellation or region-work limits fail
     */
    boolean retain(PdfOcrProvider.Line line) throws IOException;
}
