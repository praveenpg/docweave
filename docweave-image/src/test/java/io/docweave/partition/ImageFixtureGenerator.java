package io.docweave.partition;

import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;

/**
 * Writes the owned #76 image fixtures (synthetic text rendered by Java2D; no third-party content). Run once and commit
 * the output: {@code java ImageFixtureGenerator docweave-image/src/test/resources/io/docweave/partition/image}.
 */
public final class ImageFixtureGenerator {
    /** Page text of the single-page fixtures, in reading order. */
    static final List<String> PAGE =
            List.of("DocWeave image acceptance", "Invoice 4471 dated 12 March 2026", "Total due: 1,250.00 EUR");

    /** Text of each page of the TIFF fixture. */
    static final List<String> TIFF_PAGES =
            List.of("Page one alpha harbor", "Page two bravo meadow", "Page three charlie summit");

    private ImageFixtureGenerator() {
    }

    /**
     * Writes the fixtures.
     * @param args output directory
     * @throws IOException if writing fails
     */
    public static void main(final String[] args) throws IOException {
        final Path out = Files.createDirectories(Path.of(args[0]));
        final BufferedImage page = text(1600, 520, PAGE);
        ImageIO.write(page, "png", out.resolve("text.png").toFile());
        Files.write(out.resolve("text-exif6.jpg"), exif(jpeg(rotate(page, 270)), 6));
        Files.write(out.resolve("text.jpg"), jpeg(page));
        ImageIO.write(new BufferedImage(800, 400, BufferedImage.TYPE_BYTE_GRAY) {
            {
                final var g = createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.dispose();
            }
        }, "png", out.resolve("blank.png").toFile());
        final var writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (final var stream = ImageIO.createImageOutputStream(out.resolve("pages-g4.tif").toFile())) {
            writer.setOutput(stream);
            final ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType("CCITT T.6");
            writer.prepareWriteSequence(null);
            for (final String line : TIFF_PAGES) {
                final var bilevel = new BufferedImage(1400, 300, BufferedImage.TYPE_BYTE_BINARY);
                final var g = bilevel.createGraphics();
                g.drawImage(text(1400, 300, List.of(line)), 0, 0, null);
                g.dispose();
                writer.writeToSequence(new IIOImage(bilevel, null,
                                               writer.getDefaultImageMetadata(
                                                       ImageTypeSpecifier.createFromRenderedImage(bilevel), param)),
                        param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    /**
     * Renders black lines of text on white.
     * @param width pixels
     * @param height pixels
     * @param lines text
     * @return RGB image
     */
    static BufferedImage text(final int width, final int height, final List<String> lines) {
        final var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final var g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 56));
            int y = 110;
            for (final String line : lines) {
                g.drawString(line, 80, y);
                y += 140;
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * Rotates clockwise by a quarter-turn multiple.
     * @param source image
     * @param degrees 90, 180 or 270
     * @return rotated RGB image
     */
    static BufferedImage rotate(final BufferedImage source, final int degrees) {
        final boolean swap = degrees % 180 != 0;
        final int w = source.getWidth();
        final int h = source.getHeight();
        final var target = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        final var g = target.createGraphics();
        try {
            final var transform = switch (degrees) {
                case 90 -> new AffineTransform(0, 1, -1, 0, h, 0);
                case 180 -> new AffineTransform(-1, 0, 0, -1, w, h);
                default -> new AffineTransform(0, -1, 1, 0, 0, w);
            };
            g.drawImage(source, transform, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /**
     * Encodes a baseline JPEG.
     * @param image RGB image
     * @return JPEG bytes
     * @throws IOException if encoding fails
     */
    static byte[] jpeg(final BufferedImage image) throws IOException {
        final var out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    /**
     * Inserts an EXIF APP1 segment with one Orientation entry after SOI.
     * @param jpeg JPEG bytes
     * @param orientation 1 to 8
     * @return JPEG bytes with EXIF
     */
    static byte[] exif(final byte[] jpeg, final int orientation) {
        final byte[] tiff = {
                'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 1, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, 0, 0, 0, 0};
        final int length = 2 + 6 + tiff.length;
        final var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xff);
        out.write(0xe1);
        out.write(length >> 8);
        out.write(length & 0xff);
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
