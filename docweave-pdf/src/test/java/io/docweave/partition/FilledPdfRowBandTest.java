package io.docweave.partition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Original cell-paint eligibility is independent of the eventual complete row envelope. */
class FilledPdfRowBandTest {
    /** Creates isolated original path controls. */
    FilledPdfRowBandTest() {
    }

    /** A tall bounded numeric cell retains its original rectangle before row coalescing. */
    @Test
    void tallCellPaintRetainsOriginalBounds() {
        final var band = FilledPdfRowBand.from(rectangle(72, 27), 0xeeeeee);
        assertNotNull(band);
        assertEquals(new PdfLayoutItem.Bounds(10, 20, 82, 47), band.bounds());
        assertEquals(0xeeeeee, band.rgb());
    }

    /** Dimension, color and disconnected-path guards remain independent of cell aspect. */
    @Test
    void unsupportedPaintRemainsRejected() {
        for (final var dimensions : List.of(List.of(47, 27), List.of(72, 2), List.of(72, 61))) {
            assertNull(FilledPdfRowBand.from(rectangle(dimensions.getFirst(), dimensions.getLast()), 0xeeeeee));
        }
        assertNull(FilledPdfRowBand.from(rectangle(72, 27), 0x777777));
        assertNull(FilledPdfRowBand.from(rectangle(72, 27), 0xffffff));
        final var valid = rectangle(72, 27);
        assertNull(FilledPdfRowBand.from(List.of(valid.get(1), valid.get(0), valid.get(2), valid.get(3)), 0xeeeeee));
    }

    /**
     * Creates one connected original upright closed path.
     * @param width original rectangle width
     * @param height original rectangle height
     * @return four connected source edges
     */
    private static List<PdfPageGraphics.Segment> rectangle(final int width, final int height) {
        return List.of(new PdfPageGraphics.Segment(10, 20, 10 + width, 20),
                new PdfPageGraphics.Segment(10 + width, 20, 10 + width, 20 + height),
                new PdfPageGraphics.Segment(10 + width, 20 + height, 10, 20 + height),
                new PdfPageGraphics.Segment(10, 20 + height, 10, 20));
    }
}
