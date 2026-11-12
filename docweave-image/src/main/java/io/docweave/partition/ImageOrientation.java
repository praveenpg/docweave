package io.docweave.partition;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/** EXIF/TIFF orientation (tag 274) and the upright, opaque raster that OCR reads. */
final class ImageOrientation {
    /** The TIFF/EXIF Orientation tag. */
    static final int TAG = 274;

    /** Utility class. */
    private ImageOrientation() {
    }

    /**
     * Reads the Orientation of a JPEG's first EXIF segment, scanning markers up to the first scan.
     * @param jpeg JPEG bytes, read in place
     * @return orientation 1 to 8, or 1 when absent or invalid
     */
    static int jpeg(final ByteBuffer jpeg) {
        final ByteBuffer in = jpeg.duplicate();
        final int base = in.position();
        final int end = in.limit();
        int at = base + 2;
        while (at + 4 <= end && (in.get(at) & 0xff) == 0xff) {
            if ((in.get(at + 1) & 0xff) == 0xff) {
                // A fill byte: markers may be preceded by any number of 0xFF bytes.
                at++;
                continue;
            }
            final int marker = in.get(at + 1) & 0xff;
            if (marker == 0xd8 || marker >= 0xd0 && marker <= 0xd7 || marker == 0x01) {
                at += 2;
                continue;
            }
            if (marker == 0xda || marker == 0xd9) {
                return 1;
            }
            final int length = (in.get(at + 2) & 0xff) << 8 | in.get(at + 3) & 0xff;
            if (length < 2 || at + 2 + length > end) {
                return 1;
            }
            final int data = at + 4;
            if (marker == 0xe1 && length >= 16 && in.get(data) == 'E' && in.get(data + 1) == 'x'
                    && in.get(data + 2) == 'i' && in.get(data + 3) == 'f' && in.get(data + 4) == 0
                    && in.get(data + 5) == 0) {
                return tiffHeader(in, data + 6, at + 2 + length);
            }
            at += 2 + length;
        }
        return 1;
    }

    /**
     * Reads IFD0's Orientation from an embedded TIFF structure.
     * @param in buffer
     * @param start TIFF header index
     * @param end exclusive end of the segment
     * @return orientation 1 to 8, or 1
     */
    private static int tiffHeader(final ByteBuffer in, final int start, final int end) {
        if (start + 8 > end) {
            return 1;
        }
        final boolean little = in.get(start) == 'I' && in.get(start + 1) == 'I';
        if (!little && !(in.get(start) == 'M' && in.get(start + 1) == 'M') || u16(in, start + 2, little) != 42) {
            return 1;
        }
        final long ifd = u32(in, start + 4, little);
        if (ifd < 8 || start + ifd + 2 > end) {
            return 1;
        }
        final int entries = u16(in, start + (int) ifd, little);
        for (int index = 0; index < entries; index++) {
            final int entry = start + (int) ifd + 2 + index * 12;
            if (entry + 12 > end) {
                return 1;
            }
            if (u16(in, entry, little) == TAG && u16(in, entry + 2, little) == 3 && u32(in, entry + 4, little) == 1) {
                return valid(u16(in, entry + 8, little));
            }
        }
        return 1;
    }

    /**
     * Returns a value if it is a defined orientation.
     * @param value raw tag value
     * @return value 1 to 8, else 1
     */
    static int valid(final int value) {
        return value >= 1 && value <= 8 ? value : 1;
    }

    /**
     * Reads an unsigned 16-bit value.
     * @param in buffer
     * @param at absolute index
     * @param little whether little-endian
     * @return value
     */
    private static int u16(final ByteBuffer in, final int at, final boolean little) {
        final int a = in.get(at) & 0xff;
        final int b = in.get(at + 1) & 0xff;
        return little ? b << 8 | a : a << 8 | b;
    }

    /**
     * Reads an unsigned 32-bit value.
     * @param in buffer
     * @param at absolute index
     * @param little whether little-endian
     * @return value
     */
    private static long u32(final ByteBuffer in, final int at, final boolean little) {
        final long high = u16(in, at + (little ? 2 : 0), little);
        final long low = u16(in, at + (little ? 0 : 2), little);
        return high << 16 | low;
    }

    /**
     * Returns the upright, opaque raster: the decoded image unchanged when it is already upright without alpha,
     * otherwise one RGB copy composited on white (transparent areas become white, as for a printed page).
     * @param decoded decoded page
     * @param orientation orientation 1 to 8
     * @return upright raster; the decoded image itself or a new one the caller flushes
     */
    static BufferedImage upright(final BufferedImage decoded, final int orientation) {
        if (orientation == 1 && !decoded.getColorModel().hasAlpha()) {
            return decoded;
        }
        final int w = decoded.getWidth();
        final int h = decoded.getHeight();
        final boolean swap = orientation >= 5;
        final var target = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        final var graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
            graphics.drawImage(decoded, transform(orientation, w, h), null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /**
     * Maps stored pixels to displayed pixels for an orientation.
     * @param orientation 1 to 8
     * @param w stored width
     * @param h stored height
     * @return transform
     */
    static AffineTransform transform(final int orientation, final int w, final int h) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> new AffineTransform();
        };
    }
}
