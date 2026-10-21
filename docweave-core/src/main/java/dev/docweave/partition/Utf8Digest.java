package dev.docweave.partition;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Document-confined incremental UTF-8 hashing with fixed scratch buffers.
 * Unconsumed high surrogates remain in the compacted input buffer between appends, so logical
 * concatenation behaves like String.getBytes(UTF_8), including pairs crossing component/buffer
 * boundaries and the legacy question-mark replacement for malformed UTF-16. No input reference
 * is retained. Scratch arrays hold bounded copied characters/bytes until the document is released.
 */
final class Utf8Digest {
    /** Underlying message digest updated with encoded UTF-8 bytes. */
    private final MessageDigest digest;
    /** Stateful UTF-8 encoder configured to replace malformed or unmappable UTF-16 sequences. */
    private final java.nio.charset.CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                                                                    .onMalformedInput(CodingErrorAction.REPLACE)
                                                                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
    /** Fixed-capacity character scratch buffer holding input slices and carried high surrogates. */
    private final CharBuffer input;
    /** Fixed-capacity byte scratch buffer receiving encoded UTF-8 bytes. */
    private final ByteBuffer output;

    /**
     * Creates an incremental UTF-8 digest wrapper with default scratch buffer capacities.
     *
     * @param digest underlying message digest instance
     */
    Utf8Digest(final MessageDigest digest) {
        this(digest, 256, 1024);
    }

    /**
     * Small configurable capacities allow tests to force all carry/overflow paths.
     *
     * @param digest underlying message digest instance
     * @param characters capacity of the character scratch buffer (at least 2)
     * @param bytes capacity of the byte scratch buffer (at least 4)
     */
    Utf8Digest(final MessageDigest digest, final int characters, final int bytes) {
        if (characters < 2 || bytes < 4) {
            throw new IllegalArgumentException("UTF-8 scratch needs two characters and four bytes");
        }
        this.digest = digest;
        input = CharBuffer.allocate(characters);
        output = ByteBuffer.allocate(bytes);
    }

    /** Starts a message or discards an incomplete one, without clearing thread interruption. */
    void reset() {
        encoder.reset();
        input.clear();
        output.clear();
        digest.reset();
    }

    /**
     * Appends one logical component; empty components leave a carried surrogate intact.
     *
     * @param value character sequence component to encode and hash
     * @throws IOException if encoding fails or the thread is interrupted
     */
    void append(final CharSequence value) throws IOException {
        ElementCollector.checkInterrupted();
        for (int start = 0; start < value.length();) {
            ElementCollector.checkInterrupted();
            final int end = start + Math.min(input.remaining(), value.length() - start);
            if (value instanceof final String text) {
                input.put(text, start, end);
            } else {
                for (int index = start; index < end; index++) {
                    input.put(value.charAt(index));
                }
            }
            start = end;
            if (!input.hasRemaining()) {
                encode(false);
            }
        }
        ElementCollector.checkInterrupted();
    }

    /**
     * Ends the logical concatenation, replacing a final unmatched surrogate and resetting the digest.
     *
     * @return computed message digest bytes
     * @throws IOException if flushing the encoder fails or the thread is interrupted
     */
    byte[] finish() throws IOException {
        encode(true);
        while (true) {
            ElementCollector.checkInterrupted();
            final var result = encoder.flush(output);
            updateDigest();
            if (result.isError()) {
                result.throwException();
            }
            if (result.isUnderflow()) {
                break;
            }
        }
        final byte[] result = digest.digest();
        ElementCollector.checkInterrupted();
        return result;
    }

    /**
     * Drains {@link #input} through {@link #encoder} into {@link #output} and updates {@link #digest}.
     *
     * @param last {@code true} if no further input characters will be appended
     * @throws IOException if encoding fails or the thread is interrupted
     */
    private void encode(final boolean last) throws IOException {
        input.flip();
        while (true) {
            ElementCollector.checkInterrupted();
            final var result = encoder.encode(input, output, last);
            updateDigest();
            if (result.isError()) {
                result.throwException();
            }
            if (result.isUnderflow()) {
                break;
            }
        }
        input.compact();
    }

    /**
     * Feeds any encoded bytes currently in {@link #output} into {@link #digest} and clears {@link #output}.
     */
    private void updateDigest() {
        if (output.position() > 0) {
            digest.update(output.array(), 0, output.position());
        }
        output.clear();
    }
}
