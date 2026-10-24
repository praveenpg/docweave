package io.docweave.partition;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Owned PDFBox parsing, password retry and source-identity controls for read-only borrowed input cursors. */
class PdfBinaryBufferTest {
    /** Creates independent buffer/lifecycle controls. */
    PdfBinaryBufferTest() {
    }

    /**
     * Parsing honors the remaining read-only range and preserves source bytes and the caller cursor.
     * @throws Exception if owned authoring or parsing fails
     */
    @Test
    void readOnlyRemainingRangeOpensWithoutConsumingOrMutatingBorrowedInput() throws Exception {
        final byte[] pdf = source(false);
        final byte[] prefix = "Owned prefix".getBytes(StandardCharsets.US_ASCII);
        final byte[] envelope = new byte[prefix.length + pdf.length + 23];
        System.arraycopy(prefix, 0, envelope, 0, prefix.length);
        System.arraycopy(pdf, 0, envelope, prefix.length, pdf.length);
        final byte[] original = envelope.clone();
        final var view = ByteBuffer.wrap(envelope).asReadOnlyBuffer();
        view.position(prefix.length).limit(prefix.length + pdf.length);
        try (final var document = PdfDocumentLoader.load(view, "owned.pdf", null)) {
            assertEquals(1, document.getNumberOfPages());
            assertEquals(prefix.length, view.position());
            assertEquals(prefix.length + pdf.length, view.limit());
        }
        assertArrayEquals(original, envelope);
        assertEquals(prefix.length, view.position());
    }

    /**
     * Failed empty-password opening does not consume the next attempt's bytes, and credentials still clear.
     * @throws Exception if encryption, parsing or credential ownership validation fails
     */
    @Test
    void passwordRetryGetsFreshCursorAndClearsTheOwnedCredential() throws Exception {
        final byte[] pdf = source(true);
        final var view = ByteBuffer.wrap(pdf).asReadOnlyBuffer();
        final var calls = new AtomicInteger();
        final char[] credential = "reader".toCharArray();
        try (final var document = PdfDocumentLoader.load(view, "protected.pdf", filename -> {
            calls.incrementAndGet();
            assertEquals("protected.pdf", filename);
            return credential;
        })) {
            assertTrue(document.isEncrypted());
            assertEquals(1, document.getNumberOfPages());
        }
        assertEquals(1, calls.get());
        assertArrayEquals(new char[credential.length], credential);
        assertEquals(0, view.position());
        assertEquals(pdf.length, view.limit());
    }

    /**
     * Source paragraph fingerprints remain byte-identical while hashing only the remaining range of an independent
     * cursor.
     * @throws Exception if owned source authoring fails
     */
    @Test
    void paragraphIdentityMatchesBytesWithoutConsumingTheBorrowedCursor() throws Exception {
        final byte[] pdf = source(false);
        final var envelope = ByteBuffer.allocate(pdf.length + 31);
        envelope.position(11);
        envelope.put(pdf);
        envelope.flip().position(11);
        final var view = envelope.asReadOnlyBuffer();
        assertEquals(PdfParagraphIdentity.document(pdf), PdfParagraphIdentity.document(view));
        assertEquals(11, view.position());
        assertEquals(11 + pdf.length, view.limit());
    }

    /**
     * Authors one complete owned page, optionally under nonempty owner/user credentials.
     * @param encrypted whether both credentials are nonempty
     * @return complete original bytes
     * @throws Exception if authoring, protection or serialization fails
     */
    private static byte[] source(final boolean encrypted) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            if (encrypted) {
                final var policy = new StandardProtectionPolicy("owner", "reader", new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
