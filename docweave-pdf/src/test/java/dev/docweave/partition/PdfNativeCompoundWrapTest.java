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
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Owned source and exact reconstruction controls for explicit native compound-word separator interpretation. */
class PdfNativeCompoundWrapTest {
    /** Creates independent native paragraph controls. */
    PdfNativeCompoundWrapTest() {
    }

    /**
     * Public native and source-table factories omit only a generated separator, preserving the physical hyphen.
     * @throws Exception if owned PDF authoring or extraction fails
     */
    @Test
    void nativeParagraphsRetainCompoundsAndOriginalGeometry() throws Exception {
        final var bytes = source("Evidence for one-", "way analysis.");
        for (final boolean taggedTables : List.of(false, true)) {
            final var baseline = extract(bytes, PdfOptions.TextLayout.COLUMN_PARAGRAPHS, taggedTables);
            final var candidate = extract(bytes, PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS, taggedTables);
            assertEquals(1, baseline.size());
            assertEquals(1, candidate.size());
            assertEquals("Evidence for one- way analysis.", baseline.getFirst().text());
            assertEquals("Evidence for one-way analysis.", candidate.getFirst().text());
            assertEquals(baseline.getFirst().metadata().get("coordinates"),
                    candidate.getFirst().metadata().get("coordinates"));
            assertEquals(2, candidate.getFirst().metadata().get("pdf_line_count"));
            assertEquals(1, candidate.getFirst().metadata().get("pdf_native_retained_hyphen_wraps"));
            assertEquals(
                    "retained_hyphen_wraps_v1", candidate.getFirst().metadata().get("pdf_native_word_wrap_policy"));
            assertFalse(baseline.getFirst().metadata().containsKey("pdf_native_word_wrap_policy"));
        }
    }

    /**
     * Explicit source whitespace, numeric/punctuation boundaries and ordinary paragraphs retain old output.
     * @throws Exception if owned extraction fails
     */
    @Test
    void unsupportedNativeBoundariesPreserveExactHistoricalOutput() throws Exception {
        for (final var lines : List.of(List.of("Evidence for one- ", "way analysis."),
                     List.of("Evidence for one-", " way analysis."), List.of("Evidence for 2025-", "time analysis."),
                     List.of("Evidence for one-", "2026 analysis."), List.of("Evidence for one-", "(way) analysis."),
                     List.of("Evidence for ordinary", "paragraph analysis."))) {
            final var bytes = source(lines.getFirst(), lines.getLast());
            assertEquals(extract(bytes, PdfOptions.TextLayout.COLUMN_PARAGRAPHS, false),
                    extract(bytes, PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS, false));
        }
    }

    /**
     * Missing or multiply owned glyphs and mismatched line bounds prevent partial interpretation.
     * @throws Exception if source reconstruction fails unexpectedly
     */
    @Test
    void completeReconstructionRejectsMissingOverlappingAndRotatedOwnership() throws Exception {
        final var glyphs = glyphs();
        final var group = PdfGlyphLines.lines(glyphs);
        assertEquals(Set.of(0), new NativeGlyphPdfParagraphWraps(glyphs, new PdfTableWork()).boundaries(group));
        final var missing = new ArrayList<>(glyphs);
        missing.remove(0);
        assertEquals(Set.of(), new NativeGlyphPdfParagraphWraps(missing, new PdfTableWork()).boundaries(group));
        assertEquals(Set.of(), new NativeGlyphPdfParagraphWraps(glyphs, new PdfTableWork())
                                       .boundaries(List.of(group.getFirst(), group.getFirst(), group.getLast())));
        final var changed = new ArrayList<>(group);
        final var first = group.getFirst();
        changed.set(0, new PdfLayoutItem(first.type(), first.text(),
                               new PdfLayoutItem.Bounds(first.box().left() - 1, first.box().top(), first.box().right(),
                                       first.box().bottom()),
                               Map.of()));
        assertEquals(Set.of(), new NativeGlyphPdfParagraphWraps(glyphs, new PdfTableWork()).boundaries(changed));
        for (final int rotation : new int[] {0, 90}) {
            final var page = new PDPage(new PDRectangle(300, 200));
            page.setRotation(rotation);
            final var strategy = PdfParagraphWraps.select(PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS,
                    glyphs, new PdfPageGeometry(page), new PdfTableWork());
            assertEquals(rotation == 0 ? Set.of(0) : Set.of(), strategy.boundaries(group));
        }
        final var tagged =
                new PdfLayoutItem(first.type(), first.text(), first.box(), Map.of("pdf_source_paragraph_ordinal", 0));
        final var order = new GeometricPdfReadingOrder(PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS,
                new NativeGlyphPdfParagraphWraps(glyphs, new PdfTableWork()));
        assertEquals(List.of(tagged, group.getLast()), order.apply(List.of(tagged, group.getLast())));
    }

    /**
     * Native ownership probes consume the same page work counter and observe cooperative cancellation.
     * @throws Exception if owned grouping or budget setup fails
     */
    @Test
    void sharedPageLimitsAndCancellationRemainEnforced() throws Exception {
        final var glyphs = glyphs();
        final var group = PdfGlyphLines.lines(glyphs);
        final var crowded = new ArrayList<>(glyphs);
        for (int index = 0; index < 5000; index++) {
            crowded.add(new PdfPageText.Glyph("x", 40 + index % 100 * 2, 100 + index / 100 * 14, 1, 5, 2, 0));
        }
        final var indexedWork = new PdfTableWork();
        for (int index = 0; index < 1_999_000; index++) {
            indexedWork.step();
        }
        assertEquals(Set.of(0), new NativeGlyphPdfParagraphWraps(crowded, indexedWork).boundaries(group),
                "Unrelated source baselines must not exhaust the remaining paragraph work budget");
        final var work = new PdfTableWork();
        for (int index = 0; index < 2_000_000; index++) {
            work.step();
        }
        final var exhausted = new NativeGlyphPdfParagraphWraps(glyphs, work);
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> exhausted.boundaries(group)).code());
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class,
                    () -> new NativeGlyphPdfParagraphWraps(glyphs, new PdfTableWork()).boundaries(group));
        } finally {
            Thread.interrupted(); // Clear the owned interruption so other test cases retain their normal thread state.
        }
    }

    /**
     * Supplies independent per-character positions with a unique physical hyphen and next-line letter endpoint.
     * @return original two-line native positions
     */
    private static List<PdfPageText.Glyph> glyphs() {
        final var result = new ArrayList<PdfPageText.Glyph>();
        final var lines = List.of("Evidence for one-", "way analysis.");
        for (int row = 0; row < lines.size(); row++) {
            for (int index = 0; index < lines.get(row).length(); index++) {
                result.add(new PdfPageText.Glyph(
                        lines.get(row).substring(index, index + 1), 40 + index * 6, 40 + row * 14, 6, 10, 6, 0));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Extracts the same owned bytes with an explicit native layout through the selected public factory.
     * @param bytes complete owned PDF
     * @param layout native line/paragraph policy
     * @param taggedTables whether to enable independent source-table precedence
     * @return complete public output
     * @throws IOException if the public document source cannot be read
     */
    private static List<dev.docweave.core.Element> extract(
            final byte[] bytes, final PdfOptions.TextLayout layout, final boolean taggedTables) throws IOException {
        final var options = PdfOptions.defaults().withTextLayout(layout);
        final var partitioner =
                taggedTables ? PdfPartitioner.createWithTaggedTables(options) : PdfPartitioner.create(options);
        return DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                .partition(DocumentSource.bytes(bytes, "native-compound.pdf"))
                .elements();
    }

    /**
     * Authors two upright physical source lines without tags, OCR, reference-driven geometry or semantic edits.
     * @param first first native line
     * @param second second native line
     * @return complete owned source bytes
     * @throws Exception if the source cannot be encoded
     */
    private static byte[] source(final String first, final String second) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(300, 200));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                for (int index = 0; index < 2; index++) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(40, 160 - index * 14);
                    content.showText(index == 0 ? first : second);
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
