package io.docweave.partition;

import java.util.List;
import java.util.Objects;

/**
 * Output of counting a page range with {@link PdfPartitioner#countRange}: every element was extracted and charged to
 * the budget, then dropped, so pages hold only their warnings and usage. Extraction charges are those of partitioning;
 * the ordered {@code PAGE_PENDING} and {@code ELEMENTS} charges are made once per page with the page total, so usage
 * totals equal partitioning's and the standard budget fails with the same code, message and page, though not at the
 * same element within the page. A dense page can be counted without retaining it. Counts cannot be assembled into
 * elements.
 * @param coverage counted pages
 * @param documentPages physical page count of the whole document
 * @param pages one entry per counted page, in page order, each without elements
 */
public record PdfRangeCount(PdfPageRange coverage, int documentPages, List<PdfRangeResult.Page> pages) {
    /**
     * Validates and copies.
     * @param coverage nonnull counted pages
     * @param documentPages document page count, at least the last counted page
     * @param pages one element-free entry per counted page, in order
     * @throws NullPointerException if a reference is null
     * @throws IllegalArgumentException if the pages do not match the coverage or hold elements
     */
    public PdfRangeCount {
        Objects.requireNonNull(coverage, "coverage");
        pages = List.copyOf(pages);
        if (documentPages < coverage.last() || pages.size() != coverage.pageCount()) {
            throw new IllegalArgumentException("A PDF range count needs one entry per counted page");
        }
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index).pageNumber() != coverage.first() + index || !pages.get(index).elements().isEmpty()) {
                throw new IllegalArgumentException("PDF range count pages must be consecutive and element-free");
            }
        }
    }
}
