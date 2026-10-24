package io.docweave.partition;

import java.io.IOException;
import java.text.Normalizer;

/** Bounded per-glyph compatibility expansion restricted to Unicode Alphabetic/Arabic presentation forms. */
final class PdfPresentationForms {
    /** Aggregate selected-policy output ceiling per page, additional to source glyph/operator limits. */
    static final int MAX_UNITS_PER_PAGE = 16_000_000;

    /** Utility class; state and aggregate accounting remain in the page collector. */
    private PdfPresentationForms() {
    }

    /**
     * Expands only BMP presentation scalars, preserving all other UTF-16 units verbatim.
     * No script-dependent reversal, contextual font correction, trimming or blanket compatibility fold occurs.
     * @param text decoded glyph string
     * @param remaining remaining page output units, already nonnegative
     * @return original instance when unchanged, otherwise bounded expanded string
     * @throws IOException if interrupted or selected-policy output would exceed its page ceiling
     */
    static String expand(final String text, final int remaining) throws IOException {
        if (text.length() > remaining) {
            throw limit();
        }
        StringBuilder expanded = null;
        int pending = 0;
        for (int index = 0; index < text.length(); index++) {
            if ((index & 1023) == 0) {
                ElementCollector.checkInterrupted();
            }
            final char unit = text.charAt(index);
            if (unit >= 0xFB00 && unit <= 0xFDFF || unit >= 0xFE70 && unit <= 0xFEFF) {
                final var normalized = Normalizer.normalize(String.valueOf(unit), Normalizer.Form.NFKC);
                if (normalized.length() == 1 && normalized.charAt(0) == unit) {
                    continue;
                }
                if (expanded == null) {
                    expanded = new StringBuilder(Math.min(remaining, text.length() + 16));
                }
                append(expanded, text, pending, index, remaining);
                append(expanded, normalized, 0, normalized.length(), remaining);
                pending = index + 1;
            }
        }
        ElementCollector.checkInterrupted();
        if (expanded == null) {
            return text;
        }
        append(expanded, text, pending, text.length(), remaining);
        return expanded.toString();
    }

    /**
     * Checks prospective builder growth before appending a source slice or scalar decomposition.
     * @param output private lazy builder
     * @param source immutable source slice owner
     * @param first inclusive source index
     * @param last exclusive source index
     * @param remaining aggregate units available to this glyph
     * @throws PartitionException if projected output exceeds the selected page ceiling
     */
    private static void append(final StringBuilder output, final String source, final int first, final int last,
            final int remaining) throws PartitionException {
        if (last - first > remaining - output.length()) {
            throw limit();
        }
        output.append(source, first, last);
    }

    /**
     * Creates a stable native resource diagnostic without retaining decoded source content.
     * @return typed normalized-output ceiling failure
     */
    private static PartitionException limit() {
        return new PartitionException(PartitionException.Code.INPUT_TOO_LARGE,
                "PDF presentation-form expansion exceeds16million UTF-16 units per page");
    }
}
