package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable form of the PDF image output contracts ({@code docs/pdf-image-contracts.md}). On every owned fixture:
 * contract A, the Image element's PNG payload, holds exactly the image's intrinsic decoded samples whatever the
 * clipping, transparency, soft mask, groups, transform, blend mode or later paint; contract C, the explicit page-crop
 * service, holds the final composited page appearance of the placement region, including other content and with no
 * attribution of pixels to the image. Contract B (isolated rendered contribution) is not provided, and no test here
 * claims it.
 */
class PdfImageOutputContractsTest {
    /** Red image side in samples and in user-space units. */
    private static final int SIDE = 40;

    /** Owned fixtures, each a 200x200 page with one red 40x40 image displayed at x 40-80, rows 40-80. */
    enum Fixture {
        /** Plain placement. */
        PLAIN,
        /** Rectangular clip to the left half of the placement. */
        CLIP,
        /** Non-stroking alpha 0.5. */
        ALPHA,
        /** Later opaque blue overpaint of the whole placement. */
        OVERPAINT,
        /** Image inside a transparency group drawn with alpha 0.5. */
        GROUP_ALPHA,
        /** Image inside two nested groups, each drawn with alpha 0.5. */
        NESTED_GROUPS,
        /** Image inside an isolated knockout group that first paints green under it. */
        KNOCKOUT_GROUP,
        /** Luminosity soft mask: white (visible) over the left half, black over the right half. */
        SOFT_MASK,
        /** Placement rotated by a quarter turn about its centre. */
        TRANSFORM,
        /** Multiply blend mode over the white page. */
        MULTIPLY,
        /** The same red already painted under the image: the final appearance cannot tell them apart. */
        IDENTICAL_COLOUR
    }

    /** Creates the controls. */
    PdfImageOutputContractsTest() {
    }

    /**
     * Contract A: the payload is the intrinsic source samples, never the rendered appearance.
     * @throws Exception if authoring or extraction fails
     */
    @Test
    void payloadsHoldIntrinsicSamplesUnderEveryGraphicsState() throws Exception {
        for (final Fixture fixture : Fixture.values()) {
            final byte[] pdf = author(fixture);
            final List<Element> images = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                            new PdfOptions(10, 10_000, 10_000, 16_000_000, 16 * 1024 * 1024, true,
                                    TableLimits.defaults())))))
                    .partition(DocumentSource.bytes(pdf, "fixture.pdf")).elements().stream()
                    .filter(element -> element.type() == ElementType.Image).toList();
            assertEquals(1, images.size(), fixture.name());
            final var metadata = images.getFirst().metadata();
            assertEquals(SIDE, ((Number) metadata.get("image_width")).intValue(), fixture.name());
            assertEquals(SIDE, ((Number) metadata.get("image_height")).intValue(), fixture.name());
            final BufferedImage payload = ImageIO.read(
                    new ByteArrayInputStream(Base64.getDecoder().decode((String) metadata.get("image_base64"))));
            for (int y = 0; y < SIDE; y++) {
                for (int x = 0; x < SIDE; x++) {
                    assertEquals(0xFFFF0000, payload.getRGB(x, y), fixture.name() + " sample " + x + "," + y);
                }
            }
        }
    }

    /**
     * Contract C: the explicit page crop shows the final composited appearance of the placement region.
     * @throws Exception if authoring or rendering fails
     */
    @Test
    void pageCropsHoldTheFinalCompositedAppearance() throws Exception {
        final var renderer = PdfPageCropRenderer.create(PdfPageCropOptions.defaults().withDpi(72));
        for (final Fixture fixture : Fixture.values()) {
            final var crop = renderer.render(DocumentSource.bytes(author(fixture), "fixture.pdf"),
                    List.of(new PdfPageCropRequest(1, 40, 40, 80, 80, PdfPageCropRequest.Units.USER_SPACE)))
                    .getFirst();
            final BufferedImage raster = ImageIO.read(new ByteArrayInputStream(crop.png()));
            final int left = raster.getRGB(10, 20);
            final int right = raster.getRGB(30, 20);
            final int[][] expected = switch (fixture) {
                case PLAIN, MULTIPLY, TRANSFORM, KNOCKOUT_GROUP, IDENTICAL_COLOUR -> new int[][] {red(), red()};
                case CLIP, SOFT_MASK -> new int[][] {red(), white()};
                case ALPHA, GROUP_ALPHA -> new int[][] {{255, 128, 128}, {255, 128, 128}};
                case NESTED_GROUPS -> new int[][] {{255, 191, 191}, {255, 191, 191}};
                case OVERPAINT -> new int[][] {{0, 0, 255}, {0, 0, 255}};
            };
            assertNear(expected[0], left, fixture + " left");
            assertNear(expected[1], right, fixture + " right");
        }
    }

    /**
     * Contract C attributes nothing to the image: an identical-colour backdrop renders the same crop as the image.
     * @throws Exception if authoring or rendering fails
     */
    @Test
    void finalAppearanceCannotAttributePixelsToTheImage() throws Exception {
        final var renderer = PdfPageCropRenderer.create(PdfPageCropOptions.defaults().withDpi(72));
        final var request = List.of(new PdfPageCropRequest(1, 40, 40, 80, 80, PdfPageCropRequest.Units.USER_SPACE));
        final byte[] withImage = renderer.render(DocumentSource.bytes(author(Fixture.IDENTICAL_COLOUR), "a.pdf"),
                request).getFirst().png();
        final byte[] backdropOnly = renderer.render(DocumentSource.bytes(backdropOnly(), "b.pdf"), request).getFirst()
                                            .png();
        assertTrue(java.util.Arrays.equals(pixels(withImage), pixels(backdropOnly)),
                "the final appearance is identical with and without the image");
    }

    /**
     * Authors one fixture.
     * @param fixture fixture
     * @return PDF bytes
     * @throws Exception if writing fails
     */
    private static byte[] author(final Fixture fixture) throws Exception {
        try (PDDocument document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            document.addPage(page);
            final PDImageXObject image = LosslessFactory.createFromImage(document, solid(Color.RED));
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                switch (fixture) {
                    case PLAIN -> content.drawImage(image, 40, 120, SIDE, SIDE);
                    case CLIP -> {
                        content.saveGraphicsState();
                        content.addRect(40, 120, 20, SIDE);
                        content.clip();
                        content.drawImage(image, 40, 120, SIDE, SIDE);
                        content.restoreGraphicsState();
                    }
                    case ALPHA -> {
                        content.saveGraphicsState();
                        content.setGraphicsStateParameters(alpha(0.5f));
                        content.drawImage(image, 40, 120, SIDE, SIDE);
                        content.restoreGraphicsState();
                    }
                    case OVERPAINT -> {
                        content.drawImage(image, 40, 120, SIDE, SIDE);
                        content.setNonStrokingColor(Color.BLUE);
                        content.addRect(40, 120, SIDE, SIDE);
                        content.fill();
                    }
                    case GROUP_ALPHA -> {
                        content.saveGraphicsState();
                        content.setGraphicsStateParameters(alpha(0.5f));
                        content.drawForm(group(document, image, false, null));
                        content.restoreGraphicsState();
                    }
                    case NESTED_GROUPS -> {
                        content.saveGraphicsState();
                        content.setGraphicsStateParameters(alpha(0.5f));
                        content.drawForm(group(document, image, false, alpha(0.5f)));
                        content.restoreGraphicsState();
                    }
                    case KNOCKOUT_GROUP -> content.drawForm(group(document, image, true, null));
                    case SOFT_MASK -> {
                        content.saveGraphicsState();
                        content.setGraphicsStateParameters(softMask(document));
                        content.drawImage(image, 40, 120, SIDE, SIDE);
                        content.restoreGraphicsState();
                    }
                    case TRANSFORM -> {
                        content.saveGraphicsState();
                        // A quarter turn about the placement centre (60, 140) in user space.
                        content.transform(Matrix.getTranslateInstance(60, 140));
                        content.transform(Matrix.getRotateInstance(Math.PI / 2, 0, 0));
                        content.transform(Matrix.getTranslateInstance(-60, -140));
                        content.drawImage(image, 40, 120, SIDE, SIDE);
                        content.restoreGraphicsState();
                    }
                    case MULTIPLY -> {
                        content.saveGraphicsState();
                        final var state = new PDExtendedGraphicsState();
                        state.setBlendMode(BlendMode.MULTIPLY);
                        content.setGraphicsStateParameters(state);
                        content.drawImage(image, 40, 120, SIDE, SIDE);
                        content.restoreGraphicsState();
                    }
                    case IDENTICAL_COLOUR -> {
                        content.setNonStrokingColor(Color.RED);
                        content.addRect(40, 120, SIDE, SIDE);
                        content.fill();
                        content.drawImage(image, 40, 120, SIDE, SIDE);
                    }
                }
            }
            final var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    /**
     * Authors the identical-colour fixture without its image.
     * @return PDF bytes
     * @throws Exception if writing fails
     */
    private static byte[] backdropOnly() throws Exception {
        try (PDDocument document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(Color.RED);
                content.addRect(40, 120, SIDE, SIDE);
                content.fill();
            }
            final var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    /**
     * Builds a transparency group form drawing the image; optionally knockout (painting green first) or nested.
     * @param document document
     * @param image image
     * @param knockout isolated knockout group that paints green under the image first
     * @param inner alpha of a nested inner group, or null for none
     * @return form
     * @throws Exception if writing fails
     */
    private static PDFormXObject group(final PDDocument document, final PDImageXObject image, final boolean knockout,
            final PDExtendedGraphicsState inner) throws Exception {
        final var form = transparencyForm(document, knockout, knockout, null);
        try (var content = new PDFormContentStream(form)) {
            if (knockout) {
                content.setNonStrokingColor(Color.GREEN);
                content.addRect(40, 120, SIDE, SIDE);
                content.fill();
            }
            if (inner != null) {
                content.saveGraphicsState();
                content.setGraphicsStateParameters(inner);
                content.drawForm(group(document, image, false, null));
                content.restoreGraphicsState();
            } else {
                content.drawImage(image, 40, 120, SIDE, SIDE);
            }
        }
        return form;
    }

    /**
     * Builds a luminosity soft mask that is white over the placement's left half and black elsewhere.
     * @param document document
     * @return graphics state
     * @throws Exception if writing fails
     */
    private static PDExtendedGraphicsState softMask(final PDDocument document) throws Exception {
        final var mask = transparencyForm(document, false, false, COSName.DEVICERGB);
        try (var content = new PDFormContentStream(mask)) {
            content.setNonStrokingColor(Color.BLACK);
            content.addRect(0, 0, 200, 200);
            content.fill();
            content.setNonStrokingColor(Color.WHITE);
            content.addRect(40, 120, 20, SIDE);
            content.fill();
        }
        final var dictionary = new COSDictionary();
        dictionary.setItem(COSName.TYPE, COSName.MASK);
        dictionary.setItem(COSName.S, COSName.LUMINOSITY);
        dictionary.setItem(COSName.G, mask);
        final var state = new PDExtendedGraphicsState();
        state.getCOSObject().setItem(COSName.SMASK, dictionary);
        assertTrue(PDSoftMask.create(dictionary) != null);
        return state;
    }

    /**
     * Creates an empty page-sized form with a transparency group dictionary.
     * @param document owner
     * @param isolated isolation flag
     * @param knockout knockout flag
     * @param colourSpace group colour space, or null
     * @return form
     */
    private static PDFormXObject transparencyForm(final PDDocument document, final boolean isolated,
            final boolean knockout, final COSName colourSpace) {
        final var form = new PDFormXObject(document);
        form.setBBox(new PDRectangle(200, 200));
        form.setResources(new PDResources());
        final var group = new COSDictionary();
        group.setItem(COSName.S, COSName.TRANSPARENCY);
        group.setBoolean(COSName.I, isolated);
        group.setBoolean(COSName.K, knockout);
        if (colourSpace != null) {
            group.setItem(COSName.CS, colourSpace);
        }
        form.getCOSObject().setItem(COSName.GROUP, group);
        return form;
    }

    /**
     * Builds a non-stroking alpha state.
     * @param value alpha
     * @return graphics state
     */
    private static PDExtendedGraphicsState alpha(final float value) {
        final var state = new PDExtendedGraphicsState();
        state.setNonStrokingAlphaConstant(value);
        return state;
    }

    /**
     * Returns a solid image.
     * @param colour colour
     * @return 40x40 RGB image
     */
    private static BufferedImage solid(final Color colour) {
        final var image = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) {
                image.setRGB(x, y, colour.getRGB());
            }
        }
        return image;
    }

    /**
     * Returns opaque red as RGB components.
     * @return components
     */
    private static int[] red() {
        return new int[] {255, 0, 0};
    }

    /**
     * Returns white as RGB components.
     * @return components
     */
    private static int[] white() {
        return new int[] {255, 255, 255};
    }

    /**
     * Asserts that a pixel is within eight levels of the expected components.
     * @param expected components
     * @param actual ARGB pixel
     * @param message message
     */
    private static void assertNear(final int[] expected, final int actual, final String message) {
        final int[] components = {(actual >> 16) & 0xFF, (actual >> 8) & 0xFF, actual & 0xFF};
        for (int index = 0; index < 3; index++) {
            assertTrue(Math.abs(components[index] - expected[index]) <= 8,
                    message + ": expected " + java.util.Arrays.toString(expected) + " but was "
                            + java.util.Arrays.toString(components));
        }
    }

    /**
     * Decodes a PNG to its pixels.
     * @param png PNG bytes
     * @return ARGB pixels in row order
     * @throws Exception if decoding fails
     */
    private static int[] pixels(final byte[] png) throws Exception {
        final BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
