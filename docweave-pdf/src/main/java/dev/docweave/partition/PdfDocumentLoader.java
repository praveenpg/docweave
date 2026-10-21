package dev.docweave.partition;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Shared document-confined opening policy; credentials never enter service fields or extraction metadata. */
final class PdfDocumentLoader {
    /** Prevents utility instantiation. */
    private PdfDocumentLoader() {
    }

    /**
     * Preserves empty-password opening, then optionally tries one caller-supplied credential.
     * An encrypted document with restricted empty-password permissions may also need an owner credential.
     * Every returned document remains subject to the caller's ordinary extraction permission checks;
     * this loader never changes PDFBox permissions or removes document security.
     * @param bytes bounded original PDF bytes
     * @param filename nullable lookup hint
     * @param passwords optional caller-owned provider; null preserves historical opening
     * @return opened document whose ownership transfers to the caller
     * @throws IOException if parsing, credentials or cancellation prevent opening
     */
    static PDDocument load(final byte[] bytes, final String filename, final PdfPasswordProvider passwords)
            throws IOException {
        return load(ByteBuffer.wrap(bytes), filename, passwords);
    }

    /**
     * Opens bounded remaining snapshot bytes without modifying the borrowed buffer or copying its payload.
     * Every password attempt gets a fresh read-only cursor; successful documents own their heap reader.
     * Permission checks, optional credential lookup and credential erasure retain the historical policy.
     * @param bytes borrowed original PDF bytes in the remaining buffer range
     * @param filename nullable lookup hint
     * @param passwords optional caller-owned credential provider
     * @return opened document whose ownership transfers to the caller
     * @throws IOException if parsing, credentials or cancellation prevent opening
     */
    static PDDocument load(final ByteBuffer bytes, final String filename, final PdfPasswordProvider passwords)
            throws IOException {
        ElementCollector.checkInterrupted();
        try {
            final var document = open(bytes, "");
            boolean retained = false;
            try {
                ElementCollector.checkInterrupted();
                if (passwords == null || !document.isEncrypted()
                        || document.getCurrentAccessPermission().canExtractContent()) {
                    retained = true;
                    return document;
                }
            } finally {
                // Only the accepted document transfers ownership; a restricted preliminary open closes here.
                if (!retained) {
                    document.close();
                }
            }
        } catch (final InvalidPasswordException failure) {
            if (passwords == null) {
                throw failure;
            }
        }
        return supplied(bytes, filename, passwords);
    }

    /**
     * Transfers one independent heap reader only after PDFBox successfully opens its document.
     * @param bytes borrowed remaining input range
     * @param password current attempt's credential, never stored by this helper
     * @return caller-owned document that closes its reader
     * @throws IOException if parsing fails; the untransferred reader is closed first
     */
    private static PDDocument open(final ByteBuffer bytes, final String password) throws IOException {
        final var reader = new RandomAccessReadBuffer(bytes.asReadOnlyBuffer().slice());
        boolean retained = false;
        try {
            final var document = Loader.loadPDF(reader, password);
            retained = true;
            return document;
        } finally {
            // A document owns its accepted reader; failed opens release the document-confined view here.
            if (!retained) {
                reader.close();
            }
        }
    }

    /**
     * Requests one fresh credential and clears it even when lookup cancellation or parsing fails.
     * @param bytes original document bytes
     * @param filename nullable metadata lookup hint
     * @param passwords explicitly configured provider
     * @return opened caller-owned document
     * @throws IOException if lookup, opening or interruption fails
     */
    private static PDDocument supplied(
            final ByteBuffer bytes, final String filename, final PdfPasswordProvider passwords) throws IOException {
        char[] password = null;
        try {
            ElementCollector.checkInterrupted();
            try {
                password = passwords.password(filename);
            } catch (final InterruptedIOException failure) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("PDF password lookup interrupted");
            } catch (final IOException | RuntimeException failure) {
                ElementCollector.checkInterrupted();
                // Caller credential stores may put secrets in messages or causes; retain neither.
                throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION, "PDF password lookup failed");
            }
            ElementCollector.checkInterrupted();
            if (password == null) {
                throw new PartitionException(
                        PartitionException.Code.UNSUPPORTED_OPTION, "PDF password was not supplied");
            }
            return open(bytes, new String(password));
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }
}
