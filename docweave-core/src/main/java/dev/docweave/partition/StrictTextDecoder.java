package dev.docweave.partition;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Strict byte-to-text decoding with a compact path for verified ASCII UTF-8.
 * Every ASCII byte is valid UTF-8, so this case can use the JDK String constructor without
 * an input-sized intermediate char buffer. Non-ASCII UTF-8 and all other charsets retain
 * the reporting decoder, preserving malformed/unmappable failures and stateful decoding.
 * Neither path changes BOM handling (the caller supplies the payload slice) or control policy.
 */
final class StrictTextDecoder {
    /** Prevents instantiation of this static utility class. */
    private StrictTextDecoder() {
    }

    /**
     * Polls ASCII scanning; codec/String construction itself remains indivisible.
     *
     * @param bytes source byte array
     * @param offset starting byte offset within {@code bytes}
     * @param length number of bytes to decode
     * @param charset target character encoding
     * @return decoded string
     * @throws IOException if byte sequences are malformed/unmappable for {@code charset} or the thread is interrupted
     */
    static String decode(final byte[] bytes, final int offset, final int length, final Charset charset)
            throws IOException {
        ElementCollector.checkInterrupted();
        String text;
        if (charset == StandardCharsets.UTF_8 && ascii(bytes, offset, length)) {
            text = new String(bytes, offset, length, StandardCharsets.UTF_8);
        } else {
            text = charset.newDecoder()
                           .onMalformedInput(CodingErrorAction.REPORT)
                           .onUnmappableCharacter(CodingErrorAction.REPORT)
                           .decode(ByteBuffer.wrap(bytes, offset, length))
                           .toString();
        }
        ElementCollector.checkInterrupted();
        return text;
    }

    /**
     * Checks whether all bytes in {@code bytes[offset .. offset + length)} are 7-bit ASCII ({@code >= 0}).
     *
     * @param bytes source byte array
     * @param offset starting byte offset
     * @param length number of bytes to inspect
     * @return {@code true} if every byte in the slice is non-negative
     * @throws IOException if the thread is interrupted
     */
    private static boolean ascii(final byte[] bytes, final int offset, final int length) throws IOException {
        for (int index = 0; index < length; index++) {
            if ((index & 1023) == 0) {
                ElementCollector.checkInterrupted();
            }
            if (bytes[offset + index] < 0) {
                return false;
            }
        }
        ElementCollector.checkInterrupted();
        return true;
    }
}
