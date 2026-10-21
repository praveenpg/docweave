package dev.docweave.partition;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** Shared owned OCR rotation canvas and source-polygon clipping for line and actual-word decorators. */
final class PdfOcrRotation implements AutoCloseable {
    /** Borrowed original raster; never modified or flushed. */
    private final BufferedImage source;
    /** Original raster envelope, excluding white expansion added by rotation. */
    private final Rectangle2D sourceBounds;
    /** Exact inverse of the correction/translation used to paint the owned canvas. */
    private final AffineTransform inverse;
    /** Owned same-pixel-density rotation canvas. */
    private final BufferedImage corrected;

    /**
     * Preflights original and expanded dimensions before allocating one bounded RGB canvas.
     * @param source borrowed input
     * @param degrees validated finite normalized clockwise correction
     * @param limits source/expanded pixel policy
     * @throws IOException if canvas limits, transform creation or cancellation fail
     */
    PdfOcrRotation(final BufferedImage source, final double degrees, final PdfOcrOptions limits) throws IOException {
        this.source = source;
        ElementCollector.checkInterrupted();
        reserve(source.getWidth(), source.getHeight(), limits);
        final var rotation = degrees % 90 == 0 ? AffineTransform.getQuadrantRotateInstance((int) (degrees / 90))
                                               : AffineTransform.getRotateInstance(Math.toRadians(degrees));
        sourceBounds = new Rectangle2D.Double(0, 0, source.getWidth(), source.getHeight());
        final var bounds = rotation.createTransformedShape(sourceBounds).getBounds2D();
        final double width = Math.ceil(bounds.getWidth());
        final double height = Math.ceil(bounds.getHeight());
        reserve(width, height, limits);
        final var transform = AffineTransform.getTranslateInstance(-bounds.getMinX(), -bounds.getMinY());
        transform.concatenate(rotation);
        try {
            inverse = transform.createInverse();
        } catch (final NoninvertibleTransformException failure) {
            throw new IOException("Invalid OCR correction transform", failure);
        }
        corrected = new BufferedImage((int) width, (int) height, BufferedImage.TYPE_INT_RGB);
        try {
            final var graphics = corrected.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, corrected.getWidth(), corrected.getHeight());
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        degrees % 90 == 0 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                                          : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, transform, null);
            } finally {
                graphics.dispose();
            }
            ElementCollector.checkInterrupted();
        } catch (final IOException | RuntimeException | Error failure) {
            corrected.flush();
            throw failure;
        }
    }

    /**
     * Returns the synchronous borrowed view of this helper's owned correction canvas.
     * @return corrected RGB input, valid until close
     */
    BufferedImage image() {
        return corrected;
    }

    /**
     * Inverts one valid corrected rectangle and clips its polygon before taking source-axis bounds.
     * @param line positive bounds contained by the corrected canvas
     * @return original-frame rectangle, or null if entirely in added white margins
     * @throws IOException if cancellation prevents mapping
     */
    PdfOcrProvider.Line toSource(final PdfOcrProvider.Line line) throws IOException {
        ElementCollector.checkInterrupted();
        final var polygon = new Area(inverse.createTransformedShape(new Rectangle2D.Double(
                line.left(), line.top(), line.right() - line.left(), line.bottom() - line.top())));
        polygon.intersect(new Area(sourceBounds));
        if (polygon.isEmpty()) {
            return null;
        }
        final var box = polygon.getBounds2D();
        return new PdfOcrProvider.Line(line.text(), Math.max(0, box.getMinX()), Math.max(0, box.getMinY()),
                Math.min(source.getWidth(), box.getMaxX()), Math.min(source.getHeight(), box.getMaxY()));
    }

    /**
     * Rejects source/expanded canvas dimensions before allocation.
     * @param width prospective positive pixel width
     * @param height prospective positive pixel height
     * @param limits immutable raster policy
     * @throws PartitionException if any dimension or pixel count exceeds policy
     */
    private static void reserve(final double width, final double height, final PdfOcrOptions limits)
            throws PartitionException {
        if (width < 1 || height < 1 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE
                || !Double.isFinite(width * height) || width * height > limits.maxRenderPixels()) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "OCR correction exceeds maxRenderPixels");
        }
    }

    /** Releases only the owned corrected canvas, including recognition/validation failure paths. */
    @Override
    public void close() {
        corrected.flush();
    }
}
