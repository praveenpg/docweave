package io.docweave.partition;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stateless content identities; no parser, credential or mutable digest is retained. */
final class PdfParagraphIdentity {
    /** Prevents utility construction. */
    private PdfParagraphIdentity() {
    }

    /**
     * Binds source paragraph ordinals to original document bytes rather than a possibly repeated filename.
     * @param source original bounded PDF bytes, including encryption when present
     * @return full lowercase SHA256 identity
     */
    static String document(final byte[] source) {
        return HexFormat.of().formatHex(digest().digest(source));
    }

    /**
     * Binds paragraph identities to remaining snapshot bytes without consuming or copying the borrowed input.
     * @param source original bounded remaining PDF bytes, including encryption when present
     * @return the same full lowercase SHA256 as the equivalent byte-array input
     */
    static String document(final ByteBuffer source) {
        final var digest = digest();
        digest.update(source.asReadOnlyBuffer());
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Derives an independent deterministic element ID for this logical assembly rule and complete text.
     * @param source source document/paragraph identity
     * @param text complete bounded assembled text
     * @return 32 lowercase hexadecimal digits, distinct in domain from page-level element identities
     */
    static String assembled(final String source, final String text) {
        final var digest = digest();
        digest.update("docweave-paragraph-assembly-v1\n".getBytes(StandardCharsets.US_ASCII));
        digest.update(source.getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) '\n');
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)), 0, 16);
    }

    /**
     * Creates a call-confined mandatory JDK hash engine.
     * @return fresh SHA256 engine
     */
    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
