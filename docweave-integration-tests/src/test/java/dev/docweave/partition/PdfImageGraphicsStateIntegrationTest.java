package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-module preservation of source-image invocation state through JSON and coordinate conversion. */
class PdfImageGraphicsStateIntegrationTest {
    /** Creates an isolated integration control without shared PDF/model resources. */
    PdfImageGraphicsStateIntegrationTest() {
    }

    /**
     * Existing public codecs/converters retain source state, sample bytes and identity without interpreting
     * compositing.
     * @throws Exception if source authoring, public extraction, serialization or conversion fails
     */
    @Test
    void sourceStateSurvivesJsonAndPhysicalCoordinateConversion() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = new PDPage();
            document.addPage(page);
            final var pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            pixels.setRGB(0, 0, Color.RED.getRGB());
            final var image = LosslessFactory.createFromImage(document, pixels);
            pixels.flush();
            final var state = new PDExtendedGraphicsState();
            state.setNonStrokingAlphaConstant(.25f);
            state.setBlendMode(BlendMode.MULTIPLY);
            try (final var content = new PDPageContentStream(document, page)) {
                content.setGraphicsStateParameters(state);
                content.drawImage(image, 20, 40, 30, 50);
            }
            final var output = new ByteArrayOutputStream();
            document.save(output);
            source = output.toByteArray();
        }
        final var options = new PdfOptions(10, 10_000, 10_000, 100, 10_000, true, TableLimits.defaults());
        final var elements = DocWeave.create(PartitionerRegistry.of(java.util.List.of(PdfPartitioner.create(options))))
                                     .partition(DocumentSource.bytes(source, "image-state-roundtrip.pdf"))
                                     .elements();
        final var image =
                elements.stream().filter(element -> element.type() == ElementType.Image).findFirst().orElseThrow();
        final var expected = Map.of(
                "nonstroking_alpha", .25, "blend_mode", "Multiply", "has_soft_mask", false, "alpha_is_shape", false);
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
        for (final var value : java.util.List.of(image, decoded, pixels, points)) {
            assertEquals(expected, value.metadata().get("pdf_image_graphics_state"));
            assertEquals(image.elementId(), value.elementId());
            assertEquals(image.metadata().get("image_base64"), value.metadata().get("image_base64"));
        }
        assertEquals(original, ElementJson.create().write(elements));
    }
}
