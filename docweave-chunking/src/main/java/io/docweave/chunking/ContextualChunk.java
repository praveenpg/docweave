package io.docweave.chunking;

import io.docweave.core.Element;

import java.util.Objects;
import java.util.Optional;

/**
 * Original immutable chunk and separately attributed generated enrichment.
 * Original text, IDs, metadata, HTML and provenance remain untouched. Embedding text may be
 * longer than the original chunk budget by the configured context bound and two separator characters.
 * @param original original source chunk, retained by identity
 * @param outcome whether context was generated or the explicit fallback policy kept the original
 * @param context present exactly when enrichment succeeded
 */
public record ContextualChunk(Element original, Outcome outcome, Optional<ChunkContextProvider.Context> context) {
    /**
     * Validates the immutable result without copying or rewriting original source metadata.
     * @throws NullPointerException if any component is null
     * @throws IllegalArgumentException if outcome and context presence disagree
     */
    public ContextualChunk {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(context, "context");
        if ((outcome == Outcome.ENRICHED) != context.isPresent()) {
            throw new IllegalArgumentException("Context presence must match the enrichment outcome");
        }
    }

    /** Content-free outcome; original provider error messages are never copied into result metadata. */
    public enum Outcome {
        /** Explicit provider/model context is available separately from source content. */ ENRICHED,
        /** The caller-selected provider failed; the original chunk is retained. */ PROVIDER_FAILED,
        /** The provider returned null or oversized context; the original chunk is retained. */ INVALID_CONTEXT
    }

    /**
     * Produces an explicit retrieval representation without modifying the stored original chunk.
     * Context is prepended verbatim with two newlines; fallback returns the exact original string.
     * Generated context is unverified model output, not a source citation or original document text.
     * @return context plus original text, or original text when enrichment failed under KEEP_ORIGINAL
     */
    public String embeddingText() {
        return context.map(value -> value.text() + "\n\n" + original.text()).orElse(original.text());
    }
}
