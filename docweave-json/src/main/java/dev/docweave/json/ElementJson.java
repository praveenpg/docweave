package dev.docweave.json;

import dev.docweave.core.Element;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Thread-safe UTF-8 compatibility codec for the supported element taxonomy.
 * Each wire object has exactly {@code type}, {@code element_id}, {@code text} and {@code metadata}.
 * Unknown metadata survives decoding; unknown element types and extra top-level fields fail.
 * Encoding omits empty top-level metadata collections and the debug field {@code detection_origin}.
 * Reads use independent JsonReadOptions limits and return complete lists; writes remain unbounded.
 * Default reads accept at most 16 MiB and 100,000 elements, with depth 128.
 *
 * <pre>{@code
 * var codec = ElementJson.create();
 * codec.write(Path.of("elements.json"), result.elements());
 * var restored = codec.read(Path.of("elements.json"));
 * }</pre>
 *
 * @see dev.docweave.core.Element
 */
public interface ElementJson {
    /**
     * Creates the built-in thread-safe implementation behind this contract.
     * @return a reusable ElementJson service
     */
    static ElementJson create() {
        return new DefaultElementJson();
    }

    /**
     * Creates a reusable codec with independent saved-element read limits.
     * Writes and fixed provenance encoding/decoding limits are unaffected by this policy.
     * @param options nonnull immutable read limits
     * @return thread-safe codec with hidden implementation
     * @throws NullPointerException if options is null
     */
    static ElementJson create(final JsonReadOptions options) {
        return new DefaultElementJson(options);
    }

    /**
     * Serializes an ordered array without mutating its elements.
     * @param elements ordered nonnull elements
     * @return compact compatibility JSON; compression/key bytes are not a stable identity
     * @throws NullPointerException if the list or an element is null
     * @throws tools.jackson.core.JacksonException if JSON serialization fails
     */
    String write(final List<Element> elements);
    /**
     * Writes UTF-8 JSON, creating or replacing a file. The write is not atomic.
     * @param path output file; parent directories must already exist
     * @param elements ordered elements to serialize
     * @throws IOException if the file cannot be written
     * @throws tools.jackson.core.JacksonException if serialization fails
     */
    void write(final Path path, final List<Element> elements) throws IOException;
    /**
     * Reads a bounded UTF-8 element-array file with strict decoding.
     * File growth is checked during reading; size metadata alone is not trusted.
     * @throws java.io.InterruptedIOException if interrupted while reading or decoding; flag preserved
     * @param path compatibility JSON file
     * @return an immutable ordered list
     * @throws IOException if the file cannot be read or is not valid UTF-8
     * @throws IllegalArgumentException if the element schema/taxonomy is unsupported or byte/element limits are
     *         exceeded
     * @throws tools.jackson.core.JacksonException if JSON syntax or parser resource limits are invalid
     */
    List<Element> read(final Path path) throws IOException;
    /**
     * Decodes compatibility JSON without guessing missing fields or coercing text types.
     * Trailing JSON documents are rejected. Omitted metadata remains omitted.
     * @throws java.util.concurrent.CancellationException if interrupted between decoding elements; flag preserved
     * @param json one JSON array of supported element objects
     * @return ordered elements with deeply immutable metadata
     * @throws IllegalArgumentException if fields, IDs, metadata, element types, Unicode or byte/element limits are
     *         invalid
     * @throws tools.jackson.core.JacksonException if JSON syntax, parser resource limits or trailing content are
     *         invalid
     */
    List<Element> read(final String json);
    /**
     * Encodes provenance as base64 of zlib-compressed compatibility JSON.
     * Upstream calls this payload "gzipped" but uses zlib. Coordinate precision is rounded to one
     * decimal in PixelSpace and two elsewhere; detection probabilities use five decimals.
     * Rounding does not mutate input elements. Recursive provenance is not removed by this method;
     * the chunker removes it before encoding. The ceiling counts complete uncompressed UTF-8 JSON,
     * including structural fields. The default codec enforces it as JSON is encoded; caller inputs,
     * individual element graphs and the final encoded payload still materialize. Compression bytes
     * are not a stable identity; decoded elements define compatibility.
     * @param elements originals to encode in source order
     * @return a payload suitable for {@code metadata.orig_elements}
     * @throws IllegalArgumentException if precision metadata is malformed or serialized originals exceed
     *         the decoder's 16 MiB UTF-8 JSON limit
     * @throws IllegalStateException if in-memory compression fails
     * @throws tools.jackson.core.JacksonException if JSON serialization fails
     */
    String encodeOriginals(final List<Element> elements);
    /**
     * Decodes upstream-compatible zlib provenance with a 16 MiB decompressed-data limit.
     * Encoded strings over 32 MiB are rejected before allocating decoded data.
     * Compression bytes can differ between implementations; decoded elements define compatibility.
     * @param payload base64-encoded zlib JSON
     * @return an immutable ordered list of original elements
     * @throws IllegalArgumentException if base64, compression, schema or size is invalid
     * @throws tools.jackson.core.JacksonException if decompressed JSON syntax is invalid
     */
    List<Element> decodeOriginals(final String payload);
}
