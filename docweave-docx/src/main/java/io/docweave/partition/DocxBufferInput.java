package io.docweave.partition;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Reads a read-only byte buffer as a stream without copying it (#65). Each stream reads its own view, so the archive
 * verifier and POI can each read the input from the start while the context's bytes stay shared and unmodified.
 */
final class DocxBufferInput extends InputStream {
    /** Independent view of the input; its position is this stream's position. */
    private final ByteBuffer view;

    /**
     * Opens a stream over a buffer's remaining bytes.
     * @param input read-only input buffer; its own position is not changed
     */
    DocxBufferInput(final ByteBuffer input) {
        this.view = Objects.requireNonNull(input, "input").duplicate();
    }

    /**
     * Reads one byte.
     * @return the byte as 0 to 255, or -1 at the end
     */
    @Override
    public int read() {
        return view.hasRemaining() ? view.get() & 0xFF : -1;
    }

    /**
     * Reads up to {@code length} bytes into an array.
     * @param target destination
     * @param offset first index written
     * @param length most bytes read
     * @return bytes read, or -1 at the end
     */
    @Override
    public int read(final byte[] target, final int offset, final int length) {
        Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) {
            return 0;
        }
        if (!view.hasRemaining()) {
            return -1;
        }
        final int count = Math.min(length, view.remaining());
        view.get(target, offset, count);
        return count;
    }

    /**
     * Skips up to {@code count} bytes.
     * @param count bytes to skip
     * @return bytes skipped
     */
    @Override
    public long skip(final long count) {
        if (count <= 0) {
            return 0;
        }
        final int skipped = (int) Math.min(count, view.remaining());
        view.position(view.position() + skipped);
        return skipped;
    }

    /**
     * Returns the bytes left, which can be read without blocking.
     * @return remaining bytes
     */
    @Override
    public int available() {
        return view.remaining();
    }
}
