package io.docweave.batch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Per-invocation deadline state; timer signals never wait behind input-order result collection. */
final class DeadlineBatchRunner {
    /** Prevents instantiation of this static batch-execution helper. */
    private DeadlineBatchRunner() {
    }

    /**
     * Maps an input list in order under bounded concurrency and optional task/batch deadlines.
     * @param <I> input element type
     * @param <O> output result type
     * @param inputs ordered input elements to snapshot and process
     * @param options concurrency and deadline policy
     * @param operation per-item worker operation
     * @return immutable ordered results
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws ExecutionException if a task fails or a task/batch deadline expires
     */
    static <I, O> List<O> map(final List<I> inputs, final BatchOptions options,
            final BatchProcessor.Operation<I, O> operation) throws InterruptedException, ExecutionException {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(operation, "operation");
        final var snapshot = List.copyOf(inputs);
        if (snapshot.isEmpty()) {
            return List.of();
        }
        final var failed = new CompletableFuture<Throwable>();
        final var pending = new ArrayDeque<Task<O>>();
        final var output = new ArrayList<O>(snapshot.size());
        final var workers = Executors.newVirtualThreadPerTaskExecutor();
        final var timer = new ScheduledThreadPoolExecutor(
                1, Thread.ofPlatform().daemon().name("docweave-deadline-", 0).factory());
        timer.setRemoveOnCancelPolicy(true);
        final long batchStart = System.nanoTime();
        final long batchNanos = options.batchTimeout().toNanos();
        try {
            if (batchNanos > 0) {
                timer.schedule(() -> failed.complete(batchTimeout(options)), batchNanos, TimeUnit.NANOSECONDS);
            }
            // Seed up to maxInFlight virtual-thread workers before waiting on the oldest in-flight task.
            int submitted = 0;
            while (submitted < snapshot.size() && pending.size() < options.maxInFlight()) {
                submitted = submitTask(options, operation, failed, pending, submitted, snapshot, workers, timer);
            }
            // Collect results in input order while racing each head task against the shared batch-wide failure future.
            while (!pending.isEmpty()) {
                final var task = pending.getFirst();
                CompletableFuture.anyOf(task.result, failed).get();
                checkFailure(failed);
                output.add(task.result.get());
                pending.removeFirst();
                if (submitted < snapshot.size()) {
                    submitted = submitTask(options, operation, failed, pending, submitted, snapshot, workers, timer);
                }
            }
            if (batchNanos > 0 && System.nanoTime() - batchStart >= batchNanos) {
                failed.complete(batchTimeout(options));
            }
            checkFailure(failed);
            return List.copyOf(output);
        } finally {
            // Avoid try-with-resources: ExecutorService.close() waits even after shutdownNow(),
            // so an operation that ignores interruption would delay returning a timeout.
            pending.forEach(task -> {
                if (task.worker != null) {
                    task.worker.cancel(true);
                }
            });
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }

    /**
     * Submits the next indexed task from the input snapshot to a virtual-thread worker.
     * @param <I> input element type
     * @param <O> output result type
     * @param options concurrency and deadline policy
     * @param operation per-item worker operation
     * @param failed shared first-failure signal across the batch
     * @param pending queue of in-flight tasks in submission order
     * @param submitted index of the next item to submit
     * @param snapshot immutable snapshot of batch inputs
     * @param workers virtual-thread executor service
     * @param timer daemon scheduler for per-task deadlines
     * @return updated count of submitted tasks
     * @throws ExecutionException if a prior failure has already completed {@code failed}
     */
    private static <I, O> int submitTask(final BatchOptions options, final BatchProcessor.Operation<I, O> operation,
            final CompletableFuture<Throwable> failed, final ArrayDeque<Task<O>> pending, final int submitted,
            final List<I> snapshot, final ExecutorService workers, final ScheduledThreadPoolExecutor timer)
            throws ExecutionException {
        checkFailure(failed);
        final var task = new Task<O>();
        pending.addLast(task);
        final int index = submitted;
        final I input = snapshot.get(index);
        task.worker = workers.submit(() -> run(input, index, operation, options, timer, task.result, failed));
        return submitted + 1;
    }

    /**
     * Streams inputs through bounded virtual-thread workers and delivers results in input order to {@code consumer}.
     * @param <I> input element type
     * @param <O> output result type
     * @param inputs lazy or collection input source
     * @param options concurrency and deadline policy
     * @param operation per-item worker operation
     * @param consumer ordered result callback invoked on the calling thread
     * @throws InterruptedException if the calling thread or consumer is interrupted
     * @throws ExecutionException if a worker, consumer or deadline fails
     */
    static <I, O> void consume(final Iterable<I> inputs, final BatchOptions options,
            final BatchProcessor.Operation<I, O> operation, final BatchProcessor.ResultConsumer<? super O> consumer)
            throws InterruptedException, ExecutionException {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(consumer, "consumer");
        final var failed = new CompletableFuture<Throwable>();
        final var pending = new ArrayDeque<Task<O>>();
        final var workers = Executors.newVirtualThreadPerTaskExecutor();
        final var timer = new ScheduledThreadPoolExecutor(
                1, Thread.ofPlatform().daemon().name("docweave-delivery-", 0).factory());
        timer.setRemoveOnCancelPolicy(true);
        final long started = System.nanoTime();
        final long budget = options.batchTimeout().toNanos();
        try {
            if (budget > 0) {
                timer.schedule(() -> failed.complete(batchTimeout(options)), budget, TimeUnit.NANOSECONDS);
            }
            final var iterator = Objects.requireNonNull(inputs.iterator(), "iterator");
            int index = 0;
            boolean exhausted = false;
            // Alternate between filling the in-flight window from the input iterator and delivering the oldest
            // completed result to consumer.
            while (true) {
                checkElapsed(failed, options, started, budget);
                while (!exhausted && pending.size() < options.maxInFlight()) {
                    checkElapsed(failed, options, started, budget);
                    if (!iterator.hasNext()) {
                        exhausted = true;
                        break;
                    }
                    if (index == Integer.MAX_VALUE) {
                        throw new IllegalArgumentException("Too many batch inputs");
                    }
                    final I input = Objects.requireNonNull(iterator.next(), "input");
                    checkElapsed(failed, options, started, budget);
                    final int taskIndex = index++;
                    final var task = new Task<O>();
                    pending.addLast(task);
                    task.worker =
                            workers.submit(() -> run(input, taskIndex, operation, options, timer, task.result, failed));
                }
                checkElapsed(failed, options, started, budget);
                if (pending.isEmpty()) {
                    return;
                }
                final var task = pending.getFirst();
                CompletableFuture.anyOf(task.result, failed).get();
                checkElapsed(failed, options, started, budget);
                final O value = task.result.get();
                pending.removeFirst();
                try {
                    consumer.accept(value);
                } catch (final InterruptedException interrupted) {
                    throw interrupted;
                } catch (final Exception error) {
                    throw new ExecutionException(error);
                }
                // No replacement task is submitted until delivery has completed successfully.
                checkElapsed(failed, options, started, budget);
            }
        } finally {
            // Avoid try-with-resources: ExecutorService.close() waits even after shutdownNow(),
            // so an operation that ignores interruption would delay returning a timeout.
            pending.forEach(task -> {
                if (task.worker != null) {
                    task.worker.cancel(true);
                }
            });
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }

    /**
     * Polls caller interruption, overall batch elapsed time and any asynchronous worker failure.
     * @param failed shared failure future
     * @param options batch concurrency and timeout options
     * @param started monotonic start timestamp in nanoseconds
     * @param budget batch timeout in nanoseconds, or {@code 0} when disabled
     * @throws InterruptedException if the calling thread was interrupted
     * @throws ExecutionException if a task or batch timeout or worker failure occurred
     */
    private static void checkElapsed(final CompletableFuture<Throwable> failed, final BatchOptions options,
            final long started, final long budget) throws InterruptedException, ExecutionException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (budget > 0 && System.nanoTime() - started >= budget) {
            failed.complete(batchTimeout(options));
        }
        checkFailure(failed);
    }

    /**
     * Executes a single indexed operation on a worker thread with optional per-task timeout scheduling.
     * @param <I> input element type
     * @param <O> output result type
     * @param input task input value
     * @param index zero-based input position
     * @param operation operation to execute
     * @param options batch timeout configuration
     * @param timer scheduler used to trigger per-task timeout interruption
     * @param result future receiving the task output or failure
     * @param failed shared batch-wide first-failure future
     */
    private static <I, O> void run(final I input, int index, final BatchProcessor.Operation<I, O> operation,
            final BatchOptions options, final ScheduledThreadPoolExecutor timer, final CompletableFuture<O> result,
            final CompletableFuture<Throwable> failed) {
        final long started = System.nanoTime();
        final long nanos = options.taskTimeout().toNanos();
        final var thread = Thread.currentThread();
        java.util.concurrent.ScheduledFuture<?> alarm = null;
        try {
            if (failed.isDone()) {
                return;
            }
            // Schedule a per-task timeout alarm that completes both the task future and batch failure future and
            // interrupts the worker thread.
            if (nanos > 0) {
                alarm = timer.schedule(() -> {
                    final var timeout =
                            new BatchTimeoutException(BatchTimeoutException.Scope.TASK, index, options.taskTimeout());
                    if (result.completeExceptionally(timeout)) {
                        failed.complete(timeout);
                        thread.interrupt();
                    }
                }, Math.max(0, nanos - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
            }
            final var value = Objects.requireNonNull(operation.apply(input), "operation result");
            if (nanos > 0 && System.nanoTime() - started >= nanos) {
                final var timeout =
                        new BatchTimeoutException(BatchTimeoutException.Scope.TASK, index, options.taskTimeout());
                if (result.completeExceptionally(timeout)) {
                    failed.complete(timeout);
                }
            } else {
                result.complete(value);
            }
        } catch (final Throwable error) {
            final Throwable cause =
                    nanos > 0 && System.nanoTime() - started >= nanos
                            ? new BatchTimeoutException(BatchTimeoutException.Scope.TASK, index, options.taskTimeout())
                            : error;
            if (result.completeExceptionally(cause)) {
                failed.complete(cause);
            }
        } finally {
            if (alarm != null) {
                alarm.cancel(false);
            }
        }
    }

    /**
     * Rethrows the recorded failure wrapped in an {@link ExecutionException} if the batch has failed.
     * @param failed shared failure future
     * @throws ExecutionException if {@code failed} is completed
     */
    private static void checkFailure(final CompletableFuture<Throwable> failed) throws ExecutionException {
        if (failed.isDone()) {
            throw new ExecutionException(failed.join());
        }
    }

    /**
     * Creates a {@link BatchTimeoutException} representing an overall batch deadline expiration.
     * @param options batch options containing the configured batch timeout
     * @return batch-scope timeout exception
     */
    private static BatchTimeoutException batchTimeout(final BatchOptions options) {
        return new BatchTimeoutException(BatchTimeoutException.Scope.BATCH, -1, options.batchTimeout());
    }

    /**
     * In-flight task handle pairing the completion future with the underlying worker future.
     * @param <O> task result type
     */
    private static final class Task<O> {
        /** Completion future resolved with the task output or timeout/error. */
        private final CompletableFuture<O> result = new CompletableFuture<>();
        /** Submitted virtual-thread worker future used for cancellation. */
        private Future<?> worker;
    }
}
