package io.docweave.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

/** Bounds actual bytes read independently of mutable or unavailable file-size metadata. */
final class BoundedJsonInput {
    /** Prevents instantiation of this stateless utility class. */
    private BoundedJsonInput() {
    }

    /**
     * Reads at most the budget plus one detection byte; the caller owns the stream.
     * @param input byte stream to read
     * @param maximum maximum allowed byte count
     * @return all bytes read from the stream within the configured budget
     * @throws IOException if reading fails or the calling thread is interrupted
     */
    static byte[] read(final InputStream input, final int maximum) throws IOException {
        final var output = new ByteArrayOutputStream();
        final var buffer = new byte[8192];
        while (true) {
            checkInterrupted();
            final int count = input.read(buffer, 0, (int) Math.min(buffer.length, (long) maximum - output.size() + 1));
            checkInterrupted();
            if (count == -1) {
                return output.toByteArray();
            }
            if ((long) output.size() + count > maximum) {
                throw new IllegalArgumentException("JSON input byte limit exceeded");
            }
            output.write(buffer, 0, count);
        }
    }

    /**
     * Checks whether the current thread has been interrupted without clearing its interrupt status.
     * @throws InterruptedIOException if the current thread is interrupted
     */
    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("JSON reading interrupted");
        }
    }
}
