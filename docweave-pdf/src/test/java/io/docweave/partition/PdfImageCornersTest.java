package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/** Analytical source-corner identities cover reflections, shear, inherited forms, inline images and physical units. */
class PdfImageCornersTest {
    /** Creates isolated owned-source controls. */
    PdfImageCornersTest() {
    }

    /**
     * Source corner identities survive all displayed page quarter turns and both payload policies.
     * @throws Exception if source authoring, public extraction or decoded payload verification fails
     */
    @Test
    void retainsOrderedAffineCornersWithCropRotationAndUserUnit() throws Exception {
        final var transforms = List.of(new float[] {40, 0, 0, 20, 50, 60}, new float[] {-40, 0, 0, 20, 90, 60},
                new float[] {0, 40, -20, 0, 90, 60}, new float[] {40, 8, 12, 20, 50, 60},
                new float[] {40, 0, 0, -20, 50, 80}, new float[] {40, 8, 12, 20, 5, 10});
        for (final int rotation : List.of(0, 90, 180, 270)) {
            for (final var transform : transforms) {
                final byte[] pdf = source(transform, rotation);
                final var reference = images(pdf, false).getFirst();
                final var payload = images(pdf, true).getFirst();
                final var corners = corners(reference);
                final var expected = List.of(point(transform, 0, 0, rotation), point(transform, 1, 0, rotation),
                        point(transform, 1, 1, rotation), point(transform, 0, 1, rotation));
                assertEquals(expected, corners.get("points"));
                assertEquals(rotation % 180 == 0 ? 200.0 : 140.0, corners.get("layout_width"));
                assertEquals(rotation % 180 == 0 ? 140.0 : 200.0, corners.get("layout_height"));
                assertEquals("PDFUserSpace", corners.get("system"));
                assertEquals("source_unit_square_00_10_11_01", corners.get("point_order"));
                assertEquals(2.0, reference.metadata().get("pdf_user_unit"));
                assertEquals(corners, corners(payload));
                final var metadata = new LinkedHashMap<>(payload.metadata());
                metadata.remove("image_base64");
                metadata.remove("image_mime_type");
                assertEquals(reference.metadata(), metadata);
                final var pixels = ImageIO.read(new ByteArrayInputStream(
                        Base64.getDecoder().decode(payload.metadata().get("image_base64").toString())));
                try {
                    assertEquals(0xffff0000, pixels.getRGB(0, 0));
                    assertEquals(0xff00ff00, pixels.getRGB(1, 0));
                } finally {
                    pixels.flush();
                }
                final var box = (Map<?, ?>) reference.metadata().get("coordinates");
                final double left = expected.stream().mapToDouble(p -> p.get(0)).min().orElseThrow();
                final double right = expected.stream().mapToDouble(p -> p.get(0)).max().orElseThrow();
                final double top = expected.stream().mapToDouble(p -> p.get(1)).min().orElseThrow();
                final double bottom = expected.stream().mapToDouble(p -> p.get(1)).max().orElseThrow();
                assertEquals(
                        List.of(List.of(left, top), List.of(right, top), List.of(right, bottom), List.of(left, bottom)),
                        box.get("points"));
            }
        }
    }

    /**
     * The same raster gets distinct corner identities at each inherited form placement and inline invocation.
     * @throws Exception if owned form/inline sources or public extraction fails
     */
    @Test
    void keepsOccurrenceTransformsThroughFormsAndInlineImages() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            document.addPage(page);
            final var form = new PDFormXObject(document);
            form.setResources(new PDResources());
            form.setBBox(new PDRectangle(50, 30));
            final var pixels = raster();
            try (final var stream = new PDFormContentStream(form)) {
                stream.drawImage(LosslessFactory.createFromImage(document, pixels), 0, 0, 40, 20);
            } finally {
                pixels.flush();
            }
            try (final var stream = new PDPageContentStream(document, page)) {
                for (final int shift : List.of(20, 100)) {
                    stream.saveGraphicsState();
                    stream.transform(new Matrix(1, .25f, .5f, 1, shift, 40));
                    stream.drawForm(form);
                    stream.restoreGraphicsState();
                }
            }
            final var output = new ByteArrayOutputStream();
            document.save(output);
            final var images = images(output.toByteArray(), false);
            assertEquals(2, images.size());
            for (final var image : images) {
                final var points = (List<?>) corners(image).get("points");
                final double shift = ((Number) ((List<?>) points.getFirst()).getFirst()).doubleValue();
                assertTrue(shift == 20 || shift == 100);
                assertEquals(List.of(List.of(shift, 160.0), List.of(shift + 40, 150.0), List.of(shift + 50, 130.0),
                                     List.of(shift + 10, 140.0)),
                        points);
            }
        }
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            document.addPage(page);
            final var commands = "q -40 0 0 20 70 50 cm BI /W 1 /H 1 /CS /RGB /BPC 8 ID ";
            final var bytes = new ByteArrayOutputStream();
            bytes.write(commands.getBytes(StandardCharsets.US_ASCII));
            bytes.write(new byte[] {(byte) 255, 0, 0});
            bytes.write("\nEI Q\n".getBytes(StandardCharsets.US_ASCII));
            page.setContents(new PDStream(document, new ByteArrayInputStream(bytes.toByteArray())));
            final var output = new ByteArrayOutputStream();
            document.save(output);
            assertEquals(
                    List.of(List.of(70.0, 150.0), List.of(30.0, 150.0), List.of(30.0, 130.0), List.of(70.0, 130.0)),
                    corners(images(output.toByteArray(), true).getFirst()).get("points"));
        }
    }

    /**
     * Both geometry maps use their own reversible physical frames; malformed added maps cannot bypass validation.
     * @throws Exception if source extraction or coordinate conversion fails
     */
    @Test
    void convertsImageCornersWithoutReorderingOrChangingSourceContent() throws Exception {
        final var source = images(source(new float[] {40, 8, 12, 20, 50, 60}, 90), true).getFirst();
        final var converter = PdfCoordinateConverter.create();
        final var points = converter.toPoints(source);
        final var pixels = converter.toPixels(source, 144);
        final var millimeters = converter.toMillimeters(source);
        for (final var converted : List.of(points, pixels, millimeters)) {
            final var restored = converter.toUserSpace(converted);
            near(corners(source), corners(restored));
            near((Map<?, ?>) source.metadata().get("coordinates"),
                    (Map<?, ?>) restored.metadata().get("coordinates"));
            final var originalMetadata = new LinkedHashMap<>(source.metadata());
            final var restoredMetadata = new LinkedHashMap<>(restored.metadata());
            for (final String key : List.of("coordinates", "pdf_image_coordinates")) {
                originalMetadata.remove(key);
                restoredMetadata.remove(key);
            }
            assertEquals(originalMetadata, restoredMetadata);
            assertEquals(source.elementId(), converted.elementId());
            assertEquals(source.metadata().get("image_base64"), converted.metadata().get("image_base64"));
            assertEquals("source_unit_square_00_10_11_01", corners(converted).get("point_order"));
        }
        final var expected = ((List<?>) corners(source).get("points"))
                                     .stream()
                                     .map(p -> ((List<?>) p).stream().map(v -> ((Number) v).doubleValue() * 4).toList())
                                     .toList();
        assertEquals(expected, corners(pixels).get("points"));
        final var malformed = new LinkedHashMap<>(corners(source));
        malformed.put("points", List.of(List.of(1.0, 2.0)));
        final var metadata = new LinkedHashMap<>(source.metadata());
        metadata.put("pdf_image_coordinates", malformed);
        assertThrows(IllegalArgumentException.class,
                () -> converter.toPoints(new Element(source.elementId(), source.type(), source.text(), metadata)));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) corners(source).get("points")).clear());
    }

    /**
     * Allows ordinary floating-point roundoff in physical-unit round trips, without reordering any corner.
     * @param expected original displayed coordinates
     * @param actual restored coordinates
     */
    private static void near(final Map<?, ?> expected, final Map<?, ?> actual) {
        assertEquals(expected.get("system"), actual.get("system"));
        for (final String key : List.of("layout_width", "layout_height")) {
            assertEquals(((Number) expected.get(key)).doubleValue(), ((Number) actual.get(key)).doubleValue(), 1e-10);
        }
        final var first = (List<?>) expected.get("points");
        final var second = (List<?>) actual.get("points");
        assertEquals(4, second.size());
        for (int corner = 0; corner < 4; corner++) {
            for (int axis = 0; axis < 2; axis++) {
                assertEquals(((Number) ((List<?>) first.get(corner)).get(axis)).doubleValue(),
                        ((Number) ((List<?>) second.get(corner)).get(axis)).doubleValue(), 1e-10);
            }
        }
    }

    /**
     * Calculates independent displayed source positions without the production geometry helper.
     * @param matrix six original affine coefficients
     * @param x source unit-square horizontal coordinate
     * @param y source unit-square vertical coordinate
     * @param rotation clockwise displayed quarter turn
     * @return exact displayed unscaled source position
     */
    private static List<Double> point(final float[] matrix, final int x, final int y, final int rotation) {
        final double px = matrix[0] * x + matrix[2] * y + matrix[4] - 10;
        final double py = matrix[1] * x + matrix[3] * y + matrix[5] - 20;
        return switch (rotation) {
            case 90 -> List.of(py, px);
            case 180 -> List.of(200 - px, py);
            case 270 -> List.of(140 - py, 200 - px);
            default -> List.of(px, 140 - py);
        };
    }

    /**
     * Creates distinguishable source pixels without applying a placement transform to the payload.
     * @return caller-owned opaque two-pixel raster
     */
    private static BufferedImage raster() {
        final var pixels = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        pixels.setRGB(0, 0, 0xffff0000);
        pixels.setRGB(1, 0, 0xff00ff00);
        return pixels;
    }

    /**
     * Authors an original source image under explicit crop, rotation, UserUnit and affine transform.
     * @param matrix six source image coefficients
     * @param rotation displayed page quarter turn
     * @return complete owned PDF bytes
     * @throws Exception if source writing fails
     */
    private static byte[] source(final float[] matrix, final int rotation) throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(300, 300));
            page.setCropBox(new PDRectangle(10, 20, 200, 140));
            page.setRotation(rotation);
            page.setUserUnit(2);
            document.addPage(page);
            final var pixels = raster();
            try (final var stream = new PDPageContentStream(document, page)) {
                stream.drawImage(LosslessFactory.createFromImage(document, pixels),
                        new Matrix(matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]));
            } finally {
                pixels.flush();
            }
            final var output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Extracts every public image occurrence with unchanged resource budgets.
     * @param bytes complete original PDF
     * @param payloads whether normalized PNG payloads are requested
     * @return all image elements
     * @throws Exception if public partitioning fails
     */
    private static List<Element> images(final byte[] bytes, final boolean payloads) throws Exception {
        final var options = new PdfOptions(10, 10_000, 10_000, 100, 10_000, payloads, TableLimits.defaults());
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(bytes, "owned-affine-images.pdf"))
                .elements()
                .stream()
                .filter(e -> e.type() == ElementType.Image)
                .toList();
    }

    /**
     * Reads the immutable public source-image coordinate map.
     * @param image original or converted image element
     * @return source-corner metadata
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> corners(final Element image) {
        return (Map<String, Object>) image.metadata().get("pdf_image_coordinates");
    }
}
