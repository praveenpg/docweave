package io.docweave.layout;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** Internal bounded preprocessing strategy, independent of session ownership and topology inference. */
interface TableImageTransform {
    /**
     * Copies/normalizes a borrowed raster into call-confined model buffers.
     * @param image borrowed RGB/ARGB source, never retained or modified
     * @param options pixel resource policy
     * @param deadline shared monotonic call budget
     * @return normalized single-image inputs and original dimensions
     * @throws IOException if geometry, pixel limits or cancellation/deadline prevent preprocessing
     */
    LayoutPixels prepare(BufferedImage image, OnnxTableOptions options, LayoutDeadline deadline) throws IOException;
}
