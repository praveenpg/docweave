package io.docweave.validation;

import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Objects;

/** Test-only fixed median hypothesis; no production preprocessing or accuracy promise. */
final class MedianScanProbe {
    /** Prevents utility construction. */
    private MedianScanProbe() {
    }

    /**
     * Produces independent RGB pixels using per-channel3x3 medians with replicated original borders.
     * Dimensions and pixel coordinates stay fixed; thin source strokes may be removed along with noise.
     * @param source borrowed original raster, never mutated or flushed
     * @param maxPixels positive allocation ceiling
     * @return caller-owned filtered image
     * @throws NullPointerException if source is null
     * @throws IllegalArgumentException if bounds exceed the pixel ceiling
     * @throws InterruptedIOException if cancelled; interrupt flag remains set and partial output is flushed
     */
    static BufferedImage apply(final BufferedImage source, final long maxPixels) throws InterruptedIOException {
        Objects.requireNonNull(source, "source");
        final int width = source.getWidth();
        final int height = source.getHeight();
        if (maxPixels < 1 || (long) width * height > maxPixels) {
            throw new IllegalArgumentException("Median probe exceeds pixel budget");
        }
        checkInterrupted();
        final var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try {
            final var rows = new int[3][width];
            final var output = new int[width];
            final var red = new int[9];
            final var green = new int[9];
            final var blue = new int[9];
            for (int y = 0; y < height; y++) {
                checkInterrupted();
                for (int dy = -1; dy <= 1; dy++) {
                    source.getRGB(0, Math.max(0, Math.min(height - 1, y + dy)), width, 1, rows[dy + 1], 0, width);
                }
                for (int x = 0; x < width; x++) {
                    int count = 0;
                    boolean gray = true;
                    for (final var row : rows) {
                        for (int dx = -1; dx <= 1; dx++) {
                            final int rgb = row[Math.max(0, Math.min(width - 1, x + dx))];
                            red[count] = (rgb >>> 16) & 255;
                            green[count] = (rgb >>> 8) & 255;
                            blue[count] = rgb & 255;
                            gray &= red[count] == green[count] && red[count] == blue[count];
                            count++;
                        }
                    }
                    Arrays.sort(red);
                    if (gray) {
                        output[x] = red[4] * 0x010101;
                    } else {
                        Arrays.sort(green);
                        Arrays.sort(blue);
                        output[x] = (red[4] << 16) | (green[4] << 8) | blue[4];
                    }
                }
                result.setRGB(0, y, width, 1, output, 0, width);
            }
            return result;
        } catch (final RuntimeException | InterruptedIOException failure) {
            result.flush();
            throw failure;
        }
    }

    /**
     * Cooperatively cancels without clearing the caller's interrupt flag.
     * @throws InterruptedIOException if interrupted
     */
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Median scan probe interrupted");
        }
    }
}
