package io.docweave.source.s3;

import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.RemoteSourceException;
import io.docweave.partition.ResolutionContext;
import io.docweave.partition.ResolvedDocument;
import io.docweave.partition.SourceProvenance;
import io.docweave.partition.SpoolPolicy;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * S3 GetObject acquisition with bounded, deadline-limited, generation-pinned retries.
 */
final class DefaultS3SourceResolver implements S3SourceResolver {
    /** Provider identifier in provenance. */
    static final String PROVIDER = "s3";

    /** Longest delay between attempts. */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    /** Client used for all requests. */
    private final S3Client client;

    /** Whether {@link #close()} closes the client. */
    private final boolean ownsClient;

    /** Bucket for relative keys, or null. */
    private final String defaultBucket;

    /** Allowed buckets, or empty. */
    private final Set<String> allowedBuckets;

    /** Attempts per resolution. */
    private final int maxAttempts;

    /** First retry delay. */
    private final Duration retryBackoff;

    /** Concurrent download slots. */
    private final Semaphore downloads;

    /** Download buffering. */
    private final SpoolPolicy spool;

    /** Whether the resolver is closed. */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a resolver.
     * @param client S3 client
     * @param ownsClient whether close closes the client
     * @param defaultBucket default bucket or null
     * @param allowedBuckets allowed buckets
     * @param maxAttempts attempts
     * @param retryBackoff first retry delay
     * @param maxConcurrentDownloads download slots
     * @param spool buffering policy
     */
    DefaultS3SourceResolver(final S3Client client, final boolean ownsClient, final String defaultBucket,
            final Set<String> allowedBuckets, final int maxAttempts, final Duration retryBackoff,
            final int maxConcurrentDownloads, final SpoolPolicy spool) {
        this.client = client;
        this.ownsClient = ownsClient;
        this.defaultBucket = defaultBucket;
        this.allowedBuckets = allowedBuckets;
        this.maxAttempts = maxAttempts;
        this.retryBackoff = retryBackoff;
        this.downloads = new Semaphore(maxConcurrentDownloads, true);
        this.spool = spool;
    }

    @Override
    public ResolvedDocument resolve(final DocumentSource.Remote source, final ResolutionContext context)
            throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("S3 source resolver is closed");
        }
        final S3Location location = S3Location.parse(source.location(), defaultBucket, allowedBuckets);
        context.checkActive();
        acquireSlot(context);
        try {
            return fetchWithRetries(location, context);
        } finally {
            downloads.release();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownsClient) {
            client.close();
        }
    }

    /**
     * Waits for a download slot until the deadline.
     * @param context resolution context
     * @throws IOException on deadline or interruption
     */
    private void acquireSlot(final ResolutionContext context) throws IOException {
        try {
            if (!downloads.tryAcquire(context.remaining().toNanos(), TimeUnit.NANOSECONDS)) {
                throw new RemoteSourceException(RemoteSourceException.Reason.DEADLINE_EXCEEDED,
                        "No S3 download slot became free before the deadline");
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for an S3 download slot");
        }
    }

    /**
     * Runs attempts until success, a permanent failure, the attempt limit or the deadline.
     * @param location object
     * @param context resolution context
     * @return owned document
     * @throws IOException on failure
     */
    private ResolvedDocument fetchWithRetries(final S3Location location, final ResolutionContext context)
            throws IOException {
        final var pin = new Pin(location.versionId());
        Duration delay = retryBackoff;
        for (int attempt = 1;; attempt++) {
            context.checkActive();
            try {
                return fetch(location, pin, context);
            } catch (final Transient failure) {
                if (attempt >= maxAttempts) {
                    throw failure.failure;
                }
                final long jittered = ThreadLocalRandom.current().nextLong(delay.toNanos() / 2, delay.toNanos() + 1);
                if (context.remaining().toNanos() <= jittered) {
                    throw failure.failure;
                }
                sleep(jittered);
                delay = delay.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay.multipliedBy(2);
            }
        }
    }

    /**
     * Performs one GetObject and receives its body.
     * @param location object
     * @param pin generation pinned by an earlier attempt
     * @param context resolution context
     * @return owned document
     * @throws Transient for retryable failures
     * @throws IOException for permanent failures
     */
    private ResolvedDocument fetch(final S3Location location, final Pin pin, final ResolutionContext context)
            throws IOException {
        final var request = GetObjectRequest.builder().bucket(location.bucket()).key(location.key());
        if (pin.version != null) {
            request.versionId(pin.version);
        } else if (pin.entityTag != null) {
            request.ifMatch(pin.entityTag);
        }
        final Duration remaining = context.remaining();
        request.overrideConfiguration(
                override -> override.apiCallTimeout(remaining.isZero() ? Duration.ofMillis(1) : remaining));
        final ResponseInputStream<GetObjectResponse> response;
        try {
            response = client.getObject(request.build());
        } catch (final SdkException failure) {
            throw classify(failure, context);
        }
        final ResolvedDocument document;
        boolean complete = false;
        try {
            final GetObjectResponse metadata = response.response();
            final String version = normalizeVersion(metadata.versionId());
            if (pin.version == null && pin.entityTag == null) {
                // Pin later attempts to this generation; unversioned buckets use a conditional request instead.
                pin.version = version;
                pin.entityTag = version == null ? metadata.eTag() : null;
            }
            final String entityTag = unquote(metadata.eTag());
            final Long length = metadata.contentLength();
            final var body = new FilterInputStream(response) {
                @Override
                public void close() {
                    response.abort();
                }
            };
            document = context.receive(body, length == null ? -1 : length, spool, location.filename(),
                    received
                    -> new SourceProvenance(PROVIDER, location.reference(), version, entityTag, location.filename(),
                            received.bytes(), received.sha256()));
            complete = true;
        } catch (final PartitionException | InterruptedIOException | IllegalArgumentException permanent) {
            throw permanent;
        } catch (final RemoteSourceException failure) {
            if (failure.reason() == RemoteSourceException.Reason.INCOMPLETE) {
                throw new Transient(failure);
            }
            throw failure;
        } catch (final SdkException failure) {
            throw classify(failure, context);
        } catch (final IOException transfer) {
            context.checkActive();
            throw new Transient(new RemoteSourceException(RemoteSourceException.Reason.UNAVAILABLE,
                    "S3 transfer failed (" + transfer.getClass().getSimpleName() + ")"));
        } finally {
            if (!complete) {
                response.abort();
            }
        }
        // The document stays owned here until the response is released, so a release failure cannot strand it.
        try {
            response.close();
        } catch (final IOException | RuntimeException release) {
            final var failure = new RemoteSourceException(RemoteSourceException.Reason.UNAVAILABLE,
                    "S3 response could not be released (" + release.getClass().getSimpleName() + ")");
            try {
                document.close();
            } catch (final IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return document;
    }

    /**
     * Maps an SDK failure to a sanitized typed failure; SDK messages are not exposed because they can contain
     * endpoints, keys or request details.
     * @param failure SDK failure
     * @param context resolution context
     * @return permanent failure, or a {@link Transient} to retry
     * @throws IOException when the call was interrupted or its deadline passed
     */
    private static IOException classify(final SdkException failure, final ResolutionContext context)
            throws IOException {
        if (failure instanceof AbortedException || Thread.currentThread().isInterrupted()) {
            context.checkActive();
        }
        if (failure instanceof ApiCallTimeoutException || failure instanceof ApiCallAttemptTimeoutException) {
            if (context.remaining().isZero()) {
                return new RemoteSourceException(
                        RemoteSourceException.Reason.DEADLINE_EXCEEDED, "S3 request exceeded the acquisition deadline");
            }
            return new Transient(new RemoteSourceException(
                    RemoteSourceException.Reason.UNAVAILABLE, "S3 request attempt timed out"));
        }
        if (failure instanceof final S3Exception service) {
            final int status = service.statusCode();
            final String code = service.awsErrorDetails() == null ? null : service.awsErrorDetails().errorCode();
            final String described = "S3 GetObject failed: HTTP " + status + (code == null ? "" : " " + code);
            if (status == 404) {
                return new RemoteSourceException(RemoteSourceException.Reason.NOT_FOUND, described);
            }
            if (status == 403 || status == 401) {
                return new RemoteSourceException(RemoteSourceException.Reason.ACCESS_DENIED, described);
            }
            if (status == 412) {
                return new RemoteSourceException(
                        RemoteSourceException.Reason.INVALID_RESPONSE, "S3 object changed between attempts");
            }
            if (status == 429 || status >= 500) {
                return new Transient(new RemoteSourceException(RemoteSourceException.Reason.UNAVAILABLE, described));
            }
            return new RemoteSourceException(RemoteSourceException.Reason.INVALID_RESPONSE, described);
        }
        if (failure instanceof SdkClientException) {
            return new Transient(new RemoteSourceException(RemoteSourceException.Reason.UNAVAILABLE,
                    "S3 request failed (" + failure.getClass().getSimpleName() + ")"));
        }
        return new RemoteSourceException(RemoteSourceException.Reason.INVALID_RESPONSE,
                "S3 request failed (" + failure.getClass().getSimpleName() + ")");
    }

    /**
     * Sleeps between attempts.
     * @param nanos delay
     * @throws InterruptedIOException if interrupted
     */
    private static void sleep(final long nanos) throws InterruptedIOException {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted between S3 attempts");
        }
    }

    /**
     * Treats an absent or literal "null" version (unversioned or suspended buckets) as no version.
     * @param version response version
     * @return version or null
     */
    private static String normalizeVersion(final String version) {
        return version == null || version.isEmpty() || version.equals("null") ? null : version;
    }

    /**
     * Removes surrounding quotes from an entity tag.
     * @param entityTag raw tag or null
     * @return unquoted tag or null
     */
    private static String unquote(final String entityTag) {
        if (entityTag == null || entityTag.isEmpty()) {
            return null;
        }
        return entityTag.length() >= 2 && entityTag.startsWith("\"") && entityTag.endsWith("\"")
                ? entityTag.substring(1, entityTag.length() - 1)
                : entityTag;
    }

    /** Generation selected by the first response of a resolution. */
    private static final class Pin {
        /** Version to request, or null. */
        String version;

        /** Raw entity tag for If-Match, or null. */
        String entityTag;

        /**
         * Creates a pin.
         * @param version explicitly requested version, or null
         */
        Pin(final String version) {
            this.version = version;
        }
    }

    /** Retryable failure carrying the failure to report if attempts run out. */
    private static final class Transient extends IOException {
        /** Serialization identifier. */
        private static final long serialVersionUID = 1L;

        /**
         * Failure to report when no attempt remains.
         * @serial typed failure
         */
        private final RemoteSourceException failure;

        /**
         * Wraps a typed failure.
         * @param failure typed failure
         */
        Transient(final RemoteSourceException failure) {
            super(failure.getMessage());
            this.failure = failure;
        }
    }
}
