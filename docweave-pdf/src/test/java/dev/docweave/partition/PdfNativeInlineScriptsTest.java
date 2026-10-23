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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Owned controls for the explicit native small-glyph run policy on ordinary text. */
class PdfNativeInlineScriptsTest {
    /** Provenance recorded on every text element under the explicit policy. */
    private static final String POLICY = "nearby_small_glyph_runs_v1";

    /** Creates isolated owned-source controls. */
    PdfNativeInlineScriptsTest() {
    }

    /**
     * Raised and lowered one- and two-glyph markers join their body line under the policy only; unrelated sidebar
     * coordinates and the default output stay unchanged.
     * @throws Exception if owned source writing or extraction fails
     */
    @Test
    void explicitPolicyJoinsMarkersWithTheirBodyLine() throws Exception {
        for (final String script : List.of("2", "12")) {
            for (final boolean lowered : List.of(false, true)) {
                final var bytes = source(script, lowered ? -4 : 4, 0, false);
                for (final var layout : List.of(PdfOptions.TextLayout.LINES, PdfOptions.TextLayout.COLUMN_PARAGRAPHS)) {
                    final var before = extract(bytes, PdfOptions.defaults().withTextLayout(layout));
                    final var after = extract(bytes, PdfOptions.defaults().withTextLayout(layout).withInlineScripts(
                                                             PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS));
                    assertTrue(before.stream().noneMatch(e -> e.text().contains("alpha" + script + "beta")));
                    assertTrue(before.stream().anyMatch(e -> e.text().equals(script)));
                    assertTrue(after.stream().anyMatch(e -> e.text().startsWith("alpha" + script + "beta")));
                    assertTrue(after.stream().noneMatch(e -> e.text().equals(script)));
                    assertEquals(coordinates(before, "sidebar"), coordinates(after, "sidebar"));
                    assertTrue(after.stream().allMatch(
                            e -> POLICY.equals(e.metadata().get("pdf_native_inline_script_policy"))));
                    assertTrue(before.stream().noneMatch(
                            e -> e.metadata().containsKey("pdf_native_inline_script_policy")));
                    assertEquals(characters(before), characters(after));
                }
            }
        }
    }

    /**
     * Distant small text, runs longer than eight glyphs and markers between two equidistant body lines keep their
     * historical grouping; only the provenance metadata differs from the default.
     * @throws Exception if owned source writing or extraction fails
     */
    @Test
    void unsupportedSmallTextKeepsHistoricalLines() throws Exception {
        for (final var bytes :
                List.of(source("123456789", 4, 0, false), source("2", 4, 0, true), source("2", 40, 0, false))) {
            final var before = extract(bytes, PdfOptions.defaults());
            final var after = extract(
                    bytes, PdfOptions.defaults().withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS));
            assertEquals(texts(before), texts(after));
            assertEquals(before.stream().map(e -> e.metadata().get("coordinates")).toList(),
                    after.stream().map(e -> e.metadata().get("coordinates")).toList());
        }
    }

    /**
     * Pages whose upright frame is displayed rotated keep historical grouping, matching the native cell policy.
     * @throws Exception if owned source writing or extraction fails
     */
    @Test
    void rotatedPagesKeepHistoricalGrouping() throws Exception {
        final var bytes = source("2", 4, 90, false);
        assertEquals(texts(extract(bytes, PdfOptions.defaults())),
                texts(extract(
                        bytes, PdfOptions.defaults().withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS))));
    }

    /**
     * Ruled native table cells keep their own cell policy; only ordinary text outside the table is regrouped.
     * @throws Exception if owned source writing or extraction fails
     */
    @Test
    void nativeTableCellsAreNotRegrouped() throws Exception {
        final var bytes = ruledSource();
        final var before = extract(bytes, PdfOptions.defaults());
        final var after =
                extract(bytes, PdfOptions.defaults().withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS));
        final var table = before.stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        final var selected = after.stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        assertEquals(table.text(), selected.text());
        assertEquals(table.metadata().get("text_as_html"), selected.metadata().get("text_as_html"));
        assertTrue(after.stream().anyMatch(e -> e.text().equals("alpha2beta")));
    }

    /**
     * The policy defaults to historical grouping, survives every copy method and is required.
     */
    @Test
    void optionsDefaultToHistoricalGroupingAndCopiesRetainThePolicy() {
        assertEquals(PdfOptions.InlineScripts.PRESERVE, PdfOptions.defaults().inlineScripts());
        final var old = new PdfOptions(10, 100, 100, 100, 100, false, TableLimits.defaults(),
                PdfOptions.TableDetection.RULED_ONLY, PdfOptions.TextLayout.LINES, false,
                PdfOptions.TableContinuation.NONE, PdfOptions.UnicodeNormalization.RAW, PdfOptions.TextRendering.ALL,
                PdfOptions.FigureCaptions.NONE, false, false, true);
        assertEquals(PdfOptions.InlineScripts.PRESERVE, old.inlineScripts());
        assertTrue(old.curvedClipEnvelopes());
        final var selected = PdfOptions.defaults().withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
        for (final var copy : List.of(selected.withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS),
                     selected.withTableDetection(PdfOptions.TableDetection.RULED_ONLY),
                     selected.withTableCellCoordinates(true),
                     selected.withTableContinuation(PdfOptions.TableContinuation.NONE),
                     selected.withUnicodeNormalization(PdfOptions.UnicodeNormalization.RAW),
                     selected.withTextRendering(PdfOptions.TextRendering.ALL),
                     selected.withFigureCaptions(PdfOptions.FigureCaptions.NONE), selected.withImageClipBounds(true),
                     selected.withImageGroupContext(true), selected.withCurvedClipEnvelopes(true))) {
            assertEquals(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS, copy.inlineScripts());
        }
        assertNotEquals(PdfOptions.defaults(), selected);
        assertEquals(PdfOptions.defaults(), selected.withInlineScripts(PdfOptions.InlineScripts.PRESERVE));
        assertThrows(NullPointerException.class, () -> PdfOptions.defaults().withInlineScripts(null));
    }

    /**
     * Writes an owned page with a body line, an adjacent small marker, a second line and an independent sidebar.
     * @param script marker characters
     * @param shift marker baseline shift from the body baseline in points; large shifts are distant small text
     * @param rotation page rotation in degrees
     * @param competing whether a second body line sits the same distance on the other side of the marker
     * @return complete owned PDF bytes
     * @throws Exception if writing fails
     */
    private static byte[] source(final String script, final float shift, final int rotation, final boolean competing)
            throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            page.setRotation(rotation);
            document.addPage(page);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (final var content = new PDPageContentStream(document, page)) {
                draw(content, font, "alpha", 30, 300, 12);
                final float scriptX = 30 + font.getStringWidth("alpha") * .012f;
                draw(content, font, script, scriptX, 300 + shift, 7);
                draw(content, font, "beta", scriptX + font.getStringWidth(script) * .007f, 300, 12);
                if (competing) {
                    draw(content, font, "delta", 30, 300 + 2 * shift, 12);
                }
                draw(content, font, "gamma", 30, 270, 12);
                draw(content, font, "sidebar", 270, 300, 12);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Writes a ruled two-by-two native table whose first cell holds a raised marker, plus the same pattern outside.
     * @return complete owned PDF bytes
     * @throws Exception if writing fails
     */
    private static byte[] ruledSource() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            document.addPage(page);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (final var content = new PDPageContentStream(document, page)) {
                for (final float x : new float[] {20, 150, 280}) {
                    content.moveTo(x, 260);
                    content.lineTo(x, 340);
                }
                for (final float y : new float[] {260, 300, 340}) {
                    content.moveTo(20, y);
                    content.lineTo(280, y);
                }
                content.stroke();
                draw(content, font, "alpha", 30, 315, 12);
                final float scriptX = 30 + font.getStringWidth("alpha") * .012f;
                draw(content, font, "2", scriptX, 319, 7);
                draw(content, font, "beta", scriptX + font.getStringWidth("2") * .007f, 315, 12);
                draw(content, font, "one", 160, 315, 12);
                draw(content, font, "two", 30, 275, 12);
                draw(content, font, "three", 160, 275, 12);
                draw(content, font, "alpha", 30, 150, 12);
                draw(content, font, "2", scriptX, 154, 7);
                draw(content, font, "beta", scriptX + font.getStringWidth("2") * .007f, 150, 12);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Writes one original run at independently specified font metrics and coordinates.
     * @param content open source stream
     * @param font source font
     * @param value original characters
     * @param x original left edge
     * @param y original baseline
     * @param size original font size
     * @throws Exception if writing fails
     */
    private static void draw(final PDPageContentStream content, final PDType1Font font, final String value,
            final float x, final float y, final float size) throws Exception {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(value);
        content.endText();
    }

    /**
     * Extracts every element through the public partitioner.
     * @param bytes owned source
     * @param options explicit extraction policy
     * @return emitted elements in order
     * @throws Exception if extraction fails
     */
    private static List<Element> extract(final byte[] bytes, final PdfOptions options) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(bytes, "owned.pdf"))
                .elements();
    }

    /**
     * Returns emitted texts in order.
     * @param elements emitted elements
     * @return texts
     */
    private static List<String> texts(final List<Element> elements) {
        return elements.stream().map(Element::text).toList();
    }

    /**
     * Returns the sorted non-space characters of all text, which grouping must never add, drop or change.
     * @param elements emitted elements
     * @return sorted characters
     */
    private static String characters(final List<Element> elements) {
        return elements.stream()
                .map(Element::text)
                .flatMapToInt(String::codePoints)
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .sorted()
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /**
     * Returns the coordinates of the element with the given text.
     * @param elements emitted elements
     * @param text exact element text
     * @return coordinate metadata
     */
    private static Object coordinates(final List<Element> elements, final String text) {
        return elements.stream()
                .filter(e -> e.text().equals(text))
                .map(e -> e.metadata().get("coordinates"))
                .findFirst()
                .orElseThrow();
    }
}
