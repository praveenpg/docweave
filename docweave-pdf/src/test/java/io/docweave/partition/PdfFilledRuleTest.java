package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Owned thin-fill controls exercise real PDF paint operators, independent text expectations and fallback. */
class PdfFilledRuleTest {
    @Test
    void thinFilledGridRetainsWrappedHeaderAndCellBoundsWithoutAlignedMode() throws Exception {
        final var result =
                processor(PdfOptions.defaults().withTableCellCoordinates(true)).partition(source("normal", 0));
        assertEquals(1, result.elements().size());
        final var table = result.elements().getFirst();
        assertEquals(ElementType.Table, table.type());
        assertEquals("Name Wrapped Value Alpha 001 Beta 20", table.text());
        assertEquals("<table><tbody><tr><td>Name</td><td>Wrapped Value</td></tr>"
                             + "<tr><td>Alpha</td><td>001</td></tr><tr><td>Beta</td><td>20</td></tr></tbody></table>",
                table.metadata().get("text_as_html"));
        assertEquals("complete_ruled_grid", table.metadata().get("pdf_table_method"));
        final var cells = (Map<?, ?>)table.metadata().get("pdf_table_cells");
        assertEquals("rule_centerlines", cells.get("geometry"));
        assertEquals(3, cells.get("row_count"));
        assertEquals(2, cells.get("column_count"));
        final var first = (Map<?, ?>)((List<?>)cells.get("cells")).getFirst();
        final var points = (List<?>)((Map<?, ?>)first.get("coordinates")).get("points");
        assertEquals(List.of(30.0, 140.0), points.getFirst());
        assertEquals(List.of(130.0, 180.0), points.get(2));
    }

    @Test
    void transformedAndExplicitClosedLineRectanglesShareGridRecognition() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final var result = processor(PdfOptions.defaults()).partition(source("normal", rotation));
            assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Table).count());
            assertEquals(2.0, result.elements().getFirst().metadata().get("pdf_user_unit"));
        }
        for (final String mode : List.of("lines", "icc", "hairline", "boundary")) {
            assertEquals("Name Wrapped Value Alpha 001 Beta 20",
                    processor(PdfOptions.defaults()).partition(source(mode, 0)).elements().getFirst().text(), mode);
        }
    }

    @Test
    void unsupportedFillPaintAndOpenTopologyRemainOrdinaryText() throws Exception {
        for (final String mode :
                List.of("white", "pale", "transparent", "wide", "curve", "compound", "gap", "skew", "unpainted")) {
            final var result = processor(PdfOptions.defaults()).partition(source(mode, 0));
            assertTrue(result.elements().stream().noneMatch(e -> e.type() == ElementType.Table), mode);
            final String text =
                    result.elements().stream().map(e -> e.text()).collect(java.util.stream.Collectors.joining(" "));
            for (final String label : List.of("Name", "Wrapped", "Value", "Alpha", "001", "Beta", "20")) {
                assertTrue(text.contains(label), mode + ": " + label);
            }
        }
    }

    @Test
    void filledTablesUseExistingLimitsAndConcurrentDocumentIsolation() throws Exception {
        final var defaults = PdfOptions.defaults();
        final var limited =
                new PdfOptions(defaults.maxPages(), defaults.maxGlyphsPerPage(), defaults.maxOperatorsPerPage(),
                        defaults.maxImagePixels(), defaults.maxImageBytes(), false, new TableLimits(2, 2, 6, 80));
        final var source = source("normal", 0);
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> processor(limited).partition(source)).code());
        final var shared = processor(defaults);
        final var expected = shared.partition(source).elements();
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 8)
                                      .mapToObj(i -> executor.submit(() -> shared.partition(source).elements()))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(expected, task.get());
            }
        }
    }

    @Test
    void implicitlyClosedFillRectanglesMatchExplicitPaintAcrossDisplayedFrames() throws Exception {
        final var options = PdfOptions.defaults().withTableCellCoordinates(true);
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final var expected = processor(options).partition(source("normal", rotation)).elements().getFirst();
            for (final String mode : List.of("implicit", "implicit-evenodd")) {
                final var elements = processor(options).partition(source(mode, rotation)).elements();
                assertEquals(1, elements.size(), mode);
                final var actual = elements.getFirst();
                assertEquals(ElementType.Table, actual.type());
                assertEquals(expected.text(), actual.text());
                for (final String key : List.of("coordinates", "pdf_table_cells", "text_as_html")) {
                    assertEquals(expected.metadata().get(key), actual.metadata().get(key), mode + ": " + key);
                }
            }
        }
    }

    @Test
    void implicitFillDoesNotTurnTrianglesCurvesOrCompoundSubpathsIntoRules() throws Exception {
        for (final String mode : List.of("implicit-triangle", "implicit-curve", "implicit-compound",
                     "implicit-four-edge-compound", "implicit-white")) {
            final var result = processor(PdfOptions.defaults()).partition(source(mode, 0));
            assertTrue(result.elements().stream().noneMatch(element -> element.type() == ElementType.Table), mode);
            final var text = result.elements()
                                     .stream()
                                     .map(element -> element.text())
                                     .collect(java.util.stream.Collectors.joining(" "));
            for (final String label : List.of("Name", "Wrapped", "Value", "Alpha", "001", "Beta", "20")) {
                assertTrue(text.contains(label), mode);
            }
        }
    }

    @Test
    void implicitFillRetainsTableLimitsAndConcurrentDocumentIsolation() throws Exception {
        final var defaults = PdfOptions.defaults();
        final var limited =
                new PdfOptions(defaults.maxPages(), defaults.maxGlyphsPerPage(), defaults.maxOperatorsPerPage(),
                        defaults.maxImagePixels(), defaults.maxImageBytes(), false, new TableLimits(2, 2, 6, 80));
        final var input = source("implicit", 0);
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> processor(limited).partition(input)).code());
        final var shared = processor(defaults);
        final var expected = shared.partition(input).elements();
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 8)
                                      .mapToObj(index -> executor.submit(() -> shared.partition(input).elements()))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(expected, task.get());
            }
        }
    }

    @Test
    void openStrokeRemainsOpenAndDiscardedCompoundStateDoesNotLeakIntoLaterFill() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.moveTo(20, 20);
                content.lineTo(100, 20);
                content.lineTo(100, 20.5f);
                content.lineTo(20, 20.5f);
                content.stroke();
                // Two unpainted subpaths must be forgotten before the following independent fill.
                content.moveTo(10, 60);
                content.lineTo(20, 60);
                content.moveTo(30, 60);
                content.lineTo(40, 60);
                content.appendRawCommands("n\n");
                content.setNonStrokingColor(Color.BLACK);
                rectangle(content, "implicit", 20, 100, 80, 0.5f);
            }
            final var options = PdfOptions.defaults();
            final var graphics =
                    new PdfPageGraphics(page, options, new PdfImageBudget(options), new PdfPageGeometry(page));
            graphics.processPage(page);
            assertEquals(4, graphics.segments().size());
            assertEquals(new PdfPageGraphics.Segment(20, 180, 100, 180), graphics.segments().getFirst());
            assertTrue(graphics.segments().stream().noneMatch(segment -> segment.x1() == 20 && segment.x2() == 20));
            assertEquals(new PdfPageGraphics.Segment(19.75f, 99.75f, 100.25f, 99.75f), graphics.segments().getLast());
        }
    }

    private static DocWeave processor(final PdfOptions options) {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
    }

    private static DocumentSource source(final String mode, final int rotation) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            page.setCropBox(new PDRectangle(50, 40, 300, 320));
            page.setRotation(rotation);
            page.setUserUnit(2);
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.saveGraphicsState();
                content.setNonStrokingColor((mode.equals("white") || mode.equals("implicit-white")) ? Color.WHITE
                                            : mode.equals("pale")                                   ? Color.LIGHT_GRAY
                                                                                                    : Color.BLACK);
                if (mode.equals("transparent")) {
                    final var state = new PDExtendedGraphicsState();
                    state.setNonStrokingAlphaConstant(0.5f);
                    content.setGraphicsStateParameters(state);
                }
                if (mode.equals("icc")) {
                    final var color = new org.apache.pdfbox.pdmodel.graphics.color.PDICCBased(document);
                    color.getPDStream().getCOSObject().setInt(org.apache.pdfbox.cos.COSName.N, 3);
                    try (final var profile = color.getPDStream().createOutputStream()) {
                        profile.write(
                                java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData());
                    }
                    content.setNonStrokingColor(
                            new org.apache.pdfbox.pdmodel.graphics.color.PDColor(new float[] {0, 0, 0}, color));
                }
                final float thickness = mode.equals("wide")       ? 2
                                        : mode.equals("hairline") ? 0.125f
                                        : mode.equals("boundary") ? 1
                                                                  : 0.5f;
                if (mode.equals("skew")) {
                    content.transform(new org.apache.pdfbox.util.Matrix(1, 0.1f, 0, 1, 0, 0));
                }
                for (final int y : List.of(100, 140, 180, 220)) {
                    rectangle(content, mode, 80 - thickness / 2, y - thickness / 2, 200 + thickness, thickness);
                }
                for (final int x : List.of(80, 180, 280)) {
                    if (mode.equals("gap")) {
                        rectangle(content, mode, x - thickness / 2, 100, thickness, 50);
                        rectangle(content, mode, x - thickness / 2, 155, thickness, 65);
                    } else {
                        rectangle(content, mode, x - thickness / 2, 100 - thickness / 2, thickness, 120 + thickness);
                    }
                }
                if (mode.equals("compound")) {
                    content.fill();
                }
                content.restoreGraphicsState();
                text(content, "Name", 90, 200);
                text(content, "Wrapped", 190, 205);
                text(content, "Value", 190, 193);
                text(content, "Alpha", 90, 165);
                text(content, "001", 190, 165);
                text(content, "Beta", 90, 125);
                text(content, "20", 190, 125);
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "filled.pdf");
        }
    }

    private static void rectangle(final PDPageContentStream content, final String mode, final float x, final float y,
            final float width, final float height) throws Exception {
        if (mode.startsWith("implicit")) {
            content.moveTo(x, y);
            content.lineTo(x + width, y);
            if (mode.equals("implicit-compound") || mode.equals("implicit-four-edge-compound")) {
                content.moveTo(x + width, y);
            }
            content.lineTo(x + width, y + height);
            if (!mode.equals("implicit-triangle")) {
                content.lineTo(x, y + height);
            }
            if (mode.equals("implicit-four-edge-compound")) {
                content.lineTo(x, y);
            }
            if (mode.equals("implicit-curve")) {
                content.curveTo(x, y + height, x, y, x, y);
            }
        } else if (mode.equals("lines")) {
            content.moveTo(x, y);
            content.lineTo(x + width, y);
            content.lineTo(x + width, y + height);
            content.lineTo(x, y + height);
            content.closePath();
        } else {
            content.addRect(x, y, width, height);
        }
        if (mode.equals("curve")) {
            content.curveTo(x, y, x + 1, y + 1, x, y);
        }
        if (mode.equals("unpainted")) {
            content.appendRawCommands("n\n");
        } else if (mode.equals("implicit-evenodd")) {
            content.fillEvenOdd();
        } else if (!mode.equals("compound")) {
            content.fill();
        }
    }

    private static void text(final PDPageContentStream content, final String text, final float x, final float y)
            throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }
}
