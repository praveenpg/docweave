package dev.docweave.partition;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Owned geometric controls distinguish overlaid padding from real spaces and ambiguous source positions. */
class PdfOverlaidDigitPaddingTest {
    /** Creates isolated source controls. */
    PdfOverlaidDigitPaddingTest() {
    }

    /**
     * Only the unique shifted overlay is omitted; all original digit identities and coordinates stay intact.
     * @throws Exception if projection fails
     */
    @Test
    void uniqueShiftedOverlayRetainsOriginalDigitIdentities() throws Exception {
        for (final float baseline : List.of(98.5f, 101.5f)) {
            final var left = glyph("3", 10, 100, 6, 6);
            final var right = glyph("8", 15.9f, 100, 6, 6);
            final var input = List.of(left, glyph(" ", 15, baseline, 2, 6), right);
            final var result = PdfOverlaidDigitPadding.project(input, new PdfTableWork());
            assertEquals(1, result.omittedPaddingGlyphs());
            assertEquals(List.of(left, right), result.glyphs());
            assertSame(left, result.glyphs().getFirst());
            assertSame(right, result.glyphs().getLast());
            assertEquals(3, input.size());
            assertThrows(UnsupportedOperationException.class, () -> result.glyphs().clear());
        }
    }

    /**
     * Explicit spaces, Unicode, actual gaps, differing glyph heights, punctuation and shifted baselines remain.
     * @throws Exception if source preservation checks fail
     */
    @Test
    void realSpacesAndUnsupportedGeometryRemainUnchanged() throws Exception {
        final var left = glyph("3", 10, 100, 6, 6);
        final var blank = glyph(" ", 15, 98.5f, 2, 6);
        final var right = glyph("8", 15.9f, 100, 6, 6);
        for (final var input : List.of(List.of(left, glyph(" ", 15, 100, 2, 6), right),
                     List.of(left, glyph("\u00a0", 15, 98.5f, 2, 6), right),
                     List.of(left, glyph("  ", 15, 98.5f, 2, 6), right),
                     List.of(left, blank, glyph("8", 18, 100, 6, 6)), List.of(left, glyph(" ", 15, 96, 2, 6), right),
                     List.of(left, blank, glyph("8", 15.9f, 100, 6, 3)),
                     List.of(left, blank, glyph("8", 15.9f, 104, 6, 6)),
                     List.of(left, blank, glyph("8", 11, 100, 6, 6)),
                     List.of(left, blank, glyph("A", 15.9f, 100, 6, 6)),
                     List.of(left, blank, glyph(".", 15.8f, 100, 1, 6), right))) {
            final var result = PdfOverlaidDigitPadding.project(input, new PdfTableWork());
            assertEquals(0, result.omittedPaddingGlyphs());
            assertEquals(input, result.glyphs());
        }
    }

    /**
     * Duplicate endpoint or padding identities do not justify choosing one interpretation.
     * @throws Exception if ambiguity checks fail
     */
    @Test
    void duplicatedEndpointsAndPaddingRemainAmbiguous() throws Exception {
        final var left = glyph("3", 10, 100, 6, 6);
        final var blank = glyph(" ", 15, 98.5f, 2, 6);
        final var right = glyph("8", 15.9f, 100, 6, 6);
        for (final var input : List.of(List.of(left, left, blank, right), List.of(left, blank, right, right),
                     List.of(left, blank, blank, right), List.of(left, blank, glyph(" ", 15.1f, 98.5f, 2, 6), right))) {
            final var result = PdfOverlaidDigitPadding.project(input, new PdfTableWork());
            assertEquals(0, result.omittedPaddingGlyphs());
            assertEquals(input, result.glyphs());
        }
    }

    /**
     * Shared work/cancellation limits apply even to empty projection calls.
     * @throws Exception if bounded execution checks fail
     */
    @Test
    void workAndCancellationStayTyped() throws Exception {
        final var work = new PdfTableWork();
        for (int index = 0; index < 2_000_000; index++) {
            work.step();
        }
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> PdfOverlaidDigitPadding.project(List.of(), work)).code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    InterruptedIOException.class, () -> PdfOverlaidDigitPadding.project(List.of(), new PdfTableWork()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // This test owns its interruption and clears the flag before thread reuse by the runner.
            Thread.interrupted();
        }
    }

    /**
     * Creates original upright native geometry without text normalization.
     * @param value original code points
     * @param x original left
     * @param y original baseline
     * @param width original advance width
     * @param height original glyph height
     * @return owned original position
     */
    private static PdfPageText.Glyph glyph(
            final String value, final float x, final float y, final float width, final float height) {
        return new PdfPageText.Glyph(value, x, y, width, height, 3, 0);
    }
}
