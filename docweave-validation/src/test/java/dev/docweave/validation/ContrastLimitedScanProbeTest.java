package dev.docweave.validation;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;

import static org.junit.jupiter.api.Assertions.*;

/** Owned numerical, source-ownership and bounded-resource controls for the independent contrast hypothesis. */
class ContrastLimitedScanProbeTest {
    /** Creates independent raster controls. */
    ContrastLimitedScanProbeTest() {
    }

    /**
     * Flat gray fields keep every original intensity; transparent colors composite over white.
     * @throws Exception if bounded preprocessing fails
     */
    @Test
    void uniformFieldsAndAlphaHaveExplicitStableSemantics() throws Exception {
        final var source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        try {
            for (int value = 0; value < 256; value++) {
                source.setRGB(0, 0, 0xff000000 | value * 0x010101);
                final var output = ContrastLimitedScanProbe.apply(source, 1, 300);
                try {
                    assertEquals(source.getRGB(0, 0), output.getRGB(0, 0));
                } finally {
                    output.flush();
                }
            }
            source.setRGB(0, 0, 0x00ff0000);
            final var output = ContrastLimitedScanProbe.apply(source, 1, 300);
            try {
                assertEquals(0xffffffff, output.getRGB(0, 0));
            } finally {
                output.flush();
            }
            assertEquals(0x00ff0000, source.getRGB(0, 0));
        } finally {
            source.flush();
        }
    }

    /**
     * A complete64square tile has independently specified clipped populations and cumulative output values.
     * @throws Exception if preprocessing or exact output validation fails
     */
    @Test
    void clippedHistogramConservesPopulationAndExpandsThisOwnedContrast() throws Exception {
        final var source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    source.setRGB(x, y, (x < 32 ? 120 : 130) * 0x010101);
                }
            }
            final var original = source.getRGB(0, 0, 64, 64, null, 0, 64);
            final var output = ContrastLimitedScanProbe.apply(source, 4096, 300);
            try {
                assertEquals(123, output.getRGB(0, 0) & 255);
                assertEquals(134, output.getRGB(63, 63) & 255);
                assertArrayEquals(original, source.getRGB(0, 0, 64, 64, null, 0, 64));
                assertEquals(64, output.getWidth());
                assertEquals(64, output.getHeight());
            } finally {
                output.flush();
            }
        } finally {
            source.flush();
        }
    }

    /**
     * Partial tiles, DPI-scaled grids and deterministic source ownership survive repeated independent calls.
     * @throws Exception if bounded preprocessing fails
     */
    @Test
    void partialBordersRemainDeterministicAtDeclaredResolutions() throws Exception {
        final var source = new BufferedImage(131, 69, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    source.setRGB(x, y, ((x * 3 + y * 7) & 255) * 0x010101);
                }
            }
            final var original = source.getRGB(0, 0, 131, 69, null, 0, 131);
            for (final int dpi : new int[] {70, 288, 300, 600}) {
                final var first = ContrastLimitedScanProbe.apply(source, 131 * 69, dpi);
                final var second = ContrastLimitedScanProbe.apply(source, 131 * 69, dpi);
                try {
                    assertArrayEquals(
                            first.getRGB(0, 0, 131, 69, null, 0, 131), second.getRGB(0, 0, 131, 69, null, 0, 131));
                } finally {
                    first.flush();
                    second.flush();
                }
            }
            assertArrayEquals(original, source.getRGB(0, 0, 131, 69, null, 0, 131));
        } finally {
            source.flush();
        }
    }

    /**
     * Invalid limits fail before output allocation, and cancellation preserves the flag without poisoning reuse.
     * @throws Exception if a successful reuse call fails
     */
    @Test
    void invalidBoundsAndCancellationDoNotMutateOrConsumeSource() throws Exception {
        final var source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try {
            assertThrows(IllegalArgumentException.class, () -> ContrastLimitedScanProbe.apply(source, 3, 300));
            assertThrows(IllegalArgumentException.class, () -> ContrastLimitedScanProbe.apply(source, 4, 69));
            assertThrows(IllegalArgumentException.class, () -> ContrastLimitedScanProbe.apply(source, 4, 601));
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> ContrastLimitedScanProbe.apply(source, 4, 300));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            final var output = ContrastLimitedScanProbe.apply(source, 4, 300);
            output.flush();
            final var wide = new BufferedImage(245_761, 1, BufferedImage.TYPE_INT_RGB);
            try {
                assertThrows(IllegalArgumentException.class, () -> ContrastLimitedScanProbe.apply(wide, 245_761, 70));
            } finally {
                wide.flush();
            }
        } finally {
            source.flush();
        }
    }
}
