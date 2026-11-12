package io.docweave.partition;

import java.nio.ByteBuffer;
import java.util.Objects;
import javax.imageio.stream.ImageInputStreamImpl;

/**
 * A seekable image stream reading the caller's buffer in place: unlike ImageIO's cached streams it neither copies
 * the input nor caches what it has read, and it never changes the source buffer's position.
 */
final class ImageBufferInput extends ImageInputStreamImpl {
    /** Independent view of the input. */
    private final ByteBuffer buffer;

    /** Absolute index of the first input byte in {@link #buffer}. */
    private final int base;

    /** Input length in bytes. */
    private final int size;

    /**
     * Wraps the remaining bytes of a buffer.
     * @param source caller-owned buffer, read from its current position to its limit
     */
    ImageBufferInput(final ByteBuffer source) {
        buffer = Objects.requireNonNull(source, "source").duplicate();
        base = buffer.position();
        size = buffer.remaining();
    }

    @Override
    public int read() {
        bitOffset = 0;
        if (streamPos >= size) {
            return -1;
        }
        return buffer.get(base + (int) streamPos++) & 0xff;
    }

    @Override
    public int read(final byte[] target, final int offset, final int length) {
        Objects.checkFromIndexSize(offset, length, target.length);
        bitOffset = 0;
        if (length == 0) {
            return 0;
        }
        if (streamPos >= size) {
            return -1;
        }
        final int count = (int) Math.min(length, size - streamPos);
        buffer.get(base + (int) streamPos, target, offset, count);
        streamPos += count;
        return count;
    }

    @Override
    public long length() {
        return size;
    }
}
