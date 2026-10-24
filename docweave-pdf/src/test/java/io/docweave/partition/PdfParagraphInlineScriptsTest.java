package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Owned native script geometry and actual tagged PDF controls, independent of publisher documents. */
class PdfParagraphInlineScriptsTest {
    /** Creates independent test instances. */
    PdfParagraphInlineScriptsTest() {
    }

    /**
     * Raised and lowered positions retain all text and exact enclosing original coordinates.
     * @throws Exception if grouping fails
     */
    @Test
    void joinsUniqueRaisedAndLoweredGlyphsWithoutCoordinateChanges() throws Exception {
        for (final float baseline : List.of(96.5f, 103.5f)) {
            final var input = List.of(glyph("alpha", 0, 100, 20, 6), glyph("2", 20, baseline, 2, 3.5f),
                    glyph("beta", 22, 100, 16, 6), glyph("gamma", 0, 114, 25, 6));
            final var actual = PdfParagraphInlineScripts.lines(input, new PdfTableWork());
            assertEquals(1, actual.assignedGlyphs());
            assertEquals(List.of("alpha2beta", "gamma"), actual.lines().stream().map(PdfLayoutItem::text).toList());
            assertEquals(0, actual.lines().getFirst().box().left());
            assertEquals(38, actual.lines().getFirst().box().right());
            assertEquals(Math.min(94, baseline - 3.5f), actual.lines().getFirst().box().top());
            assertEquals(Math.max(100, baseline), actual.lines().getFirst().box().bottom());
            assertEquals(baseline, input.get(1).y());
            assertThrows(UnsupportedOperationException.class, () -> actual.lines().clear());
        }
    }

    /**
     * Competing rows, distant columns, ordinary-size and nonpositive positions retain historical grouping.
     * @throws Exception if grouping fails
     */
    @Test
    void ambiguityAndNonScriptGeometryKeepOriginalRuns() throws Exception {
        final var body = glyph("alpha", 0, 100, 20, 6);
        for (final var input : List.of(List.of(body, glyph("2", 20, 104, 2, 3.5f), glyph("beta", 0, 108, 20, 6)),
                     List.of(body, glyph("2", 80, 96.5f, 2, 3.5f)), List.of(body, glyph("2", 20, 90, 2, 3.5f)),
                     List.of(body, glyph("2", 20, 96.5f, 2, 6)), List.of(body, glyph("2", 20, 96.5f, 2, 0)),
                     List.of(body, glyph("2", 20, 100, 2, 3.5f)))) {
            final var actual = PdfParagraphInlineScripts.lines(input, new PdfTableWork());
            assertEquals(0, actual.assignedGlyphs());
            assertEquals(PdfGlyphLines.lines(input), actual.lines());
        }
    }

    /**
     * Actual source tags isolate script grouping from a separate same-baseline sidebar.
     * @throws Exception if owned PDF encoding or extraction fails
     */
    @Test
    void actualTaggedSuperscriptsAndSubscriptsKeepIndependentText() throws Exception {
        for (final boolean lowered : List.of(false, true)) {
            final var bytes = source(lowered, true);
            final var actual = extract(policy(), bytes);
            final var paragraph = actual.stream()
                                          .filter(e -> e.metadata().containsKey("pdf_source_inline_scripts"))
                                          .findFirst()
                                          .orElseThrow();
            assertEquals("alpha2beta gamma", paragraph.text());
            assertEquals(1, paragraph.metadata().get("pdf_source_inline_script_glyphs"));
            assertEquals("nearby_small_glyphs_v1", paragraph.metadata().get("pdf_source_inline_scripts"));
            assertEquals(List.of("sidebar"), actual.stream().filter(e -> e != paragraph).map(Element::text).toList());
            assertNotEquals(actual.stream().map(Element::text).toList(),
                    extract(PdfTaggedParagraphOptions.defaults(), bytes).stream().map(Element::text).toList());
        }
    }

    /**
     * Untagged source text receives no new geometric interpretation.
     * @throws Exception if owned PDF encoding or extraction fails
     */
    @Test
    void untaggedContentKeepsHistoricalOutput() throws Exception {
        final var bytes = source(false, false);
        assertEquals(extract(PdfTaggedParagraphOptions.defaults(), bytes), extract(policy(), bytes));
    }

    /**
     * The original line-joined character bound is checked before script grouping removes generated separators.
     * @throws Exception if owned PDF encoding fails
     */
    @Test
    void originalCharacterBudgetCannotBeBypassed() throws Exception {
        final var options = new PdfTaggedParagraphOptions(1024, 16, PdfTaggedParagraphOptions.WordWrap.PRESERVE)
                                    .withInlineScripts(PdfTaggedParagraphOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
        final var failure = assertThrows(PartitionException.class, () -> extract(options, source(false, true)));
        assertEquals(PartitionException.Code.LAYOUT_LIMIT, failure.code());
    }

    /** Validates legacy constructors, immutable copies and nonnull policy boundaries. */
    @Test
    void oldConstructorsAndPolicyCopiesRemainCompatible() {
        final var old = new PdfTaggedParagraphOptions(10, 100, PdfTaggedParagraphOptions.WordWrap.PRESERVE,
                PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS);
        assertEquals(PdfTaggedParagraphOptions.InlineScripts.PRESERVE, old.inlineScripts());
        assertEquals(PdfTaggedParagraphOptions.InlineScripts.NEARBY_SMALL_GLYPHS,
                policy().withWordWrap(PdfTaggedParagraphOptions.WordWrap.SOURCE_LINE_WRAPS)
                        .withPageBoundary(PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS)
                        .inlineScripts());
        assertThrows(NullPointerException.class, () -> old.withInlineScripts(null));
    }

    /**
     * Comparison work and cancellation remain typed and page-bounded.
     * @throws Exception if work controls fail
     */
    @Test
    void workLimitAndInterruptionRemainEnforced() throws Exception {
        final var work = new PdfTableWork();
        for (int index = 0; index < 2_000_000; index++) {
            work.step();
        }
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> PdfParagraphInlineScripts.lines(List.of(glyph("a", 0, 100, 4, 6)), work))
                        .code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> PdfParagraphInlineScripts.lines(List.of(glyph("a", 0, 100, 4, 6)), new PdfTableWork()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // The test owns the interruption and must clear it before the runner reuses this thread.
            Thread.interrupted();
        }
    }

    /**
     * Creates immutable native geometry without changing direction or source identities.
     * @param text original text
     * @param x native left
     * @param y original baseline
     * @param width original width
     * @param height original height
     * @return owned native glyph
     */
    private static PdfPageText.Glyph glyph(
            final String text, final float x, final float y, final float width, final float height) {
        return new PdfPageText.Glyph(text, x, y, width, height, 3, 0);
    }

    /**
     * Selects only the independent script-grouping policy.
     * @return explicit script grouping with otherwise historical defaults
     */
    private static PdfTaggedParagraphOptions policy() {
        return PdfTaggedParagraphOptions.defaults().withInlineScripts(
                PdfTaggedParagraphOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
    }

    /**
     * Extracts a complete owned original through the public PDF API.
     * @param options explicit policy
     * @param bytes complete owned source
     * @return all original elements
     * @throws Exception if extraction fails
     */
    private static List<Element> extract(final PdfTaggedParagraphOptions options, final byte[] bytes) throws Exception {
        return DocWeave
                .create(PartitionerRegistry.of(
                        List.of(PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(options))))
                .partition(DocumentSource.bytes(bytes, "scripts.pdf"))
                .elements();
    }

    /**
     * Encodes a source-owned paragraph plus an independent unmarked sidebar.
     * @param lowered whether the script is lowered rather than raised
     * @param tagged whether original paragraph ownership exists
     * @return complete owned source PDF
     * @throws Exception if native source encoding fails
     */
    private static byte[] source(final boolean lowered, final boolean tagged) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            document.addPage(page);
            final var root = new PDStructureTreeRoot();
            if (tagged) {
                document.getDocumentCatalog().setStructureTreeRoot(root);
            }
            final var paragraph = new PDStructureElement("P", root);
            paragraph.setPage(page);
            paragraph.getCOSObject().setInt(COSName.K, 1);
            root.appendKid(paragraph);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (final var stream = new PDPageContentStream(document, page)) {
                final var properties = new COSDictionary();
                properties.setInt(COSName.MCID, 1);
                stream.beginMarkedContent(COSName.getPDFName("Span"), PDPropertyList.create(properties));
                draw(stream, font, "alpha", 30, 300, 12);
                final float scriptX = 30 + font.getStringWidth("alpha") * 0.012f;
                draw(stream, font, "2", scriptX, lowered ? 296 : 304, 7);
                draw(stream, font, "beta", scriptX + font.getStringWidth("2") * 0.007f, 300, 12);
                draw(stream, font, "gamma", 30, 284, 12);
                stream.endMarkedContent();
                draw(stream, font, "sidebar", 270, 300, 12);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Writes one original text run without alternate text.
     * @param stream owned content stream
     * @param font owned font
     * @param text original text
     * @param x original x
     * @param y original y
     * @param size original font size
     * @throws Exception if encoding fails
     */
    private static void draw(final PDPageContentStream stream, final PDType1Font font, final String text, final float x,
            final float y, final float size) throws Exception {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
    }
}
