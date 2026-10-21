package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PdfScannedWordTableTest {
    @Test
    void oneDetailedPageCallProducesTableAndPreservesUnownedSourceWords() throws Exception {
        final var calls = new AtomicInteger();
        final var tokens = tokens();
        tokens.add(new PdfWordOcrProvider.Word("outside", 30, 110, 65, 118));
        tokens.add(new PdfWordOcrProvider.Word("prose", 70, 110, 100, 118));
        final PdfWordOcrProvider provider = (image, kind) -> {
            assertEquals(PdfOcrProvider.InputKind.PAGE, kind);
            calls.incrementAndGet();
            return List.of(source(tokens));
        };
        final var result = processor(options(), TableLimits.defaults(), provider).partition(fixture(false));
        assertEquals(1, calls.get());
        final var table =
                result.elements().stream().filter(value -> value.type() == ElementType.Table).findFirst().orElseThrow();
        assertEquals("Item name Value Red apple 1 Blue pear 2", table.text());
        assertEquals(
                "<table><tbody><tr><td>Item name</td><td>Value</td></tr><tr><td>Red apple</td><td>1</td></tr><tr><td>Blue pear</td><td>2</td></tr></tbody></table>",
                table.metadata().get("text_as_html"));
        assertEquals(List.of("outside prose"), ordinary(result));
        assertEquals("scanned_aligned_words_heuristic_v1", table.metadata().get("pdf_table_method"));
        final var cells = (Map<?, ?>) table.metadata().get("pdf_table_cells");
        assertEquals("text_bounds", cells.get("geometry"));
        assertEquals(3, cells.get("row_count"));
        assertEquals(2, cells.get("column_count"));
        assertEquals(6, ((List<?>) cells.get("cells")).size());
        assertEquals(1, result.elements().stream().filter(value -> value.type() == ElementType.Image).count());
    }

    @Test
    void defaultsAndRejectedBlocksPreserveOriginalLineWithoutSplitting() throws Exception {
        final var original = source(tokens());
        final PdfWordOcrProvider provider = (image, kind) -> List.of(original);
        final var defaults = processor(new PdfOcrOptions(72, 100_000, 100, 1000), TableLimits.defaults(), provider)
                                     .partition(fixture(false));
        assertEquals(List.of(original.line().text()), ordinary(defaults));
        assertTrue(defaults.elements().stream().noneMatch(value -> value.type() == ElementType.Table));
        for (final boolean rule : List.of(false, true)) {
            final var values = tokens();
            if (!rule) {
                values.set(values.size() - 1, new PdfWordOcrProvider.Word("two", 150, 60, 165, 68));
            }
            final var line = source(values);
            final var result =
                    processor(options(), TableLimits.defaults(), (PdfWordOcrProvider) (image, kind) -> List.of(line))
                            .partition(fixture(rule));
            assertEquals(List.of(line.line().text()), ordinary(result));
            assertTrue(result.elements().stream().noneMatch(value -> value.type() == ElementType.Table));
        }
    }

    @Test
    void capabilityRawBudgetsTableBudgetsAndCancellationRemainExplicit() throws Exception {
        final var source = fixture(false);
        final PdfOcrProvider legacy = image -> List.of(new PdfOcrProvider.Line("legacy", 1, 1, 10, 10));
        assertEquals(PartitionException.Code.UNSUPPORTED_OPTION,
                assertThrows(PartitionException.class,
                        () -> processor(options(), TableLimits.defaults(), legacy).partition(source))
                        .code());
        final var line = source(tokens());
        final PdfWordOcrProvider provider = (image, kind) -> List.of(line);
        for (final var limits : List.of(new TableLimits(2, 5, 100, 100), new TableLimits(10, 1, 100, 100),
                     new TableLimits(10, 5, 5, 100), new TableLimits(10, 5, 100, 3))) {
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(
                            PartitionException.class, () -> processor(options(), limits, provider).partition(source))
                            .code());
        }
        final var cellLimit = new PdfOcrOptions(72, 100_000, 100, 1000, PdfOcrOptions.Selection.TEXTLESS_PAGES, 256,
                PdfOcrOptions.TableDetection.ALIGNED_WORDS, 5);
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> processor(cellLimit, TableLimits.defaults(), provider).partition(source))
                        .code());
        final var rawLimit =
                new PdfOcrOptions(72, 100_000, 100, 3).withTableDetection(PdfOcrOptions.TableDetection.ALIGNED_WORDS);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        () -> processor(rawLimit, TableLimits.defaults(), provider).partition(source))
                        .code());
        final PdfWordOcrProvider invalid = (image, kind) -> null;
        assertEquals(PartitionException.Code.OCR_FAILURE,
                assertThrows(PartitionException.class,
                        () -> processor(options(), TableLimits.defaults(), invalid).partition(source))
                        .code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> processor(options(), TableLimits.defaults(), provider).partition(source));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void actualRasterScalingPreservesDisplayedCellGeometry() throws Exception {
        Object expected = null;
        for (final int dpi : List.of(72, 144, 288)) {
            final PdfWordOcrProvider provider = (image, kind) -> {
                final double scale = image.getWidth() / 300.0;
                final var words =
                        tokens().stream()
                                .map(word
                                        -> new PdfWordOcrProvider.Word(word.text(), word.left() * scale,
                                                word.top() * scale, word.right() * scale, word.bottom() * scale))
                                .toList();
                return List.of(new PdfWordOcrProvider.WordLine(
                        new PdfOcrProvider.Line(
                                String.join(" ", words.stream().map(PdfWordOcrProvider.Word::text).toList()), 0, 0,
                                image.getWidth(), image.getHeight()),
                        words));
            };
            final var policy = new PdfOcrOptions(dpi, 2_000_000, 100, 1000)
                                       .withTableDetection(PdfOcrOptions.TableDetection.ALIGNED_WORDS);
            final var result = processor(policy, TableLimits.defaults(), provider).partition(fixture(false));
            final var table = result.elements()
                                      .stream()
                                      .filter(value -> value.type() == ElementType.Table)
                                      .findFirst()
                                      .orElseThrow();
            if (expected == null) {
                expected = table.metadata().get("pdf_table_cells");
            }
            assertEquals(expected, table.metadata().get("pdf_table_cells"), "DPI " + dpi);
        }
    }

    private static List<PdfWordOcrProvider.Word> tokens() {
        final var result = new ArrayList<PdfWordOcrProvider.Word>();
        final var text =
                List.of(List.of("Item", "name", "Value"), List.of("Red", "apple", "1"), List.of("Blue", "pear", "2"));
        for (int row = 0; row < text.size(); row++) {
            final double top = 20 + row * 20;
            result.add(new PdfWordOcrProvider.Word(text.get(row).get(0), 30, top, 48, top + 8));
            result.add(new PdfWordOcrProvider.Word(text.get(row).get(1), 52, top, 75, top + 8));
            result.add(new PdfWordOcrProvider.Word(text.get(row).get(2), 150, top, 165, top + 8));
        }
        return result;
    }

    private static PdfWordOcrProvider.WordLine source(final List<PdfWordOcrProvider.Word> words) {
        return new PdfWordOcrProvider.WordLine(
                new PdfOcrProvider.Line(
                        String.join(" ", words.stream().map(PdfWordOcrProvider.Word::text).toList()), 0, 0, 300, 180),
                words);
    }

    private static PdfOcrOptions options() {
        return new PdfOcrOptions(72, 100_000, 100, 1000).withTableDetection(PdfOcrOptions.TableDetection.ALIGNED_WORDS);
    }

    private static DocWeave processor(
            final PdfOcrOptions ocr, final TableLimits limits, final PdfOcrProvider provider) {
        final var base = PdfOptions.defaults();
        final var policy = new PdfOptions(base.maxPages(), base.maxGlyphsPerPage(), base.maxOperatorsPerPage(),
                base.maxImagePixels(), base.maxImageBytes(), base.includeImageBytes(), limits)
                                   .withTableCellCoordinates(true);
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(policy, ocr, provider))));
    }

    private static List<String> ordinary(final PartitionResult result) {
        return result.elements()
                .stream()
                .filter(value -> value.type() != ElementType.Table && value.type() != ElementType.Image)
                .map(value -> value.text())
                .toList();
    }

    private static DocumentSource fixture(final boolean rule) throws Exception {
        final var image = new BufferedImage(300, 180, BufferedImage.TYPE_INT_RGB);
        final var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 300, 180);
            if (rule) {
                graphics.setColor(Color.BLACK);
                graphics.drawLine(20, 45, 200, 45);
            }
        } finally {
            graphics.dispose();
        }
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(300, 180));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, 300, 180);
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "words.pdf");
        } finally {
            image.flush();
        }
    }
}
