package dev.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

/** Bounded whole-run script grouping with original source characters and coordinates. */
final class PdfSmallGlyphRuns {
    /** Prevents utility construction. */
    private PdfSmallGlyphRuns() {
    }

    /**
     * Assigns a complete short run of adjacent smaller positions to one nearby body baseline.
     * Runs contain at most eight upright nonblank glyphs of consistent height and baseline;
     * touching/closely spaced advances connect them. Duplicate or overlapping positions reject
     * the run. A nearby body glyph must have median body height, a vertical shift greater than
     * two units and at most eighty percent of its height, and a small nonnegative horizontal gap.
     * Competing body baselines reject the entire run. No partial assignment or character repair
     * occurs. Blank positions keep their original grouping and every source bound is retained.
     * @param input complete original positions within one validated cell
     * @param work shared page work and cancellation counter
     * @return immutable original-coordinate lines and assigned glyph count
     * @throws IOException if shared work or interruption limits fail
     */
    static PdfParagraphInlineScripts.Result lines(final List<PdfPageText.Glyph> input, final PdfTableWork work)
            throws IOException {
        work.step();
        final var heights = new ArrayList<Float>();
        for (final var glyph : input) {
            work.step();
            if (glyph.direction() == 0 && glyph.height() > 0 && Float.isFinite(glyph.height())
                    && !glyph.text().isBlank()) {
                heights.add(glyph.height());
            }
        }
        if (heights.isEmpty()) {
            return new PdfParagraphInlineScripts.Result(List.copyOf(PdfGlyphLines.lines(input)), 0);
        }
        heights.sort(Float::compare);
        final float median = heights.get(heights.size() / 2);
        final var small = new ArrayList<PdfPageText.Glyph>();
        for (final var glyph : input) {
            work.step();
            if (glyph.direction() == 0 && glyph.height() > 0 && glyph.height() <= median * 0.75 && glyph.width() > 0
                    && !glyph.text().isBlank()) {
                small.add(glyph);
            }
        }
        small.sort(Comparator.comparingDouble(PdfPageText.Glyph::y).thenComparingDouble(PdfPageText.Glyph::x));
        final var assigned = new IdentityHashMap<PdfPageText.Glyph, Double>();
        final var run = new ArrayList<PdfPageText.Glyph>();
        for (final var glyph : small) {
            work.step();
            if (!run.isEmpty() && !connected(run, glyph)) {
                assign(run, input, median, assigned, work);
                run.clear();
            }
            run.add(glyph);
        }
        assign(run, input, median, assigned, work);
        return new PdfParagraphInlineScripts.Result(
                List.copyOf(PdfGlyphLines.lines(input, glyph -> assigned.getOrDefault(glyph, (double) glyph.y()))),
                assigned.size());
    }

    /**
     * Tests adjacency against the fixed first baseline and previous advance without bridging distant scripts.
     * @param run current original positions
     * @param glyph next original position
     * @return whether the position belongs to the same short script run
     */
    private static boolean connected(final List<PdfPageText.Glyph> run, final PdfPageText.Glyph glyph) {
        final var first = run.getFirst();
        final var previous = run.getLast();
        final double gap = glyph.x() - previous.x() - previous.width();
        return Math.abs(glyph.y() - first.y()) <= 0.5 && Math.abs(glyph.height() - first.height()) <= 0.5
                && gap <= Math.max(1, Math.min(glyph.space(), previous.space()) * 0.5);
    }

    /**
     * Assigns every original run member together only when one body baseline supports its complete envelope.
     * @param run complete small-position run
     * @param input complete cell positions
     * @param median median positive nonblank height
     * @param assigned original identities and comparison baselines
     * @param work shared page counter
     * @throws IOException if shared work or interruption limits fail
     */
    private static void assign(final List<PdfPageText.Glyph> run, final List<PdfPageText.Glyph> input,
            final float median, final IdentityHashMap<PdfPageText.Glyph, Double> assigned, final PdfTableWork work)
            throws IOException {
        work.step();
        if (run.isEmpty() || run.size() > 8) {
            return;
        }
        for (int index = 1; index < run.size(); index++) {
            work.step();
            if (run.get(index).x() < run.get(index - 1).x() + run.get(index - 1).width() - 0.5) {
                return;
            }
        }
        final var first = run.getFirst();
        final var last = run.getLast();
        double baseline = Double.NaN;
        for (final var body : input) {
            work.step();
            final double shift = Math.abs(body.y() - first.y());
            final double gap = Math.max(body.x() - last.x() - last.width(), first.x() - body.x() - body.width());
            if (body.direction() != 0 || body.text().isBlank() || body.width() <= 0 || body.height() < median * 0.9
                    || body.height() > median * 1.25 || shift <= 2 || shift > body.height() * 0.8) {
                continue;
            }
            if (gap < -0.5) {
                return;
            }
            if (gap > Math.max(2, body.space() * 1.5)) {
                continue;
            }
            if (Double.isNaN(baseline)) {
                baseline = body.y();
            } else if (Math.abs(body.y() - baseline) > 2) {
                return;
            }
        }
        if (!Double.isNaN(baseline)) {
            for (final var glyph : run) {
                work.step();
                assigned.put(glyph, baseline);
            }
        }
    }
}
