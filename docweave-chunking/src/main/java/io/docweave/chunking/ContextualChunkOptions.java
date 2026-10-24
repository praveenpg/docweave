package io.docweave.chunking;

import java.time.Duration;
import java.util.Objects;

/**
 * Explicit per-enrichment limits, counted in Unicode code points rather than tokens or bytes.
 * Text already exists in caller memory; these limits bound accepted input and scheduled calls,
 * not model context windows, provider allocations, billable tokens or total application heap.
 * @param maxDocumentCharacters maximum explicit document context
 * @param maxChunkCharacters maximum original text per chunk
 * @param maxContextCharacters maximum returned context per chunk
 * @param maxChunks maximum complete supplied chunk population; no truncation
 * @param maxConcurrency maximum in-flight provider calls per invocation,1 through64
 * @param timeout positive whole-invocation deadline, at most one hour
 * @param failurePolicy provider-failure behavior; timeout/cancellation always fail the invocation
 */
public record ContextualChunkOptions(int maxDocumentCharacters, int maxChunkCharacters, int maxContextCharacters,
        int maxChunks, int maxConcurrency, Duration timeout, FailurePolicy failurePolicy) {
    /**
     * Validates positive complete-population limits and a representable bounded deadline.
     * @throws NullPointerException if timeout or failurePolicy is null
     * @throws IllegalArgumentException if any limit/deadline is invalid
     */
    public ContextualChunkOptions {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(failurePolicy, "failurePolicy");
        if (maxDocumentCharacters < 1 || maxChunkCharacters < 1 || maxContextCharacters < 1 || maxChunks < 1
                || maxConcurrency < 1 || maxConcurrency > 64 || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Invalid contextual chunk limits");
        }
    }

    /** Explicit handling of provider exceptions and invalid/oversized generated context. */
    public enum FailurePolicy {
        /** Fail the invocation without returning a partial result list. */ FAIL_DOCUMENT,
        /** Keep the failed chunk unchanged with a diagnostic outcome; never invent context. */ KEEP_ORIGINAL
    }

    /**
     * Uses100K document,10K chunk and2K context code points,1000 chunks, four calls and30seconds.
     * Provider failures fail the invocation; caller-selected models still enforce their own token budgets.
     * @return immutable conservative per-call defaults
     */
    public static ContextualChunkOptions defaults() {
        return new ContextualChunkOptions(
                100_000, 10_000, 2_000, 1_000, 4, Duration.ofSeconds(30), FailurePolicy.FAIL_DOCUMENT);
    }
}
