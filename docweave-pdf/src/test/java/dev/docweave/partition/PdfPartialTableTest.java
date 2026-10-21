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

/** Independently authored partial-rule layouts fix expected strings, spans, ownership and rejection behavior. */
class PdfPartialTableTest {
    /** Creates isolated authored partial-table controls. */
    PdfPartialTableTest() {
    }

    /**
     * Checks exact spanning-header output, unchanged defaults and concurrent service reuse.
     * @throws Exception if source authoring or the expected extraction behavior fails
     */
    @Test
    void explicitModeInfersHeaderSpanWithoutChangingDefaults() throws Exception {
        final var source = source("normal");
        assertTrue(
                DocWeave.create().partition(source).elements().stream().noneMatch(e -> e.type() == ElementType.Table));
        final var processor = processor(PdfOptions.defaults().withTableCellCoordinates(true));
        final var result = processor.partition(source);
        final var table =
                result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        assertEquals("Annual A&B Item Qty Value Alpha 001 -1.5 Beta 02 3.4 Gamma 30 5.6", table.text());
        assertEquals("<table><tbody><tr><td colspan=\"3\">Annual A&amp;B</td></tr>"
                             + "<tr><td>Item</td><td>Qty</td><td>Value</td></tr>"
                             + "<tr><td>Alpha</td><td>001</td><td>-1.5</td></tr>"
                             + "<tr><td>Beta</td><td>02</td><td>3.4</td></tr>"
                             + "<tr><td>Gamma</td><td>30</td><td>5.6</td></tr></tbody></table>",
                table.metadata().get("text_as_html"));
        assertEquals("partial_rule_text_heuristic", table.metadata().get("pdf_table_method"));
        final var geometry = (Map<?, ?>)table.metadata().get("pdf_table_cells");
        assertEquals("inferred_cell_bounds", geometry.get("geometry"));
        assertEquals(5, geometry.get("row_count"));
        assertEquals(3, geometry.get("column_count"));
        assertEquals(13, ((List<?>) geometry.get("cells")).size());
        final var first = (Map<?, ?>)((List<?>)geometry.get("cells")).getFirst();
        assertEquals(3, first.get("column_span"));
        assertEquals(3, result.elements().size());
        assertEquals("Above", result.elements().getFirst().text());
        assertEquals("Below", result.elements().getLast().text());
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("infers cell areas")));
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 8)
                                      .mapToObj(i -> executor.submit(() -> processor.partition(source).elements()))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(result.elements(), task.get());
            }
        }
    }

    /**
     * Header words separated across a supported column edge must not become one spurious spanning cell.
     * @throws Exception if owned PDF authoring or extraction fails
     */
    @Test
    void closeHeaderColumnsRemainSeparateWhileTheTitleRetainsItsSpan() throws Exception {
        final var result = processor(PdfOptions.defaults()).partition(source("closeheaders"));
        final var table =
                result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        final String html = (String) table.metadata().get("text_as_html");
        assertTrue(html.contains("<td colspan=\"3\">Annual A&amp;B</td>"));
        assertTrue(html.contains("<tr><td>Left</td><td>Right</td><td>Value</td></tr>"), html);
    }

    /**
     * Checks whole-candidate fallback for unsupported or ambiguous source geometry.
     * @throws Exception if source authoring or the expected extraction behavior fails
     */
    @Test
    void unsupportedAndAmbiguousSourcesRetainOrdinaryElements() throws Exception {
        for (final var mode : List.of("missing", "prose", "uneven", "overlap", "cross", "image", "unsegmented", "shift",
                     "duplicate", "foreignrule", "headerline", "intruder", "singleheader", "rotation")) {
            final var source = source(mode);
            assertEquals(DocWeave.create().partition(source).elements(),
                    processor(PdfOptions.defaults()).partition(source).elements(), mode);
        }
    }

    /**
     * Checks retained header blanks and supported source body dividers.
     * @throws Exception if source authoring or the expected extraction behavior fails
     */
    @Test
    void blanksAndSupportedBodyBoundaryPaintAreExplicit() throws Exception {
        for (final var mode : List.of("blankheader", "vertical")) {
            final var result = processor(PdfOptions.defaults()).partition(source(mode));
            final var table =
                    result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
            if (mode.equals("blankheader")) {
                assertTrue(table.metadata().get("text_as_html").toString().contains("<tr><td></td><td>Qty</td>"));
            }
        }
    }

    /**
     * Checks source crop and UserUnit coordinates through explicit conversion.
     * @throws Exception if source authoring or the expected extraction behavior fails
     */
    @Test
    void croppedUserUnitFrameKeepsExplicitUnscaledCellCoordinates() throws Exception {
        final var result = processor(PdfOptions.defaults().withTableCellCoordinates(true)).partition(source("crop"));
        final var table =
                result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        final var cells = (Map<?, ?>)table.metadata().get("pdf_table_cells");
        final var first = (Map<?, ?>)((List<?>)cells.get("cells")).getFirst();
        final var coordinates = (Map<?, ?>)first.get("coordinates");
        assertEquals(380.0, ((Number) coordinates.get("layout_width")).doubleValue());
        final var points = (List<?>) coordinates.get("points");
        assertEquals(List.of(30.0, 50.0), points.getFirst());
        assertEquals(List.of(270.0, 50.0), points.get(1));
        assertEquals(2.0, ((Number) table.metadata().get("pdf_user_unit")).doubleValue());
        final var converter = PdfCoordinateConverter.create();
        assertEquals(table, converter.toUserSpace(converter.toPixels(table, 144)));
    }

    /**
     * Checks spanning-header text length under the caller cell ceiling.
     * @throws Exception if source authoring or the expected extraction behavior fails
     */
    @Test
    void headerLengthUsesSupportedSpanAndStillHonorsCallerCellLimit() throws Exception {
        final var source = source("longheader");
        assertEquals(1, processor(PdfOptions.defaults())
                                .partition(source)
                                .elements()
                                .stream()
                                .filter(e -> e.type() == ElementType.Table)
                                .count());
        final var base = PdfOptions.defaults();
        final var options = new PdfOptions(base.maxPages(), base.maxGlyphsPerPage(), base.maxOperatorsPerPage(),
                base.maxImagePixels(), base.maxImageBytes(), false, new TableLimits(5, 3, 15, 80));
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> processor(options).partition(source)).code());
    }

    /**
     * Checks table ceilings and immutable option-copy behavior.
     * @throws Exception if source authoring or the expected extraction behavior fails
     */
    @Test
    void structuredLimitsAndOptionCopiesRemainEnforced() throws Exception {
        final var source = source("normal");
        final var base = PdfOptions.defaults();
        for (final var limits : List.of(new TableLimits(4, 3, 15, 80), new TableLimits(5, 2, 15, 80),
                     new TableLimits(5, 3, 14, 80), new TableLimits(5, 3, 15, 3))) {
            final var options = new PdfOptions(base.maxPages(), base.maxGlyphsPerPage(), base.maxOperatorsPerPage(),
                    base.maxImagePixels(), base.maxImageBytes(), false, limits);
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class, () -> processor(options).partition(source)).code());
        }
        final var options = base.withTableDetection(PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT)
                                    .withTextLayout(PdfOptions.TextLayout.PARAGRAPHS)
                                    .withTableCellCoordinates(true)
                                    .withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER);
        assertEquals(PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT, options.tableDetection());
    }

    /**
     * Checks cancellation, shared work exhaustion and fresh detector reuse.
     * @throws Exception if source authoring or the expected extraction behavior fails
     */
    @Test
    void cancellationWorkLimitAndReuseAreBounded() throws Exception {
        final PdfNativeTableDetector detector =
                new PartiallyRuledPdfTableDetector(TableLimits.defaults(), new PdfPageGeometry(new PDPage()));
        final var segments = new ArrayList<PdfPageGraphics.Segment>();
        for (int index = 0; index < 600; index++) {
            final float y = index * 220;
            segments.add(new PdfPageGraphics.Segment(40, y + 40, 280, y + 40));
            for (final int offset : List.of(90, 180)) {
                for (final int x : List.of(40, 120, 200)) {
                    segments.add(new PdfPageGraphics.Segment(x, y + offset, x + 80, y + offset));
                }
            }
        }
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> detector.detect(List.of(), segments, List.of(), new PdfTableWork()))
                        .code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> detector.detect(List.of(), segments, List.of(), new PdfTableWork()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(detector.detect(List.of(), List.of(), List.of(), new PdfTableWork()).isEmpty());
    }

    /**
     * Creates a facade using only the selected native strategy and caller policies.
     * @param options immutable policies
     * @return interface-based document processor
     */
    private static DocWeave processor(final PdfOptions options) {
        return DocWeave.create(PartitionerRegistry.of(List.of(
                PdfPartitioner.create(options.withTableDetection(PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT)))));
    }

    /**
     * Authors an independent three-column, two-header-baseline fixture with three complete body rows.
     * @param mode one controlled positive or rejection variation
     * @return original in-memory PDF source
     * @throws Exception if PDF authoring fails
     */
    private static DocumentSource source(final String mode) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            if (mode.equals("rotation")) {
                page.setRotation(90);
            }
            if (mode.equals("crop")) {
                page.setCropBox(new PDRectangle(10, 10, 380, 380));
                page.setUserUnit(2);
            }
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                line(content, 40, 60, 280, 60);
                for (final int y : List.of(120, 205)) {
                    if (mode.equals("unsegmented")) {
                        line(content, 40, y, 280, y);
                    } else {
                        for (final int x : List.of(40, 120, 200)) {
                            line(content, x + (mode.equals("shift") && y == 205 ? 3 : 0), y,
                                    x + 80 + (mode.equals("shift") && y == 205 ? 3 : 0), y);
                        }
                    }
                }
                if (mode.equals("duplicate")) {
                    line(content, 40, 120, 120, 120);
                }
                if (mode.equals("vertical")) {
                    line(content, 200, 120, 200, 205);
                }
                if (mode.equals("foreignrule")) {
                    line(content, 185, 120, 185, 205);
                }
                if (mode.equals("headerline")) {
                    line(content, 200, 60, 200, 120);
                }
                if (mode.equals("image")) {
                    content.drawImage(LosslessFactory.createFromImage(
                                              document, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)),
                            170, 240, 10, 10);
                }
                text(content, "Above", 45, 40, 10);
                text(content, "Below", 45, 240, 10);
                if (!mode.equals("singleheader")) {
                    if (mode.equals("longheader")) {
                        text(content, "Heading ".repeat(12).strip(), 48, 85, 4);
                    } else {
                        text(content, "Annual A&B", 100, 85, 24);
                    }
                }
                if (mode.equals("closeheaders")) {
                    text(content, "Left", 100, 110, 10);
                    text(content, "Right", 124, 110, 10);
                } else {
                    if (!mode.equals("blankheader")) {
                        text(content, "Item", 48, 110, 10);
                    }
                    text(content, "Qty", 128, 110, 10);
                }
                text(content, "Value", 208, 110, 10);
                if (mode.equals("overlap")) {
                    text(content, "note", 80, 110, 10);
                }
                for (int row = 0; row < 3; row++) {
                    final float y = 140 + row * 25 + (mode.equals("uneven") && row == 2 ? 8 : 0);
                    text(content, List.of("Alpha", "Beta", "Gamma").get(row), mode.equals("cross") ? 115 : 48, y, 10);
                    text(content, mode.equals("prose") ? "Some" : List.of("001", "02", "30").get(row), 128, y, 10);
                    if (!(mode.equals("missing") && row == 1)) {
                        text(content, mode.equals("prose") ? "More" : List.of("-1.5", "3.4", "5.6").get(row), 208, y,
                                10);
                    }
                }
                if (mode.equals("intruder")) {
                    text(content, "extra", 48, 153, 10);
                }
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "partial.pdf");
        }
    }

    /**
     * Paints a source line in the fixture's top-left coordinate frame.
     * @param content source stream
     * @param x1 first x
     * @param y1 first y
     * @param x2 second x
     * @param y2 second y
     * @throws Exception if writing fails
     */
    private static void line(final PDPageContentStream content, final float x1, final float y1, final float x2,
            final float y2) throws Exception {
        content.moveTo(x1, 400 - y1);
        content.lineTo(x2, 400 - y2);
        content.stroke();
    }

    /**
     * Writes one source text run with explicit geometry, without references to the external corpus.
     * @param content source stream
     * @param value source text
     * @param x left
     * @param y baseline
     * @param size font size
     * @throws Exception if writing fails
     */
    private static void text(final PDPageContentStream content, final String value, final float x, final float y,
            final int size) throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
        content.newLineAtOffset(x, 400 - y);
        content.showText(value);
        content.endText();
    }
}
