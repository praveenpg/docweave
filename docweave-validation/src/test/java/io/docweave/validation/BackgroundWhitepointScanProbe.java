package io.docweave.validation;

import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Evaluation-only local whitepoint hypothesis; may erase legitimate gray fields and is never selected automatically.
 */
final class BackgroundWhitepointScanProbe {
    /** Prevents construction of the bounded raster utility. */
    private BackgroundWhitepointScanProbe() {
    }

    /**
     * Normalizes luminance using bilinearly interpolated95th-percentile tile whitepoints.
     * Tiles span64 pixels at300DPI, scaled by declared source DPI; partial edge tiles use
     * their actual pixels and virtual regular-grid centers. Border interpolation clamps.
     * Original dimensions/coordinates stay fixed, while color and pixel values intentionally change.
     * @param source borrowed source, never mutated or flushed
     * @param maxPixels positive output allocation ceiling
     * @param sourceDpi declared source resolution,70 through600
     * @return caller-owned opaque grayscale RGB image
     * @throws NullPointerException if source is null
     * @throws IllegalArgumentException if resolution or pixel ceiling is invalid
     * @throws InterruptedIOException if cancelled, retaining the caller's interrupt flag
     */
    static BufferedImage apply(final BufferedImage source, final long maxPixels, final int sourceDpi)
            throws InterruptedIOException {
        Objects.requireNonNull(source, "source");
        final int width = source.getWidth();
        final int height = source.getHeight();
        if (sourceDpi < 70 || sourceDpi > 600 || maxPixels < 1 || (long) width * height > maxPixels) {
            throw new IllegalArgumentException("Background probe exceeds source resolution or pixel limits");
        }
        interrupted();
        final int tile = Math.max(1, (int) Math.round(64.0 * sourceDpi / 300));
        final int columns = (width - 1) / tile + 1;
        final int rows = (height - 1) / tile + 1;
        final var whitepoints = new int[Math.multiplyExact(columns, rows)];
        final var histogram = new int[256];
        final var pixels = new int[width];
        for (int ty = 0; ty < rows; ty++) {
            for (int tx = 0; tx < columns; tx++) {
                interrupted();
                Arrays.fill(histogram, 0);
                final int left = tx * tile;
                final int top = ty * tile;
                final int countX = Math.min(tile, width - left);
                final int countY = Math.min(tile, height - top);
                for (int y = top; y < top + countY; y++) {
                    interrupted();
                    source.getRGB(left, y, countX, 1, pixels, 0, countX);
                    for (int x = 0; x < countX; x++) {
                        histogram[luminance(pixels[x])]++;
                    }
                }
                final int rank = (95 * countX * countY + 99) / 100;
                int cumulative = 0;
                int white = 0;
                while (white < 255 && (cumulative += histogram[white]) < rank) {
                    white++;
                }
                whitepoints[ty * columns + tx] = Math.max(1, white);
            }
        }
        final var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < height; y++) {
                interrupted();
                source.getRGB(0, y, width, 1, pixels, 0, width);
                final double gy = Math.max(0, Math.min(rows - 1, (y + 0.5) / tile - 0.5));
                final int firstY = (int) Math.floor(gy);
                final int nextY = Math.min(rows - 1, firstY + 1);
                final double weightY = gy - firstY;
                for (int x = 0; x < width; x++) {
                    final double gx = Math.max(0, Math.min(columns - 1, (x + 0.5) / tile - 0.5));
                    final int firstX = (int) Math.floor(gx);
                    final int nextX = Math.min(columns - 1, firstX + 1);
                    final double weightX = gx - firstX;
                    final double upper = whitepoints[firstY * columns + firstX] * (1 - weightX)
                                         + whitepoints[firstY * columns + nextX] * weightX;
                    final double lower = whitepoints[nextY * columns + firstX] * (1 - weightX)
                                         + whitepoints[nextY * columns + nextX] * weightX;
                    final int white = Math.max(1, (int) Math.round(upper * (1 - weightY) + lower * weightY));
                    final int value = Math.min(255, (luminance(pixels[x]) * 255 + white / 2) / white);
                    pixels[x] = value * 0x010101;
                }
                result.setRGB(0, y, width, 1, pixels, 0, width);
            }
            return result;
        } catch (final RuntimeException | InterruptedIOException failure) {
            result.flush();
            throw failure;
        }
    }

    /**
     * Converts source RGB with fixed integer luminance weights and nearest rounding.
     * @param rgb packed source color
     * @return luminance0 through255
     */
    private static int luminance(final int rgb) {
        return (77 * ((rgb >>> 16) & 255) + 150 * ((rgb >>> 8) & 255) + 29 * (rgb & 255) + 128) >>> 8;
    }

    /**
     * Checks cancellation without consuming the caller's interrupt flag.
     * @throws InterruptedIOException if interrupted
     */
    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Background scan probe interrupted");
        }
    }
}
