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

/** Public-facade layout policies, using owned source geometry and explicit expected reading sequences. */
class PdfReadingOrderTest {
    private record Line(float x, float y, String text, int size) {
        Line(final float x, final float y, final String text) {
            this(x, y, text, 12);
        }
    }

    @Test
    void paragraphsAreOptInPreserveHyphensAndUnionCoordinates() throws Exception {
        final var source =
                pdf(List.of(new Line(40, 100, "A wrapped para-"), new Line(40, 118, "graph remains readable.")), false,
                        false);
        assertEquals(List.of("A wrapped para-", "graph remains readable."), texts(DocWeave.create().partition(source)));
        final var result = processor(PdfOptions.TextLayout.PARAGRAPHS).partition(source);
        assertEquals(List.of("A wrapped para- graph remains readable."), texts(result));
        final var paragraph = result.elements().getFirst();
        assertEquals(ElementType.UncategorizedText, paragraph.type());
        assertEquals(2, paragraph.metadata().get("pdf_line_count"));
        assertEquals("paragraphs_v1", paragraph.metadata().get("pdf_text_layout"));
        final var coordinates = (Map<?, ?>) paragraph.metadata().get("coordinates");
        final var points = (List<?>) coordinates.get("points");
        assertEquals(40.0, ((List<?>) points.getFirst()).getFirst());
        assertEquals(118.0, ((List<?>) points.getLast()).getLast());
    }

    @Test
    void indentsListsFontChangesAndLargeGapsPreventJoins() throws Exception {
        final var lines =
                List.of(new Line(40, 100, "First ordinary line."), new Line(60, 118, "Indented ordinary line."),
                        new Line(60, 136, "1. Separate list item"), new Line(60, 154, "2. Another list item"),
                        new Line(60, 200, "A later paragraph."), new Line(60, 218, "Larger font heading", 18));
        final var source = pdf(lines, false, false);
        assertEquals(texts(DocWeave.create().partition(source)),
                texts(processor(PdfOptions.TextLayout.PARAGRAPHS).partition(source)));
    }

    @Test
    void twoColumnsReadLeftThenRightInsteadOfAlternatingRows() throws Exception {
        final var source = pdf(columns(100), false, false);
        assertEquals(List.of("Left line one.", "Right line one.", "Left line two.", "Right line two.",
                             "Left line three.", "Right line three."),
                texts(DocWeave.create().partition(source)));
        final var result = processor(PdfOptions.TextLayout.COLUMN_PARAGRAPHS).partition(source);
        assertEquals(List.of("Left line one. Left line two. Left line three.",
                             "Right line one. Right line two. Right line three."),
                texts(result));
        assertEquals(List.of(1, 2), result.elements().stream().map(e -> e.metadata().get("pdf_column")).toList());
        assertTrue(result.elements().stream().allMatch(
                e -> e.metadata().get("pdf_reading_order").equals("two_column_ltr_v1")));
        assertEquals(List.of(3, 3), result.elements().stream().map(e -> e.metadata().get("pdf_line_count")).toList());
    }

    @Test
    void gutterConsensusUsesWhitespaceIntersectionWhenLeftLinesHaveDifferentLengths() throws Exception {
        final var lines = List.of(new Line(40, 100, "Left short."), new Line(340, 100, "Right first."),
                new Line(40, 118, "Left medium length line."), new Line(340, 118, "Right second."),
                new Line(40, 136, "Left line with significantly longer visible content."),
                new Line(340, 136, "Right third."));
        final var result = processor(PdfOptions.TextLayout.COLUMN_PARAGRAPHS).partition(pdf(lines, false, false));
        assertEquals("Left short. Left medium length line. Left line with significantly longer visible content. "
                             + "Right first. Right second. Right third.",
                String.join(" ", texts(result)));
        assertTrue(result.elements().stream().allMatch(e -> e.metadata().containsKey("pdf_column")));
    }

    @Test
    void spanningTextSplitsBandsAndIsNotJoinedIntoEitherColumn() throws Exception {
        final var lines = new ArrayList<>(columns(100));
        final String span = "A full width section line extending across both native columns and the gutter.";
        lines.add(new Line(40, 180, span));
        lines.addAll(columns(220));
        final var result = processor(PdfOptions.TextLayout.COLUMN_PARAGRAPHS).partition(pdf(lines, false, false));
        assertEquals(List.of("Left line one. Left line two. Left line three.",
                             "Right line one. Right line two. Right line three.", span,
                             "Left line one. Left line two. Left line three.",
                             "Right line one. Right line two. Right line three."),
                texts(result));
        assertFalse(result.elements().get(2).metadata().containsKey("pdf_column"));
        assertNotEquals(result.elements().getFirst().metadata().get("pdf_reading_band"),
                result.elements().getLast().metadata().get("pdf_reading_band"));
    }

    @Test
    void structuralTableAndImageAreUnchangedBarriers() throws Exception {
        final var lines = new ArrayList<>(columns(100));
        lines.addAll(columns(260));
        final var source = pdf(lines, true, true);
        final var original = DocWeave.create().partition(source);
        final var result = processor(PdfOptions.TextLayout.COLUMN_PARAGRAPHS).partition(source);
        assertEquals(List.of(ElementType.UncategorizedText, ElementType.UncategorizedText, ElementType.Table,
                             ElementType.Image, ElementType.UncategorizedText, ElementType.UncategorizedText),
                result.elements().stream().map(e -> e.type()).toList());
        for (final var type : List.of(ElementType.Table, ElementType.Image)) {
            final var expected = original.elements().stream().filter(e -> e.type() == type).findFirst().orElseThrow();
            final var actual = result.elements().stream().filter(e -> e.type() == type).findFirst().orElseThrow();
            assertEquals(expected.text(), actual.text());
            assertEquals(expected.metadata(), actual.metadata());
        }
    }

    @Test
    void insufficientPairedRowsAndOverlappingBarrierBandsRetainGeometricOrder() throws Exception {
        final var shortColumns = columns(100).subList(0, 4);
        final var shortSource = pdf(shortColumns, false, false);
        assertEquals(texts(DocWeave.create().partition(shortSource)),
                texts(processor(PdfOptions.TextLayout.COLUMN_PARAGRAPHS).partition(shortSource)));
        final var overlap = new ArrayList<>(columns(100));
        overlap.add(new Line(40, 141, "A full width barrier extending well across the gutter and both text columns."));
        final var result = processor(PdfOptions.TextLayout.COLUMN_PARAGRAPHS).partition(pdf(overlap, false, false));
        assertTrue(result.elements().stream().noneMatch(e -> e.metadata().containsKey("pdf_column")));
        final String combined = String.join(" ", texts(result));
        for (final String token : List.of("Left line one.", "Left line two.", "Left line three.", "Right line one.",
                     "Right line two.", "Right line three.")) {
            assertTrue(combined.contains(token));
        }
    }

    @Test
    void pageBoundariesNeverJoinAndSharedStrategyRemainsDeterministic() throws Exception {
        final var source = pdf(columns(100), false, false);
        final var shared = processor(PdfOptions.TextLayout.COLUMN_PARAGRAPHS);
        final var expected = shared.partition(source).elements();
        try (final var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 16)
                                      .mapToObj(index -> executor.submit(() -> shared.partition(source).elements()))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(expected, task.get());
            }
        }
        try (final var document = new PDDocument()) {
            for (int index = 0; index < 2; index++) {
                final var page = new PDPage(new PDRectangle(600, 600));
                document.addPage(page);
                try (final var stream = new PDPageContentStream(document, page)) {
                    write(stream, new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                            new Line(40, 100, "Same page-local line."));
                }
            }
            final var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            final var result = shared.partition(DocumentSource.bytes(bytes.toByteArray(), "pages.pdf"));
            assertEquals(2, result.elements().size());
            assertEquals(List.of(1, 2), result.elements().stream().map(e -> e.metadata().get("page_number")).toList());
        }
    }

    @Test
    void optionCopiesPreserveBothSelectionsAndOldConstructorsKeepLines() {
        final var selected = PdfOptions.defaults()
                                     .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS)
                                     .withTableDetection(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT);
        assertEquals(PdfOptions.TextLayout.COLUMN_PARAGRAPHS, selected.textLayout());
        assertEquals(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT, selected.tableDetection());
        assertEquals(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT,
                selected.withTextLayout(PdfOptions.TextLayout.PARAGRAPHS).tableDetection());
        final var old = new PdfOptions(10, 1000, 1000, 100, 1000, false, TableLimits.defaults());
        assertEquals(PdfOptions.TextLayout.LINES, old.textLayout());
        assertEquals(
                PdfOptions.TextLayout.LINES, new PdfOptions(10, 1000, 1000, 100, 1000, false, TableLimits.defaults(),
                                                     PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT)
                                                     .textLayout());
        assertThrows(NullPointerException.class, () -> selected.withTextLayout(null));
    }

    @Test
    void layoutWorkCeilingAndEntryCancellationRemainTypedAndReusable() throws Exception {
        final var item = new PdfLayoutItem(ElementType.UncategorizedText, "Repeated native line.",
                new PdfLayoutItem.Bounds(40, 90, 160, 100), Map.of());
        final PdfReadingOrder service = new GeometricPdfReadingOrder(PdfOptions.TextLayout.COLUMN_PARAGRAPHS);
        // Repeated references exercise the work counter without allocating a million distinct source objects.
        final var limit = assertThrows(
                PartitionException.class, () -> service.apply(java.util.Collections.nCopies(1_000_001, item)));
        assertEquals(PartitionException.Code.LAYOUT_LIMIT, limit.code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> service.apply(List.of(item)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertSame(item, service.apply(List.of(item)).getFirst());
    }

    private static DocWeave processor(final PdfOptions.TextLayout mode) {
        return DocWeave.create(
                PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults().withTextLayout(mode)))));
    }

    private static List<String> texts(final PartitionResult result) {
        return result.elements().stream().map(e -> e.text()).toList();
    }

    private static List<Line> columns(final int top) {
        return List.of(new Line(40, top, "Left line one."), new Line(340, top, "Right line one."),
                new Line(40, top + 18, "Left line two."), new Line(340, top + 18, "Right line two."),
                new Line(40, top + 36, "Left line three."), new Line(340, top + 36, "Right line three."));
    }

    private static DocumentSource pdf(final List<Line> lines, final boolean table, final boolean image)
            throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(600, 600));
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                for (final var line : lines) {
                    write(stream, font, line);
                }
                if (table) {
                    for (final int y : new int[] {180, 200, 220}) {
                        stream.moveTo(40, 600 - y);
                        stream.lineTo(560, 600 - y);
                    }
                    for (final int x : new int[] {40, 300, 560}) {
                        stream.moveTo(x, 380);
                        stream.lineTo(x, 420);
                    }
                    stream.stroke();
                    write(stream, font, new Line(50, 195, "Table cell"));
                }
                if (image) {
                    stream.drawImage(LosslessFactory.createFromImage(
                                             document, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)),
                            40, 360, 20, 10);
                }
            }
            final var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return DocumentSource.bytes(bytes.toByteArray(), "reading.pdf");
        }
    }

    private static void write(final PDPageContentStream stream, final PDType1Font font, final Line line)
            throws Exception {
        stream.beginText();
        stream.setFont(font, line.size());
        stream.newLineAtOffset(line.x(), 600 - line.y());
        stream.showText(line.text());
        stream.endText();
    }
}
