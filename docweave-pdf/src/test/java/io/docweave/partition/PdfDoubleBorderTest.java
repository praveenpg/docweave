package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Owned double-rule topology controls distinguish explicit normalization from ordinary recognition. */
class PdfDoubleBorderTest {
    @Test
    void explicitPolicyKeepsWrappedHeaderAndDescribesMidpointGeometry() throws Exception {
        final var source = source(false, 0);
        final var options = PdfOptions.defaults()
                                    .withTableDetection(PdfOptions.TableDetection.RULED_AND_DOUBLE_BORDERS)
                                    .withTableCellCoordinates(true);
        final var selected = processor(options);
        final var result = selected.partition(source);
        assertEquals(1, result.elements().size());
        final var table = result.elements().getFirst();
        assertEquals(ElementType.Table, table.type());
        assertEquals("Item Wrapped Value Alpha 10 Beta 20", table.text());
        assertTrue(table.metadata().get("text_as_html").toString().contains("<td>Wrapped Value</td>"));
        assertEquals("double_border_v1", table.metadata().get("pdf_table_rule_policy"));
        final var cells = (Map<?, ?>)table.metadata().get("pdf_table_cells");
        assertEquals(3, cells.get("row_count"));
        assertEquals(2, cells.get("column_count"));
        final var first = (Map<?, ?>)((List<?>)cells.get("cells")).getFirst();
        final var points = (List<?>)((Map<?, ?>)first.get("coordinates")).get("points");
        assertEquals(List.of(39.0, 79.0), points.getFirst());
        assertEquals(List.of(140.0, 119.0), points.get(2));
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("narrow empty bands")));
        final var defaultResult = processor(PdfOptions.defaults()).partition(source);
        assertTrue(defaultResult.elements().stream().noneMatch(e -> e.metadata().containsKey("pdf_table_rule_policy")));
        assertEquals(PdfOptions.TableDetection.RULED_ONLY, PdfOptions.defaults().tableDetection());
        assertEquals(PdfOptions.TableDetection.RULED_AND_DOUBLE_BORDERS,
                options.withTextLayout(PdfOptions.TextLayout.PARAGRAPHS)
                        .withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER)
                        .tableDetection());
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
    void allQuarterTurnsPreserveOneTableAndBroadNestedGridsRemainRejected() throws Exception {
        final var options =
                PdfOptions.defaults().withTableDetection(PdfOptions.TableDetection.RULED_AND_DOUBLE_BORDERS);
        for (final int rotation : List.of(0, 90, 180, 270)) {
            assertEquals(1, processor(options)
                                    .partition(source(false, rotation))
                                    .elements()
                                    .stream()
                                    .filter(e -> e.type() == ElementType.Table)
                                    .count());
        }
        assertTrue(processor(options)
                        .partition(source(true, 0))
                        .elements()
                        .stream()
                        .noneMatch(e -> e.type() == ElementType.Table));
    }

    @Test
    void occupiedUnequalTripleWideAndIncompleteBandsAreNotCollapsed() throws Exception {
        final var normalizer = new DoublePdfRuleNormalizer();
        final var pair = List.of(horizontal(20, 0, 100), horizontal(22, 0, 100));
        for (final var barrier : List.of(
                     new PdfLayoutItem.Bounds(10, 20.5f, 11, 21.5f), new PdfLayoutItem.Bounds(10, 19.6f, 11, 19.8f))) {
            assertEquals(pair, normalizer.normalize(pair, List.of(barrier), new PdfTableWork()));
        }
        for (final var rejected : List.of(List.of(horizontal(20, 0, 100), horizontal(22, 0, 90)),
                     List.of(horizontal(20, 0, 100), horizontal(22, 0, 100), horizontal(24, 0, 100)),
                     List.of(horizontal(20, 0, 100), horizontal(23, 0, 100)),
                     List.of(horizontal(20, 0, 40), horizontal(20, 60, 100), horizontal(22, 0, 100)))) {
            assertEquals(rejected, normalizer.normalize(rejected, List.of(), new PdfTableWork()));
        }
        final var unrelated = new PdfPageGraphics.Segment(300, 20, 300, 80);
        final var diagonal = new PdfPageGraphics.Segment(0, 0, 50, 50);
        final var rules = List.of(pair.getFirst(), pair.getLast(), unrelated, diagonal);
        final var normalized = normalizer.normalize(rules, List.of(), new PdfTableWork());
        assertEquals(List.of(horizontal(21, 0, 100), horizontal(21, 0, 100), unrelated, diagonal), normalized);
        assertThrows(UnsupportedOperationException.class, () -> normalized.clear());
    }

    @Test
    void sharedWorkAndCancellationBoundNormalization() {
        final var segments = new ArrayList<PdfPageGraphics.Segment>();
        for (int index = 0; index < 3000; index++) {
            segments.add(horizontal(index * 10, 100, 200));
            segments.add(horizontal(index * 10 + 2, 100, 200));
        }
        final var barriers = java.util.Collections.nCopies(1000, new PdfLayoutItem.Bounds(0, 0, 1, 1));
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> new DoublePdfRuleNormalizer().normalize(segments, barriers, new PdfTableWork()))
                        .code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> new DoublePdfRuleNormalizer().normalize(segments, List.of(), new PdfTableWork()));
        } finally {
            Thread.interrupted();
        }
    }

    private static PdfPageGraphics.Segment horizontal(final float y, final float left, final float right) {
        return new PdfPageGraphics.Segment(left, y, right, y);
    }

    private static DocWeave processor(final PdfOptions options) {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
    }

    private static DocumentSource source(final boolean nested, final int rotation) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            page.setRotation(rotation);
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                for (final int x : List.of(38, 40, 140, 240, 242)) {
                    line(content, x, 78, x, 202);
                }
                for (final int y : List.of(78, 80, 118, 120, 160, 200, 202)) {
                    line(content, 38, y, 242, y);
                }
                if (nested) {
                    for (final int x : List.of(10, 130, 300)) {
                        line(content, x, 10, x, 300);
                    }
                    for (final int y : List.of(10, 150, 300)) {
                        line(content, 10, y, 300, y);
                    }
                }
                text(content, "Item", 50, 100);
                text(content, "Wrapped", 150, 94);
                text(content, "Value", 150, 108);
                text(content, "Alpha", 50, 145);
                text(content, "10", 150, 145);
                text(content, "Beta", 50, 185);
                text(content, "20", 150, 185);
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "double.pdf");
        }
    }

    private static void line(final PDPageContentStream content, final float x1, final float y1, final float x2,
            final float y2) throws Exception {
        content.moveTo(x1, 400 - y1);
        content.lineTo(x2, 400 - y2);
        content.stroke();
    }

    private static void text(final PDPageContentStream content, final String value, final float x, final float y)
            throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
        content.newLineAtOffset(x, 400 - y);
        content.showText(value);
        content.endText();
    }
}
