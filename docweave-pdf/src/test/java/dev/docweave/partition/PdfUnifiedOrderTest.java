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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Whole-page ordering controls preserve actual backward table links while native/OCR sources interleave. */
class PdfUnifiedOrderTest {
    @Test
    void mixedGeometryOrderUsesRealEmissionIdsAndPageLocalIndexes() throws Exception {
        final var source = fixture();
        final var historical = processor(PdfOcrOptions.ReadingOrder.SOURCE_ORDER).partition(source);
        final var selected = processor(PdfOcrOptions.ReadingOrder.GEOMETRIC);
        final var result = selected.partition(source);
        final var first = result.elements().stream().filter(e -> e.metadata().get("page_number").equals(1)).toList();
        final var second = result.elements().stream().filter(e -> e.metadata().get("page_number").equals(2)).toList();
        assertEquals(List.of("note", "Item Qty Alpha 10"),
                first.stream().filter(e -> !e.text().isBlank()).map(e -> e.text()).toList());
        assertEquals(List.of("Item Qty Beta 20", "note"),
                second.stream().filter(e -> !e.text().isBlank()).map(e -> e.text()).toList());
        assertEquals(List.of("Item Qty Alpha 10", "note"),
                historical.elements()
                        .stream()
                        .filter(e -> e.metadata().get("page_number").equals(1) && !e.text().isBlank())
                        .map(e -> e.text())
                        .toList());
        for (final var page : List.of(first, second)) {
            for (int index = 0; index < page.size(); index++) {
                assertEquals(index + 1, page.get(index).metadata().get("pdf_reading_index"));
                assertEquals("page_top_left_v1", page.get(index).metadata().get("pdf_reading_order"));
            }
        }
        final var tables = result.elements().stream().filter(e -> e.type() == ElementType.Table).toList();
        final var link = (Map<?, ?>)tables.getLast().metadata().get("pdf_table_continuation");
        assertEquals(tables.getFirst().elementId(), link.get("previous_element_id"));
        assertEquals(tables.getFirst().elementId(), link.get("root_element_id"));
        assertEquals(2, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
        assertEquals(historical.elements().stream().map(e -> e.text()).sorted().toList(),
                result.elements().stream().map(e -> e.text()).sorted().toList());
        assertTrue(historical.elements().stream().noneMatch(e -> e.metadata().containsKey("pdf_reading_index")));
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 4)
                                      .mapToObj(i -> executor.submit(() -> selected.partition(source).elements()))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(result.elements(), task.get());
            }
        }
    }

    @Test
    void orderingIsIndependentOfContinuationAndLegacyOptionCopiesPreserveDefault() throws Exception {
        final var defaults = PdfOcrOptions.defaults();
        assertEquals(PdfOcrOptions.ReadingOrder.SOURCE_ORDER, defaults.readingOrder());
        final var legacy = new PdfOcrOptions(72, 100_000, 100, 1000, PdfOcrOptions.Selection.IMAGE_REGIONS, 20,
                PdfOcrOptions.TableDetection.NONE, 25);
        assertEquals(PdfOcrOptions.ReadingOrder.SOURCE_ORDER, legacy.readingOrder());
        final var options = legacy.withReadingOrder(PdfOcrOptions.ReadingOrder.GEOMETRIC)
                                    .withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS)
                                    .withTableDetection(PdfOcrOptions.TableDetection.NONE);
        assertEquals(PdfOcrOptions.ReadingOrder.GEOMETRIC, options.readingOrder());
        assertEquals(25, options.maxTableCells());
        assertThrows(NullPointerException.class, () -> options.withReadingOrder(null));
        final var processor = DocWeave.create(
                PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults(), options, provider()))));
        final var result = processor.partition(fixture());
        assertTrue(result.elements().stream().anyMatch(e -> e.metadata().containsKey("pdf_reading_index")));
        assertTrue(result.elements().stream().noneMatch(e -> e.metadata().containsKey("pdf_table_continuation")));
    }

    @Test
    void geometricOnlyBufferKeepsStableTiesUnknownGeometryAndLimits() throws Exception {
        final var sink = new PdfTableContinuationTest.OwnedContext(5);
        final var output =
                new PdfPageOutput(sink, PdfOptions.TableContinuation.NONE, PdfOcrOptions.ReadingOrder.GEOMETRIC);
        assertFalse(output.continuations());
        final var late = Map.<String, Object>of("coordinates",
                Map.of("points", List.of(List.of(20, 100), List.of(40, 100), List.of(40, 120), List.of(20, 120))));
        final var early = Map.<String, Object>of("coordinates",
                Map.of("points", List.of(List.of(20, 10), List.of(40, 10), List.of(40, 20), List.of(20, 20))));
        output.emit(ElementType.UncategorizedText, "late", late);
        output.emit(ElementType.UncategorizedText, "missing-one", Map.of());
        output.emit(ElementType.UncategorizedText, "early", early);
        output.emit(ElementType.UncategorizedText, "missing-two", Map.of());
        output.emit(ElementType.UncategorizedText, "tie", late);
        assertEquals(0, sink.elementCount());
        assertEquals(PartitionException.Code.ELEMENT_LIMIT,
                assertThrows(
                        PartitionException.class, () -> output.emit(ElementType.UncategorizedText, "extra", Map.of()))
                        .code());
        output.finishPage(1);
        assertEquals(List.of("early", "late", "tie", "missing-one", "missing-two"),
                java.util.stream.IntStream.range(0, sink.elementCount())
                        .mapToObj(i -> sink.elementAt(i).text())
                        .toList());
        final var cancelled = new PdfPageOutput(new PdfTableContinuationTest.OwnedContext(5),
                PdfOptions.TableContinuation.NONE, PdfOcrOptions.ReadingOrder.GEOMETRIC);
        cancelled.emit(ElementType.UncategorizedText, "cancelled", early);
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> cancelled.finishPage(1));
        } finally {
            Thread.interrupted();
        }
    }

    private static DocWeave processor(final PdfOcrOptions.ReadingOrder order) {
        final var options = new PdfOcrOptions(72, 100_000, 100, 1000)
                                    .withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS)
                                    .withReadingOrder(order);
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                PdfOptions.defaults().withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER), options,
                provider()))));
    }

    private static PdfOcrProvider provider() {
        return image -> {
            final int top = (image.getRGB(40, 30) & 0xffffff) == 0 ? 30 : 110;
            return List.of(new PdfOcrProvider.Line("note", 40, top, 70, top + 8));
        };
    }

    private static DocumentSource fixture() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            for (int index = 0; index < 2; index++) {
                final var page = new PDPage(new PDRectangle(200, 200));
                document.addPage(page);
                final int top = index == 0 ? 140 : 10;
                try (final var content = new PDPageContentStream(document, page)) {
                    final int imageTop = index == 0 ? 20 : 100;
                    content.drawImage(LosslessFactory.createFromImage(
                                              document, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)),
                            20, 200 - imageTop - 40, 80, 40);
                    for (final int x : List.of(30, 110, 190)) {
                        line(content, x, top, x, top + 50);
                    }
                    for (final int y : List.of(top, top + 25, top + 50)) {
                        line(content, 30, y, 190, y);
                    }
                    text(content, "Item", 40, top + 15);
                    text(content, "Qty", 120, top + 15);
                    text(content, index == 0 ? "Alpha" : "Beta", 40, top + 40);
                    text(content, index == 0 ? "10" : "20", 120, top + 40);
                }
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "mixed-order.pdf");
        }
    }

    private static void line(final PDPageContentStream content, final int x1, final int y1, final int x2, final int y2)
            throws Exception {
        content.moveTo(x1, 200 - y1);
        content.lineTo(x2, 200 - y2);
        content.stroke();
    }

    private static void text(final PDPageContentStream content, final String value, final int x, final int y)
            throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
        content.newLineAtOffset(x, 200 - y);
        content.showText(value);
        content.endText();
    }
}
