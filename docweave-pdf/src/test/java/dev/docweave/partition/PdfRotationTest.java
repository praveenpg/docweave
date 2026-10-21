package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Source transforms, constant displayed expectations and an independent renderer path validate the shared frame. */
class PdfRotationTest {
    @Test
    void quarterTurnPagesNormalizeTextTablesImagesAndDisplayedDimensions() throws Exception {
        final var processor = DocWeave.create();
        final var baseline = processor.partition(DocumentSource.bytes(scene(0, 0, 0, 1, false), "scene.pdf"));
        assertEquals(List.of("Wrapped native line.", "Continuation of the line.", "A B C D", ""),
                baseline.elements().stream().map(Element::text).toList());
        for (final int rotation : new int[] {0, 90, 180, 270, -90, 450, 360000090}) {
            final byte[] bytes = scene(rotation, 0, 0, 1, false);
            final var result = processor.partition(DocumentSource.bytes(bytes, "scene.pdf"));
            assertEquals(baseline.elements().stream().map(Element::text).toList(),
                    result.elements().stream().map(Element::text).toList());
            for (int index = 0; index < baseline.elements().size(); index++) {
                assertCoordinates(baseline.elements().get(index), result.elements().get(index));
                assertEquals(baseline.elements().get(index).type(), result.elements().get(index).type());
            }
            final var image = result.elements().getLast();
            assertEquals(
                    List.of(List.of(40.0, 240.0), List.of(70.0, 240.0), List.of(70.0, 260.0), List.of(40.0, 260.0)),
                    coordinates(image).get("points"));
            if (Math.floorMod(rotation, 360) != 0) {
                assertEquals(Math.floorMod(rotation, 360), image.metadata().get("pdf_page_rotation"));
            }
            // The renderer uses its own page transform; a red pixel verifies the constant displayed image location.
            try (final var document = Loader.loadPDF(bytes)) {
                final var rendered = new PDFRenderer(document).renderImage(0, 1);
                assertEquals(400, rendered.getWidth());
                assertEquals(300, rendered.getHeight());
                assertEquals(0xffff0000, rendered.getRGB(55, 250));
                rendered.flush();
            }
        }
    }

    @Test
    void cropOffsetsUserUnitAndInheritedRotationRemainExplicitWithoutDoubleScaling() throws Exception {
        final var processor = DocWeave.create();
        final var baseline = processor.partition(DocumentSource.bytes(scene(0, 0, 0, 1, false), "scene.pdf"));
        for (final int rotation : new int[] {0, 90, 180, 270}) {
            final var result = processor.partition(DocumentSource.bytes(scene(rotation, 50, 70, 2, true), "scene.pdf"));
            for (int index = 0; index < baseline.elements().size(); index++) {
                final var element = result.elements().get(index);
                assertCoordinates(baseline.elements().get(index), element);
                assertEquals(2.0, element.metadata().get("pdf_user_unit"));
                final boolean quarter = rotation == 90 || rotation == 270;
                assertEquals(List.of(50.0, 70.0, quarter ? 350.0 : 450.0, quarter ? 470.0 : 370.0),
                        element.metadata().get("pdf_crop_box"));
                assertEquals(400.0, coordinates(element).get("layout_width"));
                assertEquals(300.0, coordinates(element).get("layout_height"));
            }
        }
    }

    @Test
    void everyPageAndTextQuarterTurnKeepsStringsAndReportsDisplayedOrientation() throws Exception {
        for (final int pageAngle : new int[] {0, 90, 180, 270}) {
            for (final int textAngle : new int[] {0, 90, 180, 270}) {
                try (final var document = new PDDocument()) {
                    final var page = new PDPage(new PDRectangle(400, 400));
                    page.setRotation(pageAngle);
                    document.addPage(page);
                    try (final var stream = new PDPageContentStream(document, page)) {
                        text(stream, 200, 200, textAngle, "Quarter turn");
                    }
                    final var result = DocWeave.create().partition(DocumentSource.bytes(save(document), "turn.pdf"));
                    assertEquals(1, result.elements().size());
                    final var element = result.elements().getFirst();
                    assertEquals("Quarter turn", element.text());
                    assertEquals(Math.floorMod(pageAngle - textAngle, 360),
                            ((Number) element.metadata().getOrDefault("pdf_text_rotation", 0)).intValue());
                }
            }
        }
    }

    @Test
    void mixedOrientationsHaveSeparateTextAndApproximateDisplayedBounds() throws Exception {
        final byte[] bytes;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(400, 300));
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                text(stream, 40, 260, 0, "Horizontal");
                text(stream, 200, 40, 90, "Upward");
                text(stream, 360, 120, 180, "Reverse");
                text(stream, 300, 260, 270, "Downward");
            }
            bytes = save(document);
        }
        final var options = PdfOptions.defaults()
                                    .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS)
                                    .withTableDetection(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT);
        final var result = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                                   .partition(DocumentSource.bytes(bytes, "mixed.pdf"));
        assertEquals(4, result.elements().size());
        final var byText = result.elements().stream().collect(java.util.stream.Collectors.toMap(Element::text, e -> e));
        assertEquals(java.util.Set.of("Horizontal", "Upward", "Reverse", "Downward"), byText.keySet());
        assertEquals(270, byText.get("Upward").metadata().get("pdf_text_rotation"));
        assertEquals(180, byText.get("Reverse").metadata().get("pdf_text_rotation"));
        assertEquals(90, byText.get("Downward").metadata().get("pdf_text_rotation"));
        final var upward = points(byText.get("Upward"));
        assertEquals(200, upward.get(1).get(0).doubleValue(), 0.05);
        assertEquals(260, upward.get(2).get(1).doubleValue(), 0.05);
        final var reverse = points(byText.get("Reverse"));
        assertEquals(360, reverse.get(1).get(0).doubleValue(), 0.05);
        assertEquals(180, reverse.getFirst().get(1).doubleValue(), 0.05);
        final var downward = points(byText.get("Downward"));
        assertEquals(300, downward.getFirst().get(0).doubleValue(), 0.05);
        assertEquals(40, downward.getFirst().get(1).doubleValue(), 0.05);
        assertTrue(result.elements().stream().allMatch(
                e -> !e.metadata().containsKey("pdf_column") && !e.metadata().containsKey("pdf_line_count")));
    }

    @Test
    void paragraphsAndTableOwnershipWorkWhenSourceRotationCancelsPageRotation() throws Exception {
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(
                PdfPartitioner.create(PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.PARAGRAPHS)))));
        for (final int rotation : new int[] {0, 90, 180, 270}) {
            final var result =
                    processor.partition(DocumentSource.bytes(scene(rotation, 50, 70, 1, false), "paragraph.pdf"));
            assertEquals(List.of("Wrapped native line. Continuation of the line.", "A B C D", ""),
                    result.elements().stream().map(Element::text).toList());
            assertEquals(List.of(ElementType.UncategorizedText, ElementType.Table, ElementType.Image),
                    result.elements().stream().map(Element::type).toList());
        }
    }

    @Test
    void sidewaysTextStillBelongsToItsRuledCellOnce() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                for (final int x : new int[] {40, 180, 320}) {
                    stream.moveTo(x, 40);
                    stream.lineTo(x, 320);
                }
                for (final int y : new int[] {40, 180, 320}) {
                    stream.moveTo(40, y);
                    stream.lineTo(320, y);
                }
                stream.stroke();
                text(stream, 100, 210, 90, "Sideways");
                text(stream, 200, 230, 0, "Ordinary");
            }
            final var result = DocWeave.create().partition(DocumentSource.bytes(save(document), "cells.pdf"));
            assertEquals(1, result.elements().size());
            assertEquals(ElementType.Table, result.elements().getFirst().type());
            assertEquals("Sideways Ordinary", result.elements().getFirst().text());
            assertEquals(
                    "<table><tbody><tr><td>Sideways</td><td>Ordinary</td></tr><tr><td></td><td></td></tr></tbody></table>",
                    result.elements().getFirst().metadata().get("text_as_html"));
        }
    }

    @Test
    void sidewaysTextInsideAlignedNumericCandidateIsAConservativeBarrier() throws Exception {
        for (final boolean sideways : new boolean[] {false, true}) {
            try (final var document = new PDDocument()) {
                final var page = new PDPage(new PDRectangle(400, 300));
                document.addPage(page);
                try (final var stream = new PDPageContentStream(document, page)) {
                    final var rows = List.of(List.of("Item", "Qty"), List.of("Alpha", "10"), List.of("Beta", "20"));
                    for (int row = 0; row < rows.size(); row++) {
                        text(stream, 40, 260 - row * 20, 0, rows.get(row).getFirst());
                        text(stream, 220, 260 - row * 20, 0, rows.get(row).getLast());
                    }
                    if (sideways) {
                        text(stream, 150, 220, 90, "Side");
                    }
                }
                final var source = DocumentSource.bytes(save(document), "barrier.pdf");
                final var selected = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                        PdfOptions.defaults().withTableDetection(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT)))));
                final var result = selected.partition(source);
                if (sideways) {
                    assertTrue(result.elements().stream().noneMatch(e -> e.type() == ElementType.Table));
                    assertEquals(DocWeave.create().partition(source).elements(), result.elements());
                } else {
                    assertEquals(1, result.elements().size());
                    assertEquals(ElementType.Table, result.elements().getFirst().type());
                }
            }
        }
    }

    @Test
    void unsupportedTextTransformsAndInvalidUnitsFailExplicitly() throws Exception {
        for (final int textAngle : new int[] {30, 45, 120}) {
            try (final var document = new PDDocument()) {
                final var page = new PDPage();
                document.addPage(page);
                try (final var stream = new PDPageContentStream(document, page)) {
                    text(stream, 100, 100, textAngle, "Skewed");
                }
                assertEquals(PartitionException.Code.UNSUPPORTED_OPTION,
                        assertThrows(PartitionException.class,
                                () -> DocWeave.create().partition(DocumentSource.bytes(save(document), "skew.pdf")))
                                .code());
            }
        }
        for (final int flip : new int[] {-1, 1}) {
            try (final var document = new PDDocument()) {
                final var page = new PDPage();
                document.addPage(page);
                try (final var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.setTextMatrix(new Matrix(flip, 0, 0, -flip, 200, 200));
                    stream.showText("Mirrored");
                    stream.endText();
                }
                assertEquals(PartitionException.Code.UNSUPPORTED_OPTION,
                        assertThrows(PartitionException.class,
                                () -> DocWeave.create().partition(DocumentSource.bytes(save(document), "mirror.pdf")))
                                .code());
            }
        }
        for (final float unit : new float[] {0, -1}) {
            try (final var document = new PDDocument()) {
                final var page = new PDPage();
                page.getCOSObject().setFloat(COSName.USER_UNIT, unit);
                document.addPage(page);
                assertEquals(PartitionException.Code.MALFORMED_DOCUMENT,
                        assertThrows(PartitionException.class,
                                () -> DocWeave.create().partition(DocumentSource.bytes(save(document), "unit.pdf")))
                                .code());
            }
        }
    }

    /**
     * Builds one independently rendered constant displayed scene for native strategy regressions.
     * @param angle declared source page quarter turn, possibly inherited or noncanonical
     * @param left crop origin x
     * @param bottom crop origin y
     * @param unit physical UserUnit metadata
     * @param inherited whether rotation is inherited from the page tree
     * @return complete owned PDF bytes
     * @throws Exception if fixture creation fails
     */
    static byte[] scene(final int angle, final int left, final int bottom, final float unit, final boolean inherited)
            throws Exception {
        final int rotation = Math.floorMod(angle, 360);
        final boolean quarter = rotation == 90 || rotation == 270;
        final int width = quarter ? 300 : 400;
        final int height = quarter ? 400 : 300;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(width + left * 2, height + bottom * 2));
            page.setCropBox(new PDRectangle(left, bottom, width, height));
            page.setUserUnit(unit);
            document.addPage(page);
            if (inherited) {
                document.getPages().getCOSObject().setInt(COSName.ROTATE, angle);
            } else {
                page.setRotation(angle);
            }
            final Matrix transform = switch (rotation) {
                case 90 -> new Matrix(0, 1, -1, 0, left + width, bottom);
                case 180 -> new Matrix(-1, 0, 0, -1, left + width, bottom + height);
                case 270 -> new Matrix(0, -1, 1, 0, left, bottom + height);
                default -> Matrix.getTranslateInstance(left, bottom);
            };
            try (final var stream = new PDPageContentStream(document, page)) {
                stream.transform(transform);
                text(stream, 40, 260, 0, "Wrapped native line.");
                text(stream, 40, 242, 0, "Continuation of the line.");
                for (final int y : new int[] {120, 160, 200}) {
                    stream.moveTo(40, y);
                    stream.lineTo(260, y);
                }
                for (final int x : new int[] {40, 150, 260}) {
                    stream.moveTo(x, 120);
                    stream.lineTo(x, 200);
                }
                stream.stroke();
                text(stream, 50, 180, 0, "A");
                text(stream, 160, 180, 0, "B");
                text(stream, 50, 140, 0, "C");
                text(stream, 160, 140, 0, "D");
                final var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < 2; y++) {
                    for (int x = 0; x < 3; x++) {
                        image.setRGB(x, y, 0xffff0000);
                    }
                }
                stream.drawImage(LosslessFactory.createFromImage(document, image), 40, 40, 30, 20);
            }
            return save(document);
        }
    }

    private static void text(final PDPageContentStream stream, final float x, final float y, final int angle,
            final String text) throws Exception {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(angle), x, y));
        stream.showText(text);
        stream.endText();
    }

    private static byte[] save(final PDDocument document) throws Exception {
        final var bytes = new ByteArrayOutputStream();
        document.save(bytes);
        return bytes.toByteArray();
    }

    private static Map<?, ?> coordinates(final Element element) {
        return (Map<?, ?>) element.metadata().get("coordinates");
    }

    @SuppressWarnings("unchecked")
    private static List<List<Number>> points(final Element element) {
        return (List<List<Number>>) coordinates(element).get("points");
    }

    private static void assertCoordinates(final Element expected, final Element actual) {
        assertEquals(coordinates(expected).get("layout_width"), coordinates(actual).get("layout_width"));
        assertEquals(coordinates(expected).get("layout_height"), coordinates(actual).get("layout_height"));
        final var first = points(expected);
        final var second = points(actual);
        for (int corner = 0; corner < 4; corner++) {
            for (int axis = 0; axis < 2; axis++) {
                assertEquals(
                        first.get(corner).get(axis).doubleValue(), second.get(corner).get(axis).doubleValue(), 0.05);
            }
        }
    }
}
