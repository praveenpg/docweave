package io.docweave.partition;

import java.util.HexFormat;
import java.util.Objects;

/**
 * Safe description of where a resolved remote document came from, reported in
 * {@link PartitionResult#sourceProvenance()}. It never contains credentials, signed URLs, query strings or temporary
 * paths. Element metadata and element IDs are unaffected by provenance: they depend only on the logical filename,
 * text and position, exactly as for a local source with the same bytes and filename.
 * @param provider short provider identifier, for example {@code "s3"}
 * @param reference safe logical reference chosen by the resolver, for example {@code "s3://bucket/key"}
 * @param version provider object version actually read, or null when the provider reports none (an unversioned
 *        object is mutable; its content is not a reproducible snapshot)
 * @param entityTag opaque provider entity tag, or null; not necessarily a content hash
 * @param filename logical filename used for format routing, metadata and element IDs, or null
 * @param bytes number of content bytes actually received
 * @param sha256 lowercase hexadecimal SHA-256 of the received bytes, or null when not computed
 */
public record SourceProvenance(String provider, String reference, String version, String entityTag, String filename,
        long bytes, String sha256) {
    /**
     * Validates components.
     * @param provider nonblank provider identifier
     * @param reference nonblank safe reference
     * @param version optional version
     * @param entityTag optional entity tag
     * @param filename optional logical filename
     * @param bytes nonnegative received byte count
     * @param sha256 optional 64-character lowercase hexadecimal digest
     * @throws NullPointerException if provider or reference is null
     * @throws IllegalArgumentException if a value is blank, negative or malformed
     */
    public SourceProvenance {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(reference, "reference");
        if (provider.isBlank() || reference.isBlank()) {
            throw new IllegalArgumentException("Provenance needs a provider and a reference");
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("Negative byte count");
        }
        if (sha256 != null
                && !(sha256.length() == 64
                        && sha256.chars().allMatch(c -> HexFormat.isHexDigit(c) && !Character.isUpperCase(c)))) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal digits");
        }
    }
}
