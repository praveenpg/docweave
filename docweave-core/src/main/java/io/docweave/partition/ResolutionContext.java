package io.docweave.partition;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.function.Function;

/**
 * Limits and cancellation for one remote resolution, created by the processor for each partition call. The byte
 * ceiling is the call's {@link PartitionOptions#maxInputBytes()}; the deadline is the processor's finite remote
 * acquisition timeout ({@link io.docweave.DocWeave.Builder#remoteTimeout(Duration)}), measured from the start of
 * the call. Cancellation is the calling thread's interruption, the same mechanism the rest of partitioning uses.
 * After resolution, loading and parsing keep their existing limits and interruption checkpoints.
 *
 * <p>A context is confined to the call that created it: the resolver uses it on the calling thread. Stream-based
 * providers should use {@link #receive} rather than reimplementing byte counting, deadlines, cleanup and digests.</p>
 */
public interface ResolutionContext {
    /**
     * Creates a standalone context, for testing a resolver or resolving outside a processor.
     * @param maxBytes positive byte ceiling
     * @param timeout positive, finite acquisition timeout starting now
     * @return a context bound to the calling thread
     * @throws NullPointerException if timeout is null
     * @throws IllegalArgumentException if maxBytes or timeout is not positive
     */
    static ResolutionContext create(final int maxBytes, final Duration timeout) {
        return new DefaultResolutionContext(maxBytes, timeout);
    }

    /**
     * Returns the largest number of content bytes the resolver may accept.
     * @return positive byte ceiling; exceeding it fails without truncation
     */
    int maxBytes();

    /**
     * Returns the time left before the acquisition deadline.
     * @return nonnegative remaining time; zero once the deadline has passed
     */
    Duration remaining();

    /**
     * Fails if the call should stop: the calling thread was interrupted, or the deadline passed.
     * @throws java.io.InterruptedIOException if the calling thread is interrupted; its interrupt status is preserved
     * @throws RemoteSourceException with {@link RemoteSourceException.Reason#DEADLINE_EXCEEDED} after the deadline
     */
    void checkActive() throws IOException;

    /**
     * Arranges for {@code abort} to run once if the deadline passes or the calling thread is interrupted while the
     * returned registration is open. Use it to unblock a transport read that cannot observe interruption, for example
     * by aborting an HTTP response stream. Closing the registration (normally with try-with-resources) cancels it.
     * The action runs on its own short-lived thread, so an abort that blocks delays only its own resolution.
     * @param abort idempotent action that releases or aborts the blocked transport; its failures are ignored
     * @return registration to close when the guarded work ends
     * @throws NullPointerException if abort is null
     */
    AutoCloseable abortOnStop(Closeable abort);

    /**
     * Reads a provider response body into an owned local document, enforcing this context's rules: the actual
     * received byte count against {@link #maxBytes()} (also when the declared length is missing or wrong), the
     * deadline and interruption between reads (aborting a blocked read through {@code body.close()}), and an exact
     * match with a declared length. It computes the SHA-256 of the received bytes. On any failure it releases
     * partial content (memory or temporary file) before throwing; it never returns truncated content. The body is
     * not closed on success; the caller owns it.
     * @param body provider response stream
     * @param declaredBytes length announced by the provider, or a negative value when unknown
     * @param spool in-memory or temporary-file buffering
     * @param filename logical filename for routing, metadata and element IDs, or null
     * @param provenance builds safe provenance from the measured content
     * @return an owned handle; the processor closes it after parsing
     * @throws PartitionException with {@link PartitionException.Code#INPUT_TOO_LARGE} when the declared or received
     *         size exceeds the ceiling
     * @throws RemoteSourceException with {@link RemoteSourceException.Reason#INCOMPLETE} when the received size
     *         differs from the declared one, or {@link RemoteSourceException.Reason#DEADLINE_EXCEEDED} after the
     *         deadline
     * @throws java.io.InterruptedIOException if the calling thread is interrupted
     * @throws IOException if reading the body or writing a temporary file fails
     * @throws NullPointerException if body, spool or provenance is null
     */
    ResolvedDocument receive(InputStream body, long declaredBytes, SpoolPolicy spool, String filename,
            Function<Received, SourceProvenance> provenance) throws IOException;

    /**
     * Measured facts about received content, for building {@link SourceProvenance}.
     * @param bytes number of bytes received
     * @param sha256 lowercase hexadecimal SHA-256 of the received bytes
     */
    record Received(long bytes, String sha256) {}
}
