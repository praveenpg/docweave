package io.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Reconstructs complete native line ownership before applying the existing physical-hyphen boundary predicate. */
final class NativeGlyphPdfParagraphWraps implements PdfParagraphWraps {
    /** Same endpoint tolerance as the existing source-owned boundary checks. */
    private static final double EPSILON = 0.01;
    /** Original immutable page positions; no reference text, dictionary or external recognizer is consulted. */
    private final List<PdfPageText.Glyph> glyphs;
    /** Shared page-local geometry and cooperative cancellation bound. */
    private final PdfTableWork work;

    /**
     * Captures call-confined source state; construction performs no grouping or character interpretation.
     * @param glyphs original native positions
     * @param work unchanged page work counter
     */
    NativeGlyphPdfParagraphWraps(final List<PdfPageText.Glyph> glyphs, final PdfTableWork work) {
        this.glyphs = glyphs.stream()
                              .filter(glyph -> glyph.direction() == 0)
                              .sorted(Comparator.comparingDouble(PdfPageText.Glyph::y))
                              .toList();
        this.work = work;
    }

    /**
     * Requires uniquely bounded source ownership and exact text/bounds reconstruction of every grouped line.
     * Incomplete, overlapping, rotated or mismatched groups retain all generated separators.
     * Explicit source edge whitespace, ambiguous endpoints and numeric/punctuation boundaries remain rejected
     * by the shared source-hyphen predicate. No physical glyph or hyphen is removed.
     * @param group complete original lines in one already selected native paragraph
     * @return immutable omitted-separator indices, or empty for whole interpretation fallback
     * @throws IOException if shared work or cancellation limits prevent verification
     */
    @Override
    public Set<Integer> boundaries(final List<PdfLayoutItem> group) throws IOException {
        work.step();
        if (group.size() < 2
                || group.subList(0, group.size() - 1).stream().noneMatch(line -> line.text().endsWith("-"))) {
            return Set.of();
        }
        final var members = new ArrayList<PdfPageText.Glyph>();
        final Set<PdfPageText.Glyph> claimed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final var line : group) {
            work.step();
            final var box = line.box();
            for (int index = lowerBound(box.top() - EPSILON); index < glyphs.size(); index++) {
                work.step();
                final var glyph = glyphs.get(index);
                if (glyph.y() > box.bottom() + EPSILON) {
                    break;
                }
                if (glyph.x() >= box.left() - EPSILON && glyph.x() + glyph.width() <= box.right() + EPSILON
                        && glyph.y() <= box.bottom() + EPSILON && glyph.y() - glyph.height() >= box.top() - EPSILON) {
                    if (!claimed.add(glyph)) {
                        return Set.of();
                    }
                    members.add(glyph);
                }
            }
        }
        final var reconstructed = PdfGlyphLines.lines(members);
        if (reconstructed.size() != group.size()) {
            return Set.of();
        }
        for (int index = 0; index < group.size(); index++) {
            work.step();
            if (!group.get(index).text().equals(reconstructed.get(index).text())
                    || !group.get(index).box().equals(reconstructed.get(index).box())) {
                return Set.of();
            }
        }
        return PdfSourceHyphenWraps.boundaries(members, group, work);
    }

    /**
     * Finds the first candidate baseline without scanning unrelated page glyphs for each paragraph line.
     * @param y inclusive lower native baseline bound
     * @return stable first candidate index, or the glyph count
     * @throws IOException if shared page work or cancellation limits prevent lookup
     */
    private int lowerBound(final double y) throws IOException {
        int low = 0;
        int high = glyphs.size();
        while (low < high) {
            work.step();
            final int middle = low + (high - low) / 2;
            if (glyphs.get(middle).y() < y) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
