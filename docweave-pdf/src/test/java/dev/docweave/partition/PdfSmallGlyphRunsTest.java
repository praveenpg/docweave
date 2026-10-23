package dev.docweave.partition;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Whole-marker geometry controls independent of publisher labels or numeric values. */
class PdfSmallGlyphRunsTest {
    /** Creates isolated source geometry controls. */
    PdfSmallGlyphRunsTest() {
    }

    /**
     * Prefix and suffix markers remain complete while all original positions retain their coordinates.
     * @throws Exception if grouping fails
     */
    @Test
    void completePrefixAndSuffixRunsFollowBodyBaseline() throws Exception {
        for (final boolean prefix : List.of(false, true)) {
            for (final float baseline : List.of(97f, 103f)) {
                final var input = body(prefix ? 12 : 0);
                final float start = prefix ? 1 : 24;
                input.addAll(List.of(glyph("(", start, baseline, 2, 4), glyph("2", start + 2, baseline, 3, 4),
                        glyph(")", start + 5, baseline, 2, 4)));
                final var saved = List.copyOf(input);
                final var result = PdfSmallGlyphRuns.lines(input, new PdfTableWork());
                assertEquals(3, result.assignedGlyphs());
                assertEquals(1, result.lines().size());
                assertEquals(prefix ? "(2) abcd" : "abcd (2)", result.lines().getFirst().text());
                assertEquals(saved, input);
                assertEquals(Math.max(100, baseline), result.lines().getFirst().box().bottom());
            }
        }
    }

    /**
     * Duplicate, distant, oversized and competing-baseline groups receive no partial assignment.
     * @throws Exception if ambiguity checks fail
     */
    @Test
    void ambiguousOrUnsupportedRunsStayOriginal() throws Exception {
        final var marker = List.of(glyph("(", 24, 97, 2, 4), glyph("2", 26, 97, 3, 4), glyph(")", 29, 97, 2, 4));
        final var duplicate = new ArrayList<>(marker);
        duplicate.add(1, marker.getFirst());
        final var tooLong = new ArrayList<PdfPageText.Glyph>();
        for (int index = 0; index < 9; index++) {
            tooLong.add(glyph("x", 24 + index * 2, 97, 2, 4));
        }
        for (final var source : List.of(duplicate, tooLong,
                     List.of(glyph("(", 60, 97, 2, 4), glyph("2", 62, 97, 3, 4), glyph(")", 65, 97, 2, 4)))) {
            final var input = body(0);
            input.addAll(source);
            // A longer body keeps the median independent of the deliberately oversized script run.
            for (int index = 0; index < 12; index++) {
                input.add(glyph("z", 100 + index * 5, 100, 5, 8));
            }
            final var result = PdfSmallGlyphRuns.lines(input, new PdfTableWork());
            assertEquals(0, result.assignedGlyphs());
            assertEquals(PdfGlyphLines.lines(input), result.lines());
        }
        final var competing = body(0);
        competing.addAll(body(0).stream().map(g -> glyph(g.text(), g.x(), 94, g.width(), g.height())).toList());
        competing.addAll(marker);
        assertEquals(0, PdfSmallGlyphRuns.lines(competing, new PdfTableWork()).assignedGlyphs());
        final var crossed = body(0);
        crossed.addAll(marker);
        crossed.add(glyph("x", 26, 100, 2, 8));
        assertEquals(0, PdfSmallGlyphRuns.lines(crossed, new PdfTableWork()).assignedGlyphs());
    }

    /**
     * Shared work bounds and caller interruption remain typed even for empty input.
     * @throws Exception if cancellation checks fail
     */
    @Test
    void sharedWorkAndInterruptionRemainEnforced() throws Exception {
        final var work = new PdfTableWork();
        for (int index = 0; index < 2_000_000; index++) {
            work.step();
        }
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> PdfSmallGlyphRuns.lines(List.of(), work)).code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> PdfSmallGlyphRuns.lines(List.of(), new PdfTableWork()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // The test owns this interrupt and clears it before the runner reuses the thread.
            Thread.interrupted();
        }
    }

    /**
     * The page path compares each run only with its baseline band, yet must equal the whole-input rule exactly,
     * including competing baselines, overlaps, long runs and distant small text, on seeded synthetic pages.
     * @throws Exception if grouping fails
     */
    @Test
    void pageBaselinesEqualTheWholeInputRule() throws Exception {
        final var random = new java.util.Random(20261022L);
        int assigned = 0;
        for (int sample = 0; sample < 300; sample++) {
            final var input = new ArrayList<PdfPageText.Glyph>();
            final int lines = 1 + random.nextInt(6);
            for (int line = 0; line < lines; line++) {
                final float baseline = 100 + line * (6 + random.nextInt(14));
                for (int index = 0; index < 4 + random.nextInt(8); index++) {
                    input.add(glyph("b", 10 + index * 5, baseline, 5, 8 + random.nextInt(2)));
                }
                for (int run = 0; run < random.nextInt(3); run++) {
                    final float x = 10 + random.nextInt(60);
                    final float shift = (random.nextBoolean() ? -1 : 1) * (1 + random.nextInt(9));
                    final int length = 1 + random.nextInt(10);
                    for (int index = 0; index < length; index++) {
                        input.add(glyph(
                                "s", x + index * (2 + random.nextInt(2)), baseline + shift, 2, 3 + random.nextInt(3)));
                    }
                }
            }
            java.util.Collections.shuffle(input, random);
            final var whole = PdfSmallGlyphRuns.lines(input, new PdfTableWork());
            final var banded = PdfSmallGlyphRuns.pageBaselines(input, new PdfTableWork());
            assertEquals(whole.assignedGlyphs(), banded.size());
            assertEquals(
                    whole.lines(), PdfGlyphLines.lines(input, glyph -> banded.getOrDefault(glyph, (double) glyph.y())));
            assigned += banded.size();
        }
        assertTrue(assigned > 0, "Seeded pages must exercise accepted assignments");
    }

    /**
     * The page path charges the same shared work counter and honours caller interruption.
     * @throws Exception if cancellation checks fail
     */
    @Test
    void pageBaselinesEnforceSharedWorkAndInterruption() throws Exception {
        final var work = new PdfTableWork();
        for (int index = 0; index < 2_000_000; index++) {
            work.step();
        }
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> PdfSmallGlyphRuns.pageBaselines(body(0), work)).code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    InterruptedIOException.class, () -> PdfSmallGlyphRuns.pageBaselines(body(0), new PdfTableWork()));
        } finally {
            // The test owns this interrupt and clears it before the runner reuses the thread.
            Thread.interrupted();
        }
        assertTrue(PdfSmallGlyphRuns.pageBaselines(List.of(), new PdfTableWork()).isEmpty());
    }

    /**
     * Builds four body positions with a stable nonblank median height.
     * @param start original left edge
     * @return mutable owned source positions
     */
    private static ArrayList<PdfPageText.Glyph> body(final float start) {
        final var result = new ArrayList<PdfPageText.Glyph>();
        for (int index = 0; index < 4; index++) {
            result.add(glyph("abcd".substring(index, index + 1), start + index * 5, 100, 5, 8));
        }
        return result;
    }

    /**
     * Creates one original upright glyph without normalization.
     * @param value original text
     * @param x original left
     * @param y original baseline
     * @param width original advance
     * @param height original height
     * @return owned native source position
     */
    private static PdfPageText.Glyph glyph(
            final String value, final float x, final float y, final float width, final float height) {
        return new PdfPageText.Glyph(value, x, y, width, height, 3, 0);
    }
}
