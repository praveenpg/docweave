package dev.docweave.partition;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Validates already-decoded Unicode without encoding the entire document.
 * Only a bounded UTF-8 prefix is allocated for existing byte-signature and preview routing.
 * The budget counts the original UTF-8 footprint including a leading BOM. Malformed surrogates
 * retain encoding-error precedence over a measured byte overflow, after the cheap length guard.
 */
final class DecodedTextInput {
    // SourceLoader uses at most 8 KiB after a BOM of up to four bytes.
    /** Maximum number of UTF-8 prefix bytes encoded for format sniffing. */
    private static final int PREFIX_BYTES = 8196;

    /** Prevents instantiation of this static utility class. */
    private DecodedTextInput() {
    }

    /**
     * Validates surrogate pairing and computes the exact UTF-8 byte length of {@code text} within {@code limit}.
     *
     * @param text character sequence to validate and measure
     * @param limit maximum allowed UTF-8 byte footprint
     * @return exact UTF-8 byte length of {@code text}
     * @throws IOException if surrogates are malformed, the UTF-8 footprint exceeds {@code limit}, or interrupted
     */
    static int measure(final CharSequence text, final int limit) throws IOException {
        ElementCollector.checkInterrupted();
        if (text.length() > limit) {
            throw tooLarge(limit);
        }
        long bytes = 0;
        for (int offset = 0, visited = 0; offset < text.length(); visited++) {
            if ((visited & 1023) == 0) {
                ElementCollector.checkInterrupted();
            }
            final char first = text.charAt(offset++);
            if (Character.isHighSurrogate(first)) {
                if (offset == text.length() || !Character.isLowSurrogate(text.charAt(offset))) {
                    throw invalid();
                }
                offset++;
                bytes += 4;
            } else if (Character.isLowSurrogate(first)) {
                throw invalid();
            } else {
                bytes += first < 0x80 ? 1 : first < 0x800 ? 2 : 3;
            }
        }
        ElementCollector.checkInterrupted();
        if (bytes > limit) {
            throw tooLarge(limit);
        }
        return (int) bytes;
    }

    /**
     * Encodes a validated prefix; byte truncation deliberately matches the old sniffing boundary.
     *
     * @param text validated source string
     * @return UTF-8 prefix byte array of at most {@link #PREFIX_BYTES} bytes
     * @throws IOException if the thread is interrupted
     */
    static byte[] preview(final String text) throws IOException {
        ElementCollector.checkInterrupted();
        int end = Math.min(text.length(), PREFIX_BYTES);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end++;
        }
        byte[] bytes = text.substring(0, end).getBytes(StandardCharsets.UTF_8);
        ElementCollector.checkInterrupted();
        return bytes.length <= PREFIX_BYTES ? bytes : Arrays.copyOf(bytes, PREFIX_BYTES);
    }

    /**
     * Creates an {@link PartitionException} for unpaired UTF-16 surrogates.
     *
     * @return invalid-encoding partition exception
     */
    private static PartitionException invalid() {
        return new PartitionException(PartitionException.Code.INVALID_ENCODING,
                "Text contains invalid Unicode surrogates", new MalformedInputException(1));
    }

    /**
     * Creates a {@link PartitionException} indicating that the input exceeds {@code limit} bytes.
     *
     * @param limit maximum allowed byte count
     * @return input-too-large partition exception
     */
    private static PartitionException tooLarge(final int limit) {
        return new PartitionException(
                PartitionException.Code.INPUT_TOO_LARGE, "Document exceeds maxInputBytes=" + limit);
    }
}
