package io.docweave.layout;

import io.docweave.partition.PartitionException;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Arrays;

/** Fixed exported YOLOX RGB preprocessing, with deadline checks and unchanged borrowed source pixels. */
final class YoloXImageTransform {
    /** Published fixed network height. */
    static final int HEIGHT = 1024;
    /** Published fixed network width. */
    static final int WIDTH = 768;

    /** Prevents utility construction. */
    private YoloXImageTransform() {
    }

    /**
     * Builds pixel-center bilinear, uint8-rounded RGB planes with top-left resize and114 padding.
     * Alpha-bearing caller input is composited over white before interpolation; PDF rendering supplies RGB.
     * This declared JVM interpolation is not asserted equivalent to OpenCV or another runtime.
     * @param image borrowed source raster
     * @param options input pixel and raw-query policy
     * @param deadline total pipeline deadline
     * @return owned fixed-size pixels and original frame scale
     * @throws IOException if pixels, query budget, aspect ratio or cancellation are unsupported
     */
    static YoloXPixels prepare(final BufferedImage image, final OnnxTableOptions options, final LayoutDeadline deadline)
            throws IOException {
        deadline.remaining();
        if ((long) image.getWidth() * image.getHeight() > options.maxInputPixels()
                || options.maxQueries() < YoloXObjectDecoder.QUERIES) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "YOLOX input exceeds pixel/query limit");
        }
        final double ratio = Math.min((double) HEIGHT / image.getHeight(), (double) WIDTH / image.getWidth());
        final int width = (int) (image.getWidth() * ratio);
        final int height = (int) (image.getHeight() * ratio);
        if (width < 1 || height < 1) {
            throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                    "YOLOX image aspect ratio has no positive resized extent");
        }
        final var data = new float[3 * WIDTH * HEIGHT];
        Arrays.fill(data, 114);
        for (int y = 0; y < height; y++) {
            deadline.remaining();
            final double sourceY = (y + .5) * image.getHeight() / height - .5;
            final int baseY = (int) Math.floor(sourceY);
            final double fy = sourceY - baseY;
            for (int x = 0; x < width; x++) {
                if ((x & 31) == 0) {
                    deadline.remaining();
                }
                final double sourceX = (x + .5) * image.getWidth() / width - .5;
                final int baseX = (int) Math.floor(sourceX);
                final double fx = sourceX - baseX;
                for (int channel = 0; channel < 3; channel++) {
                    final double a = pixel(image, baseX, baseY, channel) * (1 - fx)
                                     + pixel(image, baseX + 1, baseY, channel) * fx;
                    final double b = pixel(image, baseX, baseY + 1, channel) * (1 - fx)
                                     + pixel(image, baseX + 1, baseY + 1, channel) * fx;
                    data[channel * WIDTH * HEIGHT + y * WIDTH + x] = Math.round(a * (1 - fy) + b * fy);
                }
            }
        }
        return new YoloXPixels(FloatBuffer.wrap(data), image.getWidth(), image.getHeight(), ratio);
    }

    /**
     * Reads a replicated-edge channel and composites source alpha over white without changing the raster.
     * @param image borrowed original raster
     * @param x possibly outside horizontal source bounds
     * @param y possibly outside vertical source bounds
     * @param channel RGB channel index
     * @return original or white-composited unsigned channel
     */
    private static int pixel(final BufferedImage image, final int x, final int y, final int channel) {
        final int argb = image.getRGB(Math.clamp(x, 0, image.getWidth() - 1), Math.clamp(y, 0, image.getHeight() - 1));
        final int alpha = argb >>> 24;
        final int value = argb >>> (16 - channel * 8) & 255;
        return (value * alpha + 255 * (255 - alpha) + 127) / 255;
    }
}
