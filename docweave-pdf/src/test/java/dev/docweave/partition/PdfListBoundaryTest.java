package dev.docweave.partition;

import dev.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Native paragraph boundaries retain source list markers without normalizing source characters. */
class PdfListBoundaryTest {
    /** Creates independent source-line controls. */
    PdfListBoundaryTest() {
    }

    /**
     * Unicode whitespace after an existing supported list marker prevents joins on either side.
     * @throws Exception if native grouping fails
     */
    @Test
    void unicodeListSeparatorsRetainSourceLinesAndBounds() throws Exception {
        for (final var layout : List.of(PdfOptions.TextLayout.PARAGRAPHS, PdfOptions.TextLayout.COLUMN_PARAGRAPHS,
                     PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS,
                     PdfOptions.TextLayout.ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS)) {
            for (final var marker : List.of("-", "*", "•", "1.", "12)")) {
                for (final var separator : List.of(" ", "\t", "\u00a0", "\u2007", "\u202f", "\u2009")) {
                    final var source = List.of(line("Preceding ordinary prose", 20),
                            line(marker + separator + "Separate source item", 38),
                            line("Following ordinary prose", 56));
                    assertEquals(source, new GeometricPdfReadingOrder(layout).apply(source),
                            () -> "List boundary for " + layout + "/" + marker + "/" + separator.codePointAt(0));
                }
            }
        }
    }

    /**
     * Whitespace inside ordinary prose does not turn a line into a list or change its source characters.
     * @throws Exception if native grouping fails
     */
    @Test
    void ordinaryUnicodeProseStillJoinsVerbatim() throws Exception {
        final var source = List.of(line("Ordinary\u00a0source text", 20), line("continues\u202fverbatim", 38));
        final var output = new GeometricPdfReadingOrder(PdfOptions.TextLayout.PARAGRAPHS).apply(source);
        assertEquals(1, output.size());
        assertEquals("Ordinary\u00a0source text continues\u202fverbatim", output.getFirst().text());
        assertEquals(new PdfLayoutItem.Bounds(40, 20, 240, 50), output.getFirst().box());
        assertEquals(Map.of("pdf_text_layout", "paragraphs_v1", "pdf_line_count", 2), output.getFirst().metadata());
    }

    /**
     * Zero-width formatting and unsupported marker syntax retain the existing plain-prose behavior.
     * @throws Exception if native grouping fails
     */
    @Test
    void nonWhitespaceAndUnsupportedMarkersDoNotGainListSemantics() throws Exception {
        for (final var text : List.of("-\u200btext", "1.text", "A. source", "١. source", "word\u00a0text")) {
            final var source = List.of(line(text, 20), line("continues here", 38));
            final var output = new GeometricPdfReadingOrder(PdfOptions.TextLayout.PARAGRAPHS).apply(source);
            assertEquals(List.of(text + " continues here"), output.stream().map(PdfLayoutItem::text).toList());
        }
    }

    /**
     * Supplies equal-font, aligned geometry that would otherwise permit a normal soft-wrap join.
     * @param text original native source string
     * @param top displayed source-line top
     * @return immutable native line without semantic source tags
     */
    private static PdfLayoutItem line(final String text, final float top) {
        return new PdfLayoutItem(
                ElementType.UncategorizedText, text, new PdfLayoutItem.Bounds(40, top, 240, top + 12), Map.of());
    }
}
