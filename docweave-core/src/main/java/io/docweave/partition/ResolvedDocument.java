package io.docweave.partition;

import java.io.Closeable;
import java.io.IOException;

/**
 * Remote content acquired by a {@link RemoteSourceResolver} and owned by the processor until parsing ends. The
 * processor closes it exactly once after parsing, including when loading or parsing fails or the call is
 * cancelled; closing releases buffers and deletes temporary files. A cleanup failure is reported: attached as a
 * suppressed exception to a primary failure, or thrown after an otherwise successful parse. Implementations must
 * tolerate repeated {@link #close()} calls.
 */
public interface ResolvedDocument extends AutoCloseable {
    /**
     * Wraps an already local source acquired by a custom resolver.
     * @param local File, Bytes or Text source holding the content; never Remote
     * @param filename logical filename for routing, metadata and element IDs (a temporary file's own name is
     *        ignored), or null
     * @param provenance safe provenance
     * @param release releases acquired resources (for example deletes a temporary file) exactly once on close, or null
     * @return an owned handle
     * @throws NullPointerException if local or provenance is null
     * @throws IllegalArgumentException if local is a Remote source
     */
    static ResolvedDocument of(final DocumentSource local, final String filename, final SourceProvenance provenance,
            final Closeable release) {
        return DefaultResolvedDocument.wrap(local, filename, provenance, release);
    }

    /**
     * Returns the local content to parse.
     * @return a File, Bytes or Text source; never Remote
     * @throws IllegalStateException after the handle has been closed
     */
    DocumentSource localSource();

    /**
     * Returns the logical filename used for format routing, element metadata and deterministic element IDs, in
     * place of any temporary file's random name.
     * @return logical filename, or null when the remote object has none
     */
    String filename();

    /**
     * Returns safe provenance for the partition result.
     * @return provenance without credentials, signed URLs or temporary paths
     */
    SourceProvenance provenance();

    /**
     * Releases owned content. Repeated calls have no further effect.
     * @throws IOException if releasing a resource (for example deleting a temporary file) fails
     */
    @Override void close() throws IOException;
}
