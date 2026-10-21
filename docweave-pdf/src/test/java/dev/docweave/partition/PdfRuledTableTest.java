package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Owned source geometries and explicit expected cell topologies, exercised through the public facade. */
class PdfRuledTableTest {
    private record Stroke(float x1, float y1, float x2, float y2) {}

    private record Text(float x, float baseline, String value) {}

    @Test
    void multipleSideBySideAndStackedTablesIgnoreDisconnectedDecorations() throws Exception {
        final var strokes = new ArrayList<Stroke>();
        strokes.addAll(complete(40, 80));
        strokes.addAll(complete(300, 80));
        strokes.addAll(complete(40, 240));
        strokes.add(new Stroke(20, 20, 550, 20));
        strokes.add(new Stroke(20, 500, 550, 500));
        final var source = pdf(strokes, List.of(new Text(50, 100, "Left"), new Text(310, 100, "Right"),
                                                new Text(50, 260, "Lower"), new Text(50, 210, "Between")));
        final var result = DocWeave.create().partition(source);
        assertEquals(
                List.of("Left", "Right", "Between", "Lower"), result.elements().stream().map(e -> e.text()).toList());
        assertEquals(List.of(ElementType.Table, ElementType.Table, ElementType.UncategorizedText, ElementType.Table),
                result.elements().stream().map(e -> e.type()).toList());
        assertEquals("<table><tbody><tr><td>Left</td><td></td></tr><tr><td></td><td></td></tr></tbody></table>",
                result.elements().getFirst().metadata().get("text_as_html"));
    }

    @Test
    void horizontalMergeProducesOneColspanAndRetainsEmptyCellStructure() throws Exception {
        final var strokes =
                List.of(new Stroke(40, 80, 240, 80), new Stroke(40, 120, 240, 120), new Stroke(40, 160, 240, 160),
                        new Stroke(40, 80, 40, 160), new Stroke(240, 80, 240, 160), new Stroke(140, 120, 140, 160));
        final var result = DocWeave.create().partition(
                pdf(strokes, List.of(new Text(50, 100, "Merged"), new Text(50, 140, "Bottom"))));
        assertTable(result, "Merged Bottom",
                "<table><tbody><tr><td colspan=\"2\">Merged</td></tr><tr><td>Bottom</td><td></td></tr></tbody></table>");
        assertEquals("ruled_grid_with_spans", result.elements().getFirst().metadata().get("pdf_table_method"));
    }

    @Test
    void verticalMergeOwnsMultilineTextOnce() throws Exception {
        final var strokes =
                List.of(new Stroke(40, 80, 240, 80), new Stroke(140, 120, 240, 120), new Stroke(40, 160, 240, 160),
                        new Stroke(40, 80, 40, 160), new Stroke(140, 80, 140, 160), new Stroke(240, 80, 240, 160));
        final var result = DocWeave.create().partition(
                pdf(strokes, List.of(new Text(50, 100, "Tall"), new Text(50, 140, "Cell"), new Text(150, 100, "Top"),
                                     new Text(150, 140, "Bottom"))));
        assertTable(result, "Tall Cell Top Bottom",
                "<table><tbody><tr><td rowspan=\"2\">Tall Cell</td><td>Top</td></tr><tr><td>Bottom</td></tr></tbody></table>");
    }

    @Test
    void twoDimensionalMergeHasOneAnchorAcrossFourAtomicSlots() throws Exception {
        final var strokes = List.of(new Stroke(40, 80, 280, 80), new Stroke(200, 120, 280, 120),
                new Stroke(40, 160, 280, 160), new Stroke(40, 200, 280, 200), new Stroke(40, 80, 40, 200),
                new Stroke(120, 160, 120, 200), new Stroke(200, 80, 200, 200), new Stroke(280, 80, 280, 200));
        final var result = DocWeave.create().partition(
                pdf(strokes, List.of(new Text(50, 100, "M"), new Text(210, 100, "R1"), new Text(210, 140, "R2"),
                                     new Text(50, 180, "A"), new Text(130, 180, "B"), new Text(210, 180, "C"))));
        assertTable(result, "M R1 R2 A B C",
                "<table><tbody><tr><td rowspan=\"2\" colspan=\"2\">M</td><td>R1</td></tr><tr><td>R2</td></tr>"
                        + "<tr><td>A</td><td>B</td><td>C</td></tr></tbody></table>");
    }

    @Test
    void reversedDuplicatedAndSegmentedStrokesAreEquivalent() throws Exception {
        final var original = complete(40, 80);
        final var segmented = new ArrayList<Stroke>();
        for (final var stroke : original) {
            final float midX = (stroke.x1() + stroke.x2()) / 2;
            final float midY = (stroke.y1() + stroke.y2()) / 2;
            segmented.add(new Stroke(stroke.x2(), stroke.y2(), midX, midY));
            segmented.add(new Stroke(midX, midY, stroke.x1(), stroke.y1()));
            segmented.add(stroke);
        }
        Collections.reverse(segmented);
        final var text = List.of(new Text(50, 100, "A&B"), new Text(150, 140, "<D>"));
        final var processor = DocWeave.create();
        assertEquals(processor.partition(pdf(original, text)).elements(),
                processor.partition(pdf(segmented, text)).elements());
    }

    @Test
    void lShapedMergeAndOpenPerimeterPreserveOrdinaryText() throws Exception {
        final var lShape =
                List.of(new Stroke(40, 80, 240, 80), new Stroke(140, 120, 240, 120), new Stroke(40, 160, 240, 160),
                        new Stroke(40, 80, 40, 160), new Stroke(140, 120, 140, 160), new Stroke(240, 80, 240, 160));
        final var open = new ArrayList<>(complete(40, 80));
        open.removeFirst();
        for (final var strokes : List.of(lShape, open)) {
            assertOrdinaryText(strokes);
        }
    }

    @Test
    void partialCellEdgeIsNotMisinterpretedAsIntentionalMerge() throws Exception {
        final var partial = new ArrayList<>(complete(40, 80));
        partial.set(1, new Stroke(40, 120, 190, 120));
        assertOrdinaryText(partial);
    }

    @Test
    void nestedIndependentClosedGridsAreRejectedWithoutDuplicateOwnership() throws Exception {
        final var outer = new ArrayList<Stroke>();
        for (final int y : new int[] {40, 220, 400}) {
            outer.add(new Stroke(20, y, 580, y));
        }
        for (final int x : new int[] {20, 290, 580}) {
            outer.add(new Stroke(x, 40, x, 400));
        }
        outer.addAll(complete(40, 80));
        assertOrdinaryText(outer);
    }

    @Test
    void rejectedComponentDoesNotSuppressSeparateValidTable() throws Exception {
        final var strokes = new ArrayList<>(complete(40, 80));
        strokes.set(1, new Stroke(40, 120, 190, 120));
        strokes.addAll(complete(300, 240));
        final var result = DocWeave.create().partition(
                pdf(strokes, List.of(new Text(50, 100, "Ordinary"), new Text(310, 260, "Valid"))));
        assertEquals(List.of(ElementType.UncategorizedText, ElementType.Table),
                result.elements().stream().map(e -> e.type()).toList());
        assertEquals(List.of("Ordinary", "Valid"), result.elements().stream().map(e -> e.text()).toList());
    }

    @Test
    void geometryWorkLimitAndAtomicSlotLimitsFailExplicitly() throws Exception {
        final var many = Collections.nCopies(2100, new Stroke(20, 20, 500, 20));
        final var tooMuchWork = assertThrows(PartitionException.class,
                () -> DocWeave.create().partition(pdf(many, List.of(new Text(50, 100, "Text")))));
        assertEquals(PartitionException.Code.TABLE_LIMIT, tooMuchWork.code());
        assertTrue(tooMuchWork.getMessage().contains("work limit"));
        final var merged =
                List.of(new Stroke(40, 80, 240, 80), new Stroke(40, 120, 240, 120), new Stroke(40, 160, 240, 160),
                        new Stroke(40, 80, 40, 160), new Stroke(240, 80, 240, 160), new Stroke(140, 120, 140, 160));
        final var options = new PdfOptions(10, 10000, 10000, 100, 10000, false, new TableLimits(2, 2, 3, 100));
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> processor.partition(pdf(merged, List.of(new Text(50, 100, "Merged")))))
                        .code());
    }

    private static void assertOrdinaryText(final List<Stroke> strokes) throws Exception {
        final var result = DocWeave.create().partition(
                pdf(strokes, List.of(new Text(50, 100, "Alpha"), new Text(150, 100, "Beta"), new Text(50, 140, "Gamma"),
                                     new Text(150, 140, "Delta"))));
        assertTrue(result.elements().stream().noneMatch(e -> e.type() == ElementType.Table));
        final var tokens = String.join(" ", result.elements().stream().map(e -> e.text()).toList()).split("\\s+");
        assertEquals(List.of("Alpha", "Beta", "Gamma", "Delta"), List.of(tokens));
    }

    private static void assertTable(final PartitionResult result, final String text, final String html) {
        assertEquals(1, result.elements().size());
        assertEquals(ElementType.Table, result.elements().getFirst().type());
        assertEquals(text, result.elements().getFirst().text());
        assertEquals(html, result.elements().getFirst().metadata().get("text_as_html"));
    }

    private static List<Stroke> complete(final int left, final int top) {
        final var strokes = new ArrayList<Stroke>();
        for (final int y : new int[] {top, top + 40, top + 80}) {
            strokes.add(new Stroke(left, y, left + 200, y));
        }
        for (final int x : new int[] {left, left + 100, left + 200}) {
            strokes.add(new Stroke(x, top, x, top + 80));
        }
        return strokes;
    }

    private static DocumentSource pdf(final List<Stroke> strokes, final List<Text> texts) throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(600, 600));
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                for (final var stroke : strokes) {
                    stream.moveTo(stroke.x1(), 600 - stroke.y1());
                    stream.lineTo(stroke.x2(), 600 - stroke.y2());
                }
                stream.stroke();
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                for (final var text : texts) {
                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(text.x(), 600 - text.baseline());
                    stream.showText(text.value());
                    stream.endText();
                }
            }
            final var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return DocumentSource.bytes(bytes.toByteArray(), "tables.pdf");
        }
    }
}
