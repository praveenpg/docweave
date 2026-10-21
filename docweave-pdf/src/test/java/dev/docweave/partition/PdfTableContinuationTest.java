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
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Source-generated adjacent pages test real extraction IDs, page isolation, links and negative evidence. */
class PdfTableContinuationTest {
    @Test
    void nativeChainKeepsRepeatedRowsIdsAndGeometryWithoutImplicitCellMetadata() throws Exception {
        final var source =
                document(List.of(spec(400, 760, "plain"), spec(40, 760, "mergedBody"), spec(40, 400, "plain")));
        final var baseline = processor(PdfOptions.defaults()).partition(source).elements();
        final var linked = processor(enabled()).partition(source).elements();
        assertEquals(3, linked.size());
        assertFalse(linked.getFirst().metadata().containsKey("pdf_table_continuation"));
        for (int index = 0; index < linked.size(); index++) {
            final var table = linked.get(index);
            assertEquals(ElementType.Table, table.type());
            assertEquals(baseline.get(index).elementId(), table.elementId());
            assertEquals(baseline.get(index).text(), table.text());
            final var metadata = new LinkedHashMap<>(table.metadata());
            metadata.remove("pdf_table_continuation");
            assertEquals(baseline.get(index).metadata(), metadata);
            assertFalse(metadata.containsKey("pdf_table_cells"));
            assertTrue(table.text().startsWith("Item Qty"));
            if (index > 0) {
                final var link = link(table);
                assertEquals(linked.getFirst().elementId(), link.get("root_element_id"));
                assertEquals(linked.get(index - 1).elementId(), link.get("previous_element_id"));
                assertEquals(index, link.get("part_index"));
                assertEquals(index, link.get("previous_page_number"));
                assertEquals(1, link.get("root_page_number"));
            }
        }
        final var coordinates = processor(
                enabled().withTableCellCoordinates(true).withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS))
                                        .partition(source)
                                        .elements();
        assertEquals(2, coordinates.stream().filter(e -> e.metadata().containsKey("pdf_table_continuation")).count());
        assertTrue(coordinates.stream().allMatch(e -> e.metadata().containsKey("pdf_table_cells")));
    }

    @Test
    void differentHeadersColumnsNumericLabelsAndFramesDoNotLink() throws Exception {
        final var first = spec(400, 760, "plain");
        for (final var second : List.of(new Spec(40, 400, List.of("Product", "Qty"), 220, "plain"),
                     new Spec(40, 400, List.of("Item", "Qty"), 230, "plain"), spec(40, 400, "unit"),
                     spec(40, 400, "rotation"), spec(40, 400, "crop"), spec(40, 400, "mergedHeader"))) {
            assertNoLinks(document(List.of(first, second)));
        }
        assertNoLinks(document(List.of(new Spec(400, 760, List.of("2024", "2025"), 220, "plain"),
                new Spec(40, 400, List.of("2024", "2025"), 220, "plain"))));
        final var smallDrift = processor(enabled()).partition(
                document(List.of(first, new Spec(40, 400, List.of("Item", "Qty"), 221, "plain"))));
        assertTrue(smallDrift.elements().getLast().metadata().containsKey("pdf_table_continuation"));
        assertNoLinks(document(List.of(first, spec(200, 500, "plain"))));
        assertNoLinks(document(List.of(spec(200, 500, "plain"), spec(40, 400, "plain"))));
    }

    @Test
    void multipleTablesBlankPagesAndInterveningNarrativeBreakChains() throws Exception {
        for (final var pages : List.of(List.of(spec(400, 760, "after"), spec(40, 400, "plain")),
                     List.of(spec(400, 760, "plain"), spec(40, 400, "before")),
                     List.of(spec(400, 760, "multiple"), spec(40, 400, "plain")),
                     List.of(spec(400, 760, "plain"), spec(40, 400, "multiple")),
                     List.of(spec(400, 760, "plain"), spec(0, 0, "blank"), spec(40, 400, "plain")))) {
            assertNoLinks(document(pages));
        }
        final var multiple = processor(enabled()).partition(document(List.of(spec(400, 760, "multiple"))));
        assertEquals(2, multiple.elements().stream().filter(e -> e.type() == ElementType.Table).count());
    }

    @Test
    void brokenChainRestartsWithARealNewRootAndOnlyAdjacentBackwardReferences() throws Exception {
        final var source = document(List.of(spec(400, 760, "plain"), spec(40, 760, "plain"),
                new Spec(40, 760, List.of("Product", "Count"), 220, "plain"),
                new Spec(40, 400, List.of("Product", "Count"), 220, "plain")));
        final var tables = processor(enabled()).partition(source).elements();
        assertEquals(4, tables.size());
        assertTrue(tables.get(1).metadata().containsKey("pdf_table_continuation"));
        assertFalse(tables.get(2).metadata().containsKey("pdf_table_continuation"));
        assertEquals(tables.get(2).elementId(), link(tables.get(3)).get("root_element_id"));
        assertEquals(tables.get(2).elementId(), link(tables.get(3)).get("previous_element_id"));
        assertEquals(1, link(tables.get(3)).get("part_index"));
        assertEquals(3, link(tables.get(3)).get("root_page_number"));
    }

    @Test
    void ocrRepeatedHeadersLinkWithoutCrossingSourceFamiliesOrCorrectingText() throws Exception {
        final var pages = List.of(spec(400, 760, "ocr"), spec(40, 400, "ocr"));
        final var calls = new AtomicInteger();
        final var linked = ocrProcessor(enabled(), calls, List.of("Item", "Qty")).partition(document(pages)).elements();
        final var tables = linked.stream().filter(e -> e.type() == ElementType.Table).toList();
        assertEquals(8, calls.get());
        assertEquals(2, tables.size());
        assertEquals(2, linked.stream().filter(e -> e.type() == ElementType.Image).count());
        assertEquals(tables.getFirst().elementId(), link(tables.getLast()).get("previous_element_id"));
        assertFalse(tables.getLast().metadata().containsKey("pdf_table_cells"));
        final var mixed = ocrProcessor(enabled(), new AtomicInteger(), List.of("Item", "Qty"))
                                  .partition(document(List.of(spec(400, 760, "plain"), spec(40, 400, "ocr"))));
        assertTrue(mixed.elements().stream().noneMatch(e -> e.metadata().containsKey("pdf_table_continuation")));
        final var unicode = ocrProcessor(
                enabled(), new AtomicInteger(), List.of("Cafe\u0301 Name", "Quantity", "Café\u00a0Name", "Quantity"))
                                    .partition(document(pages))
                                    .elements()
                                    .stream()
                                    .filter(e -> e.type() == ElementType.Table)
                                    .toList();
        assertNotNull(link(unicode.getLast()));
        assertTrue(unicode.getFirst().text().contains("Cafe\u0301 Name"));
        assertTrue(unicode.getLast().text().contains("Café\u00a0Name"));
        for (final var labels : List.of(List.of("Item", "Qty", "item", "Qty"), List.of("Value", "Value"))) {
            final var rejected = ocrProcessor(enabled(), new AtomicInteger(), labels).partition(document(pages));
            assertTrue(rejected.elements().stream().noneMatch(e -> e.metadata().containsKey("pdf_table_continuation")));
        }
        final var oversized = ocrProcessor(enabled(), new AtomicInteger(), List.of("A".repeat(513), "Qty"))
                                      .partition(document(pages));
        assertEquals(2, oversized.elements().stream().filter(e -> e.type() == ElementType.Table).count());
        assertTrue(oversized.elements().stream().noneMatch(e -> e.metadata().containsKey("pdf_table_continuation")));
    }

    @Test
    void pageBufferEnforcesGlobalAndFixedLimitsAndPreservesCancellation() throws Exception {
        final var limited = new OwnedContext(2);
        final var output = new PdfPageOutput(limited, PdfOptions.TableContinuation.REPEATED_HEADER);
        output.emit(ElementType.UncategorizedText, "a", Map.of());
        output.emit(ElementType.UncategorizedText, "b", Map.of());
        assertEquals(0, limited.elementCount());
        assertEquals(PartitionException.Code.ELEMENT_LIMIT,
                assertThrows(PartitionException.class, () -> output.emit(ElementType.UncategorizedText, "c", Map.of()))
                        .code());
        output.finishPage(1);
        assertEquals(2, limited.elementCount());
        assertEquals(PartitionException.Code.ELEMENT_LIMIT,
                assertThrows(PartitionException.class, () -> output.emit(ElementType.UncategorizedText, "d", Map.of()))
                        .code());
        final var large = new OwnedContext(100_000);
        final var buffered = new PdfPageOutput(large, PdfOptions.TableContinuation.REPEATED_HEADER);
        for (int index = 0; index < 4096; index++) {
            buffered.emit(ElementType.UncategorizedText, "line", Map.of());
        }
        assertEquals(PartitionException.Code.ELEMENT_LIMIT,
                assertThrows(
                        PartitionException.class, () -> buffered.emit(ElementType.UncategorizedText, "extra", Map.of()))
                        .code());
        assertEquals(0, large.elementCount());
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> buffered.finishPage(1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void defaultsCopiesNullRejectionAndConcurrentReuseKeepStateDocumentLocal() throws Exception {
        final var base = PdfOptions.defaults();
        final var old = new PdfOptions(base.maxPages(), base.maxGlyphsPerPage(), base.maxOperatorsPerPage(),
                base.maxImagePixels(), base.maxImageBytes(), false, base.tableLimits(), base.tableDetection(),
                base.textLayout(), true);
        assertEquals(PdfOptions.TableContinuation.NONE, old.tableContinuation());
        final var policy = enabled()
                                   .withTableCellCoordinates(true)
                                   .withTableDetection(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT)
                                   .withTextLayout(PdfOptions.TextLayout.PARAGRAPHS);
        assertEquals(PdfOptions.TableContinuation.REPEATED_HEADER, policy.tableContinuation());
        assertTrue(policy.includeTableCellCoordinates());
        assertThrows(NullPointerException.class, () -> base.withTableContinuation(null));
        final var processor = processor(policy);
        final var source = document(List.of(spec(400, 760, "plain"), spec(40, 400, "plain")));
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 4)
                                      .mapToObj(i -> executor.submit(() -> processor.partition(source).elements()))
                                      .toList();
            final var expected = tasks.getFirst().get();
            assertEquals(1, expected.stream().filter(e -> e.metadata().containsKey("pdf_table_continuation")).count());
            for (final var task : tasks) {
                assertEquals(expected, task.get());
            }
        }
    }

    private static PdfOptions enabled() {
        return PdfOptions.defaults().withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER);
    }

    private static DocWeave processor(final PdfOptions options) {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
    }

    private static DocWeave ocrProcessor(
            final PdfOptions options, final AtomicInteger calls, final List<String> header) {
        final PdfOcrProvider provider = new PdfOcrProvider() {
            @Override
            public List<Line> recognize(final BufferedImage image) {
                throw new AssertionError("Explicit input kind required");
            }

            @Override
            public List<Line> recognize(final BufferedImage image, final InputKind kind) {
                if (kind == InputKind.PAGE) {
                    return List.of();
                }
                final int index = calls.getAndIncrement();
                final int cell = index % 4;
                final int headerOffset = header.size() == 4 ? (index / 4) * 2 : 0;
                return List.of(new Line(cell < 2    ? header.get(headerOffset + cell)
                                        : cell == 2 ? "Alpha"
                                                    : "10",
                        15, 15, 30, 30));
            }
        };
        final var ocr =
                new PdfOcrOptions(72, 1_000_000, 100, 10_000).withTableDetection(PdfOcrOptions.TableDetection.RULED);
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options, ocr, provider))));
    }

    private static Map<?, ?> link(final Element table) {
        return (Map<?, ?>) table.metadata().get("pdf_table_continuation");
    }

    private static void assertNoLinks(final DocumentSource source) throws Exception {
        final var result = processor(enabled()).partition(source);
        assertTrue(result.elements().stream().noneMatch(e -> e.metadata().containsKey("pdf_table_continuation")));
    }

    private static Spec spec(final int top, final int bottom, final String feature) {
        return new Spec(top, bottom, List.of("Item", "Qty"), 220, feature);
    }

    /** Owned input variants keep geometry independent of the candidate's output. */
    private record Spec(int top, int bottom, List<String> header, int middle, String feature) {}

    private static DocumentSource document(final List<Spec> specs) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            for (final var spec : specs) {
                final var page = new PDPage(new PDRectangle(500, 800));
                document.addPage(page);
                if (spec.feature().equals("blank")) {
                    continue;
                }
                if (spec.feature().equals("unit")) {
                    page.setUserUnit(2);
                } else if (spec.feature().equals("rotation")) {
                    page.setRotation(90);
                } else if (spec.feature().equals("crop")) {
                    page.setCropBox(new PDRectangle(5, 0, 490, 800));
                }
                try (final var content = new PDPageContentStream(document, page)) {
                    if (spec.feature().equals("ocr")) {
                        final var image = new BufferedImage(500, 800, BufferedImage.TYPE_INT_RGB);
                        final var drawing = image.createGraphics();
                        try {
                            drawing.setColor(Color.WHITE);
                            drawing.fillRect(0, 0, 500, 800);
                            drawing.setColor(Color.BLACK);
                            drawing.setStroke(new java.awt.BasicStroke(2));
                            for (final int x : List.of(40, 220, 400)) {
                                drawing.drawLine(x, spec.top(), x, spec.bottom());
                            }
                            for (final int y : List.of(spec.top(), spec.top() + 80, spec.bottom())) {
                                drawing.drawLine(40, y, 400, y);
                            }
                        } finally {
                            drawing.dispose();
                        }
                        try {
                            content.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, 500, 800);
                        } finally {
                            image.flush();
                        }
                    } else {
                        drawTable(content, spec, 40, spec.middle(), 400);
                        if (spec.feature().equals("multiple")) {
                            drawTable(content, new Spec(420, 600, List.of("A", "B"), 450, "plain"), 420, 450, 480);
                        }
                        if (spec.feature().equals("before")) {
                            text(content, "Section boundary", 40, 790, 12);
                        } else if (spec.feature().equals("after")) {
                            text(content, "End of section", 40, 15, 12);
                        }
                    }
                }
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "continuations.pdf");
        }
    }

    private static void drawTable(final PDPageContentStream content, final Spec spec, final int left, final int middle,
            final int right) throws Exception {
        final int top = 800 - spec.top();
        final int split = top - 40;
        final int bottom = 800 - spec.bottom();
        for (final int y : List.of(top, split, bottom)) {
            content.moveTo(left, y);
            content.lineTo(right, y);
        }
        for (final int x : List.of(left, middle, right)) {
            content.moveTo(x, spec.feature().equals("mergedBody") && x == middle ? split : bottom);
            content.lineTo(x, spec.feature().equals("mergedHeader") && x == middle ? split : top);
        }
        content.stroke();
        text(content, spec.header().getFirst(), left + 5, top - 25, right - left < 100 ? 8 : 12);
        text(content, spec.header().getLast(), middle + 5, top - 25, right - left < 100 ? 8 : 12);
        text(content, "Alpha", left + 5, split - 25, right - left < 100 ? 6 : 12);
        text(content, "10", middle + 5, split - 25, right - left < 100 ? 8 : 12);
    }

    private static void text(final PDPageContentStream content, final String text, final int x, final int y,
            final int size) throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    /** A custom central SPI context observes buffering/limits without using private collector internals. */
    static final class OwnedContext implements PartitionContext {
        private final List<Element> elements = new ArrayList<>();
        private final PartitionOptions options;

        OwnedContext(final int limit) {
            options = PartitionOptions.builder().maxElements(limit).build();
        }

        public String text() {
            return "";
        }

        public String filename() {
            return "owned.pdf";
        }

        public DocumentFormat format() {
            return DocumentFormat.PDF;
        }

        public PartitionOptions options() {
            return options;
        }

        public int elementCount() {
            return elements.size();
        }

        public Element elementAt(final int index) {
            return elements.get(index);
        }

        public Element emit(final ElementType type, final String text, final Map<String, Object> metadata) {
            final var element = new Element("owned-" + elements.size(), type, text, metadata);
            elements.add(element);
            return element;
        }

        public void warn(final PartitionResult.WarningCode code, final String message) {
        }
    }
}
