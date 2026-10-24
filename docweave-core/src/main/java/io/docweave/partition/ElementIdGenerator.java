package io.docweave.partition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Document-confined deterministic identity generation, separate from collection and metadata.
 * Lazily reuses one SHA-256 engine and bounded UTF-8 scratch buffers. No state is shared across
 * documents or threads. The logical legacy message is filename (or None), text, None page marker
 * and decimal sequence; component/buffer boundaries do not break surrogate pairs. Malformed
 * UTF-16 retains String.getBytes(UTF_8) replacement behavior. Only the first sixteen digest bytes
 * are formatted as thirty-two lowercase hex digits. Large messages never materialize a joined
 * String/byte array. Before promotion, the first eight short messages use a bounded path, avoiding
 * scratch-buffer overhead for tiny documents; later messages amortize the reusable buffers.
 */
final class ElementIdGenerator {
    /** Lowercase hexadecimal formatter for digest bytes. */
    private static final HexFormat HEX = HexFormat.of();
    /** Maximum character count eligible for the short-message single-allocation hashing path. */
    private static final int SMALL_MESSAGE_CHARACTERS = 256;
    /** Element sequence threshold at which incremental scratch-buffer hashing is always used. */
    private static final int STREAM_AFTER_SEQUENCE = 8;
    /** Lazily initialized per-document SHA-256 message digest. */
    private MessageDigest digest;
    /** Lazily initialized incremental UTF-8 digest helper for large messages or longer documents. */
    private Utf8Digest utf8;

    /**
     * Returns the legacy identity without retaining text or clearing the interruption flag.
     *
     * @param filename optional source filename, or {@code null}
     * @param text element text content
     * @param sequence 0-based element sequence number within the document
     * @return 32-character lowercase hexadecimal element ID
     * @throws IOException if UTF-8 encoding fails or the thread is interrupted
     */
    String create(final String filename, final String text, final int sequence) throws IOException {
        ElementCollector.checkInterrupted();
        if (digest == null) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (final NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }
        try {
            // Conservative eleven-character allowance covers every int without a temporary decimal String.
            final long units =
                    (filename == null ? 4L : filename.length()) + (text == null ? 4L : text.length()) + 4 + 11;
            if (utf8 == null && sequence < STREAM_AFTER_SEQUENCE && units <= SMALL_MESSAGE_CHARACTERS) {
                final String data = (filename == null ? "None" : filename) + text + "None" + sequence;
                final String id = HEX.formatHex(digest.digest(data.getBytes(StandardCharsets.UTF_8)), 0, 16);
                ElementCollector.checkInterrupted();
                return id;
            }
            if (utf8 == null) {
                utf8 = new Utf8Digest(digest);
            }
            utf8.reset();
            utf8.append(filename == null ? "None" : filename);
            utf8.append(text == null ? "null" : text);
            utf8.append("None");
            utf8.append(Integer.toString(sequence));
            final String id = HEX.formatHex(utf8.finish(), 0, 16);
            ElementCollector.checkInterrupted();
            return id;
        } finally {
            // An interrupted/failed message must not prefix the next attempt after caller recovery.
            if (utf8 == null) {
                digest.reset();
            } else {
                utf8.reset();
            }
        }
    }
}
