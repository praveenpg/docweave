package io.docweave.partition;

import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Independent PDFBox readers over shared immutable input; a supplied password is requested only once. */
final class PdfPageDocuments implements AutoCloseable {
    /** Read-only input bytes; each opening receives an independent cursor. */
    private final ByteBuffer bytes;
    /** Optional credential lookup hint. */
    private final String filename;
    /** Optional caller-owned credential provider. */
    private final PdfPasswordProvider passwords;
    /** Scoped copy erased when the document call ends. */
    private char[] password;
    /** Whether the original provider has already answered, including a null answer. */
    private boolean requested;
    /** Readers are reused only by the same worker and closed at the end of this partition call. */
    private final java.util.concurrent.ConcurrentMap<Thread, PDDocument> readers =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Captures immutable input without copying the PDF payload.
     * @param bytes read-only PDF snapshot
     * @param filename optional source name
     * @param passwords optional credential provider
     */
    PdfPageDocuments(final ByteBuffer bytes, final String filename, final PdfPasswordProvider passwords) {
        this.bytes = bytes.asReadOnlyBuffer();
        this.filename = filename;
        this.passwords = passwords;
    }

    /**
     * Opens a document confined to one page task; the task owns and closes it.
     * @return independent PDFBox document
     * @throws IOException if opening or credential lookup fails
     */
    PDDocument open() throws IOException {
        return PdfDocumentLoader.load(bytes.asReadOnlyBuffer(), filename, passwords == null ? null : this::password);
    }

    /**
     * Borrows the calling worker's reader without reopening the complete PDF for every page.
     * @return PDFBox document used only by this worker during this partition call
     * @throws IOException if opening fails
     */
    PDDocument page() throws IOException {
        final var thread = Thread.currentThread();
        final var existing = readers.get(thread);
        if (existing != null) {
            return existing;
        }
        final var opened = open();
        readers.put(thread, opened);
        return opened;
    }

    /**
     * Supplies a disposable password copy; PdfDocumentLoader erases each returned array.
     * @param ignored source name, already captured
     * @return password copy or null
     * @throws IOException if the original lookup fails
     */
    private synchronized char[] password(final String ignored) throws IOException {
        if (!requested) {
            final var supplied = passwords.password(filename);
            try {
                password = supplied == null ? null : supplied.clone();
                requested = true;
            } finally {
                if (supplied != null) {
                    Arrays.fill(supplied, '\0');
                }
            }
        }
        return password == null ? null : password.clone();
    }

    /**
     * Closes worker readers and erases the scoped password after every page task has ended.
     * @throws IOException if closing a reader fails; all readers are still attempted
     */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            for (final var reader : readers.values()) {
                try {
                    reader.close();
                } catch (final IOException error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
            readers.clear();
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
