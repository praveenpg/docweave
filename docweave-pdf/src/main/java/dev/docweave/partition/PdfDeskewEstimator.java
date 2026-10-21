package dev.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Opt-in, bounded small-angle raster projection analysis. This estimates a clockwise OCR correction
 * from dark-pixel row concentration; it does not recognize text, infer language or provide a calibrated
 * probability. Pages with substantial graphics, sparse content or ambiguous peaks require caller review.
 * The borrowed raster is never modified, flushed or retained. Instances are safe for concurrent calls.
 */
public interface PdfDeskewEstimator {
    /**
     * Creates the built-in estimator with a caller-selected input pixel ceiling. Analysis samples at
     * most1000 pixels along either axis, tests quarter-degree steps from minus10 through plus10 degrees,
     * and retains at most120,000 ink samples. Fixed work bounds are independent of a large caller cap.
     * @param maxPixels positive maximum borrowed raster pixels
     * @return stateless estimator with a hidden implementation
     * @throws IllegalArgumentException if maxPixels is not positive
     */
    static PdfDeskewEstimator create(final long maxPixels) {
        return new ProjectedPdfDeskewEstimator(maxPixels);
    }

    /** Conservative result categories; none represent semantic correctness probabilities. */
    enum Status {
        /** Distinct interior projection peak with sufficient gain over the uncorrected frame. */ ACCEPTED,
        /** Too few sampled dark pixels for projection evidence. */ INSUFFICIENT_INK,
        /** Dense, busy, near-zero, boundary or insufficiently separated projection evidence. */ AMBIGUOUS
    }

    /**
     * Immutable measurement without recognized text or image payloads.
     * @param status decision category
     * @param clockwiseDegrees selected correction; zero unless accepted
     * @param baselineScore uncorrected equal-row dark-pixel pair count; zero when projection is skipped
     * @param bestScore maximum tested concentration, not a probability; zero when projection is skipped
     * @param separatedScore best concentration at least one degree from the selected peak
     * @param inkSamples retained sampled dark points
     * @param width analysis grid width
     * @param height analysis grid height
     */
    record Estimate(Status status, double clockwiseDegrees, double baselineScore, double bestScore,
            double separatedScore, int inkSamples, int width, int height) {
        /**
         * Validates finite nonnegative evidence and the decision/angle relationship.
         * @throws NullPointerException if status is null
         * @throws IllegalArgumentException if measurement components are invalid
         */
        public Estimate {
            java.util.Objects.requireNonNull(status, "status");
            if (!Double.isFinite(clockwiseDegrees) || Math.abs(clockwiseDegrees) > 10
                    || (status != Status.ACCEPTED && clockwiseDegrees != 0) || !Double.isFinite(baselineScore)
                    || baselineScore < 0 || !Double.isFinite(bestScore) || bestScore < 0
                    || !Double.isFinite(separatedScore) || separatedScore < 0 || inkSamples < 0 || width < 1
                    || height < 1) {
                throw new IllegalArgumentException("Invalid PDF deskew measurement");
            }
        }
    }

    /**
     * Measures one complete borrowed raster without starting OCR. The caller decides whether/how to
     * apply an accepted angle; {@link PdfOcrProvider#rotated} can apply it with inverse source bounds.
     * @param image nonnull borrowed raster
     * @return bounded projection evidence and conservative decision
     * @throws IOException if interrupted or input/analysis work exceeds fixed resource limits
     * @throws NullPointerException if image is null
     */
    Estimate estimate(BufferedImage image) throws IOException;
}
