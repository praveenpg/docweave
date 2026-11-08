package io.docweave.partition;

import io.docweave.DocWeave;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Progress reports and cancellation checkpoints of the whole-document, range, prepared and counting paths. */
class PdfRangeProgressTest {
    /** Default document options. */
    private static final PartitionOptions OPTIONS = PartitionOptions.builder().build();

    /** Creates the test. */
    PdfRangeProgressTest() {
    }

    /**
     * Whole-document progress declares every page and counts each once, in phase order; the range, prepared and
     * counting paths declare and count the range's pages.
     * @throws Exception if partitioning fails
     */
    @Test
    void progressCountsPagesOfEachPath() throws Exception {
        final var pdf = PdfWholeDocumentGoldenTest.ocrPages(List.of(1, 2, 3, 4, 5, 6, 7));
        final var partitioner = PdfBudgetHookTest.ocr(PdfOcrOptions.ReadingOrder.GEOMETRIC);
        final var whole = new CopyOnWriteArrayList<PartitionProgress>();
        DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                .partition(DocumentSource.bytes(pdf, "p.pdf"), OPTIONS, whole::add);
        assertPages(whole, 7);
        final var range = new CopyOnWriteArrayList<PartitionProgress>();
        partitioner.partitionRange(
                DocumentSource.bytes(pdf, "p.pdf"), OPTIONS, new PdfPageRange(2, 5), PdfBudget.unlimited(), range::add);
        assertPages(range, 4);
        final var prepared = partitioner.prepare(DocumentSource.bytes(pdf, "p.pdf"), OPTIONS);
        final var reused = new CopyOnWriteArrayList<PartitionProgress>();
        partitioner.partitionRange(prepared, new PdfPageRange(6, 7), PdfBudget.unlimited(), reused::add);
        assertPages(reused, 2);
        final var counted = new CopyOnWriteArrayList<PartitionProgress>();
        partitioner.countRange(prepared, new PdfPageRange(1, 3), PdfBudget.unlimited(), counted::add);
        assertPages(counted, 3);
    }

    /**
     * An interrupt before the call, during replay (raised by a budget charge) or before assembly stops each path at
     * a checkpoint with {@link InterruptedIOException}; the flag stays set and nothing is returned.
     * @throws Exception if authoring fails
     */
    @Test
    void interruptionStopsEveryNewPath() throws Exception {
        final var pdf = PdfWholeDocumentGoldenTest.ocrPages(List.of(3, 3, 3, 3, 3, 3));
        final var partitioner = PdfBudgetHookTest.ocr(PdfOcrOptions.ReadingOrder.SOURCE_ORDER);
        final var source = DocumentSource.bytes(pdf, "i.pdf");
        final var prepared = partitioner.prepare(source, OPTIONS);
        final var whole = partitioner.partitionRange(source, OPTIONS, new PdfPageRange(1, 6), PdfBudget.unlimited());
        final List<Call> calls = List.of(
                ()
                        -> partitioner.partitionRange(source, OPTIONS, new PdfPageRange(1, 3), PdfBudget.unlimited()),
                ()
                        -> partitioner.partitionRange(prepared, new PdfPageRange(1, 3), PdfBudget.unlimited(), null),
                ()
                        -> partitioner.countRange(prepared, new PdfPageRange(1, 3), PdfBudget.unlimited(), null),
                ()
                        -> partitioner.prepare(source, OPTIONS),
                ()
                        -> PdfRangeAssembler.create().assemble(List.of(whole)),
                () -> DocWeave.create(PartitionerRegistry.of(List.of(partitioner))).partition(source));
        for (final var call : calls) {
            try {
                Thread.currentThread().interrupt();
                assertThrows(InterruptedIOException.class, call::run);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
        final PdfBudget interrupting = (kind, amount, page) -> {
            if (kind == PdfBudget.Kind.ELEMENTS && page == 2) {
                Thread.currentThread().interrupt();
            }
        };
        for (final Call call : List.<Call>of(
                     ()
                             -> partitioner.partitionRange(source, OPTIONS, new PdfPageRange(1, 6), interrupting),
                     () -> partitioner.countRange(prepared, new PdfPageRange(1, 6), interrupting, null))) {
            try {
                assertThrows(InterruptedIOException.class, call::run);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
        final var session = PdfRangeAssembler.create().open(element -> Thread.currentThread().interrupt());
        try {
            assertThrows(InterruptedIOException.class, () -> session.accept(whole));
        } finally {
            Thread.interrupted();
        }
        assertThrows(IllegalStateException.class, session::finish);
    }

    /**
     * Asserts determinate page progress: declared total, each page counted once, phases in order, DONE last.
     * @param progress reports
     * @param pages expected total
     */
    private static void assertPages(final List<PartitionProgress> progress, final int pages) {
        final var phases = progress.stream().map(PartitionProgress::phase).distinct().toList();
        assertEquals(List.of(PartitionProgress.Phase.PREPARING, PartitionProgress.Phase.EXTRACTING,
                             PartitionProgress.Phase.ASSEMBLING, PartitionProgress.Phase.DONE),
                phases);
        final var counted = progress.stream()
                                    .filter(value -> value.phase() == PartitionProgress.Phase.EXTRACTING)
                                    .filter(value -> "pages".equals(value.unit()))
                                    .map(PartitionProgress::completed)
                                    .toList();
        assertEquals(java.util.stream.LongStream.rangeClosed(0, pages).boxed().toList(), counted);
        assertTrue(progress.stream().filter(value -> "pages".equals(value.unit())).allMatch(v -> v.total() == pages));
        assertEquals(pages, progress.getLast().completed());
    }

    /** A call that may fail. */
    @FunctionalInterface
    private interface Call {
        /**
         * Runs the call.
         * @throws IOException if it fails
         */
        void run() throws IOException;
    }
}
