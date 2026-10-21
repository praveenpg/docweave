package dev.docweave.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class BatchDeadlineTest {
    private BatchOptions timed(final int window) {
        return new BatchOptions(window, Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    private BatchTimeoutException timeout(final ExecutionException failure, final BatchTimeoutException.Scope scope) {
        final var cause = assertInstanceOf(BatchTimeoutException.class, failure.getCause());
        assertEquals(scope, cause.scope());
        return cause;
    }

    @Test
    void timedMappingUsesBoundedVirtualThreadsAndPreservesInputOrder() throws Exception {
        final var active = new AtomicInteger();
        final var peak = new AtomicInteger();
        final var inputs = IntStream.range(0, 25).boxed().toList();
        final var results = BatchProcessor.create().mapOrdered(inputs, timed(3), value -> {
            assertTrue(Thread.currentThread().isVirtual());
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(value % 3 * 2L);
                return value * 2;
            } finally {
                active.decrementAndGet();
            }
        });
        assertEquals(inputs.stream().map(i -> i * 2).toList(), results);
        assertTrue(peak.get() <= 3);
        assertThrows(UnsupportedOperationException.class, () -> results.clear());
    }

    @Test
    void taskDeadlineReportsIndexAndCancelsCooperativeWork() throws Exception {
        final var stopped = new CountDownLatch(1);
        final var options = new BatchOptions(1, Duration.ofMillis(150), Duration.ZERO);
        final var failure = assertThrows(
                ExecutionException.class, () -> BatchProcessor.create().mapOrdered(List.of("input"), options, input -> {
                    try {
                        new CountDownLatch(1).await();
                        return input;
                    } finally {
                        stopped.countDown();
                    }
                }));
        final var timeout = timeout(failure, BatchTimeoutException.Scope.TASK);
        assertEquals(0, timeout.inputIndex());
        assertEquals(options.taskTimeout(), timeout.timeout());
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
    }

    @Test
    void batchDeadlineCancelsEveryRunningTask() throws Exception {
        final var stopped = new CountDownLatch(2);
        final var bothStarted = new CountDownLatch(2);
        final var options = new BatchOptions(2, Duration.ofSeconds(3), Duration.ofMillis(250));
        final var failure = assertThrows(
                ExecutionException.class, () -> BatchProcessor.create().mapOrdered(List.of(0, 1), options, input -> {
                    bothStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                        return input;
                    } finally {
                        stopped.countDown();
                    }
                }));
        assertEquals(0, bothStarted.getCount());
        final var timeout = timeout(failure, BatchTimeoutException.Scope.BATCH);
        assertEquals(-1, timeout.inputIndex());
        assertEquals(options.batchTimeout(), timeout.timeout());
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
    }

    @Test
    void overallBudgetDoesNotResetForEachSequentialTask() {
        final var calls = new AtomicInteger();
        final var options = new BatchOptions(1, Duration.ofSeconds(2), Duration.ofMillis(250));
        final var failure = assertThrows(ExecutionException.class,
                () -> BatchProcessor.create().mapOrdered(List.of(0, 1, 2, 3, 4), options, input -> {
                    calls.incrementAndGet();
                    Thread.sleep(150);
                    return input;
                }));
        timeout(failure, BatchTimeoutException.Scope.BATCH);
        assertTrue(calls.get() < 5);
    }

    @Test
    void taskBudgetRestartsWhenNextWorkerBegins() throws Exception {
        final var options = new BatchOptions(1, Duration.ofMillis(400), Duration.ZERO);
        final var inputs = List.of(0, 1, 2);
        final var results = BatchProcessor.create().mapOrdered(inputs, options, value -> {
            Thread.sleep(160);
            return value;
        });
        assertEquals(inputs, results, "Total duration exceeds a task budget but each worker stays within it");
    }

    @Test
    void laterFailureIsObservedWithoutWaitingForBlockedFirstInput() throws Exception {
        final var firstStarted = new CountDownLatch(1);
        final var firstStopped = new CountDownLatch(1);
        final var problem = new IOException("later failure");
        final var failure = assertThrows(
                ExecutionException.class, () -> BatchProcessor.create().mapOrdered(List.of(0, 1), timed(2), value -> {
                    if (value == 0) {
                        firstStarted.countDown();
                        try {
                            new CountDownLatch(1).await();
                            return value;
                        } finally {
                            firstStopped.countDown();
                        }
                    }
                    firstStarted.await();
                    throw problem;
                }));
        assertSame(problem, failure.getCause());
        assertTrue(firstStopped.await(2, TimeUnit.SECONDS));
    }

    @Test
    void timeoutReturnsWhileInterruptIgnoringOperationStillNeedsExplicitRelease() throws Exception {
        final var release = new CountDownLatch(1);
        final var stopped = new CountDownLatch(1);
        try {
            final var failure = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    ()
                            -> assertThrows(ExecutionException.class,
                                    ()
                                            -> BatchProcessor.create().mapOrdered(List.of(1),
                                                    new BatchOptions(1, Duration.ofMillis(150), Duration.ZERO),
                                                    input -> {
                                                        try {
                                                            while (release.getCount() != 0) {
                                                                try {
                                                                    release.await();
                                                                } catch (final InterruptedException
                                                                                ignored) { /* Deliberately
                                                                                              uncooperative. */
                                                                }
                                                            }
                                                            return input;
                                                        } finally {
                                                            stopped.countDown();
                                                        }
                                                    })));
            timeout(failure, BatchTimeoutException.Scope.TASK);
            assertEquals(1, stopped.getCount(), "The API did not pretend to forcibly terminate the operation");
        } finally {
            release.countDown();
        }
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
    }

    @Test
    void callerInterruptionCancelsTimedWork() throws Exception {
        final var started = new CountDownLatch(1);
        final var stopped = new CountDownLatch(1);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var caller = executor.submit(() -> BatchProcessor.create().mapOrdered(List.of(0), timed(1), input -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                    return input;
                } finally {
                    stopped.countDown();
                }
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            caller.cancel(true);
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void disabledDeadlinesEmptyInputAndReuseRetainTheirContracts() throws Exception {
        final var processor = BatchProcessor.create();
        assertFalse(new BatchOptions(2).hasDeadlines());
        assertEquals(List.of(2, 4), processor.mapOrdered(List.of(1, 2), new BatchOptions(2), value -> value * 2));
        assertEquals(List.of(), processor.mapOrdered(List.of(),
                                        new BatchOptions(1, Duration.ofNanos(1), Duration.ofNanos(1)), value -> {
                                            fail("Empty inputs must not run tasks");
                                            return value;
                                        }));
        assertThrows(ExecutionException.class,
                ()
                        -> processor.mapOrdered(
                                List.of(0), new BatchOptions(1, Duration.ofMillis(50), Duration.ZERO), input -> {
                                    new CountDownLatch(1).await();
                                    return input;
                                }));
        assertEquals(List.of(1), processor.mapOrdered(List.of(1), timed(1), input -> input));
    }

    @Test
    void policyValidationAndTimeoutFieldsAreExplicit() {
        assertThrows(IllegalArgumentException.class, () -> new BatchOptions(0));
        assertThrows(IllegalArgumentException.class, () -> new BatchOptions(1, Duration.ofNanos(-1), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new BatchOptions(1, Duration.ZERO, Duration.ofSeconds(Long.MAX_VALUE)));
        assertThrows(NullPointerException.class, () -> new BatchOptions(1, null, Duration.ZERO));
        assertThrows(NullPointerException.class,
                () -> BatchProcessor.create().mapOrdered(List.of(), (BatchOptions) null, input -> input));
        assertThrows(NullPointerException.class,
                () -> BatchProcessor.create().mapOrdered(java.util.Arrays.asList(1, null), timed(1), input -> input));
        assertThrows(IllegalArgumentException.class,
                () -> new BatchTimeoutException(BatchTimeoutException.Scope.TASK, -1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new BatchTimeoutException(BatchTimeoutException.Scope.BATCH, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new BatchTimeoutException(BatchTimeoutException.Scope.BATCH, -1, Duration.ZERO));
    }

    @Test
    void customLegacyImplementationNeverSilentlyIgnoresDeadlines() throws Exception {
        final BatchProcessor custom = new BatchProcessor() {
            @Override
            public <I, O> List<O> mapOrdered(
                    final List<I> inputs, final int maxInFlight, final Operation<I, O> operation) {
                return List.of();
            }
        };
        assertEquals(List.of(), custom.mapOrdered(List.of(), new BatchOptions(1), input -> input));
        assertThrows(UnsupportedOperationException.class, () -> custom.mapOrdered(List.of(), timed(1), input -> input));
    }

    @Test
    void nullTimedResultIsReportedAsOriginalWorkerFailure() {
        final var failure = assertThrows(ExecutionException.class,
                () -> BatchProcessor.create().mapOrdered(List.of(0), timed(1), input -> null));
        assertInstanceOf(NullPointerException.class, failure.getCause());
    }

    @Test
    void concurrentTimedInvocationsHaveIndependentDeadlineState() throws Exception {
        final var processor = BatchProcessor.create();
        final var inputs = IntStream.range(0, 12).boxed().toList();
        final var results = processor.mapOrdered(
                inputs, 3, input -> processor.mapOrdered(List.of(input), timed(1), value -> value));
        assertEquals(inputs.stream().map(List::of).toList(), results);
    }
}
