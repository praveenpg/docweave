package io.docweave.partition;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.List;

/** Conservative source-supported completion of a clipped top header line. */
final class PdfShadedHeaderBounds {
    /** Prevents utility construction. */
    private PdfShadedHeaderBounds() {
    }

    /**
     * Completes a line already crossing the initial top boundary in every painted header span.
     * At least two disjoint source spans must support the same upright nonblank baseline.
     * Each participating glyph fits one span, and the extension is bounded by half the
     * independently painted header-level pitch, capped at twelve source units. Unrelated
     * visible ink in the newly claimed strip, competing baselines and incomplete coverage
     * retain the initial boundary. Original glyph coordinates and characters never change.
     * @param initial initial inferred top coordinate
     * @param maximumExtension bounded source-supported upward extension
     * @param left original parent left edge
     * @param right original parent right edge
     * @param spans original disjoint first-level painted header intervals
     * @param glyphs original unowned page positions
     * @param work shared page work and cancellation counter
     * @return initial boundary or top of the uniquely supported clipped line
     * @throws IOException if work or interruption limits fail
     */
    static float top(final float initial, final float maximumExtension, final float left, final float right,
            final List<PdfPageGraphics.Segment> spans, final List<PdfPageText.Glyph> glyphs, final PdfTableWork work)
            throws IOException {
        work.step();
        if (spans.size() < 2 || maximumExtension <= 0) {
            return initial;
        }
        final boolean[] covered = new boolean[spans.size()];
        final var matched = new IdentityHashMap<PdfPageText.Glyph, Boolean>();
        float top = initial;
        float baseline = Float.NaN;
        for (final var glyph : glyphs) {
            work.step();
            if (glyph.text().isBlank() || glyph.y() <= initial || glyph.y() - glyph.height() >= initial
                    || glyph.x() + glyph.width() <= left || glyph.x() >= right) {
                continue;
            }
            if (glyph.direction() != 0 || glyph.width() <= 0 || glyph.height() <= 0
                    || glyph.y() - glyph.height() < Math.max(0, initial - maximumExtension)) {
                return initial;
            }
            int selected = -1;
            for (int index = 0; index < spans.size(); index++) {
                work.step();
                final var span = spans.get(index);
                if (glyph.x() >= span.x1() && glyph.x() + glyph.width() <= span.x2()) {
                    if (selected >= 0) {
                        return initial;
                    }
                    selected = index;
                }
            }
            if (selected < 0 || !Float.isNaN(baseline) && Math.abs(glyph.y() - baseline) > 0.5) {
                return initial;
            }
            baseline = Float.isNaN(baseline) ? glyph.y() : baseline;
            covered[selected] = true;
            matched.put(glyph, Boolean.TRUE);
            top = Math.min(top, glyph.y() - glyph.height());
        }
        for (final boolean present : covered) {
            work.step();
            if (!present) {
                return initial;
            }
        }
        for (final var glyph : glyphs) {
            work.step();
            if (!glyph.text().isBlank() && glyph.x() < right && glyph.x() + glyph.width() > left && glyph.y() > top
                    && glyph.y() - glyph.height() < initial && !matched.containsKey(glyph)) {
                return initial;
            }
        }
        return top;
    }
}
