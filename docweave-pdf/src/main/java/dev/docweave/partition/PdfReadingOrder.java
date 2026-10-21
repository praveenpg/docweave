package dev.docweave.partition;

import java.io.IOException;
import java.util.List;

/** Internal service contract for opt-in native text grouping and page reading order. */
interface PdfReadingOrder {
    /**
     * Orders/group native text without changing table or image contents or crossing source pages.
     * @param items pending page elements in geometric order
     * @return ordered page elements, including every ungrouped/structural item
     * @throws IOException if cancelled or a per-page traversal budget is exceeded
     */
    List<PdfLayoutItem> apply(final List<PdfLayoutItem> items) throws IOException;
}
