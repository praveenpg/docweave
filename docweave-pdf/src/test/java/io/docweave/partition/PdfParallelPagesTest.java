package io.docweave.partition;

import io.docweave.DocWeave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real page-stream concurrency, ordered assembly and cancellation with bounded in-memory OCR callbacks. */
class PdfParallelPagesTest {
    /** Creates independent controls. */
    PdfParallelPagesTest() {
    }

    /**
     * Two page callbacks must overlap; completing out of order must not reorder emitted elements or IDs.
     * @throws Exception if PDF authoring or extraction fails
     */
    @Test
    void pagesOverlapButOutputAndIdsStayInPageOrder() throws Exception {
        final var entered = new CountDownLatch(2);
        final var active = new AtomicInteger();
        final var peak = new AtomicInteger();
        final var processor = processor(image -> {
            final int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            entered.countDown();
            try {
                if (!entered.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Page callbacks did not overlap");
                }
                return List.of(new PdfOcrProvider.Line("Page width " + image.getWidth(), 1, 1, 10, 10));
            } catch (final InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Owned callback interrupted");
            } finally {
                active.decrementAndGet();
            }
        });
        final var source = source(4);
        final var result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> processor.partition(source));
        assertTrue(peak.get() >= 2);
        assertEquals(0, active.get());
        assertEquals(List.of("Page width 72", "Page width 73", "Page width 74", "Page width 75"),
                result.elements().stream().map(value -> value.text()).toList());
        assertEquals(List.of(1, 2, 3, 4),
                result.elements().stream().map(value -> value.metadata().get("page_number")).toList());
        assertEquals(result.elements(), processor.partition(source).elements());
    }

    /**
     * Interrupting the calling thread stops every active page before the partition invocation returns.
     * @throws Exception if synchronization or PDF authoring fails
     */
    @Test
    void callerCancellationInterruptsActivePages() throws Exception {
        final var entered = new CountDownLatch(2);
        final var stopped = new CountDownLatch(2);
        final var active = new AtomicInteger();
        final var failure = new AtomicReference<Throwable>();
        final var source = source(2);
        final var processor = processor(image -> {
            active.incrementAndGet();
            entered.countDown();
            try {
                Thread.sleep(30_000);
                throw new AssertionError("Cancellation failed to interrupt callback");
            } catch (final InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Owned callback interrupted");
            } finally {
                active.decrementAndGet();
                stopped.countDown();
            }
        });
        final var owner = Thread.ofPlatform().start(() -> {
            try {
                processor.partition(source);
                failure.set(new AssertionError("Cancelled partition succeeded"));
            } catch (final Throwable error) {
                failure.set(error);
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            owner.interrupt();
            owner.join(5000);
            assertFalse(owner.isAlive());
            assertTrue(stopped.await(1, TimeUnit.SECONDS));
            assertEquals(0, active.get());
            assertInstanceOf(InterruptedIOException.class, failure.get());
        } finally {
            owner.interrupt();
            owner.join(5000);
        }
    }

    /**
     * A page failure cancels a blocked sibling even when the partition caller is itself a common-pool worker.
     * @throws Exception if PDF authoring or synchronization fails
     */
    @Test
    void pageFailureStopsSiblingsBeforeReturning() throws Exception {
        final var blocked = new CountDownLatch(1);
        final var stopped = new CountDownLatch(1);
        final var cause = new IOException("Owned page failure");
        final var processor = processor(image -> {
            try {
                if (image.getWidth() == 73) {
                    blocked.countDown();
                    Thread.sleep(30_000);
                    throw new AssertionError("Failed page did not stop sibling");
                }
                if (!blocked.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Sibling did not start");
                }
                throw cause;
            } catch (final InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Sibling interrupted");
            } finally {
                if (image.getWidth() == 73) {
                    stopped.countDown();
                }
            }
        });
        final var source = source(2);
        final var error = assertTimeoutPreemptively(Duration.ofSeconds(10),
                ()
                        -> java.util.concurrent.ForkJoinPool.commonPool()
                                .submit(() -> assertThrows(PartitionException.class, () -> processor.partition(source)))
                                .get(5, TimeUnit.SECONDS));
        assertEquals(PartitionException.Code.OCR_FAILURE, error.code());
        assertSame(cause, error.getCause());
        assertEquals(0, stopped.getCount());
    }

    /**
     * Creates an OCR-only processor with independent source pages.
     * @param provider thread-safe controlled provider
     * @return processor under test
     */
    private static DocWeave processor(final PdfOcrProvider provider) {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.builder()
                        .strategy(PdfStrategy.OCR_ONLY)
                        .ocrOptions(PdfOcrOptions.defaults().withDpi(72))
                        .ocrProvider(provider)
                        .build())));
    }

    /**
     * Authors distinct page widths so callbacks can identify their source without relying on arrival order.
     * @param pages page count
     * @return bounded original PDF
     * @throws IOException if authoring fails
     */
    private static DocumentSource source(final int pages) throws IOException {
        try (final var document = new PDDocument(); final var bytes = new ByteArrayOutputStream()) {
            for (int page = 0; page < pages; page++) {
                document.addPage(new PDPage(new PDRectangle(72 + page, 100)));
            }
            document.save(bytes);
            return DocumentSource.bytes(bytes.toByteArray(), "parallel-pages.pdf");
        }
    }
}
