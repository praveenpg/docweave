package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Owned arbitrary-angle controls retain text, displayed bounds and conservative ownership boundaries. */
class PdfAngledTextTest {
    @Test
    void optInRetainsProjectedLinesAcrossAnglesAndDisplayedQuarterTurns() throws Exception {
        final var options = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.ORIENTED_LINES);
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
        for (final double angle : new double[] {5.5, 30, 135, 225, 315, -10}) {
            for (final int rotation : new int[] {0, 90, 180, 270}) {
                final var source = fixture(angle, rotation, false, false, false);
                final var error = assertThrows(PartitionException.class, () -> DocWeave.create().partition(source));
                assertEquals(PartitionException.Code.UNSUPPORTED_OPTION, error.code());
                final var elements = processor.partition(source).elements();
                assertEquals(1, elements.size());
                final var text = elements.getFirst();
                assertEquals("Alpha beta", text.text());
                assertEquals("projected_baseline_v1", text.metadata().get("pdf_text_geometry"));
                final double expectedAngle = ((rotation - angle) % 360 + 360) % 360;
                assertEquals(expectedAngle, ((Number) text.metadata().get("pdf_text_rotation")).doubleValue(), 0.01);
                final var coordinates = (Map<?, ?>)text.metadata().get("coordinates");
                assertEquals(rotation % 180 == 0 ? 200.0 : 300.0, coordinates.get("layout_width"));
                assertEquals(rotation % 180 == 0 ? 300.0 : 200.0, coordinates.get("layout_height"));
                assertEquals(2.0, ((Number) text.metadata().get("pdf_user_unit")).doubleValue());
                for (final var point : (List<?>) coordinates.get("points")) {
                    for (final var value : (List<?>) point) {
                        assertTrue(Double.isFinite(((Number) value).doubleValue()));
                    }
                }
            }
        }
    }

    @Test
    void unrelatedClosedTablesSurviveButIntersectingAngledTextVetoesOwnership() throws Exception {
        final var options = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.ORIENTED_LINES);
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
        final var separate = processor.partition(fixture(30, 0, true, false, false)).elements();
        assertEquals(1, separate.stream().filter(e -> e.type() == ElementType.Table).count());
        assertEquals(1, separate.stream().filter(e -> e.text().equals("Alpha beta")).count());
        final var crossing = processor.partition(fixture(30, 0, true, true, false)).elements();
        assertTrue(crossing.stream().noneMatch(e -> e.type() == ElementType.Table));
        assertEquals(1, crossing.stream().filter(e -> e.text().equals("Alpha beta")).count());
        for (final String cell : List.of("cell00", "cell01", "cell10", "cell11")) {
            assertTrue(crossing.stream().anyMatch(e -> e.text().contains(cell)));
        }
    }

    @Test
    void angledNativeTextSuppressesTextlessOcrAndMixedMasksExcludeItsProjectedEnvelope() throws Exception {
        final var options = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.ORIENTED_LINES);
        final var calls = new AtomicInteger();
        final PdfOcrProvider provider = image -> {
            calls.incrementAndGet();
            assertEquals(Color.WHITE.getRGB(), image.getRGB(50, 250));
            return List.of(new PdfOcrProvider.Line("scan", 140, 10, 170, 20),
                    new PdfOcrProvider.Line("native duplicate", 40, 250, 70, 270));
        };
        final var source = fixture(30, 0, false, false, true);
        final var textless = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                                                     options, new PdfOcrOptions(72, 100000, 100, 1000), provider))))
                                     .partition(source);
        assertEquals(0, calls.get());
        assertEquals(2, textless.elements().size());
        final var mixed = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options,
                                                  new PdfOcrOptions(72, 100000, 100, 1000)
                                                          .withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS),
                                                  provider))))
                                  .partition(source);
        assertEquals(1, calls.get());
        assertEquals(1, mixed.elements().stream().filter(e -> e.type() == ElementType.Image).count());
        assertEquals(List.of("Alpha beta", "scan"),
                mixed.elements().stream().filter(e -> !e.text().isBlank()).map(e -> e.text()).sorted().toList());
    }

    @Test
    void projectedEnvelopeMatchesAnalyticalSourceCornersAndSharesNativeLimits() throws Exception {
        final var page = new PDPage(new PDRectangle(300, 400));
        page.setCropBox(new PDRectangle(10, 20, 200, 300));
        final var geometry = new PdfPageGeometry(page);
        final var position = new PdfAngledText.Position("alpha", 100, 200, 20, 10, 5, 30);
        final var line = PdfAngledText.lines(List.of(position), geometry).getFirst();
        assertEquals(95, line.box().left(), 0.0001);
        assertEquals(100 + 10 * Math.sqrt(3), line.box().right(), 0.0001);
        assertEquals(100 - 10 - 5 * Math.sqrt(3), line.box().top(), 0.0001);
        assertEquals(100, line.box().bottom(), 0.0001);
        assertEquals(330.0, line.metadata().get("pdf_text_rotation"));
        final var defaults = PdfOptions.defaults();
        final var limited = new PdfOptions(defaults.maxPages(), 1, defaults.maxOperatorsPerPage(),
                defaults.maxImagePixels(), defaults.maxImageBytes(), false, defaults.tableLimits(),
                defaults.tableDetection(), PdfOptions.TextLayout.ORIENTED_LINES);
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(limited))));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                assertThrows(PartitionException.class, () -> processor.partition(fixture(30, 0, false, false, false)))
                        .code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> PdfAngledText.lines(List.of(position), geometry));
        } finally {
            Thread.interrupted();
        }
    }

    private static DocumentSource fixture(final double angle, final int rotation, final boolean table,
            final boolean crossing, final boolean image) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(300, 400));
            page.setCropBox(new PDRectangle(10, 20, 200, 300));
            page.setUserUnit(2);
            page.setRotation(rotation);
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                if (image) {
                    content.drawImage(LosslessFactory.createFromImage(
                                              document, new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB)),
                            10, 20, 200, 300);
                }
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                if (table) {
                    for (final int y : new int[] {200, 230, 260}) {
                        content.moveTo(110, y);
                        content.lineTo(210, y);
                    }
                    for (final int x : new int[] {110, 160, 210}) {
                        content.moveTo(x, 200);
                        content.lineTo(x, 260);
                    }
                    content.stroke();
                    for (int row = 0; row < 2; row++) {
                        for (int column = 0; column < 2; column++) {
                            content.beginText();
                            content.setFont(font, 6);
                            content.newLineAtOffset(115 + column * 50, 242 - row * 30);
                            content.showText("cell" + row + column);
                            content.endText();
                        }
                    }
                }
                content.beginText();
                content.setFont(font, 10);
                content.setTextMatrix(
                        Matrix.getRotateInstance(Math.toRadians(angle), crossing ? 130 : 50, crossing ? 220 : 60));
                content.showText("Alpha beta");
                content.endText();
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "angled.pdf");
        }
    }
}
