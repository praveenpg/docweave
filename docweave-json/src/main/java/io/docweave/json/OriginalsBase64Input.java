package io.docweave.json;

import java.io.InputStream;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Strictly prevalidated ASCII view over a caller-owned base64 String, without a full encoded-byte copy. */
final class OriginalsBase64Input extends InputStream {
    /** Multiple-of-four validation block, bounding temporary JDK decoder buffers. */
    private static final int VALIDATION_BLOCK = 8192;
    /** Immutable borrowed source; caller-visible text is never changed. */
    private final String source;
    /** Next encoded ASCII character to read. */
    private int position;

    /**
     * Captures a complete already-validated source.
     * @param source strict basic-base64 text
     */
    private OriginalsBase64Input(final String source) {
        this.source = source;
    }

    /**
     * Validates the entire input before inflation, including suffixes beyond a completed zlib member.
     * Uses the same JDK basic decoder as the former whole-String path, with optional final padding.
     * Non-final blocks cannot contain padding; each fixed-size block ends on a quartet boundary.
     * This deliberately makes a validation pass before streaming decode to preserve strict tail handling.
     * @param source borrowed nonnull encoded text; caller checks its overall character limit first
     * @return independent stream cursor over the validated original String
     * @throws IllegalArgumentException if any alphabet, length, padding or suffix is malformed
     * @throws CancellationException if interrupted; the interrupt flag remains set
     */
    static InputStream open(final String source) {
        Objects.requireNonNull(source, "source");
        checkInterrupted();
        final var decoder = Base64.getDecoder();
        for (int start = 0; start < source.length(); start += VALIDATION_BLOCK) {
            checkInterrupted();
            final int end = Math.min(source.length(), start + VALIDATION_BLOCK);
            final String block = source.substring(start, end);
            if (end != source.length() && block.indexOf('=') >= 0) {
                throw new IllegalArgumentException("Base64 padding before end of original elements");
            }
            // Keep JDK acceptance of optional padding/noncanonical trailing bits without retaining decoded blocks.
            decoder.decode(block);
        }
        return new OriginalsBase64Input(source);
    }

    /**
     * Reads one prevalidated ASCII character without allocating its encoded form.
     * @return unsigned ASCII byte or minus one at end of source
     * @throws CancellationException if interrupted; flag preserved
     */
    @Override
    public int read() {
        checkInterrupted();
        return position == source.length() ? -1 : source.charAt(position++);
    }

    /**
     * Copies only the requested encoded window into a caller-owned decoder buffer.
     * @param target destination bytes
     * @param offset first destination index
     * @param length maximum encoded characters to read
     * @return copied count, zero for an empty request, or minus one at end
     * @throws IndexOutOfBoundsException if the destination window is invalid
     * @throws NullPointerException if target is null
     * @throws CancellationException if interrupted; flag preserved
     */
    @Override
    public int read(final byte[] target, final int offset, final int length) {
        Objects.checkFromIndexSize(offset, length, target.length);
        checkInterrupted();
        if (length == 0) {
            return 0;
        }
        if (position == source.length()) {
            return -1;
        }
        final int count = Math.min(length, source.length() - position);
        for (int index = 0; index < count; index++) {
            if ((index & 1023) == 0) {
                checkInterrupted();
            }
            target[offset + index] = (byte) source.charAt(position++);
        }
        return count;
    }

    /**
     * Cooperates with in-memory JSON cancellation without clearing the caller's flag.
     * @throws CancellationException if interrupted
     */
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Original-element decoding interrupted");
        }
    }
}
