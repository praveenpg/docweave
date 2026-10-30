package io.docweave.source.s3;

import io.docweave.partition.SpoolPolicy;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Mutable configuration for {@link DefaultS3SourceResolver}. */
final class DefaultS3SourceResolverBuilder implements S3SourceResolver.Builder {
    /** Longest transport timeout. */
    private static final Duration MAX_TRANSPORT_TIMEOUT = Duration.ofMinutes(5);

    /** Longest first retry delay. */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(10);

    /** Caller-owned client, or null. */
    private S3Client client;

    /** Owned-client region. */
    private String region;

    /** Owned-client endpoint override. */
    private URI endpoint;

    /** Owned-client path-style addressing; null when not set. */
    private Boolean pathStyle;

    /** Owned-client credentials. */
    private AwsCredentialsProvider credentials;

    /** Default bucket for relative keys. */
    private String defaultBucket;

    /** Allowed buckets. */
    private Set<String> allowedBuckets = Set.of();

    /** Attempts. */
    private int maxAttempts = 3;

    /** First retry delay. */
    private Duration retryBackoff = Duration.ofMillis(200);

    /** Owned-client connect timeout, or null for the default. */
    private Duration connectTimeout;

    /** Owned-client socket timeout, or null for the default. */
    private Duration socketTimeout;

    /** Concurrent downloads. */
    private int maxConcurrentDownloads = 16;

    /** Spool policy. */
    private SpoolPolicy spool = SpoolPolicy.memory();

    /** Creates an empty builder. */
    DefaultS3SourceResolverBuilder() {
    }

    @Override
    public S3SourceResolver.Builder client(final S3Client value) {
        client = Objects.requireNonNull(value, "client");
        return this;
    }

    @Override
    public S3SourceResolver.Builder region(final String value) {
        Objects.requireNonNull(value, "region");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Blank region");
        }
        region = value.strip();
        return this;
    }

    @Override
    public S3SourceResolver.Builder endpoint(final URI value) {
        Objects.requireNonNull(value, "endpoint");
        final String scheme = value.getScheme() == null ? "" : value.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http")) || value.getHost() == null
                || value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new IllegalArgumentException("The S3 endpoint must be an http(s) URI without credentials or query");
        }
        endpoint = value;
        return this;
    }

    @Override
    public S3SourceResolver.Builder pathStyleAccess(final boolean value) {
        pathStyle = value;
        return this;
    }

    @Override
    public S3SourceResolver.Builder credentials(final AwsCredentialsProvider value) {
        credentials = Objects.requireNonNull(value, "credentials");
        return this;
    }

    @Override
    public S3SourceResolver.Builder defaultBucket(final String value) {
        Objects.requireNonNull(value, "defaultBucket");
        S3Location.validateBucket(value);
        defaultBucket = value;
        return this;
    }

    @Override
    public S3SourceResolver.Builder allowedBuckets(final Set<String> value) {
        final Set<String> copy = Set.copyOf(value);
        copy.forEach(S3Location::validateBucket);
        allowedBuckets = copy;
        return this;
    }

    @Override
    public S3SourceResolver.Builder maxAttempts(final int value) {
        if (value < 1 || value > 10) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
        }
        maxAttempts = value;
        return this;
    }

    @Override
    public S3SourceResolver.Builder retryBackoff(final Duration value) {
        retryBackoff = bounded(value, MAX_BACKOFF, "retryBackoff");
        return this;
    }

    @Override
    public S3SourceResolver.Builder connectTimeout(final Duration value) {
        connectTimeout = bounded(value, MAX_TRANSPORT_TIMEOUT, "connectTimeout");
        return this;
    }

    @Override
    public S3SourceResolver.Builder socketTimeout(final Duration value) {
        socketTimeout = bounded(value, MAX_TRANSPORT_TIMEOUT, "socketTimeout");
        return this;
    }

    @Override
    public S3SourceResolver.Builder maxConcurrentDownloads(final int value) {
        if (value < 1 || value > 1024) {
            throw new IllegalArgumentException("maxConcurrentDownloads must be between 1 and 1024");
        }
        maxConcurrentDownloads = value;
        return this;
    }

    @Override
    public S3SourceResolver.Builder spool(final SpoolPolicy value) {
        spool = Objects.requireNonNull(value, "spool");
        return this;
    }

    @Override
    public S3SourceResolver build() {
        if (defaultBucket != null && !allowedBuckets.isEmpty() && !allowedBuckets.contains(defaultBucket)) {
            throw new IllegalStateException("The default bucket must be one of the allowed buckets");
        }
        final boolean ownedSettings = region != null || endpoint != null || credentials != null || pathStyle != null
                                      || connectTimeout != null || socketTimeout != null;
        if (client != null) {
            if (ownedSettings) {
                throw new IllegalStateException(
                        "A caller-supplied client cannot be combined with region, endpoint, credentials, path-style or "
                        + "transport settings");
            }
            return new DefaultS3SourceResolver(client, false, defaultBucket, allowedBuckets, maxAttempts, retryBackoff,
                    maxConcurrentDownloads, spool);
        }
        if (region == null || credentials == null) {
            throw new IllegalStateException("An owned S3 client needs an explicit region and credentials provider");
        }
        final var http = Apache5HttpClient.builder()
                                 .connectionTimeout(connectTimeout != null ? connectTimeout : Duration.ofSeconds(10))
                                 .socketTimeout(socketTimeout != null ? socketTimeout : Duration.ofSeconds(30))
                                 .maxConnections(maxConcurrentDownloads);
        final var builder =
                S3Client.builder()
                        .region(Region.of(region))
                        .credentialsProvider(credentials)
                        .forcePathStyle(Boolean.TRUE.equals(pathStyle))
                        .httpClientBuilder(http)
                        // The resolver owns retries so that they stay within the acquisition deadline.
                        .overrideConfiguration(
                                configuration -> configuration.retryStrategy(retry -> retry.maxAttempts(1)));
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        return new DefaultS3SourceResolver(builder.build(), true, defaultBucket, allowedBuckets, maxAttempts,
                retryBackoff, maxConcurrentDownloads, spool);
    }

    /**
     * Validates a positive bounded duration.
     * @param value duration
     * @param max inclusive maximum
     * @param name setting name
     * @return value
     */
    private static Duration bounded(final Duration value, final Duration max, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero() || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " must be positive and at most " + max);
        }
        return value;
    }
}
