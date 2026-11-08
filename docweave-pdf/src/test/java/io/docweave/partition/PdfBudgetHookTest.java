package io.docweave.partition;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Exact charges, page completion signals, the memory ceiling and unchanged propagation of budget exceptions. */
class PdfBudgetHookTest {
    /** Default document options. */
    private static final PartitionOptions OPTIONS = PartitionOptions.builder().build();

    /** Creates the test. */
    PdfBudgetHookTest() {
    }

    /**
     * A recording budget sees exactly one PAGE_ELEMENTS, PAGE_PENDING and ELEMENTS charge per element (pending only
     * when buffered), image bytes on image pages, and one completion per page in page order after its charges.
     * @throws Exception if partitioning fails
     */
    @Test
    void recordingBudgetSeesExactChargesAndOrderedCompletions() throws Exception {
        for (final var order : List.of(PdfOcrOptions.ReadingOrder.SOURCE_ORDER, PdfOcrOptions.ReadingOrder.GEOMETRIC)) {
            final var counts = List.of(3, 0, 5, 2, 7, 1, 4);
            final var recording = new Recording();
            final var partitioner = ocr(order);
            final var result = partitioner.partitionRange(
                    DocumentSource.bytes(PdfWholeDocumentGoldenTest.ocrPages(counts), "pages.pdf"), OPTIONS,
                    new PdfPageRange(2, 6), recording);
            assertEquals(List.of(2, 3, 4, 5, 6), recording.completed);
            final boolean buffered = order == PdfOcrOptions.ReadingOrder.GEOMETRIC;
            for (int page = 2; page <= 6; page++) {
                final long elements = counts.get(page - 1);
                final var expected = new TreeMap<PdfBudget.Kind, Long>();
                if (elements > 0) {
                    expected.put(PdfBudget.Kind.PAGE_ELEMENTS, elements);
                    expected.put(PdfBudget.Kind.ELEMENTS, elements);
                    if (buffered) {
                        expected.put(PdfBudget.Kind.PAGE_PENDING, elements);
                    }
                }
                assertEquals(expected, recording.totals(page), "page " + page);
                assertEquals(Map.copyOf(expected), Map.copyOf(result.pages().get(page - 2).usage()));
            }
            // Every ordered charge of a page precedes that page's completion; completions follow page order.
            int lastCompletion = -1;
            for (final var event : recording.events) {
                if (event.kind() == null) {
                    assertTrue(event.page() > lastCompletion);
                    lastCompletion = event.page();
                } else if (event.kind() == PdfBudget.Kind.ELEMENTS || event.kind() == PdfBudget.Kind.PAGE_PENDING) {
                    assertTrue(event.page() > lastCompletion, "ordered charge after completion");
                }
            }
        }
        final var images = new Recording();
        final var imageCase = PdfRangeCorpus.cases()
                                      .stream()
                                      .filter(value -> value.name().equals("images-bytes"))
                                      .findFirst()
                                      .orElseThrow();
        imageCase.partitioner().get().partitionRange(
                DocumentSource.bytes(imageCase.pdf(), imageCase.filename()), OPTIONS, new PdfPageRange(1, 9), images);
        for (int page = 1; page <= 9; page++) {
            assertEquals(page != 8, images.totals(page).containsKey(PdfBudget.Kind.IMAGE_BYTES), "page " + page);
        }
    }

    /**
     * Counting charges PAGE_ELEMENTS once per element but PAGE_PENDING (buffered only) and ELEMENTS once per page with
     * the page total, so totals equal partitioning.
     * @throws Exception if partitioning fails
     */
    @Test
    void countingChargesOrderedKindsOncePerPage() throws Exception {
        final var counts = List.of(3, 0, 5, 2);
        for (final var order : List.of(PdfOcrOptions.ReadingOrder.SOURCE_ORDER, PdfOcrOptions.ReadingOrder.GEOMETRIC)) {
            final var partitioner = ocr(order);
            final var prepared = partitioner.prepare(
                    DocumentSource.bytes(PdfWholeDocumentGoldenTest.ocrPages(counts), "count.pdf"), OPTIONS);
            final var recording = new Recording();
            partitioner.countRange(prepared, new PdfPageRange(1, 4), recording, null);
            final boolean buffered = order == PdfOcrOptions.ReadingOrder.GEOMETRIC;
            for (int page = 1; page <= 4; page++) {
                final int elements = counts.get(page - 1);
                final int current = page;
                final var events = recording.events.stream().filter(event -> event.page() == current).toList();
                assertEquals(Collections.nCopies(elements, new Event(PdfBudget.Kind.PAGE_ELEMENTS, 1, page)),
                        events.stream().filter(event -> event.kind() == PdfBudget.Kind.PAGE_ELEMENTS).toList());
                final var expectedOrdered = new ArrayList<Event>();
                if (elements > 0 && buffered) {
                    expectedOrdered.add(new Event(PdfBudget.Kind.PAGE_PENDING, elements, page));
                }
                if (elements > 0) {
                    expectedOrdered.add(new Event(PdfBudget.Kind.ELEMENTS, elements, page));
                }
                assertEquals(expectedOrdered,
                        events.stream()
                                .filter(event
                                        -> event.kind() == PdfBudget.Kind.PAGE_PENDING
                                                   || event.kind() == PdfBudget.Kind.ELEMENTS)
                                .toList(),
                        order + " page " + page);
            }
            assertEquals(List.of(1, 2, 3, 4), recording.completed);
        }
    }

    /**
     * A page of a failed batch is never completed; pages of earlier batches are.
     * @throws Exception if authoring fails
     */
    @Test
    void failedBatchPagesAreNotCompleted() throws Exception {
        final int batch = java.util.concurrent.ForkJoinPool.getCommonPoolParallelism() + 1;
        final var counts = new ArrayList<Integer>(Collections.nCopies(batch * 2, 1));
        counts.set(batch + batch / 2, 60);
        final var recording = new Recording();
        final var options = PartitionOptions.builder().maxElements(50).build();
        final var partitioner = ocr(PdfOcrOptions.ReadingOrder.SOURCE_ORDER);
        final var failure = assertThrows(PartitionException.class,
                ()
                        -> partitioner.partitionRange(
                                DocumentSource.bytes(PdfWholeDocumentGoldenTest.ocrPages(counts), "fail.pdf"), options,
                                new PdfPageRange(1, counts.size()),
                                new Recording.Limited(recording, partitioner, options)));
        assertEquals("PDF page exceeds document element budget", failure.getMessage());
        assertEquals(java.util.stream.IntStream.rangeClosed(1, batch).boxed().toList(), recording.completed);
    }

    /**
     * A budget that admits everything still cannot make one page retain more than maxElements elements.
     * @throws Exception if authoring fails
     */
    @Test
    void permissiveBudgetKeepsTheMemoryCeiling() throws Exception {
        final var options = PartitionOptions.builder().maxElements(40).build();
        final var pdf = PdfWholeDocumentGoldenTest.ocrPages(List.of(40, 41));
        final var partitioner = ocr(PdfOcrOptions.ReadingOrder.SOURCE_ORDER);
        final var ok = partitioner.partitionRange(
                DocumentSource.bytes(pdf, "ceiling.pdf"), options, new PdfPageRange(1, 1), PdfBudget.unlimited());
        assertEquals(40, ok.pages().getFirst().elements().size());
        final var failure = assertThrows(PartitionException.class,
                ()
                        -> partitioner.partitionRange(DocumentSource.bytes(pdf, "ceiling.pdf"), options,
                                new PdfPageRange(2, 2), PdfBudget.unlimited()));
        assertEquals(PartitionException.Code.ELEMENT_LIMIT, failure.code());
        assertEquals("PDF page exceeds document element budget", failure.getMessage());
    }

    /**
     * Exceptions thrown by a caller budget reach the caller unchanged, from extraction, replay and completion.
     * @throws Exception if authoring fails
     */
    @Test
    void callerBudgetExceptionsPropagateUnchanged() throws Exception {
        final var pdf = PdfWholeDocumentGoldenTest.ocrPages(List.of(2, 3, 4));
        final var imageCase = PdfRangeCorpus.cases()
                                      .stream()
                                      .filter(value -> value.name().equals("images-bytes"))
                                      .findFirst()
                                      .orElseThrow();
        for (final var kind : PdfBudget.Kind.values()) {
            for (final var thrown : List.<Exception>of(new IOException("custom budget"),
                         new IllegalStateException("state budget"), new IllegalArgumentException("argument budget"))) {
                final PdfBudget budget = (charged, amount, page) -> {
                    if (charged == kind) {
                        throwUnchecked(thrown);
                    }
                };
                final var images = kind == PdfBudget.Kind.IMAGE_BYTES;
                final var partitioner =
                        images ? imageCase.partitioner().get() : ocr(PdfOcrOptions.ReadingOrder.GEOMETRIC);
                final var source = DocumentSource.bytes(images ? imageCase.pdf() : pdf, "budget.pdf");
                final var caught = assertThrows(Exception.class,
                        () -> partitioner.partitionRange(source, OPTIONS, new PdfPageRange(1, 3), budget));
                assertSame(thrown, caught, kind + " " + thrown);
            }
        }
        final var completion = new IOException("completion budget");
        final PdfBudget completing = new PdfBudget() {
            @Override
            public void charge(final Kind kind, final long amount, final int page) {
            }

            @Override
            public void pageCompleted(final int page) throws IOException {
                if (page == 2) {
                    throw completion;
                }
            }
        };
        assertSame(completion, assertThrows(IOException.class,
                                       ()
                                               -> ocr(PdfOcrOptions.ReadingOrder.SOURCE_ORDER)
                                                       .partitionRange(DocumentSource.bytes(pdf, "budget.pdf"), OPTIONS,
                                                               new PdfPageRange(1, 3), completing)));
    }

    /**
     * Throws any exception without declaring it.
     * @param failure exception
     * @param <T> inferred unchecked type
     * @throws T always
     */
    @SuppressWarnings("unchecked")
    private static <T extends Exception> void throwUnchecked(final Exception failure) throws T {
        throw (T) failure;
    }

    /**
     * An OCR-only strategy whose recognizer emits the line count encoded in the page width.
     * @param order reading order
     * @return strategy
     */
    static PdfPartitioner ocr(final PdfOcrOptions.ReadingOrder order) {
        return PdfPartitioner.builder()
                .strategy(PdfStrategy.OCR_ONLY)
                .ocrOptions(new PdfOcrOptions(72, 10_000_000, 20_000, 1_000_000).withReadingOrder(order))
                .ocrProvider(PdfWholeDocumentGoldenTest.countingProvider())
                .build();
    }

    /**
     * One observed budget call.
     * @param kind charged kind, or null for a page completion
     * @param amount amount
     * @param page page
     */
    record Event(PdfBudget.Kind kind, long amount, int page) {}

    /** Records every charge and completion. */
    static final class Recording implements PdfBudget {
        /** Calls in arrival order. */
        final List<Event> events = Collections.synchronizedList(new ArrayList<>());
        /** Completed pages in order. */
        final List<Integer> completed = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void charge(final Kind kind, final long amount, final int page) {
            events.add(new Event(kind, amount, page));
        }

        @Override
        public void pageCompleted(final int page) {
            events.add(new Event(null, 0, page));
            completed.add(page);
        }

        /**
         * Sums one page's charges by kind.
         * @param page page
         * @return totals
         */
        Map<Kind, Long> totals(final int page) {
            final var totals = new TreeMap<Kind, Long>();
            synchronized (events) {
                for (final var event : events) {
                    if (event.kind() != null && event.page() == page) {
                        totals.merge(event.kind(), event.amount(), Long::sum);
                    }
                }
            }
            return totals;
        }

        /** Records, then applies the standard limits. */
        static final class Limited implements PdfBudget {
            /** Recorder. */
            private final Recording recording;
            /** Standard limits. */
            private final PdfBudget standard;

            /**
             * Wraps a recorder.
             * @param recording recorder
             * @param partitioner strategy supplying standard limits
             * @param options document options
             */
            Limited(final Recording recording, final PdfPartitioner partitioner, final PartitionOptions options) {
                this.recording = recording;
                standard = partitioner.standardBudget(options);
            }

            @Override
            public void charge(final Kind kind, final long amount, final int page) throws IOException {
                standard.charge(kind, amount, page);
                recording.charge(kind, amount, page);
            }

            @Override
            public void pageCompleted(final int page) throws IOException {
                standard.pageCompleted(page);
                recording.pageCompleted(page);
            }
        }
    }
}
