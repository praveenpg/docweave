package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/** Page progress on an owned 100-page PDF across native, OCR_ONLY and HI_RES routing with in-memory providers. */
class PdfProgressTest {
    /** Pages in the controlled document. */
    private static final int PAGES = 100;

    /** Checkpoint asserted while extraction is still running. */
    private static final int CHECKPOINT = 35;

    /** Shared owned source, encoded once. */
    private static DocumentSource source;

    /** Creates independent progress controls. */
    PdfProgressTest() {
    }

    /**
     * Encodes the owned document once.
     * @throws IOException if PDF encoding fails
     */
    @BeforeAll
    static void encode() throws IOException {
        source = hundredPages();
    }

    /**
     * Native pages report 35/100 only after the first 35 pages' elements were all emitted, then assemble and finish.
     * @throws Exception if partitioning fails
     */
    @Test
    void nativePagesReportCompletedPagesThenAssemblyThenDone() throws Exception {
        final var emitted = new ConcurrentSkipListSet<Integer>();
        final var atCheckpoint = new ArrayList<Set<Integer>>();
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        final var result = observed(PdfPartitioner.builder().strategy(PdfStrategy.FAST).build(), emitted)
                                   .partition(source, PartitionOptions.defaults(), event -> {
                                       events.add(event);
                                       if (event.completed() == CHECKPOINT
                                               && event.phase() == PartitionProgress.Phase.EXTRACTING) {
                                           atCheckpoint.add(Set.copyOf(emitted));
                                       }
                                   });
        assertEquals(List.of(pages(CHECKPOINT)), atCheckpoint);
        assertCompleteSequence(events);
        assertEquals(PAGES, result.elements().stream().filter(value -> value.text().startsWith("Page ")).count());
        assertSameExtraction(
                processor(PdfPartitioner.builder().strategy(PdfStrategy.FAST).build()).partition(source), result);
    }

    /**
     * OCR_ONLY counts a page only after its recognition and emission finished, never when it is rendered or queued.
     * @throws Exception if partitioning fails
     */
    @Test
    void ocrOnlyCountsPagesAfterRecognition() throws Exception {
        final var calls = new AtomicInteger();
        final var pdf = PdfPartitioner.builder()
                                .strategy(PdfStrategy.OCR_ONLY)
                                .ocrProvider(image -> {
                                    calls.incrementAndGet();
                                    return List.of(new PdfOcrProvider.Line("OCR", 5, 5, 10, 10));
                                })
                                .build();
        final var emitted = new ConcurrentSkipListSet<Integer>();
        final var atCheckpoint = new ArrayList<List<Object>>();
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        final var result = observed(pdf, emitted).partition(source, PartitionOptions.defaults(), event -> {
            events.add(event);
            if (event.completed() == CHECKPOINT && event.phase() == PartitionProgress.Phase.EXTRACTING) {
                atCheckpoint.add(List.of(calls.get(), Set.copyOf(emitted)));
            }
        });
        assertEquals(List.of(List.of(CHECKPOINT, pages(CHECKPOINT))), atCheckpoint);
        assertCompleteSequence(events);
        assertEquals(PAGES, result.elements().stream().filter(value -> value.text().equals("OCR")).count());
        calls.set(0);
        assertSameExtraction(processor(pdf).partition(source), result);
    }

    /**
     * HI_RES counts a page only after word recognition and table layout both finished for it.
     * @throws Exception if partitioning fails
     */
    @Test
    void hiResCountsPagesAfterRecognitionAndLayout() throws Exception {
        final var words = new AtomicInteger();
        final var layouts = new AtomicInteger();
        final var box = new PdfTableLayoutProvider.Box(1, 1, 20, 20);
        final var pdf =
                PdfPartitioner.builder()
                        .strategy(PdfStrategy.HI_RES)
                        .ocrOptions(PdfOcrOptions.defaults().withDpi(72))
                        .ocrProvider((PdfWordOcrProvider) (image, kind) -> {
                            words.incrementAndGet();
                            return List.of(new PdfWordOcrProvider.WordLine(new PdfOcrProvider.Line("OCR", 5, 5, 10, 10),
                                    List.of(new PdfWordOcrProvider.Word("OCR", 5, 5, 10, 10))));
                        })
                        .tableLayoutProvider(image -> {
                            layouts.incrementAndGet();
                            return List.of(new PdfTableLayoutProvider.Table(
                                    1, 1, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "owned"));
                        })
                        .build();
        final var emitted = new ConcurrentSkipListSet<Integer>();
        final var atCheckpoint = new ArrayList<List<Object>>();
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        final var result = observed(pdf, emitted).partition(source, PartitionOptions.defaults(), event -> {
            events.add(event);
            if (event.completed() == CHECKPOINT && event.phase() == PartitionProgress.Phase.EXTRACTING) {
                atCheckpoint.add(List.of(words.get(), layouts.get(), Set.copyOf(emitted)));
            }
        });
        assertEquals(List.of(List.of(CHECKPOINT, CHECKPOINT, pages(CHECKPOINT))), atCheckpoint);
        assertCompleteSequence(events);
        assertEquals(PAGES, result.elements().stream().filter(value -> value.type() == ElementType.Table).count());
    }

    /**
     * Provider failure on page 36 stops reporting at 35/100 without assembly or completion.
     * @throws Exception if the test strategy cannot be built
     */
    @Test
    void failureStopsAtLastCompletedPage() throws Exception {
        final var calls = new AtomicInteger();
        final var pdf = PdfPartitioner.builder()
                                .strategy(PdfStrategy.OCR_ONLY)
                                .ocrProvider(image -> {
                                    if (calls.incrementAndGet() > CHECKPOINT) {
                                        throw new IOException("owned provider failure");
                                    }
                                    return List.of();
                                })
                                .build();
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        final var failure = assertThrows(PartitionException.class,
                () -> processor(pdf).partition(source, PartitionOptions.defaults(), events::add));
        assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
        assertEquals(prefix(CHECKPOINT), events);
    }

    /**
     * Cancellation requested at 35/100 stops reporting; the call fails at the next page checkpoint.
     * @throws Exception if the test strategy cannot be built
     */
    @Test
    void cancellationStopsReporting() throws Exception {
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        final var processor = processor(PdfPartitioner.builder().strategy(PdfStrategy.FAST).build());
        try {
            assertThrows(InterruptedIOException.class,
                    () -> processor.partition(source, PartitionOptions.defaults(), event -> {
                        events.add(event);
                        if (event.completed() == CHECKPOINT) {
                            Thread.currentThread().interrupt();
                        }
                    }));
        } finally {
            Thread.interrupted();
        }
        assertEquals(prefix(CHECKPOINT), events);
    }

    /**
     * AUTO keeps native text and recognizes only textless pages; a textless page counts after its OCR output.
     * @throws Exception if partitioning fails
     */
    @Test
    void autoCountsTextlessPagesAfterRecognition() throws Exception {
        final var calls = new AtomicInteger();
        final var pdf = PdfPartitioner.builder()
                                .strategy(PdfStrategy.AUTO)
                                .ocrProvider(image -> {
                                    calls.incrementAndGet();
                                    return List.of(new PdfOcrProvider.Line("OCR", 5, 5, 10, 10));
                                })
                                .build();
        final var mixed = hundredPages(page -> page % 5 != 0);
        final var emitted = new ConcurrentSkipListSet<Integer>();
        final var atCheckpoint = new ArrayList<List<Object>>();
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        final var result = observed(pdf, emitted).partition(mixed, PartitionOptions.defaults(), event -> {
            events.add(event);
            if (event.completed() == CHECKPOINT && event.phase() == PartitionProgress.Phase.EXTRACTING) {
                atCheckpoint.add(List.of(calls.get(), Set.copyOf(emitted)));
            }
        });
        // Pages 5, 10, ..., 35 are textless, so seven pages were recognized when page 35 completed.
        assertEquals(List.of(List.of(CHECKPOINT / 5, pages(CHECKPOINT))), atCheckpoint);
        assertCompleteSequence(events);
        assertEquals(PAGES / 5, result.elements().stream().filter(value -> value.text().equals("OCR")).count());
        assertEquals(PAGES - PAGES / 5,
                result.elements().stream().filter(value -> value.text().startsWith("Page ")).count());
    }

    /**
     * A document over maxPages is refused before any page total is declared: no unit events and no DONE.
     * @throws Exception if the test strategy cannot be built
     */
    @Test
    void maxPagesRefusalReportsNoUnitsAndNoDone() throws Exception {
        final var limited = PdfPartitioner.create(
                new PdfOptions(10, 250_000, 100_000, 16_000_000, 16 * 1024 * 1024, false, TableLimits.defaults()));
        final var events = new CopyOnWriteArrayList<PartitionProgress>();
        final var failure = assertThrows(PartitionException.class,
                () -> processor(limited).partition(source, PartitionOptions.defaults(), events::add));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE, failure.code());
        assertEquals(List.of(PartitionProgress.indeterminate(PartitionProgress.Phase.PREPARING),
                             PartitionProgress.indeterminate(PartitionProgress.Phase.EXTRACTING)),
                events);
    }

    /**
     * A listener that always throws leaves PDF output unchanged.
     * @throws Exception if partitioning fails
     */
    @Test
    void throwingListenerLeavesOutputUnchanged() throws Exception {
        final var processor = processor(PdfPartitioner.builder().strategy(PdfStrategy.FAST).build());
        final var offered = new AtomicInteger();
        final var observed = processor.partition(source, PartitionOptions.defaults(), event -> {
            offered.incrementAndGet();
            throw new IllegalStateException("listener failure");
        });
        assertSameExtraction(processor.partition(source), observed);
        assertEquals(PAGES + 5, offered.get());
    }

    /**
     * Checks the complete success sequence: preparing, indeterminate extraction, 0..100 pages, assembly, done.
     * @param events observed events
     */
    private static void assertCompleteSequence(final List<PartitionProgress> events) {
        final var expected = new ArrayList<>(prefix(PAGES));
        expected.add(new PartitionProgress(PartitionProgress.Phase.ASSEMBLING, "pages", PAGES, PAGES));
        expected.add(new PartitionProgress(PartitionProgress.Phase.DONE, "pages", PAGES, PAGES));
        assertEquals(expected, events);
    }

    /**
     * Builds the exact event list of a call that stopped after the given number of completed pages.
     * @param completed last completed page count
     * @return preparing, indeterminate extraction and 0..completed page events
     */
    private static List<PartitionProgress> prefix(final int completed) {
        final var expected = new ArrayList<PartitionProgress>();
        expected.add(PartitionProgress.indeterminate(PartitionProgress.Phase.PREPARING));
        expected.add(PartitionProgress.indeterminate(PartitionProgress.Phase.EXTRACTING));
        LongStream.rangeClosed(0, completed)
                .mapToObj(value -> new PartitionProgress(PartitionProgress.Phase.EXTRACTING, "pages", value, PAGES))
                .forEach(expected::add);
        return expected;
    }

    /**
     * Lists one-based pages 1 through count.
     * @param count last page
     * @return page numbers
     */
    private static Set<Integer> pages(final int count) {
        return IntStream.rangeClosed(1, count).boxed().collect(Collectors.toSet());
    }

    /**
     * Compares deterministic output fields.
     * @param expected baseline extraction
     * @param actual extraction with a listener
     */
    private static void assertSameExtraction(final PartitionResult expected, final PartitionResult actual) {
        assertEquals(expected.elements(), actual.elements());
        assertEquals(expected.warnings(), actual.warnings());
        assertEquals(expected.format(), actual.format());
        assertEquals(expected.inputBytes(), actual.inputBytes());
    }

    /**
     * Installs only the selected PDF strategy.
     * @param pdf built strategy
     * @return document processor
     */
    private static DocWeave processor(final PdfPartitioner pdf) {
        return DocWeave.create(PartitionerRegistry.of(List.of(pdf)));
    }

    /**
     * Wraps the PDF strategy so the test can see which pages' elements were emitted when progress is reported.
     * @param pdf built strategy
     * @param emitted receives one-based page numbers of emitted elements
     * @return document processor
     */
    private static DocWeave observed(final PdfPartitioner pdf, final Set<Integer> emitted) {
        final Partitioner wrapper = new Partitioner() {
            /**
             * Supplies the PDF key.
             * @return PDF
             */
            @Override
            public DocumentFormat format() {
                return DocumentFormat.PDF;
            }

            /**
             * Delegates through a recording context.
             * @param context synchronous document context
             * @throws IOException if extraction fails
             */
            @Override
            public void partition(final PartitionContext context) throws IOException {
                pdf.partition(new Recording(context, emitted));
            }
        };
        return DocWeave.create(PartitionerRegistry.of(List.of(wrapper)));
    }

    /**
     * Encodes 100 small native-text pages.
     * @return owned in-memory source
     * @throws IOException if encoding fails
     */
    private static DocumentSource hundredPages() throws IOException {
        return hundredPages(page -> true);
    }

    /**
     * Encodes 100 small pages, writing native text only on selected pages; other pages are blank.
     * @param text selects one-based pages that carry native text
     * @return owned in-memory source
     * @throws IOException if encoding fails
     */
    private static DocumentSource hundredPages(final java.util.function.IntPredicate text) throws IOException {
        try (final var document = new PDDocument(); final var bytes = new ByteArrayOutputStream()) {
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int index = 1; index <= PAGES; index++) {
                final var page = new PDPage(new PDRectangle(72, 144));
                document.addPage(page);
                if (!text.test(index)) {
                    continue;
                }
                try (final var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 8);
                    content.newLineAtOffset(5, 100);
                    content.showText("Page " + index);
                    content.endText();
                }
            }
            document.save(bytes);
            return DocumentSource.bytes(bytes.toByteArray(), "hundred-pages.pdf");
        }
    }

    /**
     * Delegating context recording emitted page numbers; progress is forwarded unchanged.
     * @param delegate framework context
     * @param emitted receives one-based page numbers
     */
    private record Recording(PartitionContext delegate, Set<Integer> emitted) implements PartitionContext {
        @Override
        public String text() {
            return delegate.text();
        }

        @Override
        public byte[] binaryContent() {
            return delegate.binaryContent();
        }

        @Override
        public ByteBuffer binaryBuffer() {
            return delegate.binaryBuffer();
        }

        @Override
        public PartitionProgressReporter progress() {
            return delegate.progress();
        }

        @Override
        public String filename() {
            return delegate.filename();
        }

        @Override
        public DocumentFormat format() {
            return delegate.format();
        }

        @Override
        public PartitionOptions options() {
            return delegate.options();
        }

        @Override
        public int elementCount() {
            return delegate.elementCount();
        }

        @Override
        public Element elementAt(final int index) {
            return delegate.elementAt(index);
        }

        @Override
        public Element emit(final ElementType type, final String text, final Map<String, Object> metadata)
                throws IOException {
            final var element = delegate.emit(type, text, metadata);
            if (element.metadata().get("page_number") instanceof final Number page) {
                emitted.add(page.intValue());
            }
            return element;
        }

        @Override
        public void warn(final PartitionResult.WarningCode code, final String message) {
            delegate.warn(code, message);
        }
    }
}
