package io.docweave.chunking;

import io.docweave.core.Element;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Explicit context generation with immutable originals and a call-confined bounded work window. */
final class DefaultContextualChunker implements ContextualChunker {
    /** Caller-owned strategy; no model/client lifecycle is assumed. */
    private final ChunkContextProvider provider;

    /**
     * Captures the sole shared service dependency without running it.
     * @param provider concurrent generation strategy
     */
    DefaultContextualChunker(final ChunkContextProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public List<ContextualChunk> enrich(final String documentText, final List<Element> chunks,
            final ContextualChunkOptions options) throws IOException, TimeoutException {
        final long started = System.nanoTime();
        ChunkCancellation.check();
        Objects.requireNonNull(documentText, "documentText");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(options, "options");
        if (chunks.size() > options.maxChunks()) {
            throw new IllegalArgumentException("Contextual enrichment exceeds maxChunks");
        }
        within(documentText, options.maxDocumentCharacters(), "document text");
        final var source = List.copyOf(chunks);
        for (final var chunk : source) {
            ChunkCancellation.check();
            within(chunk.text(), options.maxChunkCharacters(), "chunk text");
        }
        ChunkCancellation.check();
        if (source.isEmpty()) {
            return List.of();
        }
        remaining(started, options);
        final var executor = Executors.newVirtualThreadPerTaskExecutor();
        final var pending = new ArrayDeque<Future<ContextualChunk>>();
        final var output = new ArrayList<ContextualChunk>(source.size());
        int next = 0;
        try {
            while (next < Math.min(source.size(), options.maxConcurrency())) {
                ChunkCancellation.check();
                remaining(started, options);
                final int index = next++;
                pending.add(executor.submit(() -> generate(documentText, source, index, options)));
            }
            while (!pending.isEmpty()) {
                ChunkCancellation.check();
                output.add(pending.getFirst().get(remaining(started, options), TimeUnit.NANOSECONDS));
                pending.removeFirst();
                if (next < source.size()) {
                    remaining(started, options);
                    final int index = next++;
                    pending.add(executor.submit(() -> generate(documentText, source, index, options)));
                }
            }
            ChunkCancellation.check();
            remaining(started, options);
            return List.copyOf(output);
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw cancelled(failure);
        } catch (final ExecutionException failure) {
            final var cause = failure.getCause();
            if (cause instanceof final IOException io) {
                throw io;
            }
            if (cause instanceof final RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof final Error error) {
                throw error;
            }
            throw new IOException("Chunk context generation failed", cause);
        } finally {
            pending.forEach(task -> task.cancel(true));
            // Executor.close() waits for workers: it would defeat the deadline for an uncooperative provider.
            // Only this call's virtual threads are cancelled; the caller still owns the model/client lifecycle.
            executor.shutdownNow();
        }
    }

    /**
     * Calls one provider and validates the generated context before returning a separately attributed result.
     * @param documentText explicitly supplied shared context
     * @param source complete immutable original population
     * @param index zero-based original position
     * @param options context budget and explicit failure behavior
     * @return enriched result or declared original-only fallback
     * @throws IOException if FAIL_DOCUMENT requires propagation
     */
    private ContextualChunk generate(final String documentText, final List<Element> source, final int index,
            final ContextualChunkOptions options) throws IOException {
        ChunkCancellation.check();
        final var original = source.get(index);
        final ChunkContextProvider.Context context;
        try {
            context = provider.generate(
                    new ChunkContextProvider.Request(documentText, original.text(), index, source.size()));
        } catch (final CancellationException | InterruptedIOException failure) {
            throw cancelled(failure);
        } catch (final IOException | RuntimeException failure) {
            return failure(original, ContextualChunk.Outcome.PROVIDER_FAILED, failure, options);
        }
        ChunkCancellation.check();
        if (context == null) {
            return failure(original, ContextualChunk.Outcome.INVALID_CONTEXT,
                    new IllegalArgumentException("Chunk context provider returned null"), options);
        }
        try {
            within(context.text(), options.maxContextCharacters(), "generated context");
        } catch (final IllegalArgumentException failure) {
            return failure(original, ContextualChunk.Outcome.INVALID_CONTEXT, failure, options);
        }
        return new ContextualChunk(original, ContextualChunk.Outcome.ENRICHED, Optional.of(context));
    }

    /**
     * Applies only the explicit per-chunk fallback; deadline/cancellation do not pass through this path.
     * @param original unchanged source chunk
     * @param outcome content-free diagnostic
     * @param cause original provider or validation failure
     * @param options declared failure policy
     * @return original-only result when requested
     * @throws IOException when the complete invocation must fail
     */
    private static ContextualChunk failure(final Element original, final ContextualChunk.Outcome outcome,
            final Exception cause, final ContextualChunkOptions options) throws IOException {
        if (options.failurePolicy() == ContextualChunkOptions.FailurePolicy.FAIL_DOCUMENT) {
            throw new IOException("Chunk contextual enrichment failed: " + outcome, cause);
        }
        return new ContextualChunk(original, outcome, Optional.empty());
    }

    /**
     * Rejects oversize input without truncation; the UTF-16 precheck bounds the subsequent code-point scan.
     * @param text immutable source or generated string
     * @param maximum accepted Unicode code points
     * @param label content-free diagnostic name
     */
    private static void within(final String text, final int maximum, final String label) {
        if (text.length() > 2L * maximum || text.codePointCount(0, text.length()) > maximum) {
            throw new IllegalArgumentException("Contextual enrichment exceeds " + label + " limit");
        }
    }

    /**
     * Uses monotonic elapsed time so wall-clock changes cannot extend the complete invocation deadline.
     * @param started monotonic timestamp before validation
     * @param options validated duration of at most one hour
     * @return positive nanoseconds remaining
     * @throws TimeoutException if validation, scheduling or generation exhausted the deadline
     */
    private static long remaining(final long started, final ContextualChunkOptions options) throws TimeoutException {
        final long value = options.timeout().toNanos() - (System.nanoTime() - started);
        if (value <= 0) {
            throw new TimeoutException("Contextual enrichment deadline exceeded");
        }
        return value;
    }

    /**
     * Propagates cancellation without leaking provider messages into the public diagnostic.
     * @param cause original cancellation/interruption
     * @return cancellation with the original cause available to the caller
     */
    private static CancellationException cancelled(final Exception cause) {
        final var failure = new CancellationException("Chunk contextual enrichment cancelled");
        failure.initCause(cause);
        return failure;
    }
}
