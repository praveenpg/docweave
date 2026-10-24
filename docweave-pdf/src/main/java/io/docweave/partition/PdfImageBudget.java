package io.docweave.partition;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;

/** Document-confined cumulative PNG budget, shared across page visitors. */
final class PdfImageBudget {
    /** Immutable strategy policy. */
    private final PdfOptions options;
    /** Bytes emitted across all image occurrences in this document. */
    private int used;

    /**
     * Captures the per-image and cumulative limits.
     * @param options immutable image policy
     */
    PdfImageBudget(final PdfOptions options) {
        this.options = options;
    }

    /**
     * Adds optional normalized pixels to an occurrence's metadata.
     * @param image source image, decoded only when opted in
     * @param fill current nonstroking source color; solid stencil masks use its RGB paint
     * @param metadata document-local mutable occurrence properties
     * @throws IOException if decoding or a pixel/byte limit fails
     */
    void extract(final PDImage image, final PDColor fill, final Map<String, Object> metadata) throws IOException {
        if (!options.includeImageBytes()) {
            return;
        }
        final boolean stencil = image.isStencil();
        final boolean solidStencil = stencil && !fill.isPattern();
        preflight(image, !solidStencil);
        final var output = new ByteArrayOutputStream();
        // Check each codec write before retaining bytes, rather than checking an oversized final array.
        final var bounded = new java.io.OutputStream() {
            @Override
            public void write(final int value) throws IOException {
                reserve(1);
                output.write(value);
            }

            @Override
            public void write(final byte[] bytes, final int offset, final int length) throws IOException {
                reserve(length);
                output.write(bytes, offset, length);
            }

            /**
             * Checks cancellation and the cumulative budget before a codec write.
             * @param count requested byte count
             * @throws IOException if cancelled or the image budget is exhausted
             */
            private void reserve(final int count) throws IOException {
                ElementCollector.checkInterrupted();
                if (count > options.maxImageBytes() - used) {
                    throw new PartitionException(
                            PartitionException.Code.IMAGE_LIMIT, "PDF images exceed maxImageBytes");
                }
                used += count;
            }
        };
        // A stencil has no intrinsic color: the current occurrence supplies its solid fill.
        // Pattern paints remain decoded mask samples; rendering a pattern would change the source-image contract.
        final var pixels = solidStencil ? image.getStencilImage(new Color(fill.toRGB())) : image.getImage();
        if (pixels == null) {
            throw new IOException("PDF image decoder returned no pixels");
        }
        final int payloadWidth = pixels.getWidth();
        final int payloadHeight = pixels.getHeight();
        try {
            checkPixels(payloadWidth, payloadHeight);
            if (!ImageIO.write(pixels, "png", bounded)) {
                throw new IOException("PNG encoder unavailable");
            }
        } catch (final javax.imageio.IIOException failure) {
            // ImageIO may wrap the bounded sink's typed failure.
            if (failure.getCause() instanceof final PartitionException limit) {
                throw limit;
            }
            if (failure.getCause() instanceof final java.io.InterruptedIOException cancelled) {
                throw cancelled;
            }
            throw failure;
        } finally {
            pixels.flush();
        }
        metadata.put("image_base64", Base64.getEncoder().encodeToString(output.toByteArray()));
        metadata.put("image_mime_type", "image/png");
        if (payloadWidth != image.getWidth() || payloadHeight != image.getHeight()) {
            metadata.put("image_payload_width", payloadWidth);
            metadata.put("image_payload_height", payloadHeight);
        }
        if (stencil) {
            metadata.put("image_stencil", true);
            metadata.put("image_pixel_policy", solidStencil ? "stencil_solid_fill_v1" : "stencil_mask_samples_v1");
        }
    }

    /**
     * Bounds the source-declared raster extent used by the selected decoder before decoding any mask.
     * PDFBox combines a main image and an active mask at the larger width and height of either plane.
     * A soft mask takes precedence; a hard mask is decoded only when it is an image stencil.
     * Stencil-paint decoding does not composite additional image masks. Color-key arrays and unused
     * mask resources therefore do not enlarge this prospective extent.
     * @param image original source image
     * @param applyMasks whether the selected normal-image decoder composites image-mask planes
     * @throws IOException if cancellation or the prospective pixel ceiling prevents extraction
     */
    private void preflight(final PDImage image, final boolean applyMasks) throws IOException {
        ElementCollector.checkInterrupted();
        int width = image.getWidth();
        int height = image.getHeight();
        if (applyMasks && image.getCOSObject() instanceof final COSDictionary dictionary) {
            var mask = dictionary.getCOSStream(COSName.SMASK);
            if (mask == null) {
                final var hard = dictionary.getCOSStream(COSName.MASK);
                if (hard != null && hard.getBoolean(COSName.IMAGE_MASK, false)) {
                    mask = hard;
                }
            }
            if (mask != null) {
                width = Math.max(width, mask.getInt(COSName.WIDTH));
                height = Math.max(height, mask.getInt(COSName.HEIGHT));
            }
        }
        checkPixels(width, height);
    }

    /**
     * Checks source-declared prospective or returned normalized raster dimensions without summing planes.
     * @param width source or decoded pixel width
     * @param height source or decoded pixel height
     * @throws PartitionException if the raster extent exceeds the per-image pixel ceiling
     */
    private void checkPixels(final int width, final int height) throws PartitionException {
        if ((long) width * height > options.maxImagePixels()) {
            throw new PartitionException(
                    PartitionException.Code.IMAGE_LIMIT, "PDF image or effective mask extent exceeds maxImagePixels");
        }
    }
}
