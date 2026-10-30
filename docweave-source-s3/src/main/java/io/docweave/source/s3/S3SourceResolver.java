package io.docweave.source.s3;

import io.docweave.partition.RemoteSourceResolver;
import io.docweave.partition.SpoolPolicy;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * Optional {@link RemoteSourceResolver} for AWS S3 and explicitly configured S3-compatible services. It is never
 * discovered or created implicitly: an application builds it with explicit client, credential and region settings and
 * passes it to {@code DocWeave.builder().remoteSources(...)}. Installing this module performs no network access.
 *
 * <p><b>Locations.</b> {@code s3://bucket/key} with an optional {@code ?versionId=ID} (the key is percent-decoded:
 * {@code %3F} for {@code ?}, {@code %23} for {@code #}, {@code %25} for {@code %}), or a key relative to the configured
 * {@linkplain Builder#defaultBucket(String) default bucket}, used exactly as written. Anything else, a bucket outside
 * {@link Builder#allowedBuckets(Set)}, or a relative key without a default bucket is an
 * {@link IllegalArgumentException}. Locations never select an endpoint or credentials, so callers cannot redirect
 * the resolver to another service.</p>
 *
 * <p><b>Acquisition.</b> One GetObject per attempt, read through the core
 * {@link io.docweave.partition.ResolutionContext#receive} rules: actual bytes against {@code maxInputBytes}, exact
 * Content-Length, deadline and interruption (which abort the HTTP response), SHA-256 of the received bytes. Transient
 * failures (HTTP 5xx and 429, network errors, incomplete bodies) are retried up to {@link Builder#maxAttempts(int)}
 * with backoff that never exceeds the remaining deadline. Retries are pinned to the version (or, when the bucket is
 * not versioned, the entity tag) of the first response, so a retry never mixes two object generations. Missing
 * objects and denied access are not retried.</p>
 *
 * <p><b>Ownership.</b> A client supplied with {@link Builder#client(S3Client)} belongs to the caller and is never
 * closed by this resolver; its own retry and timeout settings apply within each attempt. A client built from
 * {@link Builder#region(String)} and related settings is owned and closed by {@link #close()}. Instances are
 * thread-safe; {@link Builder#maxConcurrentDownloads(int)} bounds concurrent transfers independently of parsing.</p>
 */
public interface S3SourceResolver extends RemoteSourceResolver, AutoCloseable {
    /**
     * Starts explicit configuration.
     * @return a new mutable builder
     */
    static Builder builder() {
        return new DefaultS3SourceResolverBuilder();
    }

    /**
     * Closes the client if this resolver owns it. A caller-supplied client stays open. Later resolutions fail with
     * {@link IllegalStateException}. Repeated calls have no effect.
     */
    @Override void close();

    /** Mutable, single-use resolver configuration. */
    interface Builder {
        /**
         * Uses a caller-owned client. The resolver never closes it. Incompatible with {@link #region(String)},
         * {@link #endpoint(URI)}, {@link #credentials(AwsCredentialsProvider)}, {@link #pathStyleAccess(boolean)} and
         * the transport timeouts, which configure an owned client.
         * @param value configured synchronous client
         * @return this builder
         * @throws NullPointerException if value is null
         */
        Builder client(S3Client value);

        /**
         * Sets the signing region of an owned client; required unless a client is supplied. S3-compatible services
         * usually accept {@code us-east-1}.
         * @param value region identifier
         * @return this builder
         * @throws NullPointerException if value is null
         * @throws IllegalArgumentException if value is blank
         */
        Builder region(String value);

        /**
         * Directs an owned client to an S3-compatible endpoint instead of AWS.
         * @param value http(s) endpoint without user info, query or fragment
         * @return this builder
         * @throws NullPointerException if value is null
         * @throws IllegalArgumentException if value is not such an endpoint
         */
        Builder endpoint(URI value);

        /**
         * Uses path-style requests ({@code endpoint/bucket/key}); most S3-compatible services need it.
         * @param value true for path-style addressing (default false)
         * @return this builder
         */
        Builder pathStyleAccess(boolean value);

        /**
         * Sets the credentials of an owned client; required unless a client is supplied. No credential discovery
         * happens implicitly: pass {@code DefaultCredentialsProvider.builder().build()} to opt into the AWS default
         * chain, or {@code AnonymousCredentialsProvider.create()} for public objects.
         * @param value credentials provider
         * @return this builder
         * @throws NullPointerException if value is null
         */
        Builder credentials(AwsCredentialsProvider value);

        /**
         * Sets the bucket for relative keys.
         * @param value valid bucket name
         * @return this builder
         * @throws NullPointerException if value is null
         * @throws IllegalArgumentException if value is not a valid bucket name
         */
        Builder defaultBucket(String value);

        /**
         * Restricts reads to these buckets; the default bucket must be among them. Empty means no restriction.
         * @param value allowed bucket names
         * @return this builder
         * @throws NullPointerException if value or an element is null
         * @throws IllegalArgumentException if a name is invalid
         */
        Builder allowedBuckets(Set<String> value);

        /**
         * Sets the number of attempts for transient failures, including the first (default 3).
         * @param value between 1 and 10
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        Builder maxAttempts(int value);

        /**
         * Sets the first retry delay; later delays double up to 5 seconds, with jitter, and never exceed the remaining
         * deadline (default 200 ms).
         * @param value positive delay of at most 10 seconds
         * @return this builder
         * @throws NullPointerException if value is null
         * @throws IllegalArgumentException if out of range
         */
        Builder retryBackoff(Duration value);

        /**
         * Sets the TCP connect timeout of an owned client (default 10 s).
         * @param value positive duration of at most 5 minutes
         * @return this builder
         * @throws NullPointerException if value is null
         * @throws IllegalArgumentException if out of range
         */
        Builder connectTimeout(Duration value);

        /**
         * Sets the socket read timeout of an owned client (default 30 s); the acquisition deadline still applies.
         * @param value positive duration of at most 5 minutes
         * @return this builder
         * @throws NullPointerException if value is null
         * @throws IllegalArgumentException if out of range
         */
        Builder socketTimeout(Duration value);

        /**
         * Bounds concurrent downloads (default 16); a call waits for a slot only until its deadline.
         * @param value between 1 and 1024
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        Builder maxConcurrentDownloads(int value);

        /**
         * Chooses in-memory (default) or temporary-file buffering of downloads.
         * @param value spool policy
         * @return this builder
         * @throws NullPointerException if value is null
         */
        Builder spool(SpoolPolicy value);

        /**
         * Creates the resolver. An owned client is created here, without any network call.
         * @return a thread-safe resolver
         * @throws IllegalStateException if the configuration is incomplete or contradictory
         */
        S3SourceResolver build();
    }
}
