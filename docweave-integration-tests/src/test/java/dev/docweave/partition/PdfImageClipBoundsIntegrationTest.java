package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-module preservation of the opt-in image clip-bounds classification through JSON and coordinate conversion. */
class PdfImageClipBoundsIntegrationTest {
    /** Creates an isolated integration control without shared PDF/model resources. */
    PdfImageClipBoundsIntegrationTest() {
    }

    /**
     * The nested bounds map and its classification survive JSON encoding and physical coordinate conversion.
     * @throws Exception if source authoring, public extraction, serialization or conversion fails
     */
    @Test
    void clipBoundsSurvivesJsonAndPhysicalCoordinateConversion() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(100, 60));
            document.addPage(page);
            final var pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            pixels.setRGB(0, 0, Color.RED.getRGB());
            final var image = LosslessFactory.createFromImage(document, pixels);
            pixels.flush();
            try (final var content = new PDPageContentStream(document, page)) {
                content.saveGraphicsState();
                content.addRect(20, 0, 20, 60);
                content.clip();
                content.drawImage(image, 10, 10, 20, 20);
                content.restoreGraphicsState();
            }
            final var output = new ByteArrayOutputStream();
            document.save(output);
            source = output.toByteArray();
        }
        final var options = new PdfOptions(10, 10_000, 10_000, 100, 10_000, false, TableLimits.defaults())
                                    .withImageClipBounds(true);
        final var elements = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                                     .partition(DocumentSource.bytes(source, "image-clip-bounds-roundtrip.pdf"))
                                     .elements();
        final var image =
                elements.stream().filter(element -> element.type() == ElementType.Image).findFirst().orElseThrow();
        assertTrue(image.metadata().containsKey("pdf_image_clip_bounds"));
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
            final var clip = (java.util.Map<?, ?>) value.metadata().get("pdf_image_clip_bounds");
            assertEquals("BOUNDED", clip.get("classification"));
            assertEquals(false, clip.get("excludes_image_placement"));
            assertEquals(image.elementId(), value.elementId());
        }
        assertEquals(original, ElementJson.create().write(elements));
    }
}
