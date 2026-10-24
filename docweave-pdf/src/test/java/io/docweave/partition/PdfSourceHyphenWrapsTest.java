package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Physical native endpoints and owned tagged PDFs constrain explicit hyphen-wrap spacing. */
class PdfSourceHyphenWrapsTest {
    /** Creates independent glyph/source ownership controls. */
    PdfSourceHyphenWrapsTest() {
    }

    /**
     * Only the generated line separator is removed; physical characters, geometry and unclaimed content remain.
     * @throws Exception if owned source encoding or extraction fails
     */
    @Test
    void explicitPolicyRetainsHardHyphensAndOriginalGeometry() throws Exception {
        final var bytes = source("hard");
        final var prior = extract(PdfTaggedParagraphOptions.defaults().withWordWrap(
                                          PdfTaggedParagraphOptions.WordWrap.SOURCE_SOFT_HYPHENS),
                bytes);
        final var actual = extract(policy(), bytes);
        assertEquals("alpha- beta", prior.getFirst().text());
        assertEquals("alpha-beta", actual.getFirst().text());
        assertEquals(prior.getFirst().metadata().get("coordinates"), actual.getFirst().metadata().get("coordinates"));
        assertEquals("source_line_wraps_v1", actual.getFirst().metadata().get("pdf_source_word_wrap"));
        assertEquals(1, actual.getFirst().metadata().get("pdf_source_retained_hyphen_wraps"));
        assertEquals(0, actual.getFirst().metadata().get("pdf_source_joined_soft_hyphens"));
        assertEquals(prior.subList(1, prior.size()), actual.subList(1, actual.size()));
        assertEquals(List.of("outside"), actual.subList(1, actual.size()).stream().map(Element::text).toList());
    }

    /**
     * Proven discretionary removal still takes precedence over retained physical-hyphen spacing.
     * @throws Exception if owned source encoding or extraction fails
     */
    @Test
    void sourceSoftHintsRetainTheirExistingPriority() throws Exception {
        final var actual = extract(policy(), source("soft"));
        assertEquals("alphabeta", actual.getFirst().text());
        assertEquals(0, actual.getFirst().metadata().get("pdf_source_retained_hyphen_wraps"));
        assertEquals(1, actual.getFirst().metadata().get("pdf_source_joined_soft_hyphens"));
    }

    /**
     * Actual source spaces, numeric boundaries and unsupported source ownership are not repaired.
     * @throws Exception if owned source encoding or extraction fails
     */
    @Test
    void explicitWhitespaceNumbersAndUntaggedContentKeepLegacyText() throws Exception {
        for (final String variant : List.of("trailing-space", "leading-space", "numeric", "untagged")) {
            final var bytes = source(variant);
            final var prior = extract(PdfTaggedParagraphOptions.defaults(), bytes);
            final var actual = extract(policy(), bytes);
            assertEquals(
                    prior.stream().map(Element::text).toList(), actual.stream().map(Element::text).toList(), variant);
            assertTrue(actual.stream().noneMatch(
                    element -> Integer.valueOf(1).equals(element.metadata().get("pdf_source_retained_hyphen_wraps"))));
        }
    }

    /**
     * Cross-page assembly accepts the new policy while preserving the page boundary's ordinary separator.
     * @throws Exception if owned source encoding, extraction or assembly fails
     */
    @Test
    void pageFragmentsNeverJoinPhysicalHyphensAcrossPages() throws Exception {
        final var original = extract(
                policy().withPageBoundary(PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS), source("cross-page"));
        final var actual = PdfParagraphAssembler.create().assemble(original);
        assertEquals("alpha- beta", actual.getFirst().text());
        assertEquals("source_line_wraps_v1", actual.getFirst().metadata().get("pdf_source_word_wrap"));
        assertEquals(List.of("outside"), actual.subList(1, actual.size()).stream().map(Element::text).toList());
        final var parts = (List<?>) actual.getFirst().metadata().get("pdf_source_paragraph_parts");
        assertEquals(2, parts.size());
        assertTrue(original.stream()
                        .filter(element -> element.metadata().containsKey("pdf_source_word_wrap"))
                        .allMatch(element
                                -> Integer.valueOf(0).equals(
                                        element.metadata().get("pdf_source_retained_hyphen_wraps"))));
    }

    /**
     * Prejoin original-text budgets cannot be bypassed by shortening a generated separator.
     * @throws Exception if owned source encoding fails
     */
    @Test
    void originalJoinedTextLimitStillAppliesBeforeShortening() throws Exception {
        final var bytes = source("hard");
        final var limited = new PdfTaggedParagraphOptions(10, 10, policy().wordWrap());
        assertEquals(PartitionException.Code.LAYOUT_LIMIT,
                assertThrows(PartitionException.class, () -> extract(limited, bytes)).code());
    }

    /**
     * Explicit edge whitespace is rejected even when it has zero width or overlaps the same source endpoint.
     * @throws Exception if bounded glyph grouping fails
     */
    @Test
    void nativeEndpointAmbiguityAndZeroWidthSpacesRemainUnchanged() throws Exception {
        final var members = members("alpha", "beta", "-", 34);
        final var lines = PdfGlyphLines.lines(members);
        assertEquals(Set.of(0), PdfSourceHyphenWraps.boundaries(members, lines, new PdfTableWork()));
        for (final var extra : List.of(
                     glyph(" ", 38, 20, 0), glyph("\u00a0", 10, 34, 0), glyph("-", 35, 20, 3), glyph("b", 10, 34, 2))) {
            final var ambiguous = new ArrayList<>(members);
            ambiguous.add(extra);
            assertTrue(PdfSourceHyphenWraps.boundaries(ambiguous, lines, new PdfTableWork()).isEmpty());
        }
    }

    /**
     * The policy does not reinterpret numeric ranges, punctuation, non-ASCII hyphens or long/same-line breaks.
     * @throws Exception if bounded glyph grouping fails
     */
    @Test
    void unsupportedCharactersAndVerticalGapsAreNotJoined() throws Exception {
        for (final var members : List.of(members("123", "beta", "-", 34), members("alpha", "123", "-", 34),
                     members("alpha", "(beta", "-", 34), members("alpha", "beta", "\u2010", 34),
                     members("alpha", "beta", "-", 80), members("alpha ", "beta", "-", 34))) {
            assertTrue(PdfSourceHyphenWraps.boundaries(members, PdfGlyphLines.lines(members), new PdfTableWork())
                            .isEmpty());
        }
        final var sameLine = List.of(new PdfLayoutItem(io.docweave.core.ElementType.UncategorizedText, "alpha-",
                                             new PdfLayoutItem.Bounds(10, 12, 38, 20), java.util.Map.of()),
                new PdfLayoutItem(io.docweave.core.ElementType.UncategorizedText, "beta",
                        new PdfLayoutItem.Bounds(50, 12, 70, 20), java.util.Map.of()));
        assertTrue(PdfSourceHyphenWraps.boundaries(members("alpha", "beta", "-", 20), sameLine, new PdfTableWork())
                        .isEmpty());
    }

    /**
     * Existing work and interruption contracts remain active during endpoint inspection.
     * @throws Exception if owned grouping fails
     */
    @Test
    void endpointWorkLimitsAndCancellationRemainTyped() throws Exception {
        final var members = members("alpha", "beta", "-", 34);
        final var lines = PdfGlyphLines.lines(members);
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> PdfSourceHyphenWraps.boundaries(
                                        java.util.Collections.nCopies(2_000_001, members.getFirst()), lines,
                                        new PdfTableWork()))
                        .code());
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class,
                    () -> PdfSourceHyphenWraps.boundaries(members, lines, new PdfTableWork()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // The assertion checks preservation; the test must clear its own flag before returning the worker thread.
            Thread.interrupted();
        }
    }

    /**
     * Creates actual endpoint geometry with a separate physical hyphen glyph.
     * @param first text before the physical hyphen
     * @param next text starting the following source line
     * @param hyphen original source character
     * @param baseline next source baseline
     * @return immutable owned glyph positions
     */
    private static List<PdfPageText.Glyph> members(
            final String first, final String next, final String hyphen, final float baseline) {
        return List.of(glyph(first, 10, 20, 25), glyph(hyphen, 35, 20, 3), glyph(next, 10, baseline, 20));
    }

    /**
     * Creates one upright source position without consulting an output string or reference.
     * @param text original source Unicode
     * @param x source left edge
     * @param baseline source baseline
     * @param width actual source width
     * @return owned glyph
     */
    private static PdfPageText.Glyph glyph(final String text, final float x, final float baseline, final float width) {
        return new PdfPageText.Glyph(text, x, baseline, width, 8, 3, 0);
    }

    /**
     * Selects the additional explicit paragraph-only interpretation.
     * @return immutable policy retaining default budgets and page boundary
     */
    private static PdfTaggedParagraphOptions policy() {
        return PdfTaggedParagraphOptions.defaults().withWordWrap(PdfTaggedParagraphOptions.WordWrap.SOURCE_LINE_WRAPS);
    }

    /**
     * Processes a complete original in-memory source with only one configured PDF service.
     * @param options explicit source paragraph policy
     * @param source owned original PDF
     * @return immutable complete document elements
     * @throws Exception if public partitioning fails
     */
    private static List<Element> extract(final PdfTaggedParagraphOptions options, final byte[] source)
            throws Exception {
        final var pdf = PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(options);
        return DocWeave.create(PartitionerRegistry.of(List.of(pdf)))
                .partition(DocumentSource.bytes(source, "owned-wrap.pdf"))
                .elements();
    }

    /**
     * Encodes native hyphens/whitespace separately from logical source paragraph ownership.
     * @param variant hard, soft, trailing-space, leading-space, numeric, untagged or cross-page
     * @return complete original owned PDF bytes
     * @throws Exception if source encoding fails
     */
    private static byte[] source(final String variant) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var first = new PDPage(new PDRectangle(400, 400));
            document.addPage(first);
            final var root = new PDStructureTreeRoot();
            if (!variant.equals("untagged")) {
                document.getDocumentCatalog().setStructureTreeRoot(root);
            }
            final var paragraph = new PDStructureElement("P", root);
            paragraph.setPage(first);
            root.appendKid(paragraph);
            final var content = new COSArray();
            content.add(COSInteger.get(1));
            paragraph.getCOSObject().setItem(COSName.K, content);
            if (variant.equals("soft")) {
                final var hint = new PDStructureElement("Span", paragraph);
                hint.setActualText("\u00ad");
                hint.getCOSObject().setInt(COSName.K, 2);
                content.add(hint.getCOSObject());
            } else {
                content.add(COSInteger.get(2));
            }
            final var second = variant.equals("cross-page") ? new PDPage(new PDRectangle(400, 400)) : first;
            if (second != first) {
                document.addPage(second);
                final var reference = new COSDictionary();
                reference.setItem(COSName.TYPE, COSName.MCR);
                reference.setItem(COSName.PG, second);
                reference.setInt(COSName.MCID, 3);
                content.add(reference);
            } else {
                content.add(COSInteger.get(3));
            }
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (final var stream = new PDPageContentStream(document, first)) {
                draw(stream, font, 1, "alpha", 30, 300);
                draw(stream, font, 2, variant.equals("trailing-space") ? "- " : "-",
                        30 + font.getStringWidth("alpha") * 0.012f, 300);
                if (second == first) {
                    draw(stream, font, 3,
                            variant.equals("leading-space") ? " beta"
                            : variant.equals("numeric")     ? "123"
                                                            : "beta",
                            30, 286);
                }
                draw(stream, font, -1, "outside", 30, 100);
            }
            if (second != first) {
                try (final var stream = new PDPageContentStream(document, second)) {
                    draw(stream, font, 3, "beta", 30, 300);
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Writes an owned native MCID or unmarked independent text without alternate-text substitution.
     * @param stream owned content stream
     * @param font owned source font
     * @param id nonnegative native MCID, or minus one for unrelated text
     * @param value original native text
     * @param x original left position
     * @param y original PDF baseline
     * @throws Exception if native content encoding fails
     */
    private static void draw(final PDPageContentStream stream, final PDType1Font font, final int id, final String value,
            final float x, final float y) throws Exception {
        if (id >= 0) {
            final var properties = new COSDictionary();
            properties.setInt(COSName.MCID, id);
            stream.beginMarkedContent(COSName.getPDFName("Span"), PDPropertyList.create(properties));
        }
        stream.beginText();
        stream.setFont(font, 12);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
        if (id >= 0) {
            stream.endMarkedContent();
        }
    }
}
