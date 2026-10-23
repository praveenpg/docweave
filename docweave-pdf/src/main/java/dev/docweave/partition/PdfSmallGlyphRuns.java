package dev.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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
        final var assigned = assignments(input, work, median -> first -> input);
        if (assigned == null) {
            return new PdfParagraphInlineScripts.Result(List.copyOf(PdfGlyphLines.lines(input)), 0);
        }
        return new PdfParagraphInlineScripts.Result(
                List.copyOf(PdfGlyphLines.lines(input, glyph -> assigned.getOrDefault(glyph, (double) glyph.y()))),
                assigned.size());
    }

    /**
     * Applies the same whole-run rule to all ordinary text of one page, comparing each run only with eligible body
     * positions in its own baseline band. Eligible bodies are upright, nonblank, have positive width and lie within
     * ninety to 125 percent of the median height; an accepted vertical shift is at most eighty percent of such a
     * height, so no position outside a band of 1.25 median heights can affect a run. Band members are visited in their
     * original input order, because the first supporting body fixes the comparison baseline. The result therefore
     * equals the whole-input comparison while page work grows with band occupancy rather than with the page.
     * @param input complete ordinary native positions of one page, in any order
     * @param work shared page work and cancellation counter
     * @return grouping baselines of assigned original positions, keyed by identity; empty when none is assigned
     * @throws IOException if shared work or interruption limits fail
     */
    static Map<PdfPageText.Glyph, Double> pageBaselines(final List<PdfPageText.Glyph> input, final PdfTableWork work)
            throws IOException {
        final var assigned = assignments(input, work, median -> banded(input, median, work));
        return assigned == null ? Map.of() : assigned;
    }

    /** Supplies the body positions compared with one small run. */
    @FunctionalInterface
    private interface Bodies {
        /**
         * Returns every body position that could support a run starting at the given position.
         * @param first first position of the run, in baseline then advance order
         * @return candidate body positions; possibly a superset of supporting positions
         */
        List<PdfPageText.Glyph> near(PdfPageText.Glyph first);
    }

    /** Builds the body supply for one median height. */
    @FunctionalInterface
    private interface BodySupply {
        /**
         * Creates the supply once the median height is known.
         * @param median median positive nonblank upright height
         * @return the body supply
         * @throws IOException if shared work or interruption limits fail
         */
        Bodies create(float median) throws IOException;
    }

    /**
     * Finds every small run and records the body baseline that supports it, or returns null without heights.
     * @param input complete original positions
     * @param work shared page work and cancellation counter
     * @param supply body positions compared with each run
     * @return assigned grouping baselines by identity, or null when no upright nonblank height exists
     * @throws IOException if shared work or interruption limits fail
     */
    private static IdentityHashMap<PdfPageText.Glyph, Double> assignments(
            final List<PdfPageText.Glyph> input, final PdfTableWork work, final BodySupply supply) throws IOException {
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
            return null;
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
        final var bodies = supply.create(median);
        final var assigned = new IdentityHashMap<PdfPageText.Glyph, Double>();
        final var run = new ArrayList<PdfPageText.Glyph>();
        for (final var glyph : small) {
            work.step();
            if (!run.isEmpty() && !connected(run, glyph)) {
                assign(run, bodies, median, assigned, work);
                run.clear();
            }
            run.add(glyph);
        }
        assign(run, bodies, median, assigned, work);
        return assigned;
    }

    /**
     * Indexes eligible body positions by baseline so each run sees only its own band, in original input order.
     * @param input complete original positions
     * @param median median positive nonblank upright height
     * @param work shared page work and cancellation counter
     * @return a band supply over eligible bodies
     * @throws IOException if shared work or interruption limits fail
     */
    private static Bodies banded(final List<PdfPageText.Glyph> input, final float median, final PdfTableWork work)
            throws IOException {
        final var eligible = new ArrayList<PdfPageText.Glyph>();
        for (final var body : input) {
            work.step();
            if (body.direction() == 0 && !body.text().isBlank() && body.width() > 0 && body.height() >= median * 0.9
                    && body.height() <= median * 1.25) {
                eligible.add(body);
            }
        }
        // Positions into the input-ordered eligible list, sorted by baseline for the band search.
        final int[] byBaseline = IntStream.range(0, eligible.size())
                                         .boxed()
                                         .sorted(Comparator.comparingDouble(index -> eligible.get(index).y()))
                                         .mapToInt(Integer::intValue)
                                         .toArray();
        final var baselines = new double[byBaseline.length];
        for (int index = 0; index < baselines.length; index++) {
            work.step();
            baselines[index] = eligible.get(byBaseline[index]).y();
        }
        final double reach = median * 1.25;
        return first -> {
            final int[] band = Arrays.copyOfRange(byBaseline, lowerBound(baselines, first.y() - reach),
                    lowerBound(baselines, Math.nextUp(first.y() + reach)));
            Arrays.sort(band);
            final var result = new ArrayList<PdfPageText.Glyph>(band.length);
            for (final int index : band) {
                result.add(eligible.get(index));
            }
            return result;
        };
    }

    /**
     * Returns the first index whose baseline is at least the given value.
     * @param baselines ascending baselines
     * @param value searched value
     * @return insertion index
     */
    private static int lowerBound(final double[] baselines, final double value) {
        int low = 0;
        int high = baselines.length;
        while (low < high) {
            final int middle = (low + high) >>> 1;
            if (baselines[middle] < value) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
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
     * @param bodies body positions compared with the run
     * @param median median positive nonblank height
     * @param assigned original identities and comparison baselines
     * @param work shared page counter
     * @throws IOException if shared work or interruption limits fail
     */
    private static void assign(final List<PdfPageText.Glyph> run, final Bodies bodies, final float median,
            final IdentityHashMap<PdfPageText.Glyph, Double> assigned, final PdfTableWork work) throws IOException {
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
        for (final var body : bodies.near(first)) {
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
