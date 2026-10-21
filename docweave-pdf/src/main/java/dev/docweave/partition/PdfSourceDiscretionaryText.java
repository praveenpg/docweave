package dev.docweave.partition;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Source-evidenced word joining confined to already-owned native cell or paragraph runs. */
final class PdfSourceDiscretionaryText {
    /** Prevents construction of the document-confined text utility. */
    private PdfSourceDiscretionaryText() {
    }

    /**
     * An explicit text result without changing source glyph ownership or geometry.
     * @param text complete source-owned region text
     * @param joins number of proven discretionary line breaks removed
     * @param retainedHyphenWraps generated separators omitted while retaining physical hyphens
     */
    record Result(String text, int joins, int retainedHyphenWraps) {
        /**
         * Preserves the existing table-only result without physical-hyphen interpretation.
         * @param text complete native cell text
         * @param joins proven discretionary removals
         */
        Result(final String text, final int joins) {
            this(text, joins, 0);
        }
    }

    /**
     * Removes only a single-glyph, source-declared soft hyphen at a proven word-wrap boundary.
     * Caller validates original region text budgets and upright direction before invoking. Each hint must
     * retain exactly one native '-' position; its right edge and baseline must uniquely end one native
     * run. Adjacent runs must be on nearby distinct baselines, with letters on both sides of the break.
     * No dictionary, alternate full-region string, OCR output or publisher reference enters this decision.
     * @param hints unambiguous original source discretionary references
     * @param source validated page identity mapping
     * @param lines original ordered native region runs
     * @param original existing complete space-separated text
     * @param work shared page work/cancellation counter
     * @return complete text and applied source-hint count
     * @throws IOException if work or cancellation limits fail
     */
    static Result join(final Set<PdfTaggedStructure.Content> hints, final PdfPageText source,
            final List<PdfLayoutItem> lines, final String original, final PdfTableWork work) throws IOException {
        return join(hints, source, lines, original, work, Set.of());
    }

    /**
     * Adds explicitly validated physical-hyphen wrap spacing to source-proven discretionary joining.
     * Proven soft-hyphen removal takes precedence; physical-hyphen boundaries never delete a glyph.
     * @param hints original source discretionary references
     * @param source validated source identities
     * @param lines complete original upright paragraph lines
     * @param original complete prejoin text already charged to original budgets
     * @param work shared page work/cancellation counter
     * @param retainedHyphens validated line indices from PdfSourceHyphenWraps
     * @return complete text and separately reported soft-removal/retained-hyphen counts
     * @throws IOException if work or cancellation limits fail
     */
    static Result join(final Set<PdfTaggedStructure.Content> hints, final PdfPageText source,
            final List<PdfLayoutItem> lines, final String original, final PdfTableWork work,
            final Set<Integer> retainedHyphens) throws IOException {
        if (hints.isEmpty() && retainedHyphens.isEmpty() || lines.size() < 2) {
            return new Result(original, 0, 0);
        }
        final var boundaries = new int[lines.size() - 1];
        for (final var hint : hints) {
            work.step();
            final var glyphs = source.marked(hint.id());
            if (glyphs.size() != 1 || !"-".equals(glyphs.getFirst().text())) {
                continue;
            }
            final var glyph = glyphs.getFirst();
            int match = -1;
            int matches = 0;
            for (int i = 0; i < lines.size(); i++) {
                work.step();
                final var line = lines.get(i);
                if (Math.abs(glyph.y() - line.box().bottom()) <= 0.01
                        && Math.abs(glyph.x() + glyph.width() - line.box().right()) <= 0.01
                        && glyph.x() >= line.box().left() && glyph.width() > 0) {
                    match = i;
                    matches++;
                }
            }
            if (matches == 1 && match < boundaries.length) {
                boundaries[match]++;
            }
        }
        return compose(lines, original, work, retainedHyphens, boundaries);
    }

    /**
     * Applies validated physical-hyphen separators without requiring tagged source identity mappings.
     * @param lines complete already-owned native lines
     * @param original complete prejoin text already charged to source budgets
     * @param work shared native geometry/cancellation bound
     * @param retainedHyphens validated inter-line indices; no physical glyph is removed
     * @return complete native text and retained-hyphen count with no discretionary removals
     * @throws IOException if page work or cancellation prevents composition
     */
    static Result retainHyphens(final List<PdfLayoutItem> lines, final String original, final PdfTableWork work,
            final Set<Integer> retainedHyphens) throws IOException {
        if (retainedHyphens.isEmpty() || lines.size() < 2) {
            return new Result(original, 0, 0);
        }
        return compose(lines, original, work, retainedHyphens, new int[lines.size() - 1]);
    }

    /**
     * Combines independently validated soft and physical boundaries, with soft removal taking precedence.
     * @param lines unchanged complete original lines
     * @param original original joined text for whole fallback
     * @param work shared page work/cancellation counter
     * @param retainedHyphens validated physical-hyphen boundary indices
     * @param boundaries source hint multiplicity at every original line boundary
     * @return complete interpreted text and separate soft/physical counts
     * @throws IOException if work or cancellation prevents composition
     */
    private static Result compose(final List<PdfLayoutItem> lines, final String original, final PdfTableWork work,
            final Set<Integer> retainedHyphens, final int[] boundaries) throws IOException {
        final var result = new StringBuilder();
        int joins = 0;
        int hardWraps = 0;
        for (int i = 0; i < lines.size(); i++) {
            work.step();
            final var line = lines.get(i);
            final String text = line.text();
            boolean join = false;
            if (i < boundaries.length && boundaries[i] == 1 && text.length() > 1 && text.endsWith("-")
                    && Character.isLetter(text.codePointBefore(text.length() - 1))) {
                final var next = lines.get(i + 1);
                final double gap = next.box().bottom() - line.box().bottom();
                join = !next.text().isEmpty() && Character.isLetter(next.text().codePointAt(0)) && gap > 2
                       && gap <= Math.max(24, (line.box().bottom() - line.box().top()) * 3);
            }
            result.append(join ? text.substring(0, text.length() - 1) : text);
            if (join) {
                joins++;
            } else if (retainedHyphens.contains(i)) {
                hardWraps++;
            } else if (i + 1 < lines.size()) {
                result.append(' ');
            }
        }
        return new Result(joins == 0 && hardWraps == 0 ? original : result.toString(), joins, hardWraps);
    }
}
