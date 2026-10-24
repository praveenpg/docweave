package io.docweave.partition;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Call-confined native paragraph separator interpretation; source glyphs and physical hyphens remain unchanged. */
interface PdfParagraphWraps {
    /**
     * Selects explicit native interpretation without changing historical paragraph strategies.
     * @param layout selected native layout
     * @param glyphs complete original native positions available to the page
     * @param geometry original displayed frame
     * @param work shared native geometry/cancellation budget
     * @return source-evidenced strategy or unchanged separator preservation
     */
    static PdfParagraphWraps select(final PdfOptions.TextLayout layout, final List<PdfPageText.Glyph> glyphs,
            final PdfPageGeometry geometry, final PdfTableWork work) {
        return layout.compoundWraps() && geometry.textRotation(0) == 0 ? new NativeGlyphPdfParagraphWraps(glyphs, work)
                                                                       : group -> Set.of();
    }

    /**
     * Identifies only generated inter-line separators that may be omitted inside one complete native group.
     * @param group ordered original source-line units, never joined across a page or structural barrier
     * @return immutable indices of lines whose following generated separator may be omitted
     * @throws IOException if page work or cancellation prevents complete verification
     */
    Set<Integer> boundaries(List<PdfLayoutItem> group) throws IOException;
}
