package io.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;

/**
 * Explicit thread-safe page orientation strategy, independent of OCR/layout engine installation.
 * The estimator borrows one rendered/native-masked page and must not modify, flush or retain it.
 * No provider is discovered or downloaded. Applications own external resources and their lifetime.
 * Scores are estimator-specific observations, never calibrated extraction probabilities.
 */
@FunctionalInterface
public interface PdfOrientationEstimator {
    /**
     * Estimates a clockwise quarter-turn correction before PAGE OCR and table detection.
     * Implementations bound their own work/allocations/deadline and preserve interruption flags.
     * @param image borrowed nonnull RGB page raster
     * @return nonnull immutable decision with explicit provenance
     * @throws IOException if estimation, budgets or cancellation prevent a result
     */
    Estimate estimate(BufferedImage image) throws IOException;

    /**
     * Immutable orientation observation; accepted0 means upright, unaccepted0 means undecided.
     * @param clockwiseDegrees source-to-upright correction, one of0/90/180/270
     * @param accepted whether the estimator's documented decision policy accepts this correction
     * @param rawScore finite nonnegative estimator-specific score, not a probability
     * @param provenance nonblank estimator/policy identity, at most256 UTF-16 units
     */
    record Estimate(int clockwiseDegrees, boolean accepted, double rawScore, String provenance) {
        /**
         * Validates quarter-turn decisions and bounded explicit provenance.
         * Unaccepted observations must leave the page unrotated.
         * @throws NullPointerException if provenance is null
         * @throws IllegalArgumentException if correction, score or provenance is invalid
         */
        public Estimate {
            Objects.requireNonNull(provenance, "provenance");
            if (clockwiseDegrees < 0 || clockwiseDegrees > 270 || clockwiseDegrees % 90 != 0
                    || (!accepted && clockwiseDegrees != 0) || !Double.isFinite(rawScore) || rawScore < 0
                    || provenance.isBlank() || provenance.length() > 256) {
                throw new IllegalArgumentException("Invalid PDF orientation estimate");
            }
        }
    }
}
