package io.docweave.partition;

import java.awt.Color;
import java.awt.image.BufferedImage;

/** Owned fixture pixels identify source pages independently of concurrent callback arrival order. */
final class PdfTestPageMarker {
    /** Prevents utility construction. */
    private PdfTestPageMarker() {
    }

    /**
     * Encodes a page without introducing a dark line into table-detection fixtures.
     * @param page zero-based page index
     * @return pale marker color
     */
    static Color color(final int page) {
        return new Color(255, 220 + page, 255);
    }

    /**
     * Reads an authored marker from a page, rotated page or owned cell crop.
     * @param image rendered source or cell
     * @return original zero-based page index
     */
    static int read(final BufferedImage image) {
        for (final int offset : new int[] {1, 4, 5, 6}) {
            for (final int x : new int[] {offset, image.getWidth() - offset - 1}) {
                for (final int y : new int[] {offset, image.getHeight() - offset - 1}) {
                    if (x >= 0 && x < image.getWidth() && y >= 0 && y < image.getHeight()) {
                        final var color = new Color(image.getRGB(x, y));
                        if (color.getRed() == 255 && color.getBlue() == 255 && color.getGreen() >= 220
                                && color.getGreen() <= 230) {
                            return color.getGreen() - 220;
                        }
                    }
                }
            }
        }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final var color = new Color(image.getRGB(x, y));
                if (color.getRed() == 255 && color.getBlue() == 255 && color.getGreen() >= 220
                        && color.getGreen() <= 230) {
                    return color.getGreen() - 220;
                }
            }
        }
        throw new AssertionError("Owned source page marker is missing");
    }
}
