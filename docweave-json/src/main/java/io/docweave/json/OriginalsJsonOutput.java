package io.docweave.json;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;

/** Call-confined UTF8 byte accounting before provenance compression; the codec retains ownership of the target. */
final class OriginalsJsonOutput extends OutputStream {
    /** Borrowed compression stream, never closed by this accounting view. */
    private final OutputStream target;
    /** Unchanged decoder-compatible uncompressed byte ceiling. */
    private final int limit;
    /** Uncompressed UTF8 JSON bytes already admitted. */
    private int written;

    /**
     * Wraps one borrowed compressor without allocating a serialized JSON buffer.
     * @param target codec-owned compression output
     * @param limit positive uncompressed byte ceiling
     * @throws NullPointerException if target is absent
     * @throws IllegalArgumentException if limit is not positive
     */
    OriginalsJsonOutput(final OutputStream target, final int limit) {
        this.target = Objects.requireNonNull(target, "target");
        if (limit < 1) {
            throw new IllegalArgumentException("Original JSON byte limit must be positive");
        }
        this.limit = limit;
    }

    /**
     * Admits one UTF8 byte before delegating to the borrowed compressor.
     * @param value low eight bits of the serialized byte
     * @throws IOException if the byte ceiling or target fails
     */
    @Override
    public void write(final int value) throws IOException {
        reserve(1);
        target.write(value);
    }

    /**
     * Admits an encoder block once, preserving the target's bulk-write behavior.
     * @param bytes borrowed UTF8 encoder buffer
     * @param offset first serialized byte
     * @param length number of serialized bytes
     * @throws IOException if the byte ceiling or target fails
     * @throws IndexOutOfBoundsException if the requested slice is invalid
     */
    @Override
    public void write(final byte[] bytes, final int offset, final int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        reserve(length);
        target.write(bytes, offset, length);
    }

    /**
     * Flushes already admitted data without finishing or closing the borrowed compressor.
     * @throws IOException if the target flush fails
     */
    @Override
    public void flush() throws IOException {
        target.flush();
    }

    /**
     * Rejects an encoder block before any bytes beyond the existing ceiling reach compression.
     * @param count validated nonnegative byte count
     * @throws IOException if the serialized byte ceiling would be exceeded
     */
    private void reserve(final int count) throws IOException {
        if (count > limit - written) {
            throw new SizeLimit();
        }
        written += count;
    }

    /**
     * Recognizes the private budget failure through Jackson's IO wrappers without losing the public error type.
     * @param failure original encoder/compressor failure
     * @return whether a cycle-safe cause traversal finds the private size marker
     */
    static boolean exceeded(final Throwable failure) {
        final var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (var cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SizeLimit) {
                return true;
            }
        }
        return false;
    }

    /** Private checked marker so charset/generator IO failures remain distinguishable from malformed metadata. */
    private static final class SizeLimit extends IOException {
        /** Stable private exception serialization identity. */
        private static final long serialVersionUID = 1L;

        /** Creates one typed byte-ceiling marker without exposing the internal stream as public API. */
        private SizeLimit() {
            super("Original JSON UTF8 byte ceiling exceeded");
        }
    }
}
