package io.docweave.partition;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;

/** Call-confined integer magnification with preflight and borrowed-source ownership. */
final class PdfOcrScaling implements AutoCloseable {
    /** Original-to-recognition integer ratio. */
    private final int multiplier;
    /** Borrowed original or owned enlarged recognition raster. */
    private final BufferedImage image;
    /** Whether this frame must release the recognition raster. */
    private final boolean owned;

    /**
     * Checks source/enlarged geometry before allocation and prepares nearest-neighbor source pixels.
     * Java2D drawing is checked for interruption before and after its bounded operation.
     * @param source nonnull borrowed raster, never modified or flushed
     * @param multiplier explicit integer ratio
     * @param limits nonnull per-call pixel policy
     * @throws IOException if pixel limits or cancellation prevent preparation
     * @throws NullPointerException if source or limits is null
     * @throws IllegalArgumentException if multiplier is outside 1 through 8
     */
    PdfOcrScaling(final BufferedImage source, final int multiplier, final PdfOcrOptions limits) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        checkMultiplier(multiplier);
        ElementCollector.checkInterrupted();
        final long width = (long) source.getWidth() * multiplier;
        final long height = (long) source.getHeight() * multiplier;
        if (source.getWidth() < 1 || source.getHeight() < 1
                || (long) source.getWidth() * source.getHeight() > limits.maxRenderPixels() || width > Integer.MAX_VALUE
                || height > Integer.MAX_VALUE || width > limits.maxRenderPixels() / height) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "OCR magnification exceeds raster limits");
        }
        this.multiplier = multiplier;
        if (multiplier == 1) {
            image = source;
            owned = false;
            return;
        }
        final var target = new BufferedImage((int) width, (int) height, BufferedImage.TYPE_INT_RGB);
        boolean prepared = false;
        try {
            final var graphics = target.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
                graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                graphics.drawImage(source, 0, 0, target.getWidth(), target.getHeight(), null);
            } finally {
                graphics.dispose();
            }
            ElementCollector.checkInterrupted();
            prepared = true;
        } finally {
            if (!prepared) {
                target.flush();
            }
        }
        image = target;
        owned = true;
    }

    /**
     * Validates immutable policy before constructing a decorator or allocating a raster.
     * @param multiplier explicit integer ratio
     * @throws IllegalArgumentException if multiplier is outside 1 through 8
     */
    static void checkMultiplier(final int multiplier) {
        if (multiplier < 1 || multiplier > 8) {
            throw new IllegalArgumentException("OCR multiplier must be from 1 through 8");
        }
    }

    /**
     * Supplies the borrowed/owned recognition frame for this synchronous call only.
     * @return nonnull current-frame raster
     */
    BufferedImage image() {
        return image;
    }

    /**
     * Inverts exact integer scaling without trimming text or manufacturing token geometry.
     * @param line validated recognition-frame region
     * @return source-frame region with the same text
     * @throws PartitionException if floating-point inversion cannot represent positive geometry
     */
    PdfOcrProvider.Line toSource(final PdfOcrProvider.Line line) throws PartitionException {
        try {
            return new PdfOcrProvider.Line(line.text(), line.left() / multiplier, line.top() / multiplier,
                    line.right() / multiplier, line.bottom() / multiplier);
        } catch (final IllegalArgumentException failure) {
            throw new PartitionException(PartitionException.Code.OCR_FAILURE,
                    "Magnified OCR geometry cannot be represented in the source frame", failure);
        }
    }

    /** Releases only the owned enlarged raster, including after provider failure or cancellation. */
    @Override
    public void close() {
        if (owned) {
            image.flush();
        }
    }
}
