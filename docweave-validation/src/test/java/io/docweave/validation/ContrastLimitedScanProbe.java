package io.docweave.validation;

import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Objects;

/** Evaluation-only contrast-limited local histograms; uniform tiles retain their intensity and no default changes. */
final class ContrastLimitedScanProbe {
    /** Prevents construction of this fixed-protocol raster experiment. */
    private ContrastLimitedScanProbe() {
    }

    /**
     * Clips tile histograms at twice their mean bin population and bilinearly blends cumulative lookup maps.
     * Tiles span64pixels at300DPI, scaled by declared source resolution. Actual partial-edge tile populations
     * determine clipping and normalization; virtual regular-grid centers are clamped at page boundaries.
     * Uniform tiles use identity maps, deliberately differing from some CLAHE implementations. Alpha is
     * composited over white before fixed integer luminance. Source pixels and dimensions remain unchanged.
     * @param source borrowed original raster
     * @param maxPixels positive output pixel ceiling
     * @param sourceDpi actual source resolution,70 through600
     * @return caller-owned opaque grayscale raster in the original frame
     * @throws NullPointerException if source is absent
     * @throws IllegalArgumentException if source resolution, pixels or bounded lookup allocation is invalid
     * @throws InterruptedIOException if cancelled without consuming the interrupt flag
     */
    static BufferedImage apply(final BufferedImage source, final long maxPixels, final int sourceDpi)
            throws InterruptedIOException {
        Objects.requireNonNull(source, "source");
        final int width = source.getWidth(), height = source.getHeight();
        if (sourceDpi < 70 || sourceDpi > 600 || maxPixels < 1 || (long) width * height > maxPixels) {
            throw new IllegalArgumentException("Contrast probe exceeds source resolution or pixel bounds");
        }
        interrupted();
        final int tile = Math.max(1, (int) Math.round(64.0 * sourceDpi / 300));
        final int columns = (width - 1) / tile + 1, rows = (height - 1) / tile + 1;
        final long entries = (long) columns * rows * 256;
        if (entries > 4_194_304) {
            throw new IllegalArgumentException("Contrast probe exceeds fixed lookup allocation bound");
        }
        final var maps = new int[(int) entries];
        final var histogram = new int[256];
        final var pixels = new int[width];
        for (int ty = 0; ty < rows; ty++) {
            for (int tx = 0; tx < columns; tx++) {
                interrupted();
                Arrays.fill(histogram, 0);
                final int left = tx * tile, top = ty * tile;
                final int countX = Math.min(tile, width - left), countY = Math.min(tile, height - top);
                for (int y = top; y < top + countY; y++) {
                    interrupted();
                    source.getRGB(left, y, countX, 1, pixels, 0, countX);
                    for (int x = 0; x < countX; x++) {
                        histogram[luminance(pixels[x])]++;
                    }
                }
                lookup(histogram, countX * countY, maps, (ty * columns + tx) * 256);
            }
        }
        final var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < height; y++) {
                interrupted();
                source.getRGB(0, y, width, 1, pixels, 0, width);
                final double gy = Math.clamp((y + .5) / tile - .5, 0, rows - 1);
                final int firstY = (int) Math.floor(gy), nextY = Math.min(rows - 1, firstY + 1);
                final double weightY = gy - firstY;
                for (int x = 0; x < width; x++) {
                    if ((x & 255) == 0) {
                        interrupted();
                    }
                    final double gx = Math.clamp((x + .5) / tile - .5, 0, columns - 1);
                    final int firstX = (int) Math.floor(gx), nextX = Math.min(columns - 1, firstX + 1);
                    final double weightX = gx - firstX;
                    final int gray = luminance(pixels[x]);
                    final double upper = maps[(firstY * columns + firstX) * 256 + gray] * (1 - weightX)
                                         + maps[(firstY * columns + nextX) * 256 + gray] * weightX;
                    final double lower = maps[(nextY * columns + firstX) * 256 + gray] * (1 - weightX)
                                         + maps[(nextY * columns + nextX) * 256 + gray] * weightX;
                    pixels[x] = (int) Math.round(upper * (1 - weightY) + lower * weightY) * 0x010101;
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
     * Builds one bounded monotonic cumulative lookup, preserving histogram population under redistribution.
     * @param histogram original256-bin tile histogram, mutated only within this call
     * @param count actual positive tile population
     * @param maps owned flattened output maps
     * @param offset output tile offset
     */
    private static void lookup(final int[] histogram, final int count, final int[] maps, final int offset) {
        if (Arrays.stream(histogram).anyMatch(value -> value == count)) {
            for (int value = 0; value < 256; value++) {
                maps[offset + value] = value;
            }
            return;
        }
        final int limit = Math.max(1, count * 2 / 256);
        int excess = 0;
        for (int index = 0; index < 256; index++) {
            if (histogram[index] > limit) {
                excess += histogram[index] - limit;
                histogram[index] = limit;
            }
        }
        final int batch = excess / 256;
        int residual = excess % 256;
        for (int index = 0; index < 256; index++) {
            histogram[index] += batch;
        }
        if (residual > 0) {
            final int step = Math.max(256 / residual, 1);
            for (int index = 0; residual > 0; index += step, residual--) {
                histogram[index]++;
            }
        }
        int cumulative = 0;
        for (int value = 0; value < 256; value++) {
            cumulative += histogram[value];
            maps[offset + value] = (int) Math.round(cumulative * 255.0 / count);
        }
        if (cumulative != count) {
            throw new IllegalStateException("Contrast histogram population changed");
        }
    }

    /**
     * Composites straight-alpha source RGB over white, then applies fixed integer luminance weights.
     * @param argb original packed sample
     * @return opaque grayscale intensity0through255
     */
    private static int luminance(final int argb) {
        final int alpha = argb >>> 24;
        final int white = 255 * (255 - alpha);
        final int red = (((argb >>> 16) & 255) * alpha + white + 127) / 255;
        final int green = (((argb >>> 8) & 255) * alpha + white + 127) / 255;
        final int blue = ((argb & 255) * alpha + white + 127) / 255;
        return (77 * red + 150 * green + 29 * blue + 128) >>> 8;
    }

    /**
     * Checks cooperative cancellation without clearing the caller's flag.
     * @throws InterruptedIOException if the current thread was interrupted
     */
    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Contrast probe interrupted");
        }
    }
}
