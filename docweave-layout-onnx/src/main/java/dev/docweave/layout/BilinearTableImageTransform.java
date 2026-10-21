package dev.docweave.layout;

import dev.docweave.partition.PartitionException;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/** Pixel-center separable bilinear max-height/max-width800 transform with bounded call-local scratch. */
final class BilinearTableImageTransform implements TableImageTransform {
    /** Published cross-domain model maximum height/width profile. */
    private static final int MAX_SIDE = 800;
    /** Integer coefficient precision; each channel is rounded/clipped after each separable pass. */
    private static final int PRECISION = 22;
    /** Explicit ImageNet RGB channel means. */
    private static final float[] MEAN = {.485f, .456f, .406f};
    /** Explicit ImageNet RGB channel deviations. */
    private static final float[] DEVIATION = {.229f, .224f, .225f};

    /** Creates a stateless preprocessing strategy. */
    BilinearTableImageTransform() {
    }

    @Override
    public LayoutPixels prepare(final BufferedImage image, final OnnxTableOptions options,
            final LayoutDeadline deadline) throws IOException {
        Objects.requireNonNull(image, "image");
        deadline.remaining();
        final int sourceWidth = image.getWidth();
        final int sourceHeight = image.getHeight();
        if ((long) sourceWidth * sourceHeight > options.maxInputPixels()) {
            throw limit();
        }
        final double scale = Math.min(MAX_SIDE / (double) sourceHeight, MAX_SIDE / (double) sourceWidth);
        final int width = (int) (sourceWidth * scale);
        final int height = (int) (sourceHeight * scale);
        if (width < 1 || height < 1) {
            throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                    "ONNX table image aspect ratio has no positive resized extent");
        }
        if ((long) width * sourceHeight > Integer.MAX_VALUE) {
            throw limit();
        }
        final var xWeights = coefficients(sourceWidth, width, deadline);
        final var yWeights = coefficients(sourceHeight, height, deadline);
        final var horizontal = new int[width * sourceHeight];
        final var rowPixels = new int[sourceWidth];
        for (int row = 0; row < sourceHeight; row++) {
            deadline.remaining();
            image.getRGB(0, row, sourceWidth, 1, rowPixels, 0, sourceWidth);
            for (int column = 0; column < sourceWidth; column++) {
                if ((column & 255) == 0) {
                    deadline.remaining();
                }
                rowPixels[column] = whiteComposite(rowPixels[column]);
            }
            for (int column = 0; column < width; column++) {
                if ((column & 31) == 0) {
                    deadline.remaining();
                }
                horizontal[row * width + column] = filter(rowPixels, xWeights[column], 0, 1);
            }
        }
        final var resized = new int[width * height];
        for (int row = 0; row < height; row++) {
            deadline.remaining();
            for (int column = 0; column < width; column++) {
                resized[row * width + column] = filter(horizontal, yWeights[row], column, width);
            }
        }
        final var values = FloatBuffer.allocate(3 * resized.length);
        for (int channel = 0; channel < 3; channel++) {
            for (int index = 0; index < resized.length; index++) {
                if ((index & 4095) == 0) {
                    deadline.remaining();
                }
                values.put(
                        (((resized[index] >>> (16 - 8 * channel)) & 255) / 255f - MEAN[channel]) / DEVIATION[channel]);
            }
        }
        values.flip();
        final var mask = LongBuffer.allocate(resized.length);
        while (mask.hasRemaining()) {
            if ((mask.position() & 4095) == 0) {
                deadline.remaining();
            }
            mask.put(1);
        }
        mask.flip();
        return new LayoutPixels(values, mask, width, height, sourceWidth, sourceHeight);
    }

    /**
     * Computes an RGB channel tuple using one fixed-point separable filter and8-bit pass rounding.
     * @param pixels internally owned row/intermediate pixels
     * @param weights start source index followed by normalized fixed-point weights
     * @param offset base source pixel offset
     * @param stride source stride, one for rows or output width for columns
     * @return packed opaque-independent RGB bits
     */
    private static int filter(final int[] pixels, final int[] weights, final int offset, final int stride) {
        int rgb = 0;
        for (int channel = 0; channel < 3; channel++) {
            long value = 1 << (PRECISION - 1);
            for (int index = 1; index < weights.length; index++) {
                value += (long) ((pixels[offset + (weights[0] + index - 1) * stride] >>> (16 - 8 * channel)) & 255)
                         * weights[index];
            }
            rgb |= (int) Math.max(0, Math.min(255, value >> PRECISION)) << (16 - 8 * channel);
        }
        return rgb;
    }

    /**
     * Creates widened downsampling/ordinary upsampling bilinear kernels without geometric drift.
     * @param input positive source axis size
     * @param output positive resized axis size
     * @param deadline shared deadline/interruption policy
     * @return per-output start index and22-bit integer coefficients
     * @throws IOException if deadline or cancellation prevents kernel construction
     */
    private static int[][] coefficients(final int input, final int output, final LayoutDeadline deadline)
            throws IOException {
        final var result = new int[output][];
        final double scale = input / (double) output;
        final double support = Math.max(1, scale);
        for (int position = 0; position < output; position++) {
            deadline.remaining();
            final double center = (position + .5) * scale;
            final int first = Math.max(0, (int) (center - support + .5));
            final int last = Math.min(input, (int) (center + support + .5));
            final var weights = new double[last - first];
            double sum = 0;
            for (int source = first; source < last; source++) {
                if ((source & 4095) == 0) {
                    deadline.remaining();
                }
                weights[source - first] = Math.max(0, 1 - Math.abs((source + .5 - center) / support));
                sum += weights[source - first];
            }
            result[position] = new int[weights.length + 1];
            result[position][0] = first;
            for (int index = 0; index < weights.length; index++) {
                result[position][index + 1] = (int) (weights[index] / sum * (1 << PRECISION) + .5);
            }
        }
        return result;
    }

    /**
     * Composites direct ARGB input over white; normal PDF rendering already supplies opaque RGB.
     * @param argb unpremultiplied source pixel from BufferedImage.getRGB
     * @return opaque RGB content without modifying the source image
     */
    private static int whiteComposite(final int argb) {
        final int alpha = argb >>> 24;
        if (alpha == 255) {
            return argb & 0xffffff;
        }
        int rgb = 0;
        for (int channel = 0; channel < 3; channel++) {
            final int source = (argb >>> (16 - 8 * channel)) & 255;
            rgb |= ((source * alpha + 255 * (255 - alpha) + 127) / 255) << (16 - 8 * channel);
        }
        return rgb;
    }

    /**
     * Creates a content-free pixel/allocation policy failure.
     * @return typed input resource failure
     */
    private static PartitionException limit() {
        return new PartitionException(
                PartitionException.Code.OCR_LIMIT, "ONNX table preprocessing exceeds pixel limits");
    }
}
