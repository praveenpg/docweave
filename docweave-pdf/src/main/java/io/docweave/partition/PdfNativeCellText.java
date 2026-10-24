package io.docweave.partition;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Narrow source-line-aware native cell joining; never repairs topology or arbitrary hyphenated words. */
final class PdfNativeCellText {
    /** Prevents construction of the document-confined text utility. */
    private PdfNativeCellText() {
    }

    /**
     * Interprets physical hyphen boundaries only in completely owned cell lines sharing the upright source frame.
     * The caller charges the historical unmodified text before requesting this optional interpretation.
     * @param members exact original glyphs already assigned to this cell
     * @param geometry original displayed frame
     * @param lines complete original cell lines in native coordinates
     * @param work shared native geometry/cancellation counter
     * @return immutable retained-hyphen boundary indices or empty for unsupported frames
     * @throws java.io.IOException if work or cancellation limits prevent source checks
     */
    static Set<Integer> compoundBoundaries(final List<PdfPageText.Glyph> members, final PdfPageGeometry geometry,
            final List<PdfLayoutItem> lines, final PdfTableWork work) throws java.io.IOException {
        work.step();
        if (geometry.textRotation(0) != 0 || members.stream().anyMatch(glyph -> glyph.direction() != 0)) {
            return Set.of();
        }
        return PdfSourceHyphenWraps.boundaries(members, lines, work);
    }

    /**
     * Applies the existing bounded whole-run grouping only when source and displayed upright frames coincide.
     * The caller checks the historical joined text against cell limits before asking for this interpretation.
     * Rotated/mixed directions retain their original grouping; no glyph coordinates or characters change.
     * @param members exact original cell-owned glyphs
     * @param geometry original displayed page frame
     * @param original historical grouped source lines
     * @param work shared geometry/cancellation budget
     * @return original or interpreted lines with the number of assigned small glyphs
     * @throws java.io.IOException if work or interruption limits prevent grouping
     */
    static PdfParagraphInlineScripts.Result inlineScriptRuns(final List<PdfPageText.Glyph> members,
            final PdfPageGeometry geometry, final List<PdfLayoutItem> original, final PdfTableWork work)
            throws java.io.IOException {
        work.step();
        if (geometry.textRotation(0) != 0 || members.stream().anyMatch(glyph -> glyph.direction() != 0)) {
            return new PdfParagraphInlineScripts.Result(List.copyOf(original), 0);
        }
        return PdfSmallGlyphRuns.lines(members, work);
    }

    /**
     * Retains distinct upright source baselines while keeping separated same-line runs together.
     * Uses the existing two-point native baseline tolerance, anchored at the first run of each line.
     * Mixed/rotated runs keep historical joining rather than inventing a reading direction.
     * @param lines ordered native runs with original bounds
     * @param original historical space-separated text, already checked against cell limits
     * @return original text or LF-separated native lines without geometry changes
     * @throws java.io.IOException if the caller interrupts cell processing
     */
    static String preserveLineBreaks(final List<PdfLayoutItem> lines, final String original)
            throws java.io.IOException {
        if (lines.size() < 2
                || lines.stream().anyMatch(
                        line -> ((Number) line.metadata().getOrDefault("pdf_text_rotation", 0)).intValue() != 0)) {
            return original;
        }
        final var result = new StringBuilder(lines.getFirst().text());
        float baseline = lines.getFirst().box().bottom();
        for (int index = 1; index < lines.size(); index++) {
            ElementCollector.checkInterrupted();
            final var line = lines.get(index);
            if (Math.abs(line.box().bottom() - baseline) > 2) {
                result.append('\n');
                baseline = line.box().bottom();
            } else {
                result.append(' ');
            }
            result.append(line.text());
        }
        return result.toString();
    }

    /**
     * Escapes source text before introducing only policy-owned HTML line break elements.
     * @param value native cell value
     * @param policy explicitly selected cell text representation
     * @return safe HTML cell content without cell or row tags
     */
    static String html(final String value, final PdfNativeLayoutOptions.CellTextJoin policy) {
        final String escaped = HtmlTextEscaper.escape(value);
        return policy == PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS ? escaped.replace("\n", "<br>")
                                                                                  : escaped;
    }

    /**
     * Joins only a complete valid ISO date with explicit native breaks following its existing hyphens.
     * Two or three upright, left-aligned lines are required. Internal spaces, same-baseline runs,
     * rotated text, invalid calendar dates, additional text and breaks inside digit groups retain the original.
     * The caller must validate the unmodified joined text against cell budgets before calling.
     * @param lines original ordered native runs assigned to exactly one provider cell
     * @param original historical space-separated value
     * @return joined date or the unchanged original String
     */
    static String wrappedIsoDate(final List<PdfLayoutItem> lines, final String original) {
        if (lines.size() < 2 || lines.size() > 3) {
            return original;
        }
        int length = 0;
        double baseline = Double.NEGATIVE_INFINITY;
        final double left = lines.getFirst().box().left();
        for (int i = 0; i < lines.size(); i++) {
            final var line = lines.get(i);
            final var text = line.text();
            length += text.length();
            if (length > 10 || text.isEmpty() || Math.abs(line.box().left() - left) > 2
                    || line.box().bottom() <= baseline + 2
                    || ((Number) line.metadata().getOrDefault("pdf_text_rotation", 0)).intValue() != 0
                    || i < lines.size() - 1 && !text.endsWith("-")) {
                return original;
            }
            for (int c = 0; c < text.length(); c++) {
                final char ch = text.charAt(c);
                if (ch != '-' && (ch < '0' || ch > '9')) {
                    return original;
                }
            }
            baseline = line.box().bottom();
        }
        if (length != 10) {
            return original;
        }
        final String candidate = String.join("", lines.stream().map(PdfLayoutItem::text).toList());
        if (candidate.charAt(4) != '-' || candidate.charAt(7) != '-') {
            return original;
        }
        try {
            LocalDate.parse(candidate);
            return candidate;
        } catch (final DateTimeException invalid) {
            // A source-shaped date is not enough: impossible calendar dates retain their source joining.
            return original;
        }
    }
}
