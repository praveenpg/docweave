package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.json.ElementJson;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-module preservation of opt-in enclosing transparency-group context through JSON and unit conversion. */
class PdfImageGroupContextIntegrationTest {
    /** Creates an isolated integration control without shared PDF/model resources. */
    PdfImageGroupContextIntegrationTest() {
    }

    /**
     * The ordered scalar context survives ElementJson round trips and is untouched by coordinate conversion,
     * while source identity, corners and PNG payload remain unchanged.
     * @throws Exception if source authoring, public extraction, serialization or conversion fails
     */
    @Test
    void groupContextSurvivesJsonAndPhysicalCoordinateConversion() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(100, 60));
            document.addPage(page);
            final var pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            pixels.setRGB(0, 0, Color.RED.getRGB());
            final var image = LosslessFactory.createFromImage(document, pixels);
            pixels.flush();
            final var group = new PDTransparencyGroup(document);
            final var attributes = new COSDictionary();
            attributes.setItem(COSName.S, COSName.TRANSPARENCY);
            attributes.setBoolean(COSName.I, true);
            group.getCOSObject().setItem(COSName.GROUP, attributes);
            group.setResources(new PDResources());
            group.setBBox(new PDRectangle(100, 60));
            try (final var content = new PDFormContentStream(group)) {
                content.drawImage(image, 10, 10, 20, 20);
            }
            final var state = new PDExtendedGraphicsState();
            state.setNonStrokingAlphaConstant(.25f);
            state.setBlendMode(BlendMode.MULTIPLY);
            try (final var content = new PDPageContentStream(document, page)) {
                content.setGraphicsStateParameters(state);
                content.drawForm(group);
            }
            final var output = new ByteArrayOutputStream();
            document.save(output);
            source = output.toByteArray();
        }
        final var options = new PdfOptions(10, 10_000, 10_000, 100, 10_000, true, TableLimits.defaults())
                                    .withImageGroupContext(true)
                                    .withImageClipBounds(true);
        final var elements = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                                     .partition(DocumentSource.bytes(source, "image-group-context-roundtrip.pdf"))
                                     .elements();
        final var image =
                elements.stream().filter(element -> element.type() == ElementType.Image).findFirst().orElseThrow();
        assertTrue(image.metadata().containsKey("pdf_image_clip_bounds"));
        final var expected = List.of(Map.of("nonstroking_alpha", .25, "blend_mode", "Multiply", "has_soft_mask", false,
                "alpha_is_shape", false, "isolated", true, "knockout", false));
        final var original = ElementJson.create().write(elements);
        final var decoded = ElementJson.create()
                                    .read(original)
                                    .stream()
                                    .filter(element -> element.type() == ElementType.Image)
                                    .findFirst()
                                    .orElseThrow();
        assertEquals(image, decoded);
        final var pixels = PdfCoordinateConverter.create().toPixels(decoded, 144);
        final var points = PdfCoordinateConverter.create().toPoints(pixels);
        for (final var value : List.of(image, decoded, pixels, points)) {
            assertEquals(expected, value.metadata().get("pdf_image_group_context"));
            assertEquals(image.elementId(), value.elementId());
            assertEquals(image.metadata().get("image_base64"), value.metadata().get("image_base64"));
        }
        assertEquals(original, ElementJson.create().write(elements));
    }
}
