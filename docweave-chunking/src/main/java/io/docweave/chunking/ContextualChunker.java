package io.docweave.chunking;

import io.docweave.core.Element;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Optional cross-format enrichment after existing chunk boundaries have been chosen.
 * No provider discovery, automatic LLM execution, engine download or model-vendor dependency is used.
 * Reusable implementations retain only the caller-owned provider; every invocation owns its bounded
 * work window. Across simultaneous invocations, callers must bound their own total model concurrency.
 */
public interface ContextualChunker {
    /**
     * Captures an explicit provider without invoking it or creating threads.
     * @param provider caller-owned concurrent/context-aware generation strategy
     * @return hidden reusable enrichment service
     * @throws NullPointerException if provider is null
     */
    static ContextualChunker create(final ChunkContextProvider provider) {
        return new DefaultContextualChunker(provider);
    }

    /**
     * Validates every input before submitting a bounded virtual-thread work window, preserving chunk order.
     * Document context is supplied explicitly rather than implicitly serializing elements or image payloads.
     * All supplied chunks, including tables, use the same contract. No chunk is truncated or rechunked.
     * Timeout/cancellation cancels outstanding futures and returns no partial list. Providers must honor
     * interruption and enforce their own transport deadlines; an uncooperative call can continue after
     * this method returns. The caller owns model client shutdown and any resulting remote request costs.
     * @param documentText caller-selected document text to share with each generation request
     * @param chunks complete original ordered chunks; do not mutate the list during invocation
     * @param options explicit per-invocation budgets, concurrency, deadline and failure policy
     * @return immutable results in input order, with original Elements retained unchanged
     * @throws NullPointerException if any argument or chunk is null
     * @throws IllegalArgumentException if a complete input exceeds its code-point/count budget
     * @throws IOException if generation fails under FAIL_DOCUMENT, retaining the original cause
     * @throws TimeoutException if the whole-invocation deadline expires, regardless of failure policy
     * @throws java.util.concurrent.CancellationException if interrupted or the provider signals cancellation;
     *         caller interruption remains set
     */
    List<ContextualChunk> enrich(String documentText, List<Element> chunks, ContextualChunkOptions options)
            throws IOException, TimeoutException;
}
