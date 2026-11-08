package io.docweave.partition;

import java.io.IOException;

/**
 * Consumption hook for the PDF budgets that span more than one page: encoded image bytes and element counts.
 * Extraction charges every unit it counts, together with the one-based physical page that consumed it; an
 * implementation decides whether to admit it and fails the call by throwing. The built-in implementation, from
 * {@link PdfPartitioner#standardBudget(PartitionOptions)}, is the one every whole-document partition call uses: it
 * applies exactly the limits, codes and messages of earlier releases. A caller that processes a document in page
 * ranges may supply its own implementation, for example one that only records usage, or one that compares a running
 * total with document-wide figures it keeps elsewhere.
 *
 * <p>Charges arrive in two phases, mirroring how pages are processed:</p>
 * <ul>
 *   <li>{@link Kind#IMAGE_BYTES} and {@link Kind#PAGE_ELEMENTS} are charged while a page is extracted. Pages are
 *       extracted concurrently, so these charges can arrive from several threads at once and out of page order. The
 *       charges for one page come from one thread at a time.</li>
 *   <li>{@link Kind#PAGE_PENDING} (buffered output only) and {@link Kind#ELEMENTS} are charged when a completed page
 *       is replayed into the ordered output, on the calling thread, in page order, after every page of the same
 *       extraction batch finished. For each element of a buffered page, {@code PAGE_PENDING} is charged before
 *       {@code ELEMENTS}. After a page's replay, and only if it succeeded, {@link #pageCompleted(int)} is called.</li>
 * </ul>
 *
 * <p>When a charge throws, nothing of that unit is retained and the call fails with that exception, which reaches
 * the caller unchanged (it is never rewrapped as a malformed-document failure). A failure thrown during extraction
 * stops the other pages of the batch; the first failure thrown is the one reported, as before. Implementations must
 * be thread-safe, must not block for long, and should throw {@link PartitionException} with an appropriate code.
 * Amounts are positive; pages are one-based physical page numbers of the whole document.</p>
 *
 * <p>Pages are extracted in batches of the common pool's parallelism plus one. Batches start at the first page of
 * the call: page 1 for a whole document, the range's first page for a page range. A standard budget used for one
 * range alone therefore sees batch boundaries that can differ from the whole document's, and when several limits
 * would be crossed close together its precedence can differ from the whole-document result. Reproducing the
 * whole-document precedence across ranges is the caller's responsibility, using the per-page charges and page
 * completions this interface reports.</p>
 *
 * <p>Independently of any budget, a page never retains more than {@code maxElements} elements in memory, so a
 * permissive budget cannot make one page unbounded; that safety ceiling fails with {@code ELEMENT_LIMIT} and the
 * page-local message. A counting call ({@link PdfPartitioner#countRange}) retains no elements and has no such
 * ceiling; it charges {@link Kind#PAGE_PENDING} and {@link Kind#ELEMENTS} once per page with the page's element total
 * instead of once per element, so a limit is refused at the same page but not at the same element within it.</p>
 */
public interface PdfBudget {
    /** Counted resource. */
    enum Kind {
        /** Encoded PNG bytes of source images, charged per codec write while the page is extracted. */
        IMAGE_BYTES,
        /** One element admitted to the page's own buffer while the page is extracted (the page-local check). */
        PAGE_ELEMENTS,
        /**
         * One element admitted to the ordered writer's per-page pending buffer during replay; charged only when
         * output is buffered for table continuation or geometric reading order.
         */
        PAGE_PENDING,
        /** One element admitted to the ordered document output while completed pages are replayed in order. */
        ELEMENTS
    }

    /**
     * Charges consumption to the budget.
     * @param kind nonnull resource
     * @param amount positive quantity
     * @param page one-based physical page that consumed it
     * @throws IOException if the budget refuses the charge; usually a {@link PartitionException}
     */
    void charge(Kind kind, long amount, int page) throws IOException;

    /**
     * Signals that a page is complete: all of its charges were admitted and its output was replayed in order. Called
     * on the calling thread, in page order, once per page, after the page's last {@link Kind#ELEMENTS} charge and
     * before its progress is reported. It is not called for a page whose extraction or replay failed, nor for any page
     * of a batch in which a page failed during extraction, because such a batch is never replayed. Exceptions fail
     * the call unchanged. The default does nothing.
     * @param page one-based physical page
     * @throws IOException if the budget rejects the completed page
     */
    default void pageCompleted(final int page) throws IOException {
    }

    /**
     * Returns a budget that admits everything and keeps no state, for callers that only need the per-page usage a
     * range result records, or that enforce limits themselves.
     * @return stateless thread-safe budget that validates arguments only
     */
    static PdfBudget unlimited() {
        return StandardPdfBudget.UNLIMITED;
    }
}
