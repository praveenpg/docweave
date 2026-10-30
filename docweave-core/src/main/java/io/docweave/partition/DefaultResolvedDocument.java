package io.docweave.partition;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owned resolved content: an exact byte array handed to the loader without copying, a temporary file deleted on
 * close, or a custom resolver's local source with its release action. Close runs the release exactly once.
 */
final class DefaultResolvedDocument implements ResolvedDocument {
    /** Owned exact content, or null; cleared on close. */
    private volatile byte[] owned;

    /** Local source for custom or file-backed handles, or null for owned bytes. */
    private final DocumentSource local;

    /** Logical filename. */
    private final String filename;

    /** Safe provenance. */
    private final SourceProvenance provenance;

    /** Release action, or null. */
    private final Closeable release;

    /** Whether close has run. */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a handle.
     * @param owned owned bytes or null
     * @param local local source or null
     * @param filename logical filename
     * @param provenance provenance
     * @param release release action or null
     */
    private DefaultResolvedDocument(final byte[] owned, final DocumentSource local, final String filename,
            final SourceProvenance provenance, final Closeable release) {
        this.owned = owned;
        this.local = local;
        this.filename = filename;
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        this.release = release;
    }

    /**
     * Takes ownership of an exact byte array without copying.
     * @param content received bytes, not retained by the caller
     * @param filename logical filename
     * @param provenance provenance
     * @return handle
     */
    static DefaultResolvedDocument owned(
            final byte[] content, final String filename, final SourceProvenance provenance) {
        return new DefaultResolvedDocument(content, null, filename, provenance, null);
    }

    /**
     * Owns a temporary file that is deleted on close.
     * @param file temporary file
     * @param filename logical filename
     * @param provenance provenance
     * @return handle
     */
    static DefaultResolvedDocument temporaryFile(
            final Path file, final String filename, final SourceProvenance provenance) {
        return new DefaultResolvedDocument(
                null, DocumentSource.of(file), filename, provenance, () -> Files.deleteIfExists(file));
    }

    /**
     * Wraps a custom resolver's local source.
     * @param local local source
     * @param filename logical filename
     * @param provenance provenance
     * @param release release action or null
     * @return handle
     */
    static DefaultResolvedDocument wrap(final DocumentSource local, final String filename,
            final SourceProvenance provenance, final Closeable release) {
        Objects.requireNonNull(local, "local");
        if (local instanceof DocumentSource.Remote) {
            throw new IllegalArgumentException("A resolved document must be local");
        }
        return new DefaultResolvedDocument(null, local, filename, provenance, release);
    }

    /**
     * Returns the owned bytes for copy-free loading.
     * @return exact bytes, or null for file-backed or custom handles
     */
    byte[] ownedBytes() {
        return owned;
    }

    @Override
    public DocumentSource localSource() {
        if (closed.get()) {
            throw new IllegalStateException("Resolved document is closed");
        }
        final byte[] content = owned;
        return content != null ? DocumentSource.bytes(content, filename) : local;
    }

    @Override
    public String filename() {
        return filename;
    }

    @Override
    public SourceProvenance provenance() {
        return provenance;
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            owned = null;
            if (release != null) {
                release.close();
            }
        }
    }

    /**
     * Describes the handle without content or paths.
     * @return redacted description
     */
    @Override
    public String toString() {
        return "ResolvedDocument[provider=" + provenance.provider() + ", bytes=" + provenance.bytes() + "]";
    }
}
