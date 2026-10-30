package io.docweave.source.s3;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A validated S3 object reference parsed from an opaque remote location.
 *
 * <p>Accepted syntax:</p>
 * <ul>
 * <li>{@code s3://bucket/key} with an optional {@code ?versionId=ID}. The key is percent-decoded (write {@code %3F} for
 * a literal {@code ?}, {@code %23} for {@code #}, {@code %25} for {@code %}); no other query parameter, fragment,
 * user info or port is accepted.</li>
 * <li>Any other string is a key in the resolver's configured default bucket, used exactly as written (no decoding,
 * latest version).</li>
 * </ul>
 *
 * @param bucket bucket name
 * @param key object key
 * @param versionId requested version, or null for the latest
 */
record S3Location(String bucket, String key, String versionId) {
    /** Bucket names accepted by S3 for virtual-hosted and path-style addressing. */
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");

    /** Maximum UTF-8 key length accepted by S3. */
    private static final int MAX_KEY_BYTES = 1024;

    /** Scheme prefix of fully qualified locations. */
    private static final String SCHEME = "s3://";

    /**
     * Validates components.
     * @param bucket bucket
     * @param key key
     * @param versionId version or null
     */
    S3Location {
        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(key, "key");
    }

    /**
     * Parses a location.
     * @param location opaque location, unchanged from the caller
     * @param defaultBucket default bucket for relative keys, or null
     * @param allowedBuckets buckets the resolver may read, or empty for no restriction
     * @return parsed location
     * @throws IllegalArgumentException if the location is malformed, needs a missing default bucket, or names a bucket
     *         outside the allow-list; messages never echo the location
     */
    static S3Location parse(final String location, final String defaultBucket, final Set<String> allowedBuckets) {
        Objects.requireNonNull(location, "location");
        final S3Location parsed;
        if (location.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            parsed = parseQualified(location.substring(SCHEME.length()));
        } else {
            if (defaultBucket == null) {
                throw new IllegalArgumentException(
                        "Relative S3 keys need a default bucket; use s3://bucket/key or configure defaultBucket");
            }
            parsed = new S3Location(defaultBucket, location, null);
        }
        validateBucket(parsed.bucket());
        validateKey(parsed.key());
        if (!allowedBuckets.isEmpty() && !allowedBuckets.contains(parsed.bucket())) {
            throw new IllegalArgumentException("The S3 bucket is not allowed by this resolver");
        }
        return parsed;
    }

    /**
     * Validates a bucket name.
     * @param bucket candidate
     * @throws IllegalArgumentException if invalid
     */
    static void validateBucket(final String bucket) {
        if (!BUCKET.matcher(bucket).matches() || bucket.contains("..")) {
            throw new IllegalArgumentException("Invalid S3 bucket name");
        }
    }

    /**
     * Parses {@code bucket/key[?versionId=...]}.
     * @param rest text after the scheme
     * @return parsed location
     */
    private static S3Location parseQualified(final String rest) {
        final int slash = rest.indexOf('/');
        if (slash <= 0) {
            throw new IllegalArgumentException("S3 locations need the form s3://bucket/key");
        }
        final String bucket = rest.substring(0, slash);
        if (bucket.contains("@") || bucket.contains(":")) {
            throw new IllegalArgumentException("S3 locations cannot contain credentials or ports");
        }
        String path = rest.substring(slash + 1);
        if (path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("S3 locations cannot contain a fragment; encode # as %23");
        }
        String versionId = null;
        final int query = path.indexOf('?');
        if (query >= 0) {
            final String parameters = path.substring(query + 1);
            path = path.substring(0, query);
            if (!parameters.startsWith("versionId=") || parameters.indexOf('&') >= 0) {
                throw new IllegalArgumentException("The only supported S3 location parameter is versionId");
            }
            versionId = decode(parameters.substring("versionId=".length()));
            if (versionId.isEmpty()) {
                throw new IllegalArgumentException("Empty S3 versionId");
            }
        }
        return new S3Location(bucket, decode(path), versionId);
    }

    /**
     * Validates a key.
     * @param key candidate
     * @throws IllegalArgumentException if empty, too long or containing control characters
     */
    private static void validateKey(final String key) {
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Empty S3 object key");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("S3 object key exceeds 1024 bytes");
        }
        if (key.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("S3 object key contains control characters");
        }
    }

    /**
     * Percent-decodes a path component, keeping '+' literal.
     * @param value encoded text
     * @return decoded text
     * @throws IllegalArgumentException for malformed escapes
     */
    private static String decode(final String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Malformed percent-encoding in S3 location");
        }
    }

    /**
     * Returns the logical filename: the last key segment.
     * @return filename, or null when the key ends with a slash
     */
    String filename() {
        final String name = key.substring(key.lastIndexOf('/') + 1);
        return name.isEmpty() ? null : name;
    }

    /**
     * Returns the safe reference used in provenance: bucket and key, never credentials or endpoints.
     * @return {@code s3://bucket/key}
     */
    String reference() {
        return SCHEME + bucket + "/" + key;
    }

    /**
     * Describes the location without the key, which may be sensitive.
     * @return redacted description
     */
    @Override
    public String toString() {
        return "S3Location[bucket=" + bucket + ", key=<redacted>]";
    }
}
