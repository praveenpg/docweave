package io.docweave.partition;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Clipped-line source geometry controls without publisher labels or widened arbitrary ownership. */
class PdfShadedHeaderBoundsTest {
    /** Creates isolated source controls. */
    PdfShadedHeaderBoundsTest() {
    }

    /**
     * Every independently supported header span completes one shared original line.
     * @throws Exception if bounded header inspection fails
     */
    @Test
    void completeClippedLineUsesOriginalTop() throws Exception {
        final var glyphs = List.of(glyph("first", 110, 102, 25, 6), glyph("second", 180, 102, 30, 6));
        assertEquals(96, top(glyphs));
        assertEquals(102, glyphs.getFirst().y());
        assertEquals(6, glyphs.getFirst().height());
    }

    /**
     * Missing spans, crossing glyphs, competing baselines and excessive height retain the prior boundary.
     * @throws Exception if ambiguity controls fail
     */
    @Test
    void incompleteOrAmbiguousLinesNeverExpand() throws Exception {
        final var first = glyph("first", 110, 102, 25, 6);
        for (final var input : List.of(List.of(first), List.of(first, glyph("second", 180, 103, 30, 6)),
                     List.of(first, glyph("second", 155, 102, 30, 6)),
                     List.of(first, glyph("second", 180, 102, 30, 20)),
                     List.of(first, glyph("second", 180, 102, 30, 6), glyph("label", 30, 102, 20, 6)),
                     List.of(first, glyph("second", 180, 102, 30, 6), glyph("unrelated", 110, 98, 20, 3)))) {
            assertEquals(100, top(input));
        }
    }

    /**
     * A wholly separate upper line, a single span and noncrossing text provide no extension evidence.
     * @throws Exception if source boundary checks fail
     */
    @Test
    void unconnectedUpperTextKeepsOriginalOwnership() throws Exception {
        final var above = List.of(glyph("first", 110, 98, 25, 6), glyph("second", 180, 98, 30, 6));
        assertEquals(100, top(above));
        assertEquals(100,
                PdfShadedHeaderBounds.top(100, 8, 20, 250, List.of(new PdfPageGraphics.Segment(100, 114, 230, 114)),
                        List.of(glyph("heading", 110, 102, 25, 6)), new PdfTableWork()));
    }

    /**
     * Shared work and interruption bounds remain typed even without source evidence.
     * @throws Exception if bounded execution controls fail
     */
    @Test
    void workAndCancellationRemainTyped() throws Exception {
        final var work = new PdfTableWork();
        for (int index = 0; index < 2_000_000; index++) {
            work.step();
        }
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> PdfShadedHeaderBounds.top(100, 8, 20, 250, List.of(), List.of(), work))
                        .code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> top(List.of()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // This test owns the interrupt and clears it before runner thread reuse.
            Thread.interrupted();
        }
    }

    /**
     * Applies fixed original two-span geometry to owned source positions.
     * @param input original glyphs
     * @return inferred top boundary
     * @throws Exception if work/cancellation checks fail
     */
    private static float top(final List<PdfPageText.Glyph> input) throws Exception {
        return PdfShadedHeaderBounds.top(100, 8, 20, 250,
                List.of(new PdfPageGraphics.Segment(100, 114, 160, 114),
                        new PdfPageGraphics.Segment(170, 114, 230, 114)),
                input, new PdfTableWork());
    }

    /**
     * Creates original upright source geometry without character repair.
     * @param text original characters
     * @param x original left
     * @param y original baseline
     * @param width original advance
     * @param height original height
     * @return original native position
     */
    private static PdfPageText.Glyph glyph(
            final String text, final float x, final float y, final float width, final float height) {
        return new PdfPageText.Glyph(text, x, y, width, height, 3, 0);
    }
}
