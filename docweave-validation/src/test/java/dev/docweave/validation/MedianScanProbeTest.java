package dev.docweave.validation;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;

import static org.junit.jupiter.api.Assertions.*;

/** Owned pixel/ownership/cancellation controls, including a deliberate thin-stroke failure mode. */
class MedianScanProbeTest {
    /** Creates source-independent controls. */
    MedianScanProbeTest() {
    }

    /**
     * Noise removal is not an accuracy oracle: a legitimate thin line is erased too; borrowed pixels stay intact.
     * @throws Exception if the bounded filter fails
     */
    @Test
    void fixedPixelsPreserveOwnershipAndExposeStrokeRisk() throws Exception {
        final var source = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);
        try {
            assertThrows(IllegalArgumentException.class, () -> MedianScanProbe.apply(source, 8));
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 3; x++) {
                    source.setRGB(x, y, 0xffffff);
                }
            }
            source.setRGB(1, 1, 0);
            var filtered = MedianScanProbe.apply(source, 9);
            try {
                assertEquals(0xffffff, filtered.getRGB(1, 1) & 0xffffff);
                assertEquals(0, source.getRGB(1, 1) & 0xffffff);
                assertEquals(3, filtered.getWidth());
                assertEquals(3, filtered.getHeight());
            } finally {
                filtered.flush();
            }
            source.setRGB(1, 0, 0);
            source.setRGB(1, 2, 0);
            filtered = MedianScanProbe.apply(source, 9);
            try {
                assertEquals(0xffffff, filtered.getRGB(1, 1) & 0xffffff,
                        "A legitimate one-pixel stroke is a required negative for general enhancement claims");
            } finally {
                filtered.flush();
            }
            for (int i = 0; i < 9; i++) {
                final int blue = i == 4 ? 99 : i % 2 == 0 ? 255 : 0;
                source.setRGB(i % 3, i / 3, ((i * 10) << 16) | (((8 - i) * 10) << 8) | blue);
            }
            filtered = MedianScanProbe.apply(source, 9);
            try {
                assertEquals(0x282863, filtered.getRGB(1, 1) & 0xffffff);
            } finally {
                filtered.flush();
            }
        } finally {
            source.flush();
        }
    }

    /**
     * Constant color and tiny borders stay unchanged; oversized input and cancellation reject before work.
     * @throws Exception if bounded filtering fails
     */
    @Test
    void tinyBordersBoundsAndCancellationRemainExplicit() throws Exception {
        final var source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        try {
            source.setRGB(0, 0, 0x123456);
            final var filtered = MedianScanProbe.apply(source, 1);
            try {
                assertEquals(0x123456, filtered.getRGB(0, 0) & 0xffffff);
            } finally {
                filtered.flush();
            }
            assertThrows(IllegalArgumentException.class, () -> MedianScanProbe.apply(source, 0));
            assertThrows(NullPointerException.class, () -> MedianScanProbe.apply(null, 1));
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> MedianScanProbe.apply(source, 1));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        } finally {
            source.flush();
        }
    }
}
