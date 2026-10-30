package io.docweave.partition;

import java.io.IOException;

/**
 * Acquires the content named by a {@link DocumentSource.Remote} location and hands it to the processor as an owned
 * local document. Implementations live in optional provider modules or application code (for example
 * {@code docweave-source-s3}); core contains no provider. A processor uses exactly one resolver, configured explicitly
 * through {@link io.docweave.DocWeave#builder()}; an application with several providers supplies a composite
 * resolver with its own explicit routing. Core never probes providers or downloads twice.
 *
 * <p>Contract:</p>
 * <ul>
 * <li><b>Location syntax.</b> The location reaches the resolver exactly as the caller wrote it. The resolver
 * documents and validates its own syntax and throws {@link IllegalArgumentException} for an invalid or unsupported
 * location, before any network access. Messages must not echo secrets contained in the location.</li>
 * <li><b>Acquisition failures.</b> A missing object, denied access, an unavailable service or a broken transfer is an
 * {@link IOException}, normally a {@link RemoteSourceException} with its {@link RemoteSourceException.Reason}; it is
 * never an {@code IllegalArgumentException}.</li>
 * <li><b>Limits.</b> The resolver honors {@link ResolutionContext#maxBytes()} by counting bytes actually received
 * (not only a declared length), stops at the context's deadline and when the calling thread is interrupted, and
 * never truncates: an oversized input fails with {@link PartitionException.Code#INPUT_TOO_LARGE}.
 * {@link ResolutionContext#receive} implements these rules for stream-based providers.</li>
 * <li><b>Ownership.</b> If resolution fails, the resolver releases everything it acquired (partial downloads,
 * temporary files) before throwing. A returned {@link ResolvedDocument} is owned by the processor, which closes it
 * after parsing, including on failure and cancellation.</li>
 * <li><b>Concurrency.</b> Implementations are thread-safe: one resolver serves concurrent partition calls, each with
 * its own context and handle.</li>
 * <li><b>Secrets.</b> Credentials, signed URLs and temporary paths never appear in provenance, filenames or
 * exception messages.</li>
 * </ul>
 */
@FunctionalInterface
public interface RemoteSourceResolver {
    /**
     * Validates the location, acquires the content within the context's limits and returns an owned local document.
     * @param source remote source whose location is passed unchanged
     * @param context byte ceiling, deadline and cancellation for this call
     * @return an owned handle whose {@link ResolvedDocument#localSource()} is a File, Bytes or Text source
     * @throws IllegalArgumentException if the location is invalid or not supported by this resolver
     * @throws PartitionException with {@link PartitionException.Code#INPUT_TOO_LARGE} if the content exceeds the
     *         ceiling
     * @throws RemoteSourceException if the object is missing, access is denied, the deadline passes or the transfer
     *         fails
     * @throws java.io.InterruptedIOException if the calling thread is interrupted
     * @throws IOException for other acquisition failures
     */
    ResolvedDocument resolve(DocumentSource.Remote source, ResolutionContext context) throws IOException;
}
