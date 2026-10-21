package dev.docweave.layout;

import dev.docweave.partition.TableLimits;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Explicit local single-file table graph identity and inference budgets.
 * Supports FLOAT pixel_values[1,3,H,W], INT64 pixel_mask[1,H,W], FLOAT logits[1,Q,7]
 * and pred_boxes[1,Q,4] with the published TATR six-label order plus background.
 * In the two-graph factory, the first graph instead has three detection labels: table/rotated/background.
 * Preprocessing is aspect-preserving max-height/max-width800 bilinear RGB/ImageNet normalization.
 * The explicit YOLOX factory instead uses its fixed images/output schema,1024x768 RGB letterbox
 * preprocessing and16,128 raw queries; use {@link #yoloXDefaults} for that detector policy.
 * Only compatible caller-supplied graphs are supported; nothing is downloaded or discovered.
 * @param model local trusted standalone ONNX graph, read and hash-checked during construction
 * @param sha256 expected64-character hexadecimal graph SHA256; normalized to lowercase
 * @param timeout total recognition admission/preprocessing/native/postprocessing budget
 * @param maxConcurrentCalls maximum simultaneously admitted calls on this provider
 * @param maxModelBytes encoded graph byte ceiling; at most512MiB
 * @param maxInputPixels maximum borrowed raster pixels before preprocessing allocations
 * @param maxQueries maximum raw query dimension before copying native outputs, at most16,128
 * @param tableLimits structured dimension/aggregate prospective-slot ceilings; cell-character limit applies later in
 *         PDF integration
 * @param minimumScore fixed model-class threshold, not calibrated extraction probability
 * @param nativeThreads explicit intra-operation CPU threads; inter-operation threads remain one
 * @param mergeConflicts explicit handling of overlapping rectangular merge predictions; not calibrated confidence
 */
public record OnnxTableOptions(Path model, String sha256, Duration timeout, int maxConcurrentCalls, int maxModelBytes,
        long maxInputPixels, int maxQueries, TableLimits tableLimits, double minimumScore, int nativeThreads,
        MergeConflicts mergeConflicts) {
    /** Handling of competing complete rectangular merge proposals after score ordering. */
    public enum MergeConflicts {
        /** Reject the whole table when distinct merge proposals overlap; historical default. */
        REJECT_TABLE,
        /**
         * Keep an accepted higher-score merge and discard the entire overlapping lower-score proposal.
         * Equal-score conflicts and nonrectangular selections still reject the whole table.
         * Scores order model proposals; they are not probabilities that the resulting cells are correct.
         */
        KEEP_HIGHER_SCORE
    }

    /**
     * Preserves the former constructor with conservative merge-conflict rejection.
     * @param model explicit local graph
     * @param sha256 expected graph identity
     * @param timeout total recognition budget
     * @param maxConcurrentCalls simultaneous call ceiling
     * @param maxModelBytes encoded graph byte ceiling
     * @param maxInputPixels borrowed raster pixel ceiling
     * @param maxQueries raw model-query ceiling
     * @param tableLimits structured geometry limits
     * @param minimumScore class-score threshold
     * @param nativeThreads intra-operation CPU threads
     */
    public OnnxTableOptions(final Path model, final String sha256, final Duration timeout, final int maxConcurrentCalls,
            final int maxModelBytes, final long maxInputPixels, final int maxQueries, final TableLimits tableLimits,
            final double minimumScore, final int nativeThreads) {
        this(model, sha256, timeout, maxConcurrentCalls, maxModelBytes, maxInputPixels, maxQueries, tableLimits,
                minimumScore, nativeThreads, MergeConflicts.REJECT_TABLE);
    }

    /**
     * Validates immutable identity/resource policies without loading the model or native runtime.
     * @throws NullPointerException if a required component is null
     * @throws IllegalArgumentException if identity, deadline, threshold or limits are invalid
     */
    public OnnxTableOptions {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(tableLimits, "tableLimits");
        Objects.requireNonNull(mergeConflicts, "mergeConflicts");
        sha256 = sha256.toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}") || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofDays(1)) > 0 || maxConcurrentCalls < 1 || maxConcurrentCalls > 64
                || maxModelBytes < 1 || maxModelBytes > 512 * 1024 * 1024 || maxInputPixels < 1 || maxQueries < 1
                || maxQueries > 16_128 || !Double.isFinite(minimumScore) || minimumScore < 0 || minimumScore > 1
                || nativeThreads < 1 || nativeThreads > 256) {
            throw new IllegalArgumentException("Invalid ONNX table options");
        }
    }

    /**
     * Returns30-second, single-call defaults with256MiB encoded graph,16M input pixels,256 queries,
     * default table dimensions,0.5 class threshold and at most four native CPU threads.
     * @param model explicit local graph
     * @param sha256 expected graph identity
     * @return immutable configured policy; no file or native access occurs
     */
    public static OnnxTableOptions defaults(final Path model, final String sha256) {
        return new OnnxTableOptions(model, sha256, Duration.ofSeconds(30), 1, 256 * 1024 * 1024, 16_000_000, 256,
                TableLimits.defaults(), .5, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Selects explicit fixed-shape YOLOX detection defaults without loading a graph or native runtime.
     * Uses the same model/input/deadline/concurrency/thread caps as TATR defaults, but16,128 raw
     * queries and a strict greater-than0.25 objectness-times-class threshold. Suppression additionally
     * enforces fixed2,048 pre-NMS candidates and the pipeline's shared bounded comparison work.
     * The child structure graph retains its separately selected TATR options and score threshold.
     * @param model explicit local compatible YOLOX graph
     * @param sha256 expected graph identity
     * @return immutable detector policy; nothing is downloaded or executed
     */
    public static OnnxTableOptions yoloXDefaults(final Path model, final String sha256) {
        final var base = defaults(model, sha256);
        return new OnnxTableOptions(model, sha256, base.timeout(), base.maxConcurrentCalls(), base.maxModelBytes(),
                base.maxInputPixels(), 16_128, base.tableLimits(), .25, base.nativeThreads(), base.mergeConflicts());
    }

    /**
     * Copies this policy with a new total call deadline.
     * @param duration positive timeout, at most one day
     * @return validated immutable policy
     */
    public OnnxTableOptions withTimeout(final Duration duration) {
        return new OnnxTableOptions(model, sha256, duration, maxConcurrentCalls, maxModelBytes, maxInputPixels,
                maxQueries, tableLimits, minimumScore, nativeThreads, mergeConflicts);
    }

    /**
     * Copies this policy with explicit admission concurrency.
     * @param concurrency simultaneous call ceiling from1 through64
     * @return validated immutable policy
     */
    public OnnxTableOptions withConcurrency(final int concurrency) {
        return new OnnxTableOptions(model, sha256, timeout, concurrency, maxModelBytes, maxInputPixels, maxQueries,
                tableLimits, minimumScore, nativeThreads, mergeConflicts);
    }

    /**
     * Copies structured geometry limits without changing graph identity or inference settings.
     * @param limits nonnull table dimensions/cell ceiling
     * @return validated immutable policy
     */
    public OnnxTableOptions withTableLimits(final TableLimits limits) {
        return new OnnxTableOptions(model, sha256, timeout, maxConcurrentCalls, maxModelBytes, maxInputPixels,
                maxQueries, limits, minimumScore, nativeThreads, mergeConflicts);
    }

    /**
     * Copies this policy with explicit merge-conflict handling, without loading or running a graph.
     * @param policy nonnull merge-conflict strategy
     * @return validated immutable policy preserving all model and resource settings
     */
    public OnnxTableOptions withMergeConflicts(final MergeConflicts policy) {
        return new OnnxTableOptions(model, sha256, timeout, maxConcurrentCalls, maxModelBytes, maxInputPixels,
                maxQueries, tableLimits, minimumScore, nativeThreads, policy);
    }
}
