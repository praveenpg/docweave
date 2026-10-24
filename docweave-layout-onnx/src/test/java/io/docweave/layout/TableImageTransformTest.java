package io.docweave.layout;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class TableImageTransformTest {
    @Test
    void upsamplingAndAntialiasedDownsamplingMatchIndependentPillowRgbVectors() throws Exception {
        check(7, 5, 800, 571, "6bc5748f5e4d1a3321e33bb512ccda8bd9cc658763c5840684c69bca013a1bff");
        check(1201, 503, 800, 335, "fd725a146b40a2bca43ecd077ee1bcc845abf00c7a243205edffe47b92607c95");
        check(17, 1301, 10, 800, "dd4939f17000bcf06e3d538e6fbaa32b546a858606fb9ba5cc1c9fe311a2a5dd");
    }

    private static void check(final int width, final int height, final int expectedWidth, final int expectedHeight,
            final String expectedHash) throws Exception {
        final var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y,
                        ((x * 37 + y * 11) & 255) << 16 | ((x * 13 + y * 43) & 255) << 8 | ((x * 71 + y * 5) & 255));
            }
        }
        final var options = OnnxTableOptions.defaults(Path.of("unused"), "0".repeat(64));
        final var pixels =
                new BilinearTableImageTransform().prepare(image, options, new LayoutDeadline(Duration.ofSeconds(5)));
        assertEquals(expectedWidth, pixels.width());
        assertEquals(expectedHeight, pixels.height());
        final int count = expectedWidth * expectedHeight;
        final float[] means = {.485f, .456f, .406f};
        final float[] deviations = {.229f, .224f, .225f};
        final var bytes = new byte[count * 3];
        for (int index = 0; index < count; index++) {
            for (int channel = 0; channel < 3; channel++) {
                bytes[index * 3 + channel] = (byte) Math.round(
                        (pixels.values().get(channel * count + index) * deviations[channel] + means[channel]) * 255f);
            }
            assertEquals(1L, pixels.mask().get(index));
        }
        assertEquals(expectedHash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    void fullyTransparentPixelsCompositeToWhiteWithoutSourceMutation() throws Exception {
        final var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x00ff0000);
        final var options = OnnxTableOptions.defaults(Path.of("unused"), "0".repeat(64));
        final var pixels =
                new BilinearTableImageTransform().prepare(image, options, new LayoutDeadline(Duration.ofSeconds(5)));
        assertEquals((1f - .485f) / .229f, pixels.values().get(0));
        assertEquals(0x00ff0000, image.getRGB(0, 0));
    }
}
