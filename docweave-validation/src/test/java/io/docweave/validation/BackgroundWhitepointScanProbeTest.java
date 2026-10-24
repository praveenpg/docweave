package io.docweave.validation;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;

import static org.junit.jupiter.api.Assertions.*;

/** Owned pixel, geometry, resource and known gray-field failure controls for the evaluation-only hypothesis. */
class BackgroundWhitepointScanProbeTest {
    /** Creates independent owned raster controls. */
    BackgroundWhitepointScanProbeTest() {
    }

    /**
     * Uneven neutral background brightens while black source strokes and borrowed image pixels survive.
     * @throws Exception if bounded filtering fails
     */
    @Test
    void localWhitepointsPreserveBlackStrokesAndSourceOwnership() throws Exception {
        final var source = new BufferedImage(128, 64, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 128; x++) {
                    source.setRGB(x, y, x < 64 ? 0x808080 : 0xffffff);
                }
            }
            source.setRGB(10, 10, 0);
            source.setRGB(110, 10, 0);
            final var result = BackgroundWhitepointScanProbe.apply(source, 128 * 64, 300);
            try {
                assertEquals(128, result.getWidth());
                assertEquals(64, result.getHeight());
                assertEquals(0xffffff, result.getRGB(10, 20) & 0xffffff);
                assertEquals(0xffffff, result.getRGB(110, 20) & 0xffffff);
                assertEquals(0, result.getRGB(10, 10) & 0xffffff);
                assertEquals(0, result.getRGB(110, 10) & 0xffffff);
                assertEquals(0x808080, source.getRGB(10, 20) & 0xffffff);
                assertEquals(0, source.getRGB(10, 10) & 0xffffff);
            } finally {
                result.flush();
            }
        } finally {
            source.flush();
        }
    }

    /**
     * A uniform legitimate gray field is erased; fixed luminance conversion is explicit rather than color fidelity.
     * @throws Exception if bounded filtering fails
     */
    @Test
    void tinyEdgesExposeLegitimateGrayFieldLoss() throws Exception {
        final var source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        try {
            source.setRGB(0, 0, 0x808080);
            final var result = BackgroundWhitepointScanProbe.apply(source, 1, 600);
            try {
                assertEquals(0xffffff, result.getRGB(0, 0) & 0xffffff,
                        "Normalizing background may erase legitimate gray content; it is not a universal enhancement");
                assertEquals(0x808080, source.getRGB(0, 0) & 0xffffff);
            } finally {
                result.flush();
            }
        } finally {
            source.flush();
        }
        final var colored = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    colored.setRGB(x, y, 0xffffff);
                }
            }
            colored.setRGB(10, 10, 0xff0000);
            final var result = BackgroundWhitepointScanProbe.apply(colored, 4096, 300);
            try {
                assertEquals(0x4d4d4d, result.getRGB(10, 10) & 0xffffff);
                assertEquals(0xff0000, colored.getRGB(10, 10) & 0xffffff);
            } finally {
                result.flush();
            }
        } finally {
            colored.flush();
        }
    }

    /**
     * Invalid limits and cancellation fail without consuming caller state or modifying the borrowed raster.
     * @throws Exception if bounded control setup fails
     */
    @Test
    void boundsAndCancellationRemainExplicit() throws Exception {
        final var source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try {
            assertThrows(NullPointerException.class, () -> BackgroundWhitepointScanProbe.apply(null, 4, 300));
            assertThrows(IllegalArgumentException.class, () -> BackgroundWhitepointScanProbe.apply(source, 3, 300));
            assertThrows(IllegalArgumentException.class, () -> BackgroundWhitepointScanProbe.apply(source, 4, 69));
            assertThrows(IllegalArgumentException.class, () -> BackgroundWhitepointScanProbe.apply(source, 4, 601));
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> BackgroundWhitepointScanProbe.apply(source, 4, 300));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                // This owned test sets the flag itself; clear it for subsequent JVM controls.
                Thread.interrupted();
            }
            assertEquals(0, source.getRGB(0, 0) & 0xffffff);
        } finally {
            source.flush();
        }
    }
}
