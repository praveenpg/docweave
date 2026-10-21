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
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Owned segmented-rule PDFs exercise source glyph splitting, exact text and explicit fallback. */
class PdfHorizontalTableTest {
    @Test
    void explicitRecognitionPreservesTextEscapesNumbersAndTextBoundCells() throws Exception {
        final var source = source("normal", 0);
        final var defaults = DocWeave.create().partition(source);
        assertTrue(defaults.elements().stream().noneMatch(e -> e.type() == ElementType.Table));
        final var options = PdfOptions.defaults().withTableCellCoordinates(true).withTableContinuation(
                PdfOptions.TableContinuation.REPEATED_HEADER);
        final var selected = processor(options);
        final var result = selected.partition(source);
        final var table =
                result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        assertEquals("Item Qty Value A&B 001 -12.5% Beta 20 2.4 Gamma 30 3.4", table.text());
        assertTrue(table.metadata()
                        .get("text_as_html")
                        .toString()
                        .contains("<td>A&amp;B</td><td>001</td><td>-12.5%</td>"));
        assertEquals("horizontal_rule_text_heuristic", table.metadata().get("pdf_table_method"));
        assertFalse(table.metadata().containsKey("pdf_table_continuation"));
        final var cells = (Map<?, ?>)table.metadata().get("pdf_table_cells");
        assertEquals("text_bounds", cells.get("geometry"));
        assertEquals(4, cells.get("row_count"));
        assertEquals(3, cells.get("column_count"));
        assertEquals(12, ((List<?>) cells.get("cells")).size());
        final var first = (Map<?, ?>)((List<?>)cells.get("cells")).getFirst();
        final var points = (List<?>)((Map<?, ?>)first.get("coordinates")).get("points");
        assertEquals(48.0, ((Number) ((List<?>) points.getFirst()).getFirst()).doubleValue(), 0.01);
        assertTrue(result.elements().stream().anyMatch(e -> e.text().equals("Above")));
        assertTrue(result.elements().stream().anyMatch(e -> e.text().equals("Below")));
        assertEquals(3, result.elements().size());
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("rule divisions")));
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
    void ambiguousLayoutsRemainIdenticalOrdinaryElements() throws Exception {
        for (final String mode : List.of("unsegmented", "shift", "ragged", "uneven", "textonly", "vertical", "intruder",
                     "cross", "two", "lowergap", "duplicate", "partialimage")) {
            final var source = source(mode, 0);
            assertEquals(DocWeave.create().partition(source).elements(),
                    processor(PdfOptions.defaults()).partition(source).elements(), mode);
        }
        final var rotated = source("normal", 90);
        assertEquals(DocWeave.create().partition(rotated).elements(),
                processor(PdfOptions.defaults()).partition(rotated).elements());
    }

    @Test
    void enclosingRasterRemainsAnImageAndStructuredLimitsAreExplicit() throws Exception {
        final var result = processor(PdfOptions.defaults()).partition(source("enclosingimage", 0));
        assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Table).count());
        assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
        final var source = source("normal", 0);
        final var base = PdfOptions.defaults();
        for (final var limit : List.of(new TableLimits(3, 3, 12, 80), new TableLimits(4, 2, 12, 80),
                     new TableLimits(4, 3, 11, 80), new TableLimits(4, 3, 12, 2))) {
            final var options = new PdfOptions(base.maxPages(), base.maxGlyphsPerPage(), base.maxOperatorsPerPage(),
                    base.maxImagePixels(), base.maxImageBytes(), false, limit);
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class, () -> processor(options).partition(source)).code());
        }
        final var mode = base.withTableDetection(PdfOptions.TableDetection.RULED_AND_HORIZONTAL_TEXT);
        assertEquals(PdfOptions.TableDetection.RULED_AND_HORIZONTAL_TEXT,
                mode.withTextLayout(PdfOptions.TextLayout.PARAGRAPHS)
                        .withTableCellCoordinates(true)
                        .withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER)
                        .tableDetection());
    }

    @Test
    void candidateSearchSharesBoundedWorkAndCancellation() throws Exception {
        final var segments = new ArrayList<PdfPageGraphics.Segment>();
        for (int block = 0; block < 600; block++) {
            for (final int offset : List.of(80, 100, 170)) {
                for (final int column : List.of(0, 1, 2)) {
                    segments.add(new PdfPageGraphics.Segment(
                            40 + column * 60, block * 250 + offset, 100 + column * 60, block * 250 + offset));
                }
            }
        }
        final var detector = new HorizontalPdfTableDetector(TableLimits.defaults(), new PdfPageGeometry(new PDPage()));
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> detector.detect(List.of(), segments, List.of(), new PdfTableWork()))
                        .code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> detector.detect(List.of(), segments, List.of(), new PdfTableWork()));
        } finally {
            Thread.interrupted();
        }
    }

    private static DocWeave processor(final PdfOptions options) {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                options.withTableDetection(PdfOptions.TableDetection.RULED_AND_HORIZONTAL_TEXT)))));
    }

    private static DocumentSource source(final String mode, final int rotation) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            page.setRotation(rotation);
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                if (mode.endsWith("image")) {
                    final var image = LosslessFactory.createFromImage(
                            document, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
                    if (mode.equals("enclosingimage")) {
                        content.drawImage(image, 20, 210, 300, 130);
                    } else {
                        content.drawImage(image, 180, 270, 10, 10);
                    }
                }
                for (final int y : List.of(80, 100, 170)) {
                    if (mode.equals("unsegmented")) {
                        line(content, 40, y, 290, y);
                    } else {
                        final float joint = mode.equals("shift") && y == 100 ? 173 : 170;
                        if (!(mode.equals("lowergap") && y == 170)) {
                            line(content, 40, y, joint, y);
                        }
                        line(content, joint, y, 230, y);
                        line(content, 230, y, 290, y);
                    }
                }
                if (mode.equals("duplicate")) {
                    line(content, 40, 80, 290, 80);
                }
                if (mode.equals("vertical")) {
                    line(content, 170, 80, 170, 170);
                }
                text(content, "Above", 48, 50);
                text(content, "Below", 48, 210);
                text(content, "Item", 48, 93);
                text(content, "Qty", 178, 93);
                text(content, "Value", 238, 93);
                for (int row = 0; row < (mode.equals("two") ? 1 : 3); row++) {
                    final float y = 115 + row * 20 + (mode.equals("uneven") && row == 2 ? 7 : 0);
                    text(content, List.of("A&B", "Beta", "Gamma").get(row), mode.equals("cross") && row == 0 ? 168 : 48,
                            y);
                    text(content, mode.equals("textonly") ? "Some" : List.of("001", "20", "30").get(row), 178, y);
                    if (!(mode.equals("ragged") && row == 1)) {
                        text(content, mode.equals("textonly") ? "More" : List.of("-12.5%", "2.4", "3.4").get(row), 238,
                                y);
                    }
                }
                if (mode.equals("intruder")) {
                    text(content, "note", 80, 143);
                }
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "horizontal.pdf");
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
