package dev.docweave.partition;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;

/**
 * Reads an open, caller-owned channel into an owned exact-length byte array.
 * Size is only an allocation hint, capped by the input budget; actual reads enforce the limit.
 * Stable files avoid geometric buffer growth and a final copy. Concurrent truncation trims the
 * result; growth expands within the budget. Reading one extra byte distinguishes exact-limit EOF
 * from overflow. No returned buffer contains unused capacity, including for binary inputs.
 */
final class BoundedFileInput {
    /** Prevents instantiation of this static utility class. */
    private BoundedFileInput() {
    }

    /**
     * Does not close the channel; polls interruption around reads and before returning.
     *
     * @param channel open seekable byte channel to read
     * @param limit maximum allowed input byte count
     * @return exact-length byte array containing all bytes read from {@code channel}
     * @throws IOException if the input exceeds {@code limit}, the thread is interrupted, or channel I/O fails
     */
    static byte[] read(final SeekableByteChannel channel, final int limit) throws IOException {
        ElementCollector.checkInterrupted();
        byte[] bytes = new byte[(int) Math.min(Math.max(0, channel.size()), limit)];
        var target = ByteBuffer.wrap(bytes);
        ByteBuffer extra = null;
        while (true) {
            ElementCollector.checkInterrupted();
            if (target.position() < bytes.length) {
                target.limit((int) Math.min(bytes.length, (long) target.position() + 8192));
                final int read = channel.read(target);
                ElementCollector.checkInterrupted();
                if (read < 0) {
                    return finish(bytes, target.position());
                }
            } else {
                if (extra == null) {
                    extra = ByteBuffer.allocate(1);
                }
                extra.clear();
                final int read = channel.read(extra);
                ElementCollector.checkInterrupted();
                if (read < 0) {
                    return bytes;
                }
                if (read == 0) {
                    continue;
                }
                final int used = target.position();
                if (used == limit) {
                    throw new PartitionException(
                            PartitionException.Code.INPUT_TOO_LARGE, "Document exceeds maxInputBytes=" + limit);
                }
                final int capacity = (int) Math.min(limit, Math.max(8192L, 2L * bytes.length));
                bytes = Arrays.copyOf(bytes, capacity);
                bytes[used] = extra.get(0);
                target = ByteBuffer.wrap(bytes);
                target.position(used + 1);
            }
        }
    }

    /**
     * Returns {@code bytes} directly if full, or a trimmed copy of length {@code used}.
     *
     * @param bytes backing buffer
     * @param used number of valid leading bytes in {@code bytes}
     * @return exact-length byte array of length {@code used}
     */
    private static byte[] finish(byte[] bytes, final int used) {
        return used == bytes.length ? bytes : Arrays.copyOf(bytes, used);
    }
}
