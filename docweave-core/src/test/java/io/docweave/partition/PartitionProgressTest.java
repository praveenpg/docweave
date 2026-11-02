package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/** Core progress contract: phase order, exact unit counting, isolation and end-of-call silence. */
class PartitionProgressTest {
    /** Creates independent progress controls. */
    PartitionProgressTest() {
    }

    /**
     * Units count once and in range; ASSEMBLING and DONE follow extraction and carry the final counters.
     * @throws Exception if partitioning fails
     */
    @Test
    void reportsOrderedPhasesAndCountsEachUnitOnce() throws Exception {
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        processor(context -> {
            final var progress = context.progress();
            progress.unitCompleted(0);
            progress.beginUnits("pages", 3);
            progress.beginUnits("rows", 9);
            for (int index = 0; index < 3; index++) {
                context.emit(ElementType.NarrativeText, "Unit " + index, Map.of());
                progress.unitCompleted(index);
                progress.unitCompleted(index);
            }
            progress.unitCompleted(3);
        }).partition(source(), PartitionOptions.defaults(), events::add);
        assertEquals(
                List.of(PartitionProgress.indeterminate(PartitionProgress.Phase.PREPARING),
                        PartitionProgress.indeterminate(PartitionProgress.Phase.EXTRACTING),
                        units(PartitionProgress.Phase.EXTRACTING, 0, 3),
                        units(PartitionProgress.Phase.EXTRACTING, 1, 3),
                        units(PartitionProgress.Phase.EXTRACTING, 2, 3),
                        units(PartitionProgress.Phase.EXTRACTING, 3, 3),
                        units(PartitionProgress.Phase.ASSEMBLING, 3, 3), units(PartitionProgress.Phase.DONE, 3, 3)),
                events);
    }

    /**
     * Strategies without a reliable unit count are reported with indeterminate phases only.
     * @throws Exception if partitioning fails
     */
    @Test
    void strategiesWithoutUnitsReportIndeterminatePhases() throws Exception {
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        processor(context -> context.emit(ElementType.NarrativeText, "Body", Map.of()))
                .partition(source(), PartitionOptions.defaults(), events::add);
        assertEquals(List.of(PartitionProgress.Phase.values()), events.stream().map(PartitionProgress::phase).toList());
        assertTrue(events.stream().noneMatch(PartitionProgress::determinate));
        assertTrue(events.stream().allMatch(event -> event.unit() == null && event.completed() == 0));
    }

    /**
     * Concurrent and repeated completions are counted exactly once, delivered serially and monotonically.
     * @throws Exception if partitioning or worker joining fails
     */
    @Test
    void concurrentCompletionsCountExactlyOnce() throws Exception {
        final int total = 200;
        final var events = Collections.synchronizedList(new ArrayList<PartitionProgress>());
        final var inside = new AtomicBoolean();
        final var overlap = new AtomicBoolean();
        processor(context -> {
            final var progress = context.progress();
            progress.beginUnits("pages", total);
            try (final var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int worker = 0; worker < 16; worker++) {
                    final var order = new ArrayList<>(IntStream.range(0, total).boxed().toList());
                    Collections.shuffle(order, new java.util.Random(worker));
                    workers.submit(() -> order.forEach(progress::unitCompleted));
                }
            }
        }).partition(source(), PartitionOptions.defaults(), event -> {
            if (!inside.compareAndSet(false, true)) {
                overlap.set(true);
            }
            events.add(event);
            inside.set(false);
        });
        assertFalse(overlap.get(), "listener invoked concurrently");
        final var counts = events.stream()
                                   .filter(event -> event.phase() == PartitionProgress.Phase.EXTRACTING)
                                   .filter(PartitionProgress::determinate)
                                   .map(PartitionProgress::completed)
                                   .toList();
        assertEquals(LongStream.rangeClosed(0, total).boxed().toList(), counts);
        assertTrue(events.stream().filter(PartitionProgress::determinate).allMatch(event -> event.total() == total));
        assertEquals(units(PartitionProgress.Phase.DONE, total, total), events.getLast());
    }

    /**
     * Failure stops reporting without ASSEMBLING/DONE, and a retained reporter is silent after the call.
     * @throws Exception if the test strategy cannot be built
     */
    @Test
    void failureStopsReportingAndRetainedReportersAreSilent() throws Exception {
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        final var retained = new AtomicReference<PartitionProgressReporter>();
        final var failure = assertThrows(PartitionException.class, () -> processor(context -> {
            retained.set(context.progress());
            context.progress().beginUnits("pages", 3);
            context.progress().unitCompleted(0);
            throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, "owned failure");
        }).partition(source(), PartitionOptions.defaults(), events::add));
        assertEquals("owned failure", failure.getMessage());
        final var expected = List.of(PartitionProgress.indeterminate(PartitionProgress.Phase.PREPARING),
                PartitionProgress.indeterminate(PartitionProgress.Phase.EXTRACTING),
                units(PartitionProgress.Phase.EXTRACTING, 0, 3), units(PartitionProgress.Phase.EXTRACTING, 1, 3));
        assertEquals(expected, events);
        retained.get().unitCompleted(1);
        retained.get().unitCompleted(2);
        assertEquals(expected, events, "retained reporter must be silent after the call failed");

        final var success = new CopyOnWriteArrayList<PartitionProgress>();
        processor(context -> {
            retained.set(context.progress());
            context.progress().beginUnits("pages", 2);
            context.progress().unitCompleted(0);
        }).partition(source(), PartitionOptions.defaults(), success::add);
        final int completed = success.size();
        retained.get().unitCompleted(1);
        assertEquals(completed, success.size());
        assertEquals(units(PartitionProgress.Phase.DONE, 1, 2), success.getLast());
    }

    /**
     * Cancellation requested from a listener stops reporting; the call fails at the next checkpoint.
     * @throws Exception if the test strategy cannot be built
     */
    @Test
    void cancellationStopsReporting() throws Exception {
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        final var processor = processor(context -> {
            context.progress().beginUnits("pages", 3);
            for (int index = 0; index < 3; index++) {
                context.emit(ElementType.NarrativeText, "Unit " + index, Map.of());
                context.progress().unitCompleted(index);
            }
        });
        try {
            assertThrows(InterruptedIOException.class,
                    () -> processor.partition(source(), PartitionOptions.defaults(), event -> {
                        events.add(event);
                        if (event.completed() == 1) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(units(PartitionProgress.Phase.EXTRACTING, 1, 3), events.getLast());
        assertTrue(events.stream().noneMatch(event -> event.phase().compareTo(PartitionProgress.Phase.EXTRACTING) > 0));
    }

    /**
     * Listener runtime failures never alter the result, and later events are still offered.
     * @throws Exception if partitioning fails
     */
    @Test
    void throwingListenerIsIsolatedAndOutputIsUnchanged() throws Exception {
        final var processor = processor(context -> {
            context.progress().beginUnits("pages", 2);
            for (int index = 0; index < 2; index++) {
                context.emit(ElementType.NarrativeText, "Unit " + index, Map.of());
                context.progress().unitCompleted(index);
            }
        });
        final var plain = processor.partition(source(), PartitionOptions.defaults());
        final var offered = new CopyOnWriteArrayList<PartitionProgress>();
        final var observed = processor.partition(source(), PartitionOptions.defaults(), event -> {
            offered.add(event);
            throw new IllegalStateException("listener failure");
        });
        assertEquals(plain.elements(), observed.elements());
        assertEquals(plain.warnings(), observed.warnings());
        assertEquals(PartitionProgress.Phase.DONE, offered.getLast().phase());
        assertEquals(7, offered.size());
    }

    /**
     * Custom facades inherit coarse reporting, and argument contracts hold with or without a listener.
     * @throws Exception if partitioning fails
     */
    @Test
    void defaultFacadeAndArgumentContracts() throws Exception {
        final var result = new PartitionResult(List.of(), DocumentFormat.TXT, "UTF-8", 0, Duration.ZERO, List.of());
        final DocWeave custom = (source, options) -> result;
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        assertSame(result, custom.partition(source(), PartitionOptions.defaults(), events::add));
        assertEquals(List.of(PartitionProgress.indeterminate(PartitionProgress.Phase.PREPARING),
                             PartitionProgress.indeterminate(PartitionProgress.Phase.DONE)),
                events);
        assertThrows(NullPointerException.class, () -> custom.partition(source(), PartitionOptions.defaults(), null));
        assertThrows(NullPointerException.class,
                () -> processor(context -> {}).partition(source(), PartitionOptions.defaults(), null));

        final var none = PartitionProgressReporter.none();
        assertThrows(NullPointerException.class, () -> none.beginUnits(null, 1));
        assertThrows(IllegalArgumentException.class, () -> none.beginUnits(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> none.beginUnits("pages", -1));
        assertThrows(IllegalArgumentException.class, () -> none.unitCompleted(-1));
        none.beginUnits("pages", 1);
        none.unitCompleted(0);

        assertThrows(NullPointerException.class, () -> PartitionProgress.indeterminate(null));
        assertThrows(IllegalArgumentException.class,
                () -> new PartitionProgress(PartitionProgress.Phase.EXTRACTING, "pages", 4, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new PartitionProgress(PartitionProgress.Phase.EXTRACTING, null, 1, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new PartitionProgress(PartitionProgress.Phase.EXTRACTING, "", 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PartitionProgress(PartitionProgress.Phase.EXTRACTING, "pages", -1, 1));
        assertTrue(units(PartitionProgress.Phase.EXTRACTING, 0, 0).determinate());
        assertFalse(new PartitionProgress(PartitionProgress.Phase.EXTRACTING, "units", 5, -1).determinate());
    }

    /**
     * Undeclared checked exceptions and non-fatal errors from a listener are isolated, for the engine and the
     * default facade overload.
     * @throws Exception if partitioning fails
     */
    @Test
    void sneakyCheckedAndErrorListenerFailuresAreIsolated() throws Exception {
        final var processor = processor(context -> {
            context.progress().beginUnits("pages", 2);
            for (int index = 0; index < 2; index++) {
                context.emit(ElementType.NarrativeText, "Unit " + index, Map.of());
                context.progress().unitCompleted(index);
            }
        });
        final var plain = processor.partition(source(), PartitionOptions.defaults());
        final var offered = new CopyOnWriteArrayList<PartitionProgress>();
        final var sneaky = processor.partition(source(), PartitionOptions.defaults(), event -> {
            offered.add(event);
            PartitionProgressTest.<RuntimeException>sneakyThrow(new IOException("listener checked failure"));
        });
        assertEquals(plain.elements(), sneaky.elements());
        assertEquals(plain.warnings(), sneaky.warnings());
        assertEquals(7, offered.size());
        assertEquals(PartitionProgress.Phase.DONE, offered.getLast().phase());
        final var errors = processor.partition(
                source(), PartitionOptions.defaults(), event -> { throw new AssertionError("listener error"); });
        assertEquals(plain.elements(), errors.elements());
        assertThrows(OutOfMemoryError.class, () -> processor.partition(source(), PartitionOptions.defaults(), event -> {
            throw new OutOfMemoryError("fatal listener error");
        }));

        final var result = new PartitionResult(List.of(), DocumentFormat.TXT, "UTF-8", 0, Duration.ZERO, List.of());
        final DocWeave custom = (source, options) -> result;
        assertSame(result, custom.partition(source(), PartitionOptions.defaults(), event -> {
            PartitionProgressTest.<RuntimeException>sneakyThrow(new IOException("listener checked failure"));
        }));
    }

    /**
     * An interrupt after the last extraction checkpoint does not drop DONE: the call returns its result.
     * @throws Exception if partitioning fails
     */
    @Test
    void doneAccompaniesEveryReturnedResult() throws Exception {
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        try {
            final var result = processor(context -> context.emit(ElementType.NarrativeText, "Body", Map.of()))
                                       .partition(source(), PartitionOptions.defaults(), event -> {
                                           events.add(event);
                                           if (event.phase() == PartitionProgress.Phase.ASSEMBLING) {
                                               Thread.currentThread().interrupt();
                                           }
                                       });
            assertEquals(1, result.elements().size());
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag is preserved for the caller");
        } finally {
            Thread.interrupted();
        }
        assertEquals(List.of(PartitionProgress.Phase.values()), events.stream().map(PartitionProgress::phase).toList());
    }

    /**
     * Cancellation observed on a worker thread latches: later extraction reports from other threads are discarded.
     * @throws Exception if partitioning or worker joining fails
     */
    @Test
    void workerCancellationStopsExtractionReportsFromEveryThread() throws Exception {
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        processor(context -> {
            final var progress = context.progress();
            progress.beginUnits("pages", 3);
            progress.unitCompleted(0);
            final var worker = Thread.ofVirtual().start(() -> {
                Thread.currentThread().interrupt();
                progress.unitCompleted(1);
            });
            try {
                worker.join();
            } catch (final InterruptedException failure) {
                throw new AssertionError(failure);
            }
            progress.unitCompleted(2);
        }).partition(source(), PartitionOptions.defaults(), events::add);
        assertEquals(
                List.of(PartitionProgress.indeterminate(PartitionProgress.Phase.PREPARING),
                        PartitionProgress.indeterminate(PartitionProgress.Phase.EXTRACTING),
                        units(PartitionProgress.Phase.EXTRACTING, 0, 3),
                        units(PartitionProgress.Phase.EXTRACTING, 1, 3),
                        units(PartitionProgress.Phase.ASSEMBLING, 1, 3), units(PartitionProgress.Phase.DONE, 1, 3)),
                events);
    }

    /**
     * Remote sources report DONE only after the resolved handle was released; a release failure fails the call
     * without DONE.
     * @throws Exception if partitioning fails unexpectedly
     */
    @Test
    void remoteSourcesReportDoneOnlyAfterRelease() throws Exception {
        final var released = new java.util.concurrent.atomic.AtomicInteger();
        final var success = new CopyOnWriteArrayList<PartitionProgress>();
        final var releasedAtDone = new CopyOnWriteArrayList<Integer>();
        // Listener failures are isolated, so observations are captured and asserted after the call.
        final var result =
                remote(() -> released.incrementAndGet())
                        .partition(DocumentSource.remote("owned://ok"), PartitionOptions.defaults(), event -> {
                            if (event.phase() == PartitionProgress.Phase.DONE) {
                                releasedAtDone.add(released.get());
                            }
                            success.add(event);
                        });
        assertEquals(List.of(1), releasedAtDone, "DONE must follow release");
        assertEquals(1, released.get());
        assertNotNull(result.sourceProvenance());
        assertEquals(
                List.of(PartitionProgress.Phase.values()), success.stream().map(PartitionProgress::phase).toList());

        final var failing = new CopyOnWriteArrayList<PartitionProgress>();
        final var failure = assertThrows(IOException.class, () -> remote(() -> {
            throw new IOException("owned release failure");
        }).partition(DocumentSource.remote("owned://fails"), PartitionOptions.defaults(), failing::add));
        assertEquals("owned release failure", failure.getMessage());
        assertEquals(List.of(PartitionProgress.Phase.PREPARING, PartitionProgress.Phase.EXTRACTING,
                             PartitionProgress.Phase.ASSEMBLING),
                failing.stream().map(PartitionProgress::phase).toList());
    }

    /** Progress contracts are interfaces and values; the tracker stays hidden. */
    @Test
    void publicContractsKeepImplementationsHidden() {
        assertTrue(PartitionProgressListener.class.isInterface());
        assertTrue(PartitionProgressReporter.class.isInterface());
        assertTrue(PartitionProgress.class.isRecord());
        assertFalse(java.lang.reflect.Modifier.isPublic(PartitionProgressTracker.class.getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(PartitionProgressReporter.none().getClass().getModifiers()));
    }

    /**
     * Builds one determinate snapshot.
     * @param phase phase
     * @param completed completed units
     * @param total unit total
     * @return pages snapshot
     */
    private static PartitionProgress units(
            final PartitionProgress.Phase phase, final long completed, final long total) {
        return new PartitionProgress(phase, "pages", completed, total);
    }

    /**
     * Builds a processor whose remote resolver returns owned text with the given release action.
     * @param release release action run when the engine closes the handle
     * @return processor with one owned TXT strategy
     */
    private static DocWeave remote(final java.io.Closeable release) {
        final var registry = PartitionerRegistry.of(List.of(new Partitioner() {
            /**
             * Supplies the TXT key.
             * @return TXT
             */
            @Override
            public DocumentFormat format() {
                return DocumentFormat.TXT;
            }

            /**
             * Emits one element.
             * @param context synchronous document context
             * @throws IOException if emission fails
             */
            @Override
            public void partition(final PartitionContext context) throws IOException {
                context.emit(ElementType.NarrativeText, context.text(), Map.of());
            }
        }));
        return DocWeave.builder()
                .registry(registry)
                .remoteSources(
                        (source, context)
                                -> ResolvedDocument.of(DocumentSource.text("remote body"), "remote.txt",
                                        new SourceProvenance("test", "owned://x", null, null, null, 11, null), release))
                .build();
    }

    /**
     * Throws any throwable without declaring it, as listeners written in other JVM languages can.
     * @param failure throwable to raise
     * @param <T> inferred unchecked type
     * @throws T always
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(final Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * Supplies a small owned text source.
     * @return text source with a TXT filename
     */
    private static DocumentSource source() {
        return DocumentSource.text("owned", "owned.txt");
    }

    /**
     * Installs one owned TXT strategy.
     * @param body synchronous extraction body
     * @return reusable public facade
     */
    private static DocWeave processor(final Body body) {
        final Partitioner parser = new Partitioner() {
            /**
             * Supplies the TXT key.
             * @return TXT
             */
            @Override
            public DocumentFormat format() {
                return DocumentFormat.TXT;
            }

            /**
             * Runs the owned body.
             * @param context synchronous document context
             * @throws IOException if the body fails
             */
            @Override
            public void partition(final PartitionContext context) throws IOException {
                body.run(context);
            }
        };
        return DocWeave.create(PartitionerRegistry.of(List.of(parser)));
    }

    /** Owned strategy body that may throw checked failures. */
    @FunctionalInterface
    private interface Body {
        /**
         * Extracts through the context.
         * @param context synchronous document context
         * @throws IOException if extraction fails
         */
        void run(PartitionContext context) throws IOException;
    }
}
