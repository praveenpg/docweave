package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Owned paint/operator controls with independently equal rendered pixels for invisible source grid strokes. */
class PdfStrokeOpacityTest {
    /** Creates independent paint controls. */
    PdfStrokeOpacityTest() {
    }

    /**
     * Zero-opacity stroke and fill/stroke paths render exactly like no grid and must leave native text unclaimed.
     * @throws Exception if owned authoring, rendering or extraction fails
     */
    @Test
    void zeroAlphaStrokesCannotDefineTables() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final var ordinary = source("none", 1, rotation);
            for (final String mode : List.of("stroke", "transparent-fillstroke")) {
                final var bytes = source(mode, 0, rotation);
                assertSameRaster(ordinary, bytes);
                final var result = extract(bytes);
                assertTrue(result.elements().stream().noneMatch(element -> element.type() == ElementType.Table), mode);
                final String text =
                        result.elements().stream().map(element -> element.text()).collect(Collectors.joining(" "));
                for (final String value : List.of("Item", "Qty", "Alpha", "10", "Beta", "20")) {
                    assertTrue(text.contains(value), value);
                }
            }
        }
    }

    /**
     * Nonzero stroke evidence retains historical interpretation even at a small positive opacity.
     * @throws Exception if source authoring or extraction fails
     */
    @Test
    void positiveStrokeOpacityRetainsExistingRules() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final var expected = extract(source("stroke", 1, rotation)).elements().getFirst();
            for (final float alpha : new float[] {1, 0.25f, 0.0001f}) {
                for (final String mode : List.of("stroke", "transparent-fillstroke")) {
                    final var result = extract(source(mode, alpha, rotation));
                    assertEquals(1, result.elements().size());
                    assertEquals(ElementType.Table, result.elements().getFirst().type());
                    assertEquals(expected.text(), result.elements().getFirst().text());
                    assertEquals(expected.metadata().get("pdf_table_cells"),
                            result.elements().getFirst().metadata().get("pdf_table_cells"));
                }
            }
        }
    }

    /**
     * Fill/stroke operators retain valid independent thin-fill evidence when their stroke is invisible.
     * @throws Exception if source authoring or extraction fails
     */
    @Test
    void invisibleStrokeStillAllowsValidThinFillEvidence() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final var result = extract(source("thin-fillstroke", 0, rotation));
            assertEquals(1, result.elements().size());
            assertEquals(ElementType.Table, result.elements().getFirst().type());
            assertEquals(extract(source("stroke", 1, rotation)).elements().getFirst().text(),
                    result.elements().getFirst().text());
        }
        assertTrue(extract(source("wide-fillstroke", 0, 0))
                        .elements()
                        .stream()
                        .noneMatch(element -> element.type() == ElementType.Table));
    }

    /**
     * Extracts through the public default rule-only strategy with cell coordinates enabled.
     * @param bytes complete owned source
     * @return complete public partition result
     * @throws java.io.IOException if source extraction fails
     */
    private static PartitionResult extract(final byte[] bytes) throws java.io.IOException {
        return DocWeave
                .create(PartitionerRegistry.of(
                        List.of(PdfPartitioner.create(PdfOptions.defaults().withTableCellCoordinates(true)))))
                .partition(DocumentSource.bytes(bytes, "stroke-opacity.pdf"));
    }

    /**
     * Independently renders both source programs at equal resolution and requires exact RGB identity.
     * @param first complete no-grid source
     * @param second complete source with invisible rule operators
     * @throws Exception if rendering or pixel equality fails
     */
    private static void assertSameRaster(final byte[] first, final byte[] second) throws Exception {
        try (final var original = Loader.loadPDF(first); final var candidate = Loader.loadPDF(second)) {
            final var before = new PDFRenderer(original).renderImageWithDPI(0, 72);
            final var after = new PDFRenderer(candidate).renderImageWithDPI(0, 72);
            try {
                assertEquals(before.getWidth(), after.getWidth());
                assertEquals(before.getHeight(), after.getHeight());
                assertArrayEquals(
                        before.getRGB(0, 0, before.getWidth(), before.getHeight(), null, 0, before.getWidth()),
                        after.getRGB(0, 0, after.getWidth(), after.getHeight(), null, 0, after.getWidth()));
            } finally {
                before.flush();
                after.flush();
            }
        }
    }

    /**
     * Authors identical original text under source-only stroke/fill opacity and frame variants.
     * @param mode no grid, stroke-only, transparent fill/stroke, thin fill/stroke or wide fill/stroke
     * @param alpha source stroke opacity
     * @param rotation displayed quarter turn
     * @return complete owned PDF bytes
     * @throws Exception if source authoring fails
     */
    private static byte[] source(final String mode, final float alpha, final int rotation) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            page.setCropBox(new PDRectangle(20, 30, 360, 340));
            page.setUserUnit(2);
            page.setRotation(rotation);
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.saveGraphicsState();
                final var state = new PDExtendedGraphicsState();
                state.setStrokingAlphaConstant(alpha);
                state.setNonStrokingAlphaConstant(mode.equals("transparent-fillstroke") ? 0f : 1f);
                content.setGraphicsStateParameters(state);
                if (mode.equals("stroke") || mode.equals("transparent-fillstroke")) {
                    for (final int x : List.of(40, 190, 340)) {
                        content.moveTo(x, 150);
                        content.lineTo(x, 300);
                    }
                    for (final int y : List.of(150, 200, 250, 300)) {
                        content.moveTo(40, y);
                        content.lineTo(340, y);
                    }
                    if (mode.equals("stroke")) {
                        content.stroke();
                    } else {
                        content.fillAndStroke();
                    }
                } else if (mode.equals("thin-fillstroke")) {
                    for (final int x : List.of(40, 190, 340)) {
                        content.addRect(x - 0.25f, 149.75f, 0.5f, 150.5f);
                        content.fillAndStroke();
                    }
                    for (final int y : List.of(150, 200, 250, 300)) {
                        content.addRect(39.75f, y - 0.25f, 300.5f, 0.5f);
                        content.fillAndStroke();
                    }
                } else if (mode.equals("wide-fillstroke")) {
                    content.addRect(40, 150, 300, 150);
                    content.fillAndStroke();
                }
                content.restoreGraphicsState();
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                final var values = List.of("Item", "Qty", "Alpha", "10", "Beta", "20");
                for (int index = 0; index < values.size(); index++) {
                    content.beginText();
                    content.setFont(font, 10);
                    content.newLineAtOffset(index % 2 == 0 ? 50 : 200, 280 - (index / 2) * 50);
                    content.showText(values.get(index));
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
