package dev.docweave.json;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Thread-safe UTF-8 compatibility codec for the supported element taxonomy.
 * Each wire object has exactly {@code type}, {@code element_id}, {@code text} and {@code metadata}.
 * Unknown metadata survives decoding; unknown element types and extra top-level fields fail.
 * Encoding omits empty top-level metadata collections and the debug field {@code detection_origin}.
 * Reads enforce independent JSON resource limits; output lists still materialize.
 *
 * <pre>{@code
 * var codec = ElementJson.create();
 * codec.write(Path.of("elements.json"), result.elements());
 * var restored = codec.read(Path.of("elements.json"));
 * }</pre>
 *
 * @see dev.docweave.core.Element
 */
final class DefaultElementJson implements ElementJson {
    /** Allowed top-level JSON field names for serialized elements. */
    private static final Set<String> FIELDS = Set.of("type", "element_id", "text", "metadata");
    /** Shared immutable Jackson mapper used for writing element JSON. */
    private static final JsonMapper MAPPER =
            JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    /** Maximum allowed decompressed byte count for compressed original-element payloads. */
    private static final int MAX_ORIGINAL_BYTES = 16 * 1024 * 1024;

    /** Creates a codec using a shared immutable Jackson mapper. */
    DefaultElementJson() {
        this(JsonReadOptions.defaults());
    }

    /** Resource and structural limits enforced when reading element JSON. */
    private final JsonReadOptions options;
    /** Jackson mapper configured with stream read constraints for deserialization. */
    private final JsonMapper reader;

    /**
     * Creates a codec configured with the specified JSON read resource limits.
     *
     * @param options non-null JSON read resource limits
     */
    DefaultElementJson(final JsonReadOptions options) {
        this.options = java.util.Objects.requireNonNull(options, "options");
        final var constraints = tools.jackson.core.StreamReadConstraints.builder()
                                        .maxNestingDepth(options.maxNestingDepth())
                                        .maxStringLength(options.maxStringLength())
                                        .build();
        reader = JsonMapper
                         .builder(tools.jackson.core.json.JsonFactory.builder()
                                         .streamReadConstraints(constraints)
                                         .build())
                         .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                         .build();
    }

    /**
     * Serializes an ordered array without mutating its elements.
     * @param elements ordered nonnull elements
     * @return compact compatibility JSON; compression/key bytes are not a stable identity
     * @throws NullPointerException if the list or an element is null
     * @throws tools.jackson.core.JacksonException if JSON serialization fails
     */
    public String write(final List<Element> elements) {
        return MAPPER.writeValueAsString(elements.stream().map(this::asMap).toList());
    }

    /**
     * Writes UTF-8 JSON, creating or replacing a file. The write is not atomic.
     * @param path output file; parent directories must already exist
     * @param elements ordered elements to serialize
     * @throws IOException if the file cannot be written
     * @throws tools.jackson.core.JacksonException if serialization fails
     */
    public void write(final Path path, final List<Element> elements) throws IOException {
        Files.writeString(path, write(elements), StandardCharsets.UTF_8);
    }

    /**
     * Reads a complete UTF-8 element-array file.
     * @param path compatibility JSON file
     * @return an immutable ordered list
     * @throws IOException if the file cannot be read or is not valid UTF-8
     * @throws IllegalArgumentException if the element schema or taxonomy is unsupported
     * @throws tools.jackson.core.JacksonException if JSON syntax is invalid
     */
    public List<Element> read(final Path path) throws IOException {
        checkFileInterrupted();
        if (Files.size(path) > options.maxInputBytes()) {
            throw new IllegalArgumentException("JSON input byte limit exceeded");
        }
        byte[] bytes;
        try (final var input = Files.newInputStream(path)) {
            bytes = BoundedJsonInput.read(input, options.maxInputBytes());
        }
        checkFileInterrupted();
        final var text = StandardCharsets.UTF_8.newDecoder()
                                 .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                 .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                                 .decode(java.nio.ByteBuffer.wrap(bytes))
                                 .toString();
        try {
            return read(text);
        } catch (final java.util.concurrent.CancellationException error) {
            throw new java.io.InterruptedIOException("JSON reading interrupted");
        }
    }

    /**
     * Decodes compatibility JSON without guessing missing fields or coercing text types.
     * Trailing JSON documents are rejected. Omitted metadata remains omitted.
     * @param json one JSON array of supported element objects
     * @return ordered elements with deeply immutable metadata
     * @throws IllegalArgumentException if fields, IDs, metadata or element types are invalid
     * @throws tools.jackson.core.JacksonException if JSON syntax or trailing content is invalid
     */
    public List<Element> read(final String json) {
        java.util.Objects.requireNonNull(json, "json");
        checkInterrupted();
        // Preflight the string for unpaired surrogates and compute its exact UTF-8 byte length against maxInputBytes.
        long bytes = 0;
        for (int offset = 0; offset < json.length();) {
            if ((offset & 1023) == 0) {
                checkInterrupted();
            }
            final int point = json.codePointAt(offset);
            if (point >= 0xd800 && point <= 0xdfff) {
                throw new IllegalArgumentException("Invalid JSON source Unicode");
            }
            bytes += point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
            if (bytes > options.maxInputBytes()) {
                throw new IllegalArgumentException("JSON input byte limit exceeded");
            }
            offset += Character.charCount(point);
        }
        final var result = new ArrayList<Element>();
        // Stream-parse the top-level JSON array element by element to enforce maxElements before full list
        // materialization.
        try (final var parser = reader.createParser(json)) {
            if (parser.nextToken() != tools.jackson.core.JsonToken.START_ARRAY) {
                throw new IllegalArgumentException("Expected an element array");
            }
            while (parser.nextToken() != tools.jackson.core.JsonToken.END_ARRAY) {
                checkInterrupted();
                if (result.size() >= options.maxElements()) {
                    throw new IllegalArgumentException("JSON element count limit exceeded");
                }
                if (parser.currentToken() != tools.jackson.core.JsonToken.START_OBJECT) {
                    throw new IllegalArgumentException("Expected an element object");
                }
                final Object item = reader.readValue(parser, Object.class);
                result.add(element(item));
            }
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("Trailing JSON content");
            }
        }
        checkInterrupted();
        return List.copyOf(result);
    }

    /**
     * Validates and converts a decoded Jackson object graph into an immutable {@link Element}.
     * @param item decoded JSON value expected to be a four-field element map
     * @return validated immutable element
     */
    private static Element element(final Object item) {
        if (!(item instanceof final Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected an element object");
        }
        if (!map.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("Unsupported or missing element fields: " + map.keySet());
        }
        if (!(map.get("metadata") instanceof final Map<?, ?> metadata)) {
            throw new IllegalArgumentException("Expected metadata object");
        }
        final var typedMetadata = new LinkedHashMap<String, Object>();
        metadata.forEach((key, nested) -> {
            if (!(key instanceof final String name)) {
                throw new IllegalArgumentException("Expected metadata string key");
            }
            typedMetadata.put(name, nested);
        });
        return new Element(string(map, "element_id"), ElementType.valueOf(string(map, "type")), string(map, "text"),
                typedMetadata);
    }

    /**
     * Checks thread interruption during in-memory JSON decoding without clearing the interrupt flag.
     */
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("JSON reading interrupted");
        }
    }

    /**
     * Checks thread interruption during file-based JSON reading without clearing the interrupt flag.
     * @throws java.io.InterruptedIOException if the calling thread is interrupted
     */
    private static void checkFileInterrupted() throws java.io.InterruptedIOException {
        BoundedJsonInput.checkInterrupted();
    }

    /**
     * Encodes provenance as base64 of zlib-compressed compatibility JSON.
     * Upstream calls this payload "gzipped" but uses zlib. Coordinate precision is rounded to one
     * decimal in PixelSpace and two elsewhere; detection probabilities use five decimals.
     * Rounding does not mutate input elements. Recursive provenance is not removed by this method;
     * the chunker removes it before encoding. UTF8 JSON is counted as it streams into compression;
     * only one rounded wire element is staged at a time. Caller inputs, individual element graphs and
     * the final compressed/base64 payload still materialize; this is not a total heap bound.
     * @param elements originals to encode in source order
     * @return a payload suitable for {@code metadata.orig_elements}
     * @throws IllegalArgumentException if precision metadata is malformed or serialized originals exceed
     *         the decoder's 16 MiB UTF-8 JSON limit
     * @throws IllegalStateException if in-memory compression fails
     * @throws tools.jackson.core.JacksonException if JSON serialization fails
     */
    public String encodeOriginals(final List<Element> elements) {
        final var bytes = new ByteArrayOutputStream();
        try {
            try (final var compressed = new DeflaterOutputStream(bytes)) {
                // A Writer preserves the legacy String.getBytes UTF8 replacement semantics for malformed surrogates.
                // Its accounting view borrows the compressor, whose outer scope owns final zlib completion.
                try (final var writer = new OutputStreamWriter(
                             new OriginalsJsonOutput(compressed, MAX_ORIGINAL_BYTES), StandardCharsets.UTF_8);
                        final var sequence = MAPPER.writer().writeValuesAsArray(writer)) {
                    for (final var element : elements) {
                        sequence.write(asMap(roundOriginalPrecision(element)));
                    }
                }
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (final IOException | JacksonException error) {
            if (OriginalsJsonOutput.exceeded(error)) {
                throw new IllegalArgumentException(
                        "Encoded original elements exceed the 16 MiB decoded-data limit", error);
            }
            if (error instanceof JacksonException jsonError) {
                throw jsonError;
            }
            throw new IllegalStateException("Cannot encode original elements", error);
        }
    }

    /**
     * Decodes upstream-compatible zlib provenance with a 16 MiB decompressed-data limit.
     * Encoded strings over 32 MiB are rejected before allocating decoded data.
     * Compression bytes can differ between implementations; decoded elements define compatibility.
     * @param payload base64-encoded zlib JSON
     * @return an immutable ordered list of original elements
     * @throws IllegalArgumentException if base64, compression, schema or size is invalid
     * @throws tools.jackson.core.JacksonException if decompressed JSON syntax is invalid
     */
    public List<Element> decodeOriginals(final String payload) {
        if (payload.length() > MAX_ORIGINAL_BYTES * 2) {
            throw new IllegalArgumentException("Original-element payload too large");
        }
        try (final var compressed =
                        new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(payload)))) {
            byte[] bytes = compressed.readNBytes(MAX_ORIGINAL_BYTES + 1);
            if (bytes.length > MAX_ORIGINAL_BYTES) {
                throw new IllegalArgumentException("Decoded original elements exceed 16 MiB");
            }
            return new DefaultElementJson(new JsonReadOptions(MAX_ORIGINAL_BYTES, Integer.MAX_VALUE, 1000, 20_000_000))
                    .read(new String(bytes, StandardCharsets.UTF_8));
        } catch (final IOException error) {
            throw new IllegalArgumentException("Invalid compressed original elements", error);
        }
    }

    /**
     * Projects an element into the four-key wire map while omitting empty top-level metadata containers.
     * @param element source element to convert
     * @return mutable wire representation for Jackson serialization
     */
    private Map<String, Object> asMap(final Element element) {
        final var metadata = new LinkedHashMap<>(element.metadata());
        metadata.remove("detection_origin");
        metadata.values().removeIf(value -> value instanceof final List<?> list && list.isEmpty()
                || value instanceof final Map<?, ?> map && map.isEmpty());
        final var result = new LinkedHashMap<String, Object>();
        result.put("type", element.type().name());
        result.put("element_id", element.elementId());
        result.put("text", element.text());
        result.put("metadata", metadata);
        return result;
    }

    /**
     * Extracts a required string property from a decoded element map.
     * @param map decoded element map
     * @param field required property key
     * @return nonnull string value
     */
    private static String string(final Map<?, ?> map, final String field) {
        if (map.get(field) instanceof final String value) {
            return value;
        }
        throw new IllegalArgumentException(field + " must be a string");
    }

    /**
     * Returns a copy of the element with coordinate points and detection probabilities rounded for provenance.
     * @param element source element
     * @return element with rounded numeric provenance metadata
     */
    private static Element roundOriginalPrecision(final Element element) {
        final var metadata = new LinkedHashMap<>(element.metadata());
        // Round coordinate point pairs to 1 decimal place in PixelSpace or 2 decimal places in other coordinate
        // systems.
        if (metadata.get("coordinates") instanceof final Map<?, ?> coordinates && coordinates.get("points") instanceof final List<?> points) {
            final int precision = "PixelSpace".equals(coordinates.get("system")) ? 1 : 2;
            final var adjusted = new LinkedHashMap<String, Object>();
            coordinates.forEach((key, value) -> adjusted.put((String) key, value));
            adjusted.put("points", points.stream()
                                           .map(point -> {
                                               if (!(point instanceof final List<?> pair) || pair.size() != 2) {
                                                   throw new IllegalArgumentException(
                                                           "Coordinate point must contain two numbers");
                                               }
                                               return pair.stream().map(value -> round(value, precision)).toList();
                                           })
                                           .toList());
            metadata.put("coordinates", adjusted);
        }
        if (metadata.get("detection_class_prob") instanceof final Number probability
                && probability.doubleValue() != 0) {
            metadata.put("detection_class_prob", round(probability, 5));
        }
        return new Element(element.elementId(), element.type(), element.text(), metadata);
    }

    /**
     * Rounds a finite numeric value using half-even decimal rounding to match upstream provenance encoding.
     * @param value numeric object to round
     * @param precision decimal scale
     * @return rounded double value
     */
    private static double round(final Object value, final int precision) {
        if (!(value instanceof final Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Coordinate/probability value must be finite numeric data");
        }
        // Exact binary-double decimal expansion reproduces Python's half-even decimal rounding.
        return new BigDecimal(number.doubleValue()).setScale(precision, RoundingMode.HALF_EVEN).doubleValue();
    }
}
