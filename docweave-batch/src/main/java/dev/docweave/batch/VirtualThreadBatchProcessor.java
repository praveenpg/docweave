package dev.docweave.batch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * Bounded, input-order mapping with one virtual thread per task.
 * Intended for blocking I/O. The task window bounds resource use; virtual threads are not pooled.
 * Legacy calls observe input-order failures; enabled deadlines use independent failure signals.
 */
final class VirtualThreadBatchProcessor implements BatchProcessor {
    /** Creates a stateless virtual-thread batch processor. */
    VirtualThreadBatchProcessor() {
    }

    /**
     * Maps an input snapshot while retaining input order.
     * At most maxInFlight tasks are submitted. On observed failure/interruption, outstanding futures
     * are cancelled and executor close waits for termination. Tasks ignoring interruption may delay
     * return. This concurrency-only overload has no deadline; timed calls use a separate per-call runner.
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
    public <I, O> List<O> mapOrdered(final List<I> inputs, final int maxInFlight, final Operation<I, O> operation)
            throws InterruptedException, ExecutionException {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(operation, "operation");
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        final var snapshot = List.copyOf(inputs);
        final var pending = new ArrayDeque<Future<O>>();
        final var output = new ArrayList<O>(snapshot.size());
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                int submitted = 0;
                while (submitted < snapshot.size() && pending.size() < maxInFlight) {
                    final I input = snapshot.get(submitted++);
                    pending.addLast(submit(executor, operation, input));
                }
                while (!pending.isEmpty()) {
                    // Keep the current future in the queue until get succeeds so it is cancelled on interruption.
                    output.add(Objects.requireNonNull(pending.getFirst().get(), "operation result"));
                    pending.removeFirst();
                    if (submitted < snapshot.size()) {
                        final I input = snapshot.get(submitted++);
                        pending.addLast(submit(executor, operation, input));
                    }
                }
            } finally {
                pending.forEach(future -> future.cancel(true));
            }
        }
        return List.copyOf(output);
    }

    /**
     * Submits a separately tracked future through execute so cancellation cannot unregister the worker
     * before its operation/finally blocks have returned on Java 21. Executor closure still joins the
     * actual runnable, preserving the untimed batch cleanup contract across supported JDKs.
     * @param <I> input type
     * @param <O> result type
     * @param executor call-owned virtual-thread executor
     * @param operation caller operation
     * @param input current snapshot value
     * @return cancellable result future
     */
    private static <I, O> Future<O> submit(
            final ExecutorService executor, final Operation<I, O> operation, final I input) {
        final var task = new FutureTask<O>(() -> operation.apply(input));
        executor.execute(task);
        return task;
    }

    /**
     * Maps an input snapshot in order under the given concurrency and deadline policy.
     * @param <I> input type
     * @param <O> output type
     * @param inputs inputs to snapshot
     * @param options concurrency and deadline policy
     * @param operation concurrently callable operation returning nonnull results
     * @return immutable results in input order
     * @throws InterruptedException if the caller is interrupted while waiting
     * @throws ExecutionException if a worker or deadline fails
     */
    @Override
    public <I, O> List<O> mapOrdered(final List<I> inputs, final BatchOptions options, final Operation<I, O> operation)
            throws InterruptedException, ExecutionException {
        Objects.requireNonNull(options, "options");
        return options.hasDeadlines() ? DeadlineBatchRunner.map(inputs, options, operation)
                                      : mapOrdered(inputs, options.maxInFlight(), operation);
    }

    /**
     * Processes inputs with bounded concurrency and delivers results in input order to {@code consumer}.
     * @param <I> input type
     * @param <O> output type
     * @param inputs input sequence to process
     * @param options concurrency and deadline policy
     * @param operation concurrently callable operation returning nonnull results
     * @param consumer ordered result callback invoked on the calling thread
     * @throws InterruptedException if the caller or consumer is interrupted
     * @throws ExecutionException if a worker, consumer or deadline fails
     */
    @Override
    public <I, O> void forEachOrdered(final Iterable<I> inputs, final BatchOptions options,
            final Operation<I, O> operation, final ResultConsumer<? super O> consumer)
            throws InterruptedException, ExecutionException {
        DeadlineBatchRunner.consume(inputs, options, operation, consumer);
    }
}
