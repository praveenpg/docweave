package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real PDF raster controls for rectangular anchor ownership and conservative damaged-grid fallback. */
class PdfScannedMergedTableTest {
    @Test
    void combinedSpansOcrFullAnchorOnceAndPreserveEmptyPositions() throws Exception {
        final var calls = new AtomicInteger();
        final var result = processor(options(), TableLimits.defaults(), image -> {
            if (image.getWidth() == 600) {
                assertEquals(0xffffff, image.getRGB(100, 100) & 0xffffff);
                return List.of(line("outside", 20, 20), line("duplicate", 100, 100));
            }
            final int anchor = calls.getAndIncrement();
            if (anchor == 0) {
                assertTrue(image.getWidth() > 300 && image.getHeight() > 200);
                assertTrue(hasColor(image, Color.RED));
                assertTrue(hasColor(image, Color.BLUE));
                return List.of(line("A&B <top>", 15, 15), line("0007", 15, 40));
            }
            return anchor == 2 ? List.of() : List.of(line("cell" + anchor, 15, 15));
        }).partition(source("both", 0, 0));
        assertEquals(6, calls.get());
        final var table = table(result);
        assertEquals("A&B <top> 0007 cell1 cell3 cell4 cell5", table.text());
        assertEquals(
                "<table><tbody><tr><td rowspan=\"2\" colspan=\"2\">A&amp;B &lt;top&gt; 0007</td><td>cell1</td></tr>"
                        + "<tr><td></td></tr><tr><td>cell3</td><td>cell4</td><td>cell5</td></tr></tbody></table>",
                table.metadata().get("text_as_html"));
        assertEquals("scanned_ruled_grid_with_spans_v1", table.metadata().get("pdf_table_method"));
        assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
        assertEquals(List.of("outside"), result.elements()
                                                 .stream()
                                                 .filter(e -> e.type() == ElementType.UncategorizedText)
                                                 .map(e -> e.text())
                                                 .toList());
    }

    @Test
    void horizontalAndVerticalMergesRetainAnchorsAndQuarterTurnBounds() throws Exception {
        for (final String shape : List.of("horizontal", "vertical")) {
            final var calls = new AtomicInteger();
            final var result = processor(options(), TableLimits.defaults(), image -> {
                if (image.getWidth() == 600) {
                    return List.of();
                }
                calls.incrementAndGet();
                return List.of(line("cell", 15, 15));
            }).partition(source(shape, 0, 0));
            assertEquals(8, calls.get());
            final String html = table(result).metadata().get("text_as_html").toString();
            assertTrue(html.contains(shape.equals("horizontal") ? "colspan=\"2\"" : "rowspan=\"2\""));
        }
        final var rotated = processor(options(), TableLimits.defaults(),
                image -> image.getWidth() == 500 ? List.of() : List.of(line("cell", 15, 15)))
                                    .partition(source("both", 90, 0));
        final var coordinates = (Map<?, ?>) table(rotated).metadata().get("coordinates");
        assertEquals(500.0, coordinates.get("layout_width"));
        assertEquals(600.0, coordinates.get("layout_height"));
        final var points = (List<?>) coordinates.get("points");
        final var first = (List<?>) points.getFirst();
        final var third = (List<?>) points.get(2);
        assertEquals(60, ((Number) first.getFirst()).doubleValue(), 1);
        assertEquals(40, ((Number) first.getLast()).doubleValue(), 1);
        assertEquals(420, ((Number) third.getFirst()).doubleValue(), 1);
        assertEquals(520, ((Number) third.getLast()).doubleValue(), 1);
    }

    @Test
    void supportedRuleThicknessesTrimCapsWithoutRepairingBrokenDividers() throws Exception {
        for (final int thickness : List.of(1, 2, 3, 8)) {
            final var result = processor(options(), TableLimits.defaults(),
                    image -> image.getWidth() == 600 ? List.of() : List.of(line("cell", 15, 15)))
                                       .partition(source("both", 0, 0, thickness));
            assertTrue(table(result).metadata().get("text_as_html").toString().contains("rowspan=\"2\" colspan=\"2\""),
                    "Supported rule thickness " + thickness);
            final var broken = processor(options(), TableLimits.defaults(), image -> {
                assertEquals(600, image.getWidth(), "Stub must remain rejected at thickness " + thickness);
                return List.of();
            }).partition(source("stub", 0, 0, thickness));
            assertTrue(broken.elements().stream().noneMatch(e -> e.type() == ElementType.Table));
        }
    }

    @Test
    void partialRulesNonrectangularMergesAndOpenPerimetersKeepOrdinaryOcr() throws Exception {
        for (final String shape : List.of("stub", "lshape", "open")) {
            final var calls = new AtomicInteger();
            final var result = processor(options(), TableLimits.defaults(), image -> {
                calls.incrementAndGet();
                assertEquals(600, image.getWidth(), "Rejected grids must not invoke cell OCR: " + shape);
                return List.of(line("fallback", 20, 20));
            }).partition(source(shape, 0, 0));
            assertEquals(1, calls.get(), shape);
            assertTrue(result.elements().stream().noneMatch(e -> e.type() == ElementType.Table), shape);
        }
    }

    @Test
    void mergedGridBudgetsRemainProspectiveAndRawOutputIsShared() throws Exception {
        final var base = options();
        final var small = new PdfOcrOptions(base.dpi(), base.maxRenderPixels(), 100, 1000, base.selection(),
                base.maxRegions(), base.tableDetection(), 8);
        // Nine atomic slots still require admission even though only six anchors need OCR.
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> processor(small, TableLimits.defaults(), image -> {
                    throw new AssertionError("Atomic topology budget precedes OCR");
                }).partition(source("both", 0, 0))).code());
        final var lines =
                new PdfOcrOptions(72, 1_000_000, 6, 1000).withTableDetection(PdfOcrOptions.TableDetection.RULED);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> processor(lines, TableLimits.defaults(), image -> List.of(line("raw", 15, 15)))
                                        .partition(source("both", 0, 0)))
                        .code());
        assertEquals(
                PartitionException.Code.TABLE_LIMIT, assertThrows(PartitionException.class,
                                                             ()
                                                                     -> processor(base, new TableLimits(10, 10, 100, 3),
                                                                             image -> List.of(line("long", 15, 15)))
                                                                             .partition(source("both", 0, 0)))
                                                             .code());
    }

    @Test
    void blankMergedCellsKeepRasterAndNativeOwnershipRemainsExclusive() throws Exception {
        final var blank = processor(options(), TableLimits.defaults(), image -> {
            if (image.getWidth() != 600) {
                return List.of();
            }
            assertEquals(0, image.getRGB(520, 100) & 0xffffff);
            return List.of(line("fallback", 20, 20));
        }).partition(source("both", 0, 0));
        assertTrue(blank.elements().stream().noneMatch(e -> e.type() == ElementType.Table));
        final var mixed = options().withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS);
        for (final int nativePosition : List.of(1, 2)) {
            final var cells = new AtomicInteger();
            final var result = processor(mixed, TableLimits.defaults(), image -> {
                if (image.getWidth() == 600) {
                    return List.of();
                }
                cells.incrementAndGet();
                return List.of(line("cell", 15, 15));
            }).partition(source("both", 0, nativePosition));
            assertEquals(nativePosition == 1 ? 0 : 6, cells.get());
            assertEquals(1, result.elements().stream().filter(e -> e.text().equals("Native")).count());
        }
    }

    @Test
    void concurrentMergedCallsHaveIndependentOwnershipAndBudgets() throws Exception {
        final var processor = processor(options(), TableLimits.defaults(),
                image -> image.getWidth() == 600 ? List.of() : List.of(line("cell", 15, 15)));
        final var source = source("both", 0, 0);
        try (final var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            final var results = java.util.stream.IntStream.range(0, 4)
                                        .mapToObj(i -> tasks.submit(() -> processor.partition(source).elements()))
                                        .toList();
            final var expected = results.getFirst().get();
            assertEquals(1, expected.stream().filter(e -> e.type() == ElementType.Table).count());
            for (final var task : results) {
                assertEquals(expected, task.get());
            }
        }
    }

    private static PdfOcrOptions options() {
        return new PdfOcrOptions(72, 1_000_000, 100, 1000).withTableDetection(PdfOcrOptions.TableDetection.RULED);
    }

    private static DocWeave processor(
            final PdfOcrOptions ocr, final TableLimits limits, final PdfOcrProvider provider) {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                new PdfOptions(100, 100_000, 100_000, 1_000_000, 1_000_000, false, limits), ocr, provider))));
    }

    private static Element table(final PartitionResult result) {
        return result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
    }

    private static PdfOcrProvider.Line line(final String text, final int x, final int y) {
        return new PdfOcrProvider.Line(text, x, y, x + 40, y + 15);
    }

    private static boolean hasColor(final BufferedImage image, final Color color) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) == color.getRGB()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Draws missing edges intentionally; malformed controls add a short attached stub or nonrectangular union. */
    private static DocumentSource source(final String shape, final int rotation, final int nativePosition)
            throws Exception {
        return source(shape, rotation, nativePosition, 2);
    }

    /** Supplies explicit supported rule thickness for the junction-cap regression. */
    private static DocumentSource source(
            final String shape, final int rotation, final int nativePosition, final int thickness) throws Exception {
        final var raster = new BufferedImage(600, 500, BufferedImage.TYPE_INT_RGB);
        final var drawing = raster.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, 600, 500);
            drawing.setColor(Color.BLACK);
            drawing.setStroke(new BasicStroke(thickness));
            drawing.drawRect(40, 80, 480, 360);
            for (int row = 0; row < 3; row++) {
                for (int column = 1; column < 3; column++) {
                    final boolean skip =
                            column == 1
                            && (shape.equals("both") || shape.equals("stub")
                                            ? row < 2
                                            : row == 0 && (shape.equals("horizontal") || shape.equals("lshape")));
                    if (!skip) {
                        drawing.drawLine(40 + column * 160, 80 + row * 120, 40 + column * 160, 200 + row * 120);
                    }
                }
            }
            for (int row = 1; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    final boolean skip =
                            row == 1
                            && (shape.equals("both") || shape.equals("stub")
                                            ? column < 2
                                            : column == 0 && (shape.equals("vertical") || shape.equals("lshape")));
                    if (!skip) {
                        drawing.drawLine(40 + column * 160, 80 + row * 120, 200 + column * 160, 80 + row * 120);
                    }
                }
            }
            if (shape.equals("stub")) {
                drawing.drawLine(200, 80, 200, 110);
            }
            if (shape.equals("open")) {
                drawing.setColor(Color.WHITE);
                drawing.fillRect(35, 140, 10, 30);
            }
            drawing.setColor(Color.RED);
            drawing.fillRect(300, 280, 5, 5);
            drawing.setColor(Color.BLUE);
            drawing.fillRect(100, 130, 5, 5);
        } finally {
            drawing.dispose();
        }
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(600, 500));
            page.setRotation(rotation);
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, raster), 0, 0, 600, 500);
                if (nativePosition > 0) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(100, nativePosition == 1 ? 380 : 475);
                    content.showText("Native");
                    content.endText();
                }
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "merged-scan.pdf");
        } finally {
            raster.flush();
        }
    }
}
