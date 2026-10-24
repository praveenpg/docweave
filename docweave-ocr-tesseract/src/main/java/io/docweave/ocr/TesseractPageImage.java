package io.docweave.ocr;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** Call-confined page preparation, borrowing historical inputs and owning only explicit normalized copies. */
final class TesseractPageImage implements AutoCloseable {
    /** Borrowed original source, never flushed. */
    private final BufferedImage source;
    /** Borrowed source or owned same-size output. */
    private final BufferedImage image;

    /**
     * Applies the explicit page policy inside the existing process admission/deadline budget.
     * @param source borrowed raster, already admitted under input-pixel limits
     * @param normalize whether this PAGE call requests local whitepoint normalization
     * @param maxPixels authoritative input/output pixel ceiling
     * @param sourceDpi actual raster resolution, required when normalization is selected
     * @param deadline shared call deadline
     * @throws IOException if normalization is cancelled or exceeds its deadline
     */
    TesseractPageImage(final BufferedImage source, final boolean normalize, final long maxPixels, final int sourceDpi,
            final OcrDeadline deadline) throws IOException {
        this.source = source;
        image = normalize ? TesseractWhitepoint.apply(source, maxPixels, sourceDpi, deadline) : source;
    }

    /**
     * Supplies the same coordinate frame to existing PNG encoding.
     * @return borrowed or owned prepared raster
     */
    BufferedImage image() {
        return image;
    }

    /** Flushes only a copy owned by this call, including when later encoding/process/parsing fails. */
    @Override
    public void close() {
        if (image != source) {
            image.flush();
        }
    }
}
