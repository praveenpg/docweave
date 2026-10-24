package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/** Public-path limits for normalized PNG extent when PDF image masks have different source dimensions. */
class PdfImageMaskBudgetTest {
    /** Creates one independent fixture test without retaining mutable documents. */
    PdfImageMaskBudgetTest() {
    }

    /**
     * Larger soft and hard mask planes enlarge PNGs and must be preflighted before decoding.
     * @throws Exception if owned source creation, extraction or PNG decoding fails
     */
    @Test
    void largerMaskPlaneIsBoundedAndActualPayloadDimensionsAreExplicit() throws Exception {
        for (final var mode : List.of(MaskMode.HARD, MaskMode.SOFT)) {
            final byte[] source = fixture(2, 1, 64, 1, mode, false);
            assertLimit(source, 2);
            assertLimit(source, 63);
            final var accepted = image(source, true, 64);
            assertEquals(2, accepted.metadata().get("image_width"));
            assertEquals(1, accepted.metadata().get("image_height"));
            assertEquals(64, accepted.metadata().get("image_payload_width"));
            assertEquals(1, accepted.metadata().get("image_payload_height"));
            assertDimensions(accepted, 64, 1);
            final var reference = image(source, false, 1);
            assertFalse(reference.metadata().containsKey("image_base64"));
            assertFalse(reference.metadata().containsKey("image_payload_width"));
            assertEquals(reference.metadata().get("coordinates"), accepted.metadata().get("coordinates"));
        }
    }

    /**
     * Checking each plane alone misses the larger combined rectangle when their long axes differ.
     * @throws Exception if fixture or public extraction checks fail
     */
    @Test
    void crossedPlaneAxesUseCombinedMaximumExtent() throws Exception {
        for (final var mode : List.of(MaskMode.HARD, MaskMode.SOFT)) {
            final byte[] source = fixture(2, 16, 16, 2, mode, false);
            assertLimit(source, 32);
            assertLimit(source, 255);
            final var accepted = image(source, true, 256);
            assertEquals(2, accepted.metadata().get("image_width"));
            assertEquals(16, accepted.metadata().get("image_height"));
            assertEquals(16, accepted.metadata().get("image_payload_width"));
            assertEquals(16, accepted.metadata().get("image_payload_height"));
            assertDimensions(accepted, 16, 16);
        }
    }

    /**
     * An oversized undecodable plane must fail at the source-size check before its unsupported filter is read.
     * @throws Exception if fixture construction or public failure inspection fails
     */
    @Test
    void sourceMaskExtentRejectsBeforeMalformedMaskDecoding() throws Exception {
        for (final var mode : List.of(MaskMode.HARD, MaskMode.SOFT)) {
            final byte[] source = fixture(2, 1, 64, 1, mode, true);
            assertLimit(source, 2);
            assertEquals(PartitionException.Code.MALFORMED_DOCUMENT,
                    assertThrows(PartitionException.class, () -> image(source, true, 64)).code());
            assertFalse(image(source, false, 1).metadata().containsKey("image_base64"));
        }
    }

    /**
     * Effective soft masks override hard masks; non-stencil hard-mask streams are not decoded by this backend.
     * @throws Exception if fixture creation, extraction or pixel checks fail
     */
    @Test
    void unusedMaskStreamsAndSameSizedAlphaDoNotSpendAdditionalPixels() throws Exception {
        for (final var mode : List.of(MaskMode.UNUSED_NONSTENCIL, MaskMode.OVERRIDDEN_HARD)) {
            final byte[] source = fixture(2, 1, 64, 1, mode, true);
            final var accepted = image(source, true, 2);
            assertDimensions(accepted, 2, 1);
            assertFalse(accepted.metadata().containsKey("image_payload_width"));
            assertFalse(accepted.metadata().containsKey("image_payload_height"));
        }
    }

    /**
     * Color-key ranges mask source samples without adding another decoded image plane.
     * @throws Exception if owned source creation, extraction or PNG inspection fails
     */
    @Test
    void colorKeyMaskArraysRetainIntrinsicColorAndTransparencyAtBasePixelLimit() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(100, 100));
            document.addPage(page);
            final var samples = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
            samples.setRGB(0, 0, Color.RED.getRGB());
            samples.setRGB(1, 0, Color.BLUE.getRGB());
            final PDImageXObject base;
            try {
                base = LosslessFactory.createFromImage(document, samples);
            } finally {
                samples.flush();
            }
            final var range = new COSArray();
            for (final int value : new int[] {255, 255, 0, 0, 0, 0}) {
                range.add(COSInteger.get(value));
            }
            base.getCOSObject().setItem(COSName.MASK, range);
            final var actual = image(paint(document, page, base), true, 2);
            assertFalse(actual.metadata().containsKey("image_payload_width"));
            final var pixels = pixels(actual);
            try {
                assertEquals(0, pixels.getRGB(0, 0) >>> 24);
                assertEquals(Color.BLUE.getRGB(), pixels.getRGB(1, 0));
            } finally {
                pixels.flush();
            }
        }
    }

    /** Explicit source-mask roles keep ignored-stream fixtures distinct from active malformed masks. */
    private enum MaskMode {
        /** Active grayscale soft-mask plane. */ SOFT,
        /** Active one-bit hard-mask plane. */ HARD,
        /** An ordinary, non-stencil Mask stream ignored by the backend. */ UNUSED_NONSTENCIL,
        /** Hard mask superseded by a valid same-sized soft mask. */ OVERRIDDEN_HARD
    }

    /**
     * Builds a complete owned image PDF, optionally containing a mask ignored by backend precedence.
     * @param width source base width
     * @param height source base height
     * @param maskWidth supporting mask width
     * @param maskHeight supporting mask height
     * @param mode declared mask role and decoder precedence
     * @param broken whether its filter is deliberately unsupported
     * @return original owned source bytes
     * @throws Exception if fixture creation fails
     */
    private static byte[] fixture(final int width, final int height, final int maskWidth, final int maskHeight,
            final MaskMode mode, final boolean broken) throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(100, 100));
            document.addPage(page);
            final var samples = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            final PDImageXObject base;
            try {
                base = LosslessFactory.createFromImage(document, samples);
            } finally {
                samples.flush();
            }
            final boolean soft = mode == MaskMode.SOFT;
            final var mask = mask(document, maskWidth, maskHeight, soft, broken);
            if (mode == MaskMode.UNUSED_NONSTENCIL) {
                // A non-stencil Mask stream is ignored rather than decoded as an image mask.
                mask.setStencil(false);
            }
            base.getCOSObject().setItem(soft ? COSName.SMASK : COSName.MASK, mask);
            if (mode == MaskMode.OVERRIDDEN_HARD) {
                base.getCOSObject().setItem(COSName.SMASK, mask(document, width, height, true, false));
            }
            return paint(document, page, base);
        }
    }

    /**
     * Creates source mask samples without using a decoder to establish their declared dimensions.
     * @param document source owner
     * @param width source mask width
     * @param height source mask height
     * @param soft whether to create grayscale alpha instead of one-bit stencil samples
     * @param broken whether the source filter must fail if it is actually decoded
     * @return owned direct image-mask stream
     * @throws Exception if mask fixture construction fails
     */
    private static PDImageXObject mask(final PDDocument document, final int width, final int height, final boolean soft,
            final boolean broken) throws Exception {
        final var mask = new PDImageXObject(document);
        mask.setWidth(width);
        mask.setHeight(height);
        mask.setBitsPerComponent(soft ? 8 : 1);
        mask.setStencil(!soft);
        if (soft) {
            mask.setColorSpace(PDDeviceGray.INSTANCE);
        }
        if (broken) {
            mask.getCOSObject().setItem(COSName.FILTER, COSName.getPDFName("OwnedUnsupportedMaskFilter"));
        }
        final byte[] samples = new byte[(soft ? width : (width + 7) / 8) * height];
        Arrays.fill(samples, soft ? (byte) 255 : (byte) 0);
        try (final var output = mask.getCOSObject().createRawOutputStream()) {
            output.write(samples);
        }
        return mask;
    }

    /**
     * Paints exactly one occurrence and serializes the complete original fixture document.
     * @param document source owner
     * @param page original source page
     * @param image image to paint without decoding samples
     * @return complete source PDF bytes
     * @throws Exception if source painting or serialization fails
     */
    private static byte[] paint(final PDDocument document, final PDPage page, final PDImageXObject image)
            throws Exception {
        try (final var content = new PDPageContentStream(document, page)) {
            content.drawImage(image, 20, 20, 20, 10);
        }
        final var output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }

    /**
     * Selects explicit bounded image payloads through the public PDF service interface.
     * @param source complete owned PDF
     * @param payload whether PNG extraction is enabled
     * @param pixels maximum normalized image pixels
     * @return the fixture's single emitted image
     * @throws Exception if public extraction fails
     */
    private static Element image(final byte[] source, final boolean payload, final long pixels) throws Exception {
        final var options = new PdfOptions(10, 10_000, 10_000, pixels, 100_000, payload, TableLimits.defaults());
        final var images = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                                   .partition(DocumentSource.bytes(source, "owned-mask-limits.pdf"))
                                   .elements()
                                   .stream()
                                   .filter(e -> e.type() == ElementType.Image)
                                   .toList();
        assertEquals(1, images.size());
        return images.getFirst();
    }

    /**
     * Requires a typed source preflight failure without accepting codec errors as a pixel-limit success.
     * @param source owned complete input
     * @param pixels selected source/normalized raster ceiling
     */
    private static void assertLimit(final byte[] source, final long pixels) {
        assertEquals(PartitionException.Code.IMAGE_LIMIT,
                assertThrows(PartitionException.class, () -> image(source, true, pixels)).code());
    }

    /**
     * Checks actual PNG dimensions independently of metadata about original source dimensions.
     * @param image public extracted image
     * @param width expected normalized width
     * @param height expected normalized height
     * @throws Exception if emitted PNG decoding fails
     */
    private static void assertDimensions(final Element image, final int width, final int height) throws Exception {
        final var pixels = pixels(image);
        try {
            assertEquals(width, pixels.getWidth());
            assertEquals(height, pixels.getHeight());
        } finally {
            pixels.flush();
        }
    }

    /**
     * Decodes the actual emitted PNG without deriving expected geometry from decoder internals.
     * @param image public extracted Image element
     * @return caller-owned normalized pixels
     * @throws Exception if PNG decoding fails
     */
    private static BufferedImage pixels(final Element image) throws Exception {
        assertEquals("image/png", image.metadata().get("image_mime_type"));
        final var pixels = ImageIO.read(
                new ByteArrayInputStream(Base64.getDecoder().decode(image.metadata().get("image_base64").toString())));
        assertNotNull(pixels);
        return pixels;
    }
}
