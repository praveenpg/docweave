package io.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;

/** Caller-selected projection of one shifted ASCII blank over two adjacent overlapping digit advances. */
final class PdfOverlaidDigitPadding {
    /** Prevents utility construction. */
    private PdfOverlaidDigitPadding() {
    }

    /**
     * Projection without changing original source positions or their caller-owned membership.
     * @param glyphs immutable retained original identities in original input order
     * @param omittedPaddingGlyphs count of single-space positions omitted from text projection only
     */
    record Projection(List<PdfPageText.Glyph> glyphs, int omittedPaddingGlyphs) {}

    /**
     * One pair of original neighboring nonblank positions; duplicate endpoints are excluded before use.
     * @param left original left ASCII digit
     * @param right original right ASCII digit
     */
    private record Pair(PdfPageText.Glyph left, PdfPageText.Glyph right) {}

    /**
     * Requires one uniquely overlaid single ASCII space and two unique neighboring ASCII digits.
     * Digit baselines/heights agree within half a unit; advance boxes overlap by more than0.01
     * and at most one quarter of the smaller width. The blank overlaps both advances, stays
     * inside their horizontal union, matches their heights and is shifted more than one and
     * at most two units vertically. Real gaps, ordinary baseline spaces, NBSP, punctuation,
     * duplicate endpoints/padding and competing pairs retain original projection.
     * This geometric interpretation does not repair arbitrary numeric values or alter source ownership.
     * @param input original positions belonging to exactly one native numeric cell
     * @param work shared page work and cancellation counter
     * @return immutable original identities and an explicit omitted-padding count
     * @throws IOException if work or cancellation limits fail
     */
    static Projection project(final List<PdfPageText.Glyph> input, final PdfTableWork work) throws IOException {
        work.step();
        final var ink = new ArrayList<PdfPageText.Glyph>();
        for (final var glyph : input) {
            work.step();
            if (!glyph.text().isBlank()) {
                ink.add(glyph);
            }
        }
        ink.sort(Comparator.comparingDouble(PdfPageText.Glyph::x).thenComparingDouble(PdfPageText.Glyph::y));
        final var matches = new IdentityHashMap<PdfPageText.Glyph, Pair>();
        final var counts = new HashMap<Pair, Integer>();
        for (final var blank : input) {
            work.step();
            if (!blank.text().equals(" ") || blank.width() <= 0 || blank.height() <= 0 || blank.direction() != 0) {
                continue;
            }
            Pair selected = null;
            int candidates = 0;
            for (int index = 0; index + 1 < ink.size(); index++) {
                work.step();
                final var left = ink.get(index);
                final var right = ink.get(index + 1);
                if (index > 0 && sameEndpoint(ink.get(index - 1), left)
                        || index + 2 < ink.size() && sameEndpoint(right, ink.get(index + 2))) {
                    continue;
                }
                if (eligible(blank, left, right)) {
                    selected = new Pair(left, right);
                    candidates++;
                }
            }
            if (candidates == 1) {
                matches.put(blank, selected);
                counts.merge(selected, 1, Integer::sum);
            }
        }
        final var result = new ArrayList<PdfPageText.Glyph>();
        int omitted = 0;
        for (final var glyph : input) {
            work.step();
            final var pair = matches.get(glyph);
            if (pair != null && counts.get(pair) == 1) {
                omitted++;
            } else {
                result.add(glyph);
            }
        }
        return new Projection(List.copyOf(result), omitted);
    }

    /**
     * Rejects duplicate or overprinted nonblank endpoint identities at one source position.
     * @param first original nonblank position
     * @param second adjacent original nonblank position
     * @return whether endpoints are geometrically indistinguishable
     */
    private static boolean sameEndpoint(final PdfPageText.Glyph first, final PdfPageText.Glyph second) {
        return Math.abs(first.x() - second.x()) <= 0.01 && Math.abs(first.y() - second.y()) <= 0.5;
    }

    /**
     * Evaluates source geometry without modifying characters, inferring digits or parsing a reference value.
     * @param blank candidate original single ASCII space
     * @param left neighboring original nonblank advance
     * @param right neighboring original nonblank advance
     * @return whether the narrow overlaid-padding interpretation is supported
     */
    private static boolean eligible(
            final PdfPageText.Glyph blank, final PdfPageText.Glyph left, final PdfPageText.Glyph right) {
        final double overlap = left.x() + left.width() - right.x();
        final double shift = Math.abs(blank.y() - (left.y() + right.y()) / 2);
        return digit(left) && digit(right) && Math.abs(left.y() - right.y()) <= 0.5
                && Math.abs(left.height() - right.height()) <= 0.5 && Math.abs(blank.height() - left.height()) <= 0.5
                && Math.abs(blank.height() - right.height()) <= 0.5 && overlap > 0.01
                && overlap <= Math.min(left.width(), right.width()) / 4 && shift > 1 && shift <= 2
                && blank.x() >= left.x() && blank.x() + blank.width() <= right.x() + right.width()
                && blank.x() < left.x() + left.width() && blank.x() + blank.width() > right.x();
    }

    /**
     * Restricts endpoint interpretation to positive-size upright single ASCII digits.
     * @param glyph original native position
     * @return whether a digit advance is eligible
     */
    private static boolean digit(final PdfPageText.Glyph glyph) {
        return glyph.direction() == 0 && glyph.width() > 0 && glyph.height() > 0 && glyph.text().length() == 1
                && glyph.text().charAt(0) >= '0' && glyph.text().charAt(0) <= '9';
    }
}
