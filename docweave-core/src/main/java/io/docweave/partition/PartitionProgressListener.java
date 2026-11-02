package io.docweave.partition;

/**
 * Optional caller-supplied observer of one partition call's progress, independent of any UI or job system.
 *
 * <p>Supply a listener per call with
 * {@link io.docweave.DocWeave#partition(DocumentSource, PartitionOptions, PartitionProgressListener)}.
 * The built-in engine invokes it synchronously while holding a per-call lock, never concurrently for one call, in
 * phase order and with monotonic unit counters; a strategy that completes units on worker threads can make the
 * invoking thread vary. Keep the callback short because extraction waits for it, and never wait inside it for work
 * that itself reports progress for the same call: that would deadlock.
 *
 * <p>Invocations happen only while the call is running; none occur after it returns or throws. Once any reporting
 * thread of the call is observed interrupted, or the call fails, later extraction reports from every thread are
 * discarded. ASSEMBLING and DONE are reported only after extraction finished; DONE is reported whenever the call
 * returns a result, even if an interrupt arrived after the last cancellation checkpoint.
 *
 * <p>Anything thrown by the listener, including undeclared checked exceptions and errors other than
 * {@link VirtualMachineError}, is caught and ignored for that event; it never fails or alters the partition result,
 * and later events are still offered. A swallowed {@link InterruptedException} restores the interrupt flag. Calls
 * without a listener behave exactly as before.
 *
 * <pre>{@code
 * PartitionResult result = processor.partition(source, options, progress -> {
 *     if (progress.determinate()) {
 *         log.info("{} {}/{} {}", progress.phase(), progress.completed(), progress.total(), progress.unit());
 *     } else {
 *         log.info("{}", progress.phase());
 *     }
 * });
 * }</pre>
 */
@FunctionalInterface
public interface PartitionProgressListener {
    /**
     * Receives one content-free progress snapshot.
     *
     * @param progress immutable snapshot, never null
     */
    void onProgress(PartitionProgress progress);
}
