package dev.docweave.partition;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Open connected rule components are rejected before structured topology budgets or allocations. */
class PdfOpenGridLimitTest {
    @Test
    void oversizedOpenComponentIsFallbackButClosedComponentStillEnforcesEveryDimensionBudget() throws Exception {
        final var open = grid(false);
        final var closed = grid(true);
        for (final var limits : List.of(new TableLimits(1, 100, 1000, 100), new TableLimits(100, 2, 1000, 100),
                     new TableLimits(100, 100, 4, 100))) {
            final var detector = new RuledPdfTableDetector(limits);
            assertTrue(detector.detect(open, new PdfTableWork()).isEmpty());
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class, () -> detector.detect(closed, new PdfTableWork())).code());
        }
        assertEquals(1, new RuledPdfTableDetector(TableLimits.defaults()).detect(closed, new PdfTableWork()).size());
        assertTrue(new RuledPdfTableDetector(TableLimits.defaults()).detect(open, new PdfTableWork()).isEmpty());
    }

    @Test
    void incompleteOuterEdgesDoNotConsumeTheBudgetForAnIndependentSmallClosedTable() throws Exception {
        final var segments = new ArrayList<>(grid(false));
        for (final int y : new int[] {400, 500, 600}) {
            segments.add(new PdfPageGraphics.Segment(0, y, 200, y));
        }
        for (final int x : new int[] {0, 100, 200}) {
            segments.add(new PdfPageGraphics.Segment(x, 400, x, 600));
        }
        final var grids = new RuledPdfTableDetector(new TableLimits(2, 2, 4, 100)).detect(segments, new PdfTableWork());
        assertEquals(1, grids.size());
        assertEquals(List.of(400f, 500f, 600f), grids.getFirst().ys());
        assertEquals(4, grids.getFirst().cells().size());
    }

    @Test
    void perimeterProbesStillHonorSharedWorkAndCancellationLimits() throws Exception {
        final var work = new PdfTableWork();
        for (int index = 0; index < 2_000_000; index++) {
            work.step();
        }
        final var detector = new RuledPdfTableDetector(new TableLimits(2, 2, 4, 100));
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> detector.detect(grid(false), work)).code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> detector.detect(grid(false), new PdfTableWork()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static List<PdfPageGraphics.Segment> grid(final boolean closed) {
        final var segments = new ArrayList<PdfPageGraphics.Segment>();
        for (final int y : new int[] {0, 100, 200}) {
            segments.add(new PdfPageGraphics.Segment(0, y, 1000, y));
        }
        for (int x = 0; x <= 1000; x += 100) {
            segments.add(new PdfPageGraphics.Segment(x, 0, x, x == 1000 && !closed ? 180 : 200));
        }
        return segments;
    }
}
