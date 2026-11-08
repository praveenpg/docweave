package io.docweave.partition;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The historical document budgets behind {@link PdfBudget}: one call's image-byte total, the page-local element
 * ceiling and the cumulative element ceiling, with the codes and messages of 0.1.0-beta.13.
 * <ul>
 *   <li>Images: a shared total, refused when a write would exceed {@code maxImageBytes}.</li>
 *   <li>Page-local elements: a page admits {@code cap} elements and fails on element {@code cap + 1}, where
 *       {@code cap} is {@code min(4096, maxElements)} for buffered output (table continuation or geometric reading
 *       order) and {@code maxElements} otherwise.</li>
 *   <li>Pending elements (buffered output): a page's pending buffer admits 4096 elements, refused with the
 *       buffered-page message.</li>
 *   <li>Ordered elements: the document admits {@code maxElements} and fails on the next element, reported with the
 *       buffered-page message for buffered output and the collector message otherwise.</li>
 * </ul>
 */
final class StandardPdfBudget implements PdfBudget {
    /** Stateless budget that admits everything. */
    static final PdfBudget UNLIMITED = (kind, amount, page) -> validate(kind, amount, page);

    /** Fixed pending-element ceiling of buffered output, additional to maxElements. */
    static final int MAX_PENDING = 4096;

    /** Document element ceiling. */
    private final int maxElements;
    /** Cumulative encoded image-byte ceiling. */
    private final int maxImageBytes;
    /** Whether output is buffered per page (table continuation or geometric reading order). */
    private final boolean buffered;
    /** Page-local element ceiling. */
    private final int pageCap;
    /** Image bytes admitted in this call, guarded by this instance. */
    private int images;
    /** Elements admitted to the ordered output in this call, guarded by this instance. */
    private long elements;
    /**
     * Per-page counts until the page completes: extraction admissions, then pending admissions. Each page is
     * extracted by one thread at a time and replayed on the calling thread.
     */
    private final ConcurrentMap<Integer, int[]> pages = new ConcurrentHashMap<>();

    /**
     * Creates one call's budget.
     * @param maxElements document element ceiling
     * @param maxImageBytes cumulative image-byte ceiling
     * @param buffered whether page output is buffered for table continuation or geometric reading order
     */
    StandardPdfBudget(final int maxElements, final int maxImageBytes, final boolean buffered) {
        this.maxElements = maxElements;
        this.maxImageBytes = maxImageBytes;
        this.buffered = buffered;
        pageCap = buffered ? Math.min(MAX_PENDING, maxElements) : maxElements;
    }

    @Override
    public void charge(final Kind kind, final long amount, final int page) throws IOException {
        validate(kind, amount, page);
        switch (kind) {
        case IMAGE_BYTES -> {
            synchronized (this) {
                if (amount > maxImageBytes - images) {
                    throw new PartitionException(
                            PartitionException.Code.IMAGE_LIMIT, "PDF images exceed maxImageBytes");
                }
                images += (int) amount;
            }
        }
        case PAGE_ELEMENTS -> {
            final int[] admitted = pages.computeIfAbsent(page, ignored -> new int[2]);
            if (amount > pageCap - admitted[0]) {
                throw new PartitionException(
                        PartitionException.Code.ELEMENT_LIMIT, "PDF page exceeds document element budget");
            }
            admitted[0] += (int) amount;
        }
        case PAGE_PENDING -> {
            final int[] admitted = pages.computeIfAbsent(page, ignored -> new int[2]);
            if (amount > MAX_PENDING - admitted[1]) {
                throw new PartitionException(
                        PartitionException.Code.ELEMENT_LIMIT, "PDF buffered page exceeds pending-element budget");
            }
            admitted[1] += (int) amount;
        }
        case ELEMENTS -> {
            synchronized (this) {
                if (amount > maxElements - elements) {
                    throw new PartitionException(PartitionException.Code.ELEMENT_LIMIT,
                            buffered ? "PDF buffered page exceeds pending-element budget"
                                     : "Document exceeds maxElements=" + maxElements);
                }
                elements += amount;
            }
        }
        }
    }

    /**
     * Forgets a completed page's counts.
     * @param page one-based page
     */
    @Override
    public void pageCompleted(final int page) {
        pages.remove(page);
    }

    /**
     * Validates a charge's arguments.
     * @param kind resource
     * @param amount quantity
     * @param page one-based page
     * @throws NullPointerException if kind is null
     * @throws IllegalArgumentException if amount or page is not positive
     */
    static void validate(final Kind kind, final long amount, final int page) {
        Objects.requireNonNull(kind, "kind");
        if (amount <= 0 || page <= 0) {
            throw new IllegalArgumentException("Budget charges need a positive amount and a one-based page");
        }
    }
}
