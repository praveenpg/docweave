package io.docweave.partition;

/**
 * A contiguous range of physical PDF pages, one-based and inclusive at both ends.
 * @param first first page, at least 1
 * @param last last page, at least {@code first}
 */
public record PdfPageRange(int first, int last) {
    /**
     * Validates the range.
     * @param first first page, at least 1
     * @param last last page, at least {@code first}
     * @throws IllegalArgumentException if first is below 1 or last is below first
     */
    public PdfPageRange {
        if (first < 1 || last < first) {
            throw new IllegalArgumentException("A PDF page range needs 1 <= first <= last");
        }
    }

    /**
     * Returns the number of pages in the range.
     * @return {@code last - first + 1}
     */
    public int pageCount() {
        return last - first + 1;
    }

    /**
     * Tests whether a page lies in the range.
     * @param page one-based physical page
     * @return whether {@code first <= page <= last}
     */
    public boolean contains(final int page) {
        return page >= first && page <= last;
    }
}
