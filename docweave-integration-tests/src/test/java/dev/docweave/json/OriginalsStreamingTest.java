package dev.docweave.json;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Independent wire/precision, UTF8-boundary and early-budget controls for compressed original provenance. */
class OriginalsStreamingTest {
    /** Unchanged decoder-compatible uncompressed UTF8 ceiling. */
    private static final int LIMIT = 16 * 1024 * 1024;
    /** Public codec service, shared safely by independent calls. */
    private final ElementJson json = ElementJson.create();

    /** Creates public codec behavioral controls. */
    OriginalsStreamingTest() {
    }

    /**
     * Known oversize input fails without visiting later originals, rather than staging the complete collection first.
     */
    @Test
    void oversizedFirstOriginalStopsBeforeLaterListAccess() {
        final var first = element("x".repeat(LIMIT));
        final var second = element("Later original");
        final var laterReads = new AtomicInteger();
        final List<Element> originals = new AbstractList<>() {
            /**
             * Supplies ordinary immutable source elements while recording access to the later one.
             * @param index valid original position
             * @return unchanged source element
             */
            @Override
            public Element get(final int index) {
                Objects.checkIndex(index, 2);
                if (index == 1) {
                    laterReads.incrementAndGet();
                }
                return index == 0 ? first : second;
            }

            /**
             * Declares the complete valid source population without forcing either element.
             * @return two originals
             */
            @Override
            public int size() {
                return 2;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> json.encodeOriginals(originals));
        assertEquals(0, laterReads.get(), "The UTF8 ceiling must stop before later originals are staged");
    }

    /**
     * Exact16MiB JSON remains readable; a single extra ASCII byte fails for both ASCII and multibyte source text.
     * @throws Exception if compression or independent decompression fails
     */
    @Test
    void decodedUtf8CeilingIncludesWireOverheadAndMultibyteCharacters() throws Exception {
        final int overhead = json.write(List.of(element(""))).getBytes(StandardCharsets.UTF_8).length;
        for (final String unit : List.of("x", "€")) {
            final int bytes = unit.getBytes(StandardCharsets.UTF_8).length;
            final String text = unit.repeat((LIMIT - overhead) / bytes) + "x".repeat((LIMIT - overhead) % bytes);
            final String encoded = json.encodeOriginals(List.of(element(text)));
            assertEquals(LIMIT, inflated(encoded).length);
            assertEquals(text, json.decodeOriginals(encoded).getFirst().text());
            assertThrows(IllegalArgumentException.class, () -> json.encodeOriginals(List.of(element(text + "x"))));
        }
    }

    /**
     * Independent expected precision/wire projection matches exact decompressed bytes without input mutation.
     * @throws Exception if compression or decompression fails
     */
    @Test
    void roundingEscapingOrderAndMetadataProjectionMatchExistingWireWriter() throws Exception {
        final var coordinates = Map.of("system", "PixelSpace", "points", List.of(List.of(1.25, 2.75)));
        final var metadata = new LinkedHashMap<String, Object>();
        metadata.put("coordinates", coordinates);
        metadata.put("detection_class_prob", .12345678);
        metadata.put("detection_origin", "owned");
        metadata.put("empty", List.of());
        metadata.put("vendor", Map.of("nested_empty", List.of()));
        final var original = new Element("precision", ElementType.Image, "Quoted \"text\" \\ tab\t 🧵", metadata);
        final var expectedMetadata = new LinkedHashMap<String, Object>();
        expectedMetadata.put("coordinates", Map.of("system", "PixelSpace", "points", List.of(List.of(1.2, 2.8))));
        expectedMetadata.put("detection_class_prob", .12346);
        expectedMetadata.put("vendor", Map.of("nested_empty", List.of()));
        final var expected = List.of(new Element("precision", ElementType.Image, original.text(), expectedMetadata));
        final String before = json.write(List.of(original));
        final String encoded = json.encodeOriginals(List.of(original));
        assertArrayEquals(json.write(expected).getBytes(StandardCharsets.UTF_8), inflated(encoded));
        assertEquals(expected, json.decodeOriginals(encoded));
        assertEquals(before, json.write(List.of(original)));
        assertEquals(.12345678, original.metadata().get("detection_class_prob"));
    }

    /**
     * Streaming retains legacy whole-string UTF8 replacement for malformed surrogate text and metadata keys/values.
     * @throws Exception if compression or decoding fails
     */
    @Test
    void malformedSurrogatesAndEmptyCollectionsKeepLegacyDecodedBytes() throws Exception {
        final String high = Character.toString((char) 0xd800), low = Character.toString((char) 0xdc00);
        final var original = new Element("unicode", ElementType.NarrativeText,
                "Before" + high + "middle" + low + "after 🧵", Map.of("key" + high, "value" + low));
        final byte[] expected = json.write(List.of(original)).getBytes(StandardCharsets.UTF_8);
        final String encoded = json.encodeOriginals(List.of(original));
        assertArrayEquals(expected, inflated(encoded));
        assertEquals(json.read(new String(expected, StandardCharsets.UTF_8)), json.decodeOriginals(encoded));
        assertArrayEquals("[]".getBytes(StandardCharsets.UTF_8), inflated(json.encodeOriginals(List.of())));
        assertEquals(List.of(), json.decodeOriginals(json.encodeOriginals(List.of())));
    }

    /**
     * Independent virtual-thread calls share only the immutable codec configuration, never counters or encoders.
     * @throws Exception if a task or provenance round trip fails
     */
    @Test
    void sharedCodecKeepsConcurrentEncodersAndBudgetsIndependent() throws Exception {
        final var originals = List.of(element("Parallel Unicode 🧵 source"));
        final String expected = json.encodeOriginals(originals);
        try (final var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 32)
                                      .mapToObj(index -> executor.submit(() -> json.encodeOriginals(originals)))
                                      .toList();
            for (final var task : tasks) {
                final String encoded = task.get();
                assertEquals(expected, encoded);
                assertEquals(originals, json.decodeOriginals(encoded));
            }
        }
    }

    /**
     * Creates a source with fixed wire overhead for independent byte-boundary arithmetic.
     * @param text original unmodified source text
     * @return immutable public element value
     */
    private static Element element(final String text) {
        return new Element("limit", ElementType.NarrativeText, text, Map.of());
    }

    /**
     * Independently expands a controlled encoded fixture without using the production provenance decoder.
     * @param encoded controlled base64/zlib payload
     * @return complete UTF8 JSON bytes
     * @throws Exception if the controlled wire encoding is invalid
     */
    private static byte[] inflated(final String encoded) throws Exception {
        try (final var stream =
                        new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            return stream.readAllBytes();
        }
    }
}
