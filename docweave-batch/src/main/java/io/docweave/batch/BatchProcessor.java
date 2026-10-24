package io.docweave.batch;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Bounded, input-order mapping with one virtual thread per task.
 * Intended for blocking I/O. The task window bounds resource use; virtual threads are not pooled.
 * CPU-only work gains no speed guarantee. The concurrency-only overload observes failures in input
 * order. The BatchOptions overload can enforce task/batch budgets and observe failures independently.
 */
public interface BatchProcessor {
    /**
     * Creates the built-in thread-safe implementation behind this contract.
     * @return a reusable BatchProcessor service
     */
    static BatchProcessor create() {
        return new VirtualThreadBatchProcessor();
    }

    /**
     * An operation safe for concurrent calls and cooperative interruption.
     * @param <I> input type
     * @param <O> nonnull output type
     */
    @FunctionalInterface
    interface Operation<I, O> {
        /**
         * Processes one input on its own virtual thread.
         * @param input safely shared input
         * @return nonnull result
         * @throws Exception if processing fails or is interrupted
         */
        O apply(final I input) throws Exception;
    }
    /**
     * Maps an input snapshot while retaining input order.
     * At most maxInFlight tasks are submitted. On observed failure/interruption, outstanding futures
     * are cancelled and executor close waits for termination. Tasks ignoring interruption may delay
     * return. This overload has no execution deadline; use the BatchOptions overload for deadlines.
     * Use forEachOrdered for incremental delivery instead of retaining all results.
     * @param <I> input type
     * @param <O> output type
     * @param inputs inputs to snapshot
     * @param maxInFlight positive outstanding-task limit
     * @param operation concurrently callable operation returning nonnull results
     * @return immutable results in input order
     * @throws InterruptedException if the caller is interrupted while waiting
     * @throws ExecutionException if a worker fails; inspect its original cause
     * @throws IllegalArgumentException if maxInFlight is not positive
     * @throws NullPointerException if inputs, operation, entries or results are null
     */
    <I, O> List<O> mapOrdered(final List<I> inputs, final int maxInFlight, final Operation<I, O> operation)
            throws InterruptedException, ExecutionException;

    /**
     * Maps with optional task/batch deadlines while retaining input-order successful results.
     * With deadlines enabled, failures/timeouts are observed independently of input order; the first
     * observed signal ends the call without partial output. A timeout is the cause of an
     * ExecutionException and exposes its scope/index through BatchTimeoutException. Outstanding work
     * is interrupted and executors shut down without waiting indefinitely; operations ignoring
     * interruption can outlive the call. Timers do not guarantee real-time return or rollback of side
     * effects. Without deadlines, delegates to the original concurrency-only contract.
     *
     * <p>The default preserves existing custom implementations for policies without deadlines.
     * Implementations must override this method to support elapsed-time policies; the built-in
     * virtual-thread implementation does so.</p>
     * @param <I> input type
     * @param <O> nonnull result type
     * @param inputs ordered inputs to snapshot
     * @param options nonnull immutable concurrency/deadline policy
     * @param operation thread-safe, interruption-cooperative operation
     * @return immutable results in input order
     * @throws InterruptedException if the caller is interrupted while waiting
     * @throws ExecutionException if a worker fails, returns null under a timed policy, or a deadline expires
     * @throws NullPointerException if a required argument/input is null, or an untimed operation returns null
     * @throws UnsupportedOperationException if a custom implementation does not support enabled deadlines
     */
    default<I, O> List<O> mapOrdered(final List<I> inputs, final BatchOptions options, final Operation<I, O> operation)
            throws InterruptedException, ExecutionException {
        java.util.Objects.requireNonNull(options, "options");
        if (options.hasDeadlines()) {
            throw new UnsupportedOperationException("This batch processor does not implement deadlines");
        }
        return mapOrdered(inputs, options.maxInFlight(), operation);
    }

    /**
     * A sequential receiver of completed results on the calling thread.
     * @param <O> result type
     */
    @FunctionalInterface
    interface ResultConsumer<O> {
        /**
         * Consumes one result before the task window is replenished.
         * @param result nonnull completed output
         * @throws Exception if delivery fails; the batch wraps it in ExecutionException
         */
        void accept(final O result) throws Exception;
    }

    /**
     * Processes a lazy input sequence with bounded, input-order incremental delivery.
     * The built-in implementation obtains one iterator and uses it only on the calling thread.
     * It retains at most maxInFlight tasks/results, without snapshotting inputs or accumulating
     * outputs. The caller must not concurrently mutate the source and owns any source resources
     * (for example, a directory stream). Each document still materializes its own output.
     *
     * <p>Delivery is sequential on the calling thread; slow consumers apply backpressure. Already
     * delivered results and side effects are not rolled back on failure. Failures are observed
     * independently of input order, even without deadlines. Cleanup requests interruption without
     * waiting indefinitely; uncooperative workers may outlive return. Task time excludes delivery;
     * batch time includes iteration and delivery. Blocking iterators/consumers cannot be forcibly
     * interrupted by deadlines, which are checked when they return. Timeout indices count inputs
     * from zero and are limited to Integer.MAX_VALUE inputs per invocation.</p>
     *
     * <p>Custom implementations must override this method; the default never falls back to
     * buffering every result.</p>
     * @param <I> input type
     * @param <O> nonnull output type
     * @param inputs lazy ordered source; not closed by this method
     * @param options concurrency and optional elapsed-time budgets
     * @param operation concurrently callable worker operation
     * @param consumer sequential result receiver
     * @throws InterruptedException if the caller is interrupted or the consumer throws it
     * @throws ExecutionException if a worker/consumer fails or a deadline expires
     * @throws NullPointerException if required arguments, an input or a worker result is null
     * @throws RuntimeException if input iteration fails; earlier deliveries remain committed
     * @throws UnsupportedOperationException if a custom implementation lacks incremental delivery
     */
    default<I, O> void forEachOrdered(final Iterable<I> inputs, final BatchOptions options,
            final Operation<I, O> operation, final ResultConsumer<? super O> consumer)
            throws InterruptedException, ExecutionException {
        throw new UnsupportedOperationException("This batch processor does not implement incremental delivery");
    }
}
