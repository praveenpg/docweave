package dev.docweave.partition;

import dev.docweave.DocWeave;
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
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PdfScannedTableTest {
    @Test
    void optInCellsProduceEscapedHtmlEmptyPositionsAndNoDuplicateOrdinaryText() throws Exception {
        final var cells = new AtomicInteger();
        final var result = processor(ruled(), TableLimits.defaults(), image -> {
            if (image.getWidth() == 600) {
                assertEquals(0xffffff, image.getRGB(100, 100) & 0xffffff);
                return List.of(line("outside prose", 40, 25, 250, 50), line("duplicate", 100, 100, 150, 130));
            }
            assertEquals(0xffffff, image.getRGB(0, 0) & 0xffffff);
            final String value = List.of("A&B", "<C>", "", "0007").get(cells.getAndIncrement());
            return value.isEmpty() ? List.of() : List.of(line(value, 15, 15, 55, 35));
        }).partition(fixture(false, false, 0));
        assertEquals(4, cells.get());
        final var table = result.elements()
                                  .stream()
                                  .filter(element -> element.type() == ElementType.Table)
                                  .findFirst()
                                  .orElseThrow();
        assertEquals("A&B <C> 0007", table.text());
        assertEquals(
                "<table><tbody><tr><td>A&amp;B</td><td>&lt;C&gt;</td></tr><tr><td></td><td>0007</td></tr></tbody></table>",
                table.metadata().get("text_as_html"));
        assertEquals("scanned_complete_ruled_grid_v1", table.metadata().get("pdf_table_method"));
        assertEquals("ocr", table.metadata().get("pdf_text_method"));
        assertEquals(List.of("outside prose"), ordinary(result));
        assertEquals(1, result.elements().stream().filter(element -> element.type() == ElementType.Image).count());
    }

    @Test
    void contextualProviderReceivesCellsAndPageWithoutInferringRasterSize() throws Exception {
        final var kinds = new java.util.ArrayList<PdfOcrProvider.InputKind>();
        final PdfOcrProvider provider = new PdfOcrProvider() {
            @Override
            public List<Line> recognize(final BufferedImage image) {
                throw new AssertionError("Contextual override must receive every integration call");
            }

            @Override
            public List<Line> recognize(final BufferedImage image, final InputKind kind) {
                kinds.add(kind);
                return kind == InputKind.TABLE_CELL
                        ? List.of(line("first", 15, 15, 55, 35), line("second", 15, 40, 55, 60))
                        : List.of(line("prose", 40, 25, 250, 50));
            }
        };
        final var result = processor(ruled(), TableLimits.defaults(), provider).partition(fixture(false, false, 0));
        assertEquals(List.of(PdfOcrProvider.InputKind.TABLE_CELL, PdfOcrProvider.InputKind.TABLE_CELL,
                             PdfOcrProvider.InputKind.TABLE_CELL, PdfOcrProvider.InputKind.TABLE_CELL,
                             PdfOcrProvider.InputKind.PAGE),
                kinds);
        assertEquals(List.of("prose"), ordinary(result));
        assertTrue(result.elements()
                        .stream()
                        .filter(e -> e.type() == ElementType.Table)
                        .allMatch(e -> e.text().equals("first second first second first second first second")));
        final PdfOcrProvider legacy = image -> List.of(line("legacy", 1, 1, 10, 10));
        assertEquals("legacy", legacy.recognize(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB),
                                             PdfOcrProvider.InputKind.TABLE_CELL)
                                       .getFirst()
                                       .text());
        assertThrows(NullPointerException.class,
                () -> legacy.recognize(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), null));
    }

    @Test
    void defaultsAndBrokenOrSkewedGridsRetainOrdinaryOcr() throws Exception {
        final PdfOcrProvider provider = image -> {
            assertEquals(600, image.getWidth());
            return List.of(line("fallback", 40, 100, 150, 130));
        };
        final var baseline = processor(new PdfOcrOptions(72, 1_000_000, 100, 1000), TableLimits.defaults(), provider)
                                     .partition(fixture(false, false, 0));
        assertEquals(List.of("fallback"), ordinary(baseline));
        for (final var source : List.of(fixture(true, false, 0), fixture(false, true, 0))) {
            final var result = processor(ruled(), TableLimits.defaults(), provider).partition(source);
            assertEquals(List.of("fallback"), ordinary(result));
            assertTrue(result.elements().stream().noneMatch(element -> element.type() == ElementType.Table));
        }
    }

    @Test
    void blankCellRecognitionDoesNotEraseTheGridBeforeFallback() throws Exception {
        final var result = processor(ruled(), TableLimits.defaults(), image -> {
            if (image.getWidth() == 600) {
                assertEquals(0, image.getRGB(300, 100) & 0xffffff);
                return List.of(line("fallback", 40, 100, 150, 130));
            }
            return List.of();
        }).partition(fixture(false, false, 0));
        assertEquals(List.of("fallback"), ordinary(result));
        assertTrue(result.elements().stream().noneMatch(element -> element.type() == ElementType.Table));
    }

    @Test
    void dimensionsAndProspectiveCellCallsFailBeforeAnyProviderCall() throws Exception {
        final var base = ruled();
        final var small = new PdfOcrOptions(base.dpi(), base.maxRenderPixels(), base.maxLines(), base.maxCharacters(),
                base.selection(), base.maxRegions(), base.tableDetection(), 3);
        final PdfOcrProvider never = image -> {
            throw new AssertionError("Limits must precede calls");
        };
        assertTableLimit(small, TableLimits.defaults(), never);
        for (final var limits : List.of(new TableLimits(1, 10, 10, 100), new TableLimits(10, 1, 10, 100),
                     new TableLimits(10, 10, 3, 100))) {
            assertTableLimit(base, limits, never);
        }
    }

    @Test
    void cellAndRemainingPageShareRawLineAndCharacterBudgets() throws Exception {
        final PdfOcrProvider provider =
                image -> image.getWidth() == 600 ? List.of() : List.of(line("A", 15, 15, 25, 25));
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> processor(new PdfOcrOptions(72, 1_000_000, 3, 100)
                                                     .withTableDetection(PdfOcrOptions.TableDetection.RULED),
                                        TableLimits.defaults(), provider)
                                        .partition(fixture(false, false, 0)))
                        .code());
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> processor(new PdfOcrOptions(72, 1_000_000, 100, 6)
                                                     .withTableDetection(PdfOcrOptions.TableDetection.RULED),
                                        TableLimits.defaults(), provider)
                                        .partition(fixture(false, false, 0)))
                        .code());
        final var exact =
                processor(new PdfOcrOptions(72, 1_000_000, 4, 7).withTableDetection(PdfOcrOptions.TableDetection.RULED),
                        TableLimits.defaults(), provider)
                        .partition(fixture(false, false, 0));
        assertEquals("A A A A", exact.elements().getLast().text());
        assertTableLimit(ruled(), new TableLimits(10, 10, 10, 1),
                image -> image.getWidth() == 600 ? List.of() : List.of(line("AB", 15, 15, 25, 25)));
    }

    @Test
    void mixedNativeOwnershipPreventsASecondTableFromClaimingNativeCells() throws Exception {
        final var options = ruled().withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS);
        final var result = processor(options, TableLimits.defaults(), image -> {
            assertEquals(600, image.getWidth());
            return List.of(line("scan beside native", 350, 100, 500, 125));
        }).partition(fixture(false, false, 1));
        assertTrue(result.elements().stream().anyMatch(element -> element.text().equals("Native cell")));
        assertTrue(result.elements().stream().noneMatch(element -> element.type() == ElementType.Table));
        assertEquals(List.of("scan beside native"), ordinary(result));
    }

    @Test
    void mixedTableOutsideNativeTextAndQuarterTurnCoordinatesRemainValid() throws Exception {
        final var mixed =
                processor(ruled().withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS), TableLimits.defaults(),
                        image
                        -> image.getWidth() == 600 ? List.of(line("Native cell", 100, 14, 180, 30))
                                                   : List.of(line("cell", 15, 15, 55, 35)))
                        .partition(fixture(false, false, 2));
        assertEquals(1, mixed.elements().stream().filter(element -> element.type() == ElementType.Table).count());
        assertTrue(ordinary(mixed).isEmpty());
        assertEquals(1, mixed.elements().stream().filter(element -> element.text().equals("Native cell")).count());
        final var rotated = processor(ruled(), TableLimits.defaults(),
                image
                -> image.getWidth() == 400 && image.getHeight() == 600 ? List.of()
                                                                       : List.of(line("cell", 15, 15, 35, 35)))
                                    .partition(fixture(false, false, 0, 90));
        final var table = rotated.elements()
                                  .stream()
                                  .filter(element -> element.type() == ElementType.Table)
                                  .findFirst()
                                  .orElseThrow();
        final var coordinates = (Map<?, ?>) table.metadata().get("coordinates");
        assertEquals(400.0, coordinates.get("layout_width"));
        assertEquals(600.0, coordinates.get("layout_height"));
        final var points = (List<?>) coordinates.get("points");
        final var first = (List<?>) points.getFirst();
        final var third = (List<?>) points.get(2);
        assertEquals(80, ((Number) first.getFirst()).doubleValue(), 1);
        assertEquals(40, ((Number) first.getLast()).doubleValue(), 1);
        assertEquals(320, ((Number) third.getFirst()).doubleValue(), 1);
        assertEquals(560, ((Number) third.getLast()).doubleValue(), 1);
    }

    @Test
    void independentGridsAndTheirAggregateCallBudgetAreHandledBeforeOcr() throws Exception {
        final var image = new BufferedImage(640, 240, BufferedImage.TYPE_INT_RGB);
        final var drawing = image.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, 640, 240);
            drawing.setColor(Color.BLACK);
            drawing.setStroke(new java.awt.BasicStroke(2));
            for (final int offset : List.of(0, 320)) {
                for (final int x : List.of(20, 160, 300)) {
                    drawing.drawLine(x + offset, 40, x + offset, 200);
                }
                for (final int y : List.of(40, 120, 200)) {
                    drawing.drawLine(20 + offset, y, 300 + offset, y);
                }
            }
        } finally {
            drawing.dispose();
        }
        try {
            assertEquals(2, RasterPdfTableDetector.detect(image, TableLimits.defaults(), new PdfTableWork()).size());
            final var base = ruled();
            final var policy = new PdfOcrOptions(base.dpi(), base.maxRenderPixels(), base.maxLines(),
                    base.maxCharacters(), base.selection(), base.maxRegions(), base.tableDetection(), 7);
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class, () -> new PdfScannedTables().recognize(image, raster -> {
                        throw new AssertionError("Preflight limit");
                    }, new PdfOcrBudget(policy), policy, TableLimits.defaults(), null)).code());
            final var erase = image.createGraphics();
            try {
                erase.setColor(Color.WHITE);
                erase.fillRect(157, 42, 6, 76);
            } finally {
                erase.dispose();
            }
            // The entire top divider is now absent apart from junction pixels: a supported colspan.
            final var grids = RasterPdfTableDetector.detect(image, TableLimits.defaults(), new PdfTableWork());
            assertEquals(List.of(3, 4), grids.stream().map(grid -> grid.cells().size()).toList());
        } finally {
            image.flush();
        }
    }

    @Test
    void cellFailuresAndCancellationPreserveCausesAndInterruptFlags() throws Exception {
        final var cause = new IOException("owned provider failure");
        final var failure =
                assertThrows(PartitionException.class, () -> processor(ruled(), TableLimits.defaults(), image -> {
                    throw cause;
                }).partition(fixture(false, false, 0)));
        assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
        assertSame(cause, failure.getCause());
        try {
            assertThrows(InterruptedIOException.class, () -> processor(ruled(), TableLimits.defaults(), image -> {
                Thread.currentThread().interrupt();
                return List.of();
            }).partition(fixture(false, false, 0)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void busyRasterWorkAndOptionsAreBoundedWithoutAnOcrEngine() throws Exception {
        final var black = new BufferedImage(4200, 64, BufferedImage.TYPE_INT_RGB);
        try {
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class,
                            () -> RasterPdfTableDetector.detect(black, TableLimits.defaults(), new PdfTableWork()))
                            .code());
        } finally {
            black.flush();
        }
        final var base = new PdfOcrOptions(100, 500_000, 50, 2000, PdfOcrOptions.Selection.IMAGE_REGIONS, 12);
        assertEquals(PdfOcrOptions.TableDetection.NONE, base.tableDetection());
        assertEquals(12, base.withTableDetection(PdfOcrOptions.TableDetection.RULED).maxRegions());
        assertEquals(PdfOcrOptions.TableDetection.RULED,
                ruled().withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS).tableDetection());
        assertThrows(NullPointerException.class, () -> base.withTableDetection(null));
        assertThrows(IllegalArgumentException.class,
                () -> new PdfOcrOptions(72, 1, 1, 1, base.selection(), 1, PdfOcrOptions.TableDetection.RULED, 0));
    }

    @Test
    void concurrentCallsKeepCellAndOrdinaryBudgetsIndependent() throws Exception {
        final var processor = processor(ruled(), TableLimits.defaults(),
                image -> image.getWidth() == 600 ? List.of() : List.of(line("cell", 15, 15, 55, 35)));
        final var source = fixture(false, false, 0);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 4)
                                      .mapToObj(i -> executor.submit(() -> processor.partition(source)))
                                      .toList();
            final var expected = tasks.getFirst().get().elements();
            for (final var task : tasks) {
                assertEquals(expected, task.get().elements());
            }
        }
    }

    private static void assertTableLimit(
            final PdfOcrOptions options, final TableLimits limits, final PdfOcrProvider provider) {
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> processor(options, limits, provider).partition(fixture(false, false, 0)))
                        .code());
    }

    private static PdfOcrOptions ruled() {
        return new PdfOcrOptions(72, 1_000_000, 100, 1000).withTableDetection(PdfOcrOptions.TableDetection.RULED);
    }

    private static DocWeave processor(
            final PdfOcrOptions ocr, final TableLimits limits, final PdfOcrProvider provider) {
        final var pdf = new PdfOptions(100, 100_000, 100_000, 1_000_000, 1_000_000, false, limits);
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(pdf, ocr, provider))));
    }

    private static PdfOcrProvider.Line line(
            final String text, final double left, final double top, final double right, final double bottom) {
        return new PdfOcrProvider.Line(text, left, top, right, bottom);
    }

    private static List<String> ordinary(final PartitionResult result) {
        return result.elements()
                .stream()
                .filter(element
                        -> element.type() == ElementType.UncategorizedText
                                   && element.metadata().containsKey("pdf_text_method"))
                .map(element -> element.text())
                .toList();
    }

    private static DocumentSource fixture(final boolean broken, final boolean skewed, final int nativeText)
            throws Exception {
        return fixture(broken, skewed, nativeText, 0);
    }

    private static DocumentSource fixture(
            final boolean broken, final boolean skewed, final int nativeText, final int rotation) throws Exception {
        final var raster = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        final var drawing = raster.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, 600, 400);
            drawing.setColor(Color.BLACK);
            drawing.setStroke(new java.awt.BasicStroke(2));
            if (skewed) {
                drawing.rotate(Math.toRadians(2), 300, 200);
            }
            for (final int y : List.of(80, 200, 320)) {
                drawing.drawLine(40, y, 560, y);
            }
            for (final int x : List.of(40, 300, 560)) {
                drawing.drawLine(x, 80, x, 320);
            }
            if (broken) {
                drawing.setColor(Color.WHITE);
                drawing.fillRect(295, 100, 10, 60);
            }
        } finally {
            drawing.dispose();
        }
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(600, 400));
            document.addPage(page);
            page.setRotation(rotation);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, raster), 0, 0, 600, 400);
                if (nativeText > 0) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(100, nativeText == 2 ? 375 : 280);
                    content.showText("Native cell");
                    content.endText();
                }
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "scanned-table.pdf");
        } finally {
            raster.flush();
        }
    }
}
