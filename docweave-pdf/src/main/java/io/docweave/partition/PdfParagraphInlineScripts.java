package io.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Bounded optional grouping baselines for smaller glyphs within one validated source paragraph. */
final class PdfParagraphInlineScripts {
    /** Prevents utility construction. */
    private PdfParagraphInlineScripts() {
    }

    /**
     * Original-coordinate lines and the number of glyphs assigned to a nearby body baseline.
     * @param lines grouped lines retaining every original position and text
     * @param assignedGlyphs smaller positions with one unambiguous body baseline
     */
    record Result(List<PdfLayoutItem> lines, int assignedGlyphs) {}

    /**
     * Uses median positive nonblank glyph height to distinguish body positions from smaller scripts.
     * A candidate is at most three quarters of that height, shifted by more than two units and
     * at most eighty percent of its adjacent body glyph's height. Body positions are within
     * ninety to125 percent of the median. Horizontal separation is bounded by body space width;
     * competing body baselines reject assignment. This is geometric interpretation, not script semantics.
     * Source identities, text and bounding coordinates are never changed or synthesized.
     * @param glyphs exact complete source paragraph positions after ownership validation
     * @param work shared page work and cancellation counter
     * @return immutable original-coordinate runs and assignment count
     * @throws IOException if shared work or cancellation bounds fail
     */
    static Result lines(final List<PdfPageText.Glyph> glyphs, final PdfTableWork work) throws IOException {
        final var heights = new ArrayList<Float>();
        for (final var glyph : glyphs) {
            work.step();
            if (glyph.height() > 0 && Float.isFinite(glyph.height()) && !glyph.text().isBlank()) {
                heights.add(glyph.height());
            }
        }
        if (heights.isEmpty()) {
            return new Result(List.copyOf(PdfGlyphLines.lines(glyphs)), 0);
        }
        heights.sort(Float::compare);
        final float median = heights.get(heights.size() / 2);
        final var assigned = new IdentityHashMap<PdfPageText.Glyph, Double>();
        for (final var script : glyphs) {
            work.step();
            if (script.height() <= 0 || script.height() > median * 0.75 || script.width() <= 0) {
                continue;
            }
            double baseline = Double.NaN;
            boolean ambiguous = false;
            for (final var body : glyphs) {
                work.step();
                final double shift = Math.abs(body.y() - script.y());
                final double horizontalGap =
                        Math.max(body.x() - script.x() - script.width(), script.x() - body.x() - body.width());
                if (body.height() < median * 0.9 || body.height() > median * 1.25 || body.width() <= 0
                        || body.text().isBlank() || shift <= 2 || shift > body.height() * 0.8 || horizontalGap < -0.5
                        || horizontalGap > Math.max(2, body.space() * 1.5)) {
                    continue;
                }
                if (Double.isNaN(baseline)) {
                    baseline = body.y();
                } else if (Math.abs(baseline - body.y()) > 2) {
                    ambiguous = true;
                }
            }
            if (!ambiguous && !Double.isNaN(baseline)) {
                assigned.put(script, baseline);
            }
        }
        return new Result(
                List.copyOf(PdfGlyphLines.lines(glyphs, glyph -> assigned.getOrDefault(glyph, (double) glyph.y()))),
                assigned.size());
    }
}
