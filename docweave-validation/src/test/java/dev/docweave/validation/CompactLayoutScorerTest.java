package dev.docweave.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Rounded positive cell interiors, migrated from {@code test_compact_layout.py}; no model or OCR. */
class CompactLayoutScorerTest {
    /** Creates the controls. */
    CompactLayoutScorerTest() {
    }

    @Test
    void fractionalCenterlinesRoundInwardAndKeepCompactInteriorsPositive() {
        assertArrayEquals(new long[] {16, 26, 21, 27},
                CompactLayoutScorer.interior(new TableGeometry.Box(10.5, 20.5, 26.5, 32.5)));
        assertArrayEquals(
                new long[] {15, 15, 21, 17}, CompactLayoutScorer.interior(new TableGeometry.Box(10, 10, 26, 22)));
    }

    @Test
    void tooSmallPredictionCannotBeRepairedIntoAPositiveInterior() {
        final var failure = assertThrows(IllegalArgumentException.class,
                () -> CompactLayoutScorer.interior(new TableGeometry.Box(10, 10, 20, 20)));
        assertEquals("Nonpositive predicted interior", failure.getMessage());
    }
}
