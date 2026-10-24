package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.apache.pdfbox.Loader;
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
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Source-image invocation state controls, deliberately separate from final page compositing. */
class PdfImageGraphicsStateTest {
    /** Creates independent source-image state controls. */
    PdfImageGraphicsStateTest() {
    }

    /**
     * One image under different source paint states retains occurrence state without changing its PNG samples.
     * A separately rendered page confirms that identical payloads do not imply identical visible appearances.
     * @throws Exception if source authoring, rendering or public extraction fails
     */
    @Test
    void reusedImageRetainsAlphaBlendAndRestoredDefaultsWithoutRewritingPixels() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 100));
            document.addPage(page);
            final var pixels = red();
            final var image = LosslessFactory.createFromImage(document, pixels);
            pixels.flush();
            try (final var content = new PDPageContentStream(document, page)) {
                content.saveGraphicsState();
                content.setGraphicsStateParameters(state(.25f, BlendMode.MULTIPLY));
                content.drawImage(image, 10, 40, 20, 20);
                content.restoreGraphicsState();
                content.drawImage(image, 50, 40, 20, 20);
                content.saveGraphicsState();
                content.setGraphicsStateParameters(state(0, BlendMode.SCREEN));
                content.drawImage(image, 90, 40, 20, 20);
                content.restoreGraphicsState();
            }
            source = save(document);
        }
        final var references = images(source, false);
        final var payloads = images(source, true);
        assertEquals(3, references.size());
        assertEquals(3, payloads.size());
        final var expected =
                List.of(paint(.25, "Multiply", false), paint(1, "Normal", false), paint(0, "Screen", false));
        for (int index = 0; index < 3; index++) {
            assertEquals(expected.get(index), graphics(references.get(index)));
            assertEquals(expected.get(index), graphics(payloads.get(index)));
            assertEquals(payloads.getFirst().metadata().get("image_base64"),
                    payloads.get(index).metadata().get("image_base64"));
            assertEquals(references.get(index).metadata().get("coordinates"),
                    payloads.get(index).metadata().get("coordinates"));
            assertThrows(UnsupportedOperationException.class, () -> graphics(payloads.getFirst()).clear());
        }
        try (final var original = Loader.loadPDF(source)) {
            final var rendered = new PDFRenderer(original).renderImage(0);
            try {
                assertEquals(Color.RED.getRGB(), rendered.getRGB(60, 50));
                assertEquals(Color.WHITE.getRGB(), rendered.getRGB(100, 50));
                assertNotEquals(rendered.getRGB(20, 50), rendered.getRGB(60, 50));
                assertNotEquals(Color.WHITE.getRGB(), rendered.getRGB(20, 50));
            } finally {
                rendered.flush();
            }
        }
    }

    /**
     * Ordinary forms inherit invocation state while transparency groups reset their internal alpha/blend state.
     * Group composition remains outside the per-image state contract rather than being multiplied into the PNG.
     * @throws Exception if form authoring or public extraction fails
     */
    @Test
    void inheritedFormAndTransparencyGroupStateRemainDistinctAndRestoreCallerState() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 100));
            document.addPage(page);
            final var pixels = red();
            final var image = LosslessFactory.createFromImage(document, pixels);
            pixels.flush();
            final var form = new PDFormXObject(document);
            final var group = new PDTransparencyGroup(document);
            final var attributes = new COSDictionary();
            attributes.setItem(COSName.S, COSName.TRANSPARENCY);
            group.getCOSObject().setItem(COSName.GROUP, attributes);
            for (final var child : List.of(form, group)) {
                child.setResources(new PDResources());
                child.setBBox(new PDRectangle(20, 20));
                try (final var content = new PDFormContentStream(child)) {
                    content.drawImage(image, 0, 0, 20, 20);
                }
            }
            try (final var content = new PDPageContentStream(document, page)) {
                content.setGraphicsStateParameters(state(.5f, BlendMode.MULTIPLY));
                content.drawForm(form);
                content.drawForm(group);
                content.drawImage(image, 90, 40, 20, 20);
            }
            source = save(document);
        }
        final var actual = images(source, false).stream().map(PdfImageGraphicsStateTest::graphics).toList();
        assertEquals(3, actual.size());
        assertEquals(2, actual.stream().filter(paint(.5, "Multiply", false)::equals).count());
        assertEquals(1, actual.stream().filter(paint(1, "Normal", false)::equals).count());
    }

    /**
     * Graphics-state soft masks and alpha-as-shape are reported separately from intrinsic image masks.
     * The mask group is not emitted as an additional painted page occurrence by source-only traversal.
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void softMaskAndShapeFlagsDescribeInvocationWithoutChangingIntrinsicPayload() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 100));
            document.addPage(page);
            final var pixels = red();
            final var image = LosslessFactory.createFromImage(document, pixels);
            pixels.flush();
            final var group = new PDTransparencyGroup(document);
            group.setResources(new PDResources());
            group.setBBox(new PDRectangle(200, 100));
            final var attributes = new COSDictionary();
            attributes.setItem(COSName.S, COSName.TRANSPARENCY);
            group.getCOSObject().setItem(COSName.GROUP, attributes);
            try (final var mask = new PDFormContentStream(group)) {
                mask.setNonStrokingColor(Color.WHITE);
                mask.addRect(0, 0, 200, 100);
                mask.fill();
            }
            final var softMask = new COSDictionary();
            softMask.setItem(COSName.S, COSName.ALPHA);
            softMask.setItem(COSName.G, group);
            final var masked = state(.5f, BlendMode.NORMAL);
            masked.getCOSObject().setItem(COSName.SMASK, softMask);
            masked.setAlphaSourceFlag(true);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 10, 40, 20, 20);
                content.saveGraphicsState();
                content.setGraphicsStateParameters(masked);
                content.drawImage(image, 50, 40, 20, 20);
                content.restoreGraphicsState();
                content.drawImage(image, 90, 40, 20, 20);
            }
            source = save(document);
        }
        final var actual = images(source, true);
        assertEquals(3, actual.size());
        assertEquals(paint(1, "Normal", false), graphics(actual.getFirst()));
        assertEquals(
                Map.of("nonstroking_alpha", .5, "blend_mode", "Normal", "has_soft_mask", true, "alpha_is_shape", true),
                graphics(actual.get(1)));
        assertEquals(paint(1, "Normal", false), graphics(actual.getLast()));
        assertEquals(actual.getFirst().metadata().get("image_base64"), actual.get(1).metadata().get("image_base64"));
    }

    /**
     * Builds one complete source state with explicit nonstroking alpha and blend mode.
     * @param alpha source constant, independent from the image's intrinsic sample alpha
     * @param blend source blending mode
     * @return owned extended graphics state
     */
    private static PDExtendedGraphicsState state(final float alpha, final BlendMode blend) {
        final var result = new PDExtendedGraphicsState();
        result.setNonStrokingAlphaConstant(alpha);
        result.setBlendMode(blend);
        return result;
    }

    /**
     * Independent expected invocation properties, without using production extraction to generate expectations.
     * @param alpha exact supported source constant
     * @param blend PDF standard blend-mode name
     * @param softMask whether a graphics-state soft mask is active
     * @return expected immutable metadata
     */
    private static Map<String, Object> paint(final double alpha, final String blend, final boolean softMask) {
        return Map.of(
                "nonstroking_alpha", alpha, "blend_mode", blend, "has_soft_mask", softMask, "alpha_is_shape", false);
    }

    /**
     * Reads one actual public state map.
     * @param element emitted source image
     * @return original immutable invocation metadata
     */
    private static Map<?, ?> graphics(final Element element) {
        assertInstanceOf(Map.class, element.metadata().get("pdf_image_graphics_state"));
        return (Map<?, ?>) element.metadata().get("pdf_image_graphics_state");
    }

    /**
     * Authors opaque red source pixels so page transparency cannot be mistaken for intrinsic PNG alpha.
     * @return owned two-by-two RGB source image
     */
    private static BufferedImage red() {
        final var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                image.setRGB(x, y, Color.RED.getRGB());
            }
        }
        return image;
    }

    /**
     * Encodes a complete owned source without model or external input dependencies.
     * @param document authored document
     * @return complete source bytes
     * @throws Exception if PDF encoding fails
     */
    private static byte[] save(final PDDocument document) throws Exception {
        final var output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }

    /**
     * Runs the unchanged public source-image extraction path under explicit payload selection.
     * @param source complete source bytes
     * @param payload whether PNG samples are requested
     * @return every original image occurrence, including fully transparent occurrences
     * @throws Exception if partitioning or a resource limit fails
     */
    private static List<Element> images(final byte[] source, final boolean payload) throws Exception {
        final var options = new PdfOptions(10, 10_000, 10_000, 100, 10_000, payload, TableLimits.defaults());
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(source, "owned-image-paint.pdf"))
                .elements()
                .stream()
                .filter(element -> element.type() == ElementType.Image)
                .toList();
    }
}
