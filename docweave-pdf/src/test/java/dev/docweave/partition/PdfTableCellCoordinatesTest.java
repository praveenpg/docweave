package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit frame expectations independently check emitted cell metadata against source rule geometry. */
class PdfTableCellCoordinatesTest {
    @Test
    void nativeMergedEmptyAnchorsFollowAllDisplayedCropRotationsAndUserUnit() throws Exception {
        final var expected = Map.of(0, List.of(40.0, 20.0, 190.0, 260.0), 90, List.of(60.0, 40.0, 300.0, 190.0), 180,
                List.of(210.0, 60.0, 360.0, 300.0), 270, List.of(20.0, 210.0, 260.0, 360.0));
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final var source = nativeSource(rotation);
            final var baseline = nativeProcessor(PdfOptions.defaults()).partition(source);
            assertFalse(table(baseline).metadata().containsKey("pdf_table_cells"));
            final var result = nativeProcessor(PdfOptions.defaults().withTableCellCoordinates(true)).partition(source);
            final var table = table(result);
            final var metadata = new LinkedHashMap<>(table.metadata());
            final var extension = (Map<?, ?>) metadata.remove("pdf_table_cells");
            assertEquals(table(baseline).metadata(), metadata);
            assertEquals(table(baseline).text(), table.text());
            assertEquals("rule_centerlines", extension.get("geometry"));
            assertEquals(1, ((Number) extension.get("version")).intValue());
            assertEquals(2, ((Number) extension.get("row_count")).intValue());
            assertEquals(2, ((Number) extension.get("column_count")).intValue());
            final var cells = cells(table);
            assertEquals(3, cells.size(), "Empty anchor is retained, covered slot is omitted");
            final var merged = cells.stream()
                                       .filter(c -> number(c, "row_span") == 2 || number(c, "column_span") == 2)
                                       .findFirst()
                                       .orElseThrow();
            assertEquals(rotation % 180 == 0 ? 2 : 1, number(merged, "row_span"));
            assertEquals(rotation % 180 == 0 ? 1 : 2, number(merged, "column_span"));
            assertEquals(expected.get(rotation), rectangle(merged));
            assertEquals(2.0, table.metadata().get("pdf_user_unit"));
            assertEquals(List.of(50.0, 40.0, 450.0, 360.0), table.metadata().get("pdf_crop_box"));
            assertCoordinates(table);
            assertThrows(UnsupportedOperationException.class, () -> cells.clear());
        }
    }

    @Test
    void alignedTextCellsDescribeTextBoundsRatherThanInventedRuleBorders() throws Exception {
        final byte[] pdf;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(500, 400));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                final var rows = List.of(List.of("Item", "Qty"), List.of("Alpha", "10"), List.of("Beta", "20"));
                for (int row = 0; row < rows.size(); row++) {
                    for (int column = 0; column < 2; column++) {
                        text(content, rows.get(row).get(column), 80 + column * 180, 300 - row * 20);
                    }
                }
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        final var options = PdfOptions.defaults()
                                    .withTableDetection(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT)
                                    .withTableCellCoordinates(true);
        final var table = table(nativeProcessor(options).partition(DocumentSource.bytes(pdf, "aligned.pdf")));
        assertEquals("text_bounds", ((Map<?, ?>) table.metadata().get("pdf_table_cells")).get("geometry"));
        final var cells = cells(table);
        assertEquals(6, cells.size());
        for (int index = 0; index < cells.size(); index++) {
            assertEquals(index / 2, number(cells.get(index), "row_index"));
            assertEquals(index % 2, number(cells.get(index), "column_index"));
            assertEquals(1, number(cells.get(index), "row_span"));
            assertEquals(1, number(cells.get(index), "column_span"));
        }
        assertEquals(80.0, rectangle(cells.getFirst()).getFirst());
        assertTrue(rectangle(cells.get(1)).getFirst() - rectangle(cells.getFirst()).get(2) > 100);
        assertCoordinates(table);
    }

    @Test
    void ocrCoordinatesUseActualRoundedRasterDimensionsAndFullMergedRules() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final var calls = new AtomicInteger();
            final var width = new AtomicInteger();
            final var height = new AtomicInteger();
            final PdfOcrProvider provider = new PdfOcrProvider() {
                @Override
                public List<Line> recognize(final BufferedImage image) {
                    throw new AssertionError("Integration must supply explicit input kind");
                }

                @Override
                public List<Line> recognize(final BufferedImage image, final InputKind kind) {
                    if (kind == InputKind.PAGE) {
                        width.set(image.getWidth());
                        height.set(image.getHeight());
                        return List.of();
                    }
                    return calls.getAndIncrement() == 2 ? List.of() : List.of(new Line("cell", 15, 15, 25, 25));
                }
            };
            final var options = PdfOptions.defaults().withTableCellCoordinates(true);
            final var ocr =
                    new PdfOcrOptions(137, 1_000_000, 100, 1000).withTableDetection(PdfOcrOptions.TableDetection.RULED);
            final var processor =
                    DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options, ocr, provider))));
            final var table = table(processor.partition(ocrSource(rotation)));
            assertEquals(3, calls.get());
            assertEquals(3, cells(table).size());
            assertCoordinates(table);
            final var parent = (Map<?, ?>) table.metadata().get("coordinates");
            final double pageWidth = ((Number) parent.get("layout_width")).doubleValue();
            final double pageHeight = ((Number) parent.get("layout_height")).doubleValue();
            for (final var cell : cells(table)) {
                final var bounds = rectangle(cell);
                for (int index = 0; index < bounds.size(); index++) {
                    final double pixels =
                            bounds.get(index) * (index % 2 == 0 ? width.get() / pageWidth : height.get() / pageHeight);
                    assertEquals(Math.rint(pixels * 2), pixels * 2, 0.000001,
                            "Rule center must be an integer/half pixel in the actual rendered frame");
                }
            }
            assertEquals(2.0, table.metadata().get("pdf_user_unit"));
            assertTrue(table.metadata().containsKey("pdf_crop_box"));
            assertEquals(137, table.metadata().get("pdf_ocr_dpi"));
        }
    }

    @Test
    void defaultsAndImmutablePolicyCopiesPreserveHistoricalOutputAndConfiguration() throws Exception {
        final var base = PdfOptions.defaults();
        final var old = new PdfOptions(base.maxPages(), base.maxGlyphsPerPage(), base.maxOperatorsPerPage(),
                base.maxImagePixels(), base.maxImageBytes(), base.includeImageBytes(), base.tableLimits(),
                base.tableDetection(), base.textLayout());
        assertEquals(base, old);
        assertFalse(base.includeTableCellCoordinates());
        final var enabled = base.withTableCellCoordinates(true)
                                    .withTableDetection(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT)
                                    .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS);
        assertTrue(enabled.includeTableCellCoordinates());
        assertEquals(base, enabled.withTableCellCoordinates(false)
                                   .withTableDetection(base.tableDetection())
                                   .withTextLayout(base.textLayout()));
        final var source = nativeSource(0);
        assertEquals(nativeProcessor(base).partition(source).elements(),
                nativeProcessor(base.withTableCellCoordinates(false)).partition(source).elements());
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> nativeProcessor(new PdfOptions(10, 10000, 10000, 100, 1000, false,
                                                           new TableLimits(10, 10, 3, 100))
                                                           .withTableCellCoordinates(true))
                                        .partition(source))
                        .code());
    }

    @Test
    void cellMetadataIsImmutableAndStableAcrossConcurrentPartitions() throws Exception {
        final var processor = nativeProcessor(PdfOptions.defaults().withTableCellCoordinates(true));
        final var source = nativeSource(90);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 4)
                                      .mapToObj(i -> executor.submit(() -> processor.partition(source).elements()))
                                      .toList();
            final var expected = tasks.getFirst().get();
            for (final var task : tasks) {
                assertEquals(expected, task.get());
            }
        }
    }

    private static DocWeave nativeProcessor(final PdfOptions options) {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
    }

    private static Element table(final PartitionResult result) {
        return result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cells(final Element table) {
        return (List<Map<String, Object>>) ((Map<?, ?>) table.metadata().get("pdf_table_cells")).get("cells");
    }

    private static int number(final Map<?, ?> map, final String key) {
        return ((Number) map.get(key)).intValue();
    }

    private static List<Double> rectangle(final Map<?, ?> cell) {
        final var coordinates = (Map<?, ?>) cell.get("coordinates");
        final var points = (List<?>) coordinates.get("points");
        final var first = (List<?>) points.getFirst();
        final var third = (List<?>) points.get(2);
        return List.of(((Number) first.getFirst()).doubleValue(), ((Number) first.getLast()).doubleValue(),
                ((Number) third.getFirst()).doubleValue(), ((Number) third.getLast()).doubleValue());
    }

    private static void assertCoordinates(final Element table) {
        final var parent = (Map<?, ?>) table.metadata().get("coordinates");
        final var outer = rectangle(table.metadata());
        for (final var cell : cells(table)) {
            final var coordinates = (Map<?, ?>) cell.get("coordinates");
            assertEquals("PDFUserSpace", coordinates.get("system"));
            assertEquals(parent.get("layout_width"), coordinates.get("layout_width"));
            assertEquals(parent.get("layout_height"), coordinates.get("layout_height"));
            final var bounds = rectangle(cell);
            assertTrue(bounds.get(0) >= outer.get(0) && bounds.get(1) >= outer.get(1));
            assertTrue(bounds.get(2) <= outer.get(2) && bounds.get(3) <= outer.get(3));
            assertTrue(bounds.get(0) < bounds.get(2) && bounds.get(1) < bounds.get(3));
        }
    }

    private static DocumentSource nativeSource(final int rotation) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(500, 400));
            page.setCropBox(new PDRectangle(50, 40, 400, 320));
            page.setRotation(rotation);
            page.setUserUnit(2);
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                for (final int x : List.of(90, 240, 390)) {
                    content.moveTo(x, 100);
                    content.lineTo(x, 340);
                }
                content.moveTo(90, 100);
                content.lineTo(390, 100);
                content.moveTo(240, 220);
                content.lineTo(390, 220);
                content.moveTo(90, 340);
                content.lineTo(390, 340);
                content.stroke();
                text(content, "Tall", 110, 300);
                text(content, "Right", 260, 300);
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "native-cells.pdf");
        }
    }

    private static void text(final PDPageContentStream content, final String value, final int x, final int y)
            throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(x, y);
        content.showText(value);
        content.endText();
    }

    private static DocumentSource ocrSource(final int rotation) throws Exception {
        final var raster = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        final var drawing = raster.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, 600, 400);
            drawing.setColor(Color.BLACK);
            drawing.setStroke(new java.awt.BasicStroke(2));
            for (final int x : List.of(40, 300, 560)) {
                drawing.drawLine(x, 80, x, 320);
            }
            for (final int y : List.of(80, 200, 320)) {
                drawing.drawLine(y == 200 ? 300 : 40, y, 560, y);
            }
        } finally {
            drawing.dispose();
        }
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(500, 400));
            page.setCropBox(new PDRectangle(25, 30, 288.3f, 192.7f));
            page.setRotation(rotation);
            page.setUserUnit(2);
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, raster), 25, 30, 288.3f, 192.7f);
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "ocr-cells.pdf");
        } finally {
            raster.flush();
        }
    }
}
