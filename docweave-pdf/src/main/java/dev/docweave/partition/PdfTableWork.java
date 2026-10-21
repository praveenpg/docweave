package dev.docweave.partition;

import java.io.IOException;

/** Shared per-page bound on table geometry comparisons, slot scans and glyph-ownership probes. */
final class PdfTableWork {
    /** Conservative fixed work ceiling, independent of caller-supplied large table dimensions. */
    private static final int MAX_WORK = 2_000_000;
    /** Work already consumed on this page. */
    private int used;

    /** Accepted native anchors, tracked separately for explicit native/provider composition. */
    private long nativeAnchors;

    /**
     * Records anchors already admitted under native table/work limits, without changing legacy ceilings.
     * @param count nonnegative accepted anchor count
     */
    void retainNativeAnchors(final long count) {
        nativeAnchors += count;
    }

    /**
     * Returns accepted native anchors for a combined native/raw-provider page budget.
     * @return nonnegative page-local anchor count
     */
    long nativeAnchors() {
        return nativeAnchors;
    }

    /** Creates a fresh page-confined counter. */
    PdfTableWork() {
    }

    /**
     * Reserves one bounded geometry operation and checks cooperative cancellation.
     * @throws IOException if cancelled or the fixed per-page ceiling is exceeded
     */
    void step() throws IOException {
        if ((used & 255) == 0) {
            ElementCollector.checkInterrupted();
        }
        if (++used > MAX_WORK) {
            throw new PartitionException(
                    PartitionException.Code.TABLE_LIMIT, "PDF table geometry exceeds page work limit");
        }
    }
}
