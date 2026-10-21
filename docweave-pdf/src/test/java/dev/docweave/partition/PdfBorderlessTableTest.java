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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Owned native PDFs with explicit source runs; no OCR, upstream or human-layout accuracy oracle is implied. */
class PdfBorderlessTableTest {
    private record Text(float x, float baseline, String value, boolean alignRight) {
        Text(final float x, final float baseline, final String value) {
            this(x, baseline, value, false);
        }
    }

    private record Stroke(float x1, float y1, float x2, float y2) {}

    @Test
    void optInRecognizesAlignedNumericTableAndDefaultRemainsOrdinaryText() throws Exception {
        final var source = pdf(rows(100), List.of(), false);
        final var ordinary = DocWeave.create().partition(source);
        assertTrue(ordinary.elements().stream().noneMatch(e -> e.type() == ElementType.Table));
        final var result = processor(PdfOptions.defaults()).partition(source);
        assertEquals(1, result.elements().size());
        final var table = result.elements().getFirst();
        assertEquals(ElementType.Table, table.type());
        assertEquals("Item Qty Alpha 10 Beta 20", table.text());
        assertEquals("<table><tbody><tr><td>Item</td><td>Qty</td></tr><tr><td>Alpha</td><td>10</td></tr>"
                             + "<tr><td>Beta</td><td>20</td></tr></tbody></table>",
                table.metadata().get("text_as_html"));
        assertEquals("aligned_text_heuristic", table.metadata().get("pdf_table_method"));
        assertEquals(Map.of("rows", 3, "columns", 2, "numeric_columns", List.of(1), "policy", "aligned_numeric_v1"),
                table.metadata().get("pdf_table_evidence"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("is heuristic")));
        assertEquals(1, table.metadata().get("page_number"));
        final var shared = processor(PdfOptions.defaults());
        try (final var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 16)
                                      .mapToObj(index -> executor.submit(() -> shared.partition(source).elements()))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(result.elements(), task.get());
            }
        }
    }

    @Test
    void rightAlignedNumericColumnAndEscapingPreserveStringsWithoutTypeCoercion() throws Exception {
        final var source = pdf(
                List.of(new Text(40, 100, "Name"), new Text(260, 100, "Value", true), new Text(40, 120, "A&B"),
                        new Text(260, 120, "001", true), new Text(40, 140, "<C>"), new Text(260, 140, "-12.50%", true)),
                List.of(), false);
        final var result = processor(PdfOptions.defaults()).partition(source);
        assertEquals(1, result.elements().size());
        assertEquals("Name Value A&B 001 <C> -12.50%", result.elements().getFirst().text());
        final String html = (String) result.elements().getFirst().metadata().get("text_as_html");
        assertTrue(html.contains("<td>A&amp;B</td>"));
        assertTrue(html.contains("<td>&lt;C&gt;</td>"));
        assertTrue(html.contains("<td>001</td>"));
    }

    @Test
    void separateBlocksAndInterveningParagraphKeepSourceOwnership() throws Exception {
        final var text = new ArrayList<>(rows(100));
        text.add(new Text(40, 200, "Between tables."));
        text.addAll(rows(260));
        final var result = processor(PdfOptions.defaults()).partition(pdf(text, List.of(), false));
        assertEquals(List.of(ElementType.Table, ElementType.UncategorizedText, ElementType.Table),
                result.elements().stream().map(e -> e.type()).toList());
        assertEquals(List.of("Item Qty Alpha 10 Beta 20", "Between tables.", "Item Qty Alpha 10 Beta 20"),
                result.elements().stream().map(e -> e.text()).toList());
    }

    @Test
    void textOnlyColumnsAndNumbersInsideProseAreRejectedWithoutTextLoss() throws Exception {
        final var prose = List.of(new Text(40, 100, "First"), new Text(220, 100, "Column"), new Text(40, 120, "Second"),
                new Text(220, 120, "Text"), new Text(40, 140, "Third"), new Text(220, 140, "More"));
        final var numbersInProse =
                List.of(new Text(40, 100, "Label"), new Text(220, 100, "Description"), new Text(40, 120, "Alpha"),
                        new Text(220, 120, "About 10"), new Text(40, 140, "Beta"), new Text(220, 140, "Around 20"));
        for (final var text : List.of(prose, numbersInProse)) {
            assertRejected(text, List.of(), false);
        }
    }

    @Test
    void tooFewRowsRaggedColumnsMisalignmentAndUnevenPitchRemainOrdinary() throws Exception {
        final var shortTable = rows(100).subList(0, 4);
        final var ragged = new ArrayList<>(rows(100));
        ragged.add(new Text(350, 140, "Extra"));
        final var shifted = new ArrayList<>(rows(100));
        shifted.set(5, new Text(230, 140, "20"));
        final var uneven = new ArrayList<>(rows(100));
        uneven.set(4, new Text(40, 150, "Beta"));
        uneven.set(5, new Text(220, 150, "20"));
        for (final var text : List.of(shortTable, ragged, shifted, uneven)) {
            assertRejected(text, List.of(), false);
        }
    }

    @Test
    void intersectingStrokeAndRasterAreAmbiguityBarriers() throws Exception {
        assertRejected(rows(100), List.of(new Stroke(150, 85, 150, 145)), false);
        assertRejected(rows(100), List.of(), true);
    }

    @Test
    void ruledOwnershipAndAlignedTextCanCoexistWithoutDuplicatingGlyphs() throws Exception {
        final var strokes = new ArrayList<Stroke>();
        for (final int y : new int[] {300, 340, 380}) {
            strokes.add(new Stroke(40, y, 260, y));
        }
        for (final int x : new int[] {40, 150, 260}) {
            strokes.add(new Stroke(x, 300, x, 380));
        }
        final var text = new ArrayList<>(rows(100));
        text.add(new Text(50, 320, "Ruled"));
        final var result = processor(PdfOptions.defaults()).partition(pdf(text, strokes, false));
        assertEquals(List.of("aligned_text_heuristic", "complete_ruled_grid"),
                result.elements().stream().map(e -> e.metadata().get("pdf_table_method")).toList());
        assertEquals(
                List.of("Item Qty Alpha 10 Beta 20", "Ruled"), result.elements().stream().map(e -> e.text()).toList());
    }

    @Test
    void recognizedTablesHonorRowColumnCellAndFieldBudgets() throws Exception {
        final var source = pdf(rows(100), List.of(), false);
        for (final var limit : List.of(new TableLimits(2, 2, 6, 80), new TableLimits(3, 1, 6, 80),
                     new TableLimits(3, 2, 5, 80), new TableLimits(3, 2, 6, 2))) {
            final var options = new PdfOptions(10, 10000, 10000, 100, 10000, false, limit);
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class, () -> processor(options).partition(source)).code());
        }
    }

    @Test
    void oldConstructorAndImmutableModeCopyPreserveDefaultsAndValidateNull() {
        final var old = new PdfOptions(10, 100, 1000, 100, 1000, true, TableLimits.defaults());
        assertEquals(PdfOptions.TableDetection.RULED_ONLY, old.tableDetection());
        final var selected = old.withTableDetection(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT);
        assertEquals(old.maxPages(), selected.maxPages());
        assertEquals(old.includeImageBytes(), selected.includeImageBytes());
        assertEquals(PdfOptions.TableDetection.RULED_ONLY, old.tableDetection());
        assertThrows(NullPointerException.class, () -> old.withTableDetection(null));
    }

    @Test
    void sharedWorkBudgetBoundsManyAlignedBlocks() {
        final var runs = new ArrayList<PdfTextRun>();
        for (int block = 0; block < 650; block++) {
            for (int row = 0; row < 3; row++) {
                final int baseline = block * 100 + row * 20;
                runs.add(new PdfTextRun("Label", 40, baseline - 7, 70, baseline));
                runs.add(new PdfTextRun("10", 220, baseline - 7, 232, baseline));
            }
        }
        final var failure = assertThrows(PartitionException.class,
                ()
                        -> new AlignedPdfTableDetector(TableLimits.defaults())
                                .detect(runs, List.of(), List.of(), new PdfTableWork()));
        assertEquals(PartitionException.Code.TABLE_LIMIT, failure.code());
        assertTrue(failure.getMessage().contains("work limit"));
    }

    @Test
    void explicitWrappedModeOwnsBothLinesAndReportsUnionCellBounds() throws Exception {
        final var source = pdf(wrappedRows(), List.of(), false);
        assertTrue(processor(PdfOptions.defaults())
                        .partition(source)
                        .elements()
                        .stream()
                        .noneMatch(e -> e.type() == ElementType.Table));
        final var selected = multiline(PdfOptions.defaults().withTableCellCoordinates(true));
        final var table = selected.partition(source).elements().getFirst();
        assertEquals(ElementType.Table, table.type());
        assertEquals("Item Qty Alpha wrapped 10 Beta continued 20", table.text());
        assertTrue(table.metadata().get("text_as_html").toString().contains("<td>Alpha wrapped</td>"));
        assertEquals("aligned_numeric_multiline_v1", ((Map<?, ?>)table.metadata().get("pdf_table_evidence")).get("policy"));
        final var cells = (List<?>) ((Map<?, ?>)table.metadata().get("pdf_table_cells")).get("cells");
        assertEquals(6, cells.size());
        final var coordinates = (Map<?, ?>)((Map<?, ?>)cells.get(4)).get("coordinates");
        final var points = (List<?>) coordinates.get("points");
        assertEquals(170.0, ((Number) ((List<?>) points.get(2)).get(1)).doubleValue(), 0.01);
        assertEquals(1, selected.partition(source).elements().size());
        try (final var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 4)
                                      .mapToObj(i -> executor.submit(() -> selected.partition(source).elements()))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(List.of(table), task.get());
            }
        }
    }

    @Test
    void wrappedAmbiguityAndLastContinuationBarriersFallBackWithoutLoss() throws Exception {
        for (final int variant : java.util.stream.IntStream.range(0, 7).toArray()) {
            final var rows = new ArrayList<>(wrappedRows());
            final var strokes = new ArrayList<Stroke>();
            if (variant == 0) {
                rows.set(4, new Text(50, 140, "wrapped"));
            }
            if (variant == 1) {
                rows.set(7, new Text(40, 175, "continued"));
            }
            if (variant == 2) {
                rows.removeLast();
            }
            if (variant == 3) {
                rows.set(4, new Text(40, 140, "123"));
            }
            if (variant == 4) {
                strokes.add(new Stroke(45, 167, 45, 173));
            }
            if (variant == 5) {
                rows.set(4, new Text(40, 140, "A very long continuation that crosses into the next column"));
            }
            if (variant == 6) {
                rows.add(new Text(120, 170, "intruder"));
            }
            final var source = pdf(rows, strokes, false);
            assertEquals(DocWeave.create().partition(source).elements(),
                    multiline(PdfOptions.defaults()).partition(source).elements(), "variant " + variant);
        }
    }

    @Test
    void wrappedCombinedCellLimitsApplyAndCopiesKeepExplicitMode() throws Exception {
        final var defaults = PdfOptions.defaults();
        final var options =
                new PdfOptions(defaults.maxPages(), defaults.maxGlyphsPerPage(), defaults.maxOperatorsPerPage(),
                        defaults.maxImagePixels(), defaults.maxImageBytes(), false, new TableLimits(3, 2, 6, 9));
        final var source = pdf(wrappedRows(), List.of(), false);
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> multiline(options).partition(source)).code());
        final var copied = defaults.withTableDetection(PdfOptions.TableDetection.RULED_AND_MULTILINE_ALIGNED_TEXT)
                                   .withTableCellCoordinates(true)
                                   .withTextLayout(PdfOptions.TextLayout.PARAGRAPHS)
                                   .withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER);
        assertEquals(PdfOptions.TableDetection.RULED_AND_MULTILINE_ALIGNED_TEXT, copied.tableDetection());
        assertEquals("Item Qty Alpha 10 Beta 20",
                multiline(defaults).partition(pdf(rows(100), List.of(), false)).elements().getFirst().text());
    }

    private static List<Text> wrappedRows() {
        return List.of(new Text(40, 100, "Item"), new Text(220, 100, "Qty"), new Text(40, 130, "Alpha"),
                new Text(220, 130, "10"), new Text(40, 140, "wrapped"), new Text(40, 160, "Beta"),
                new Text(220, 160, "20"), new Text(40, 170, "continued"));
    }

    private static DocWeave multiline(final PdfOptions options) {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                options.withTableDetection(PdfOptions.TableDetection.RULED_AND_MULTILINE_ALIGNED_TEXT)))));
    }

    private static DocWeave processor(final PdfOptions options) {
        return DocWeave.create(PartitionerRegistry.of(List.of(
                PdfPartitioner.create(options.withTableDetection(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT)))));
    }

    private static List<Text> rows(final int top) {
        return List.of(new Text(40, top, "Item"), new Text(220, top, "Qty"), new Text(40, top + 20, "Alpha"),
                new Text(220, top + 20, "10"), new Text(40, top + 40, "Beta"), new Text(220, top + 40, "20"));
    }

    private static void assertRejected(final List<Text> text, final List<Stroke> strokes, final boolean image)
            throws Exception {
        final var source = pdf(text, strokes, image);
        final var ordinary = DocWeave.create().partition(source);
        final var selected = processor(PdfOptions.defaults()).partition(source);
        assertTrue(selected.elements().stream().noneMatch(e -> e.type() == ElementType.Table));
        assertEquals(ordinary.elements(), selected.elements());
    }

    private static DocumentSource pdf(final List<Text> text, final List<Stroke> strokes, final boolean image)
            throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(600, 600));
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                if (!strokes.isEmpty()) {
                    for (final var stroke : strokes) {
                        stream.moveTo(stroke.x1(), 600 - stroke.y1());
                        stream.lineTo(stroke.x2(), 600 - stroke.y2());
                    }
                    stream.stroke();
                }
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                for (final var value : text) {
                    stream.beginText();
                    stream.setFont(font, 12);
                    final float x =
                            value.alignRight() ? value.x() - font.getStringWidth(value.value()) * 12 / 1000 : value.x();
                    stream.newLineAtOffset(x, 600 - value.baseline());
                    stream.showText(value.value());
                    stream.endText();
                }
                if (image) {
                    stream.drawImage(LosslessFactory.createFromImage(
                                             document, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)),
                            150, 475, 10, 10);
                }
            }
            final var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return DocumentSource.bytes(bytes.toByteArray(), "borderless.pdf");
        }
    }
}
