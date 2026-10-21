package dev.docweave.partition;

/**
 * Explicit repeated-margin policy without changing source text, identity or coordinates.
 * Wider bands or allowing first-page body repetitions can introduce false positives; neither infers cover semantics.
 * @param headerBandFraction outer top fraction containing the whole candidate, finite and greater than0 up to0.2
 * @param footerBandFraction outer bottom fraction containing the whole candidate, finite and greater than0 up to0.2
 * @param allowFirstPageBodyText whether exact body occurrences on physical page1 may coexist with repeated margins;
 *         later-page body occurrences still veto classification
 */
public record PdfMarginOptions(double headerBandFraction, double footerBandFraction, boolean allowFirstPageBodyText) {
    /**
     * Validates separate nonoverlapping bounded margin bands.
     * @throws IllegalArgumentException if a fraction is nonfinite, nonpositive or exceeds0.2
     */
    public PdfMarginOptions {
        if (!Double.isFinite(headerBandFraction) || headerBandFraction <= 0 || headerBandFraction > .2
                || !Double.isFinite(footerBandFraction) || footerBandFraction <= 0 || footerBandFraction > .2) {
            throw new IllegalArgumentException("PDF margin bands must be finite and greater than0 through0.2");
        }
    }

    /**
     * Returns the historical eight-percent bands with all body occurrences vetoing repetition.
     * @return immutable conservative defaults
     */
    public static PdfMarginOptions defaults() {
        return new PdfMarginOptions(.08, .08, false);
    }

    /**
     * Copies separate header/footer search bands without changing body repetition policy.
     * @param header outer top fraction, greater than0 through0.2
     * @param footer outer bottom fraction, greater than0 through0.2
     * @return validated immutable copy
     */
    public PdfMarginOptions withBands(final double header, final double footer) {
        return new PdfMarginOptions(header, footer, allowFirstPageBodyText);
    }

    /**
     * Copies explicit physical-page1 body handling; this does not identify a semantic cover page.
     * @param allowed whether page1 body repetitions cease to veto margin groups
     * @return immutable copy retaining both bands
     */
    public PdfMarginOptions withFirstPageBodyText(final boolean allowed) {
        return new PdfMarginOptions(headerBandFraction, footerBandFraction, allowed);
    }
}
