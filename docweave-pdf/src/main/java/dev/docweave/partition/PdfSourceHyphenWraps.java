package dev.docweave.partition;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Physical-hyphen boundary checks within a source-owned or completely reconstructed native paragraph. */
final class PdfSourceHyphenWraps {
    /** Native endpoint/baseline identity tolerance, matching source discretionary-hyphen checks. */
    private static final double EPSILON = 0.01;

    /** Prevents construction of the page-confined utility. */
    private PdfSourceHyphenWraps() {
    }

    /**
     * Finds letter-hyphen/letter line boundaries without explicit adjacent native whitespace.
     * The physical hyphen must be one positive-width glyph uniquely ending its original line;
     * the next line must uniquely start with a letter glyph. Same-baseline runs, large vertical
     * gaps, punctuation, numeric ranges and ambiguous endpoints retain the generated separator.
     * This opt-in interpretation assumes compound-word continuation; source geometry cannot
     * establish word semantics. Every native glyph and source coordinate is retained.
     * @param members exact already-owned native source identities
     * @param lines complete upright ordered paragraph lines
     * @param work shared page work/cancellation counter
     * @return immutable zero-based line indices whose following generated separator may be omitted
     * @throws IOException if work or cancellation limits fail
     */
    static Set<Integer> boundaries(final List<PdfPageText.Glyph> members, final List<PdfLayoutItem> lines,
            final PdfTableWork work) throws IOException {
        final var boundaries = new HashSet<Integer>();
        for (int index = 0; index + 1 < lines.size(); index++) {
            work.step();
            final var line = lines.get(index);
            final var next = lines.get(index + 1);
            final String value = line.text();
            final double gap = next.box().bottom() - line.box().bottom();
            if (value.length() < 2 || !value.endsWith("-")
                    || !Character.isLetter(value.codePointBefore(value.length() - 1)) || next.text().isEmpty()
                    || !Character.isLetter(next.text().codePointAt(0)) || gap <= 2
                    || gap > Math.max(24, (line.box().bottom() - line.box().top()) * 3)) {
                continue;
            }
            int terminals = 0;
            int starts = 0;
            boolean explicitSpace = false;
            for (final var glyph : members) {
                work.step();
                final boolean currentBaseline = Math.abs(glyph.y() - line.box().bottom()) <= EPSILON;
                final boolean nextBaseline = Math.abs(glyph.y() - next.box().bottom()) <= EPSILON;
                if (currentBaseline && glyph.width() > 0 && glyph.text().equals("-")
                        && Math.abs(glyph.x() + glyph.width() - line.box().right()) <= EPSILON) {
                    terminals++;
                }
                if (nextBaseline && glyph.width() > 0 && !glyph.text().isEmpty()
                        && Character.isLetter(glyph.text().codePointAt(0))
                        && Math.abs(glyph.x() - next.box().left()) <= EPSILON) {
                    starts++;
                }
                if ((currentBaseline && glyph.x() + glyph.width() >= line.box().right() - EPSILON
                            || nextBaseline && glyph.x() <= next.box().left() + EPSILON)
                        && glyph.text().codePoints().anyMatch(
                                point -> Character.isWhitespace(point) || Character.isSpaceChar(point))) {
                    explicitSpace = true;
                }
            }
            if (terminals == 1 && starts == 1 && !explicitSpace) {
                boundaries.add(index);
            }
        }
        return Set.copyOf(boundaries);
    }
}
