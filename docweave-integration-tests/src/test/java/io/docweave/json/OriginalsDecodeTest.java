package io.docweave.json;

import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Legacy decoder compatibility, strict encoded tails and independent reusable streaming cursors. */
class OriginalsDecodeTest {
    /** Unchanged raw decompressed byte ceiling. */
    private static final int LIMIT = 16 * 1024 * 1024;

    /** Creates the owned decoder controls. */
    OriginalsDecodeTest() {
    }

    /**
     * JDK whole-input and streaming acceptance agree across padding and validation-block boundaries.
     * @throws Exception if a valid encoded input fails to stream
     */
    @Test
    void encodedAcceptanceMatchesJdkAcrossValidationBoundaries() throws Exception {
        final var random = new Random(901);
        for (final int length : new int[] {0, 1, 2, 3, 4, 6143, 6144, 6145, 12287, 12288, 12289}) {
            final byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            for (final String encoded : List.of(Base64.getEncoder().encodeToString(bytes),
                         Base64.getEncoder().withoutPadding().encodeToString(bytes))) {
                try (final var input = Base64.getDecoder().wrap(OriginalsBase64Input.open(encoded))) {
                    assertArrayEquals(Base64.getDecoder().decode(encoded), input.readAllBytes());
                }
            }
        }
        for (final String encoded : List.of("Zh==", "Zh", "Zm9=", "Zm9")) {
            try (final var input = Base64.getDecoder().wrap(OriginalsBase64Input.open(encoded))) {
                assertArrayEquals(Base64.getDecoder().decode(encoded), input.readAllBytes());
            }
        }
        for (final String invalid :
                List.of("A", "AA=", "A===", "AA==AAAA", "AAAA!", "AA\nA", "ÄAAA", "A".repeat(8191) + "=AAAA")) {
            assertThrows(IllegalArgumentException.class, () -> Base64.getDecoder().decode(invalid));
            assertThrows(IllegalArgumentException.class, () -> OriginalsBase64Input.open(invalid));
        }
    }

    /**
     * Invalid encoded tails after a complete zlib member are rejected before inflation can ignore them.
     * Valid trailing compressed bytes retain the historical first-member behavior.
     * @throws Exception if owned compression or legacy decoding fails
     */
    @Test
    void completeEncodedTailIsValidatedWithoutChangingZlibTailBehavior() throws Exception {
        final var original = new Element("source", ElementType.NarrativeText, "owned 😀 text", Map.of());
        final byte[] compressed =
                compress(ElementJson.create().write(List.of(original)).getBytes(StandardCharsets.UTF_8));
        final byte[] trailing = Arrays.copyOf(compressed, compressed.length + 12_000);
        new Random(9).nextBytes(trailing);
        System.arraycopy(compressed, 0, trailing, 0, compressed.length);
        final String valid = Base64.getEncoder().encodeToString(trailing);
        assertEquals(legacy(valid), ElementJson.create().decodeOriginals(valid));
        assertEquals(List.of(original), ElementJson.create().decodeOriginals(valid));
        final String invalid = valid.substring(0, valid.length() - 1) + "!";
        assertThrows(IllegalArgumentException.class, () -> legacy(invalid));
        assertThrows(IllegalArgumentException.class, () -> ElementJson.create().decodeOriginals(invalid));
        final String prematurelyPadded = Base64.getEncoder().encodeToString(compressed) + "="
                                         + "AAAA".repeat(3000);
        assertThrows(IllegalArgumentException.class, () -> legacy(prematurelyPadded));
        assertThrows(IllegalArgumentException.class, () -> ElementJson.create().decodeOriginals(prematurelyPadded));
    }

    /**
     * Replacement of malformed UTF8 inside a string and rejection of JSON/schema/zlib errors remain compatible.
     * @throws Exception if owned compression fails
     */
    @Test
    void malformedUtf8AndInvalidDocumentsRetainLegacyOutcomes() throws Exception {
        final String wire =
                "[{\"type\":\"NarrativeText\",\"element_id\":\"source\",\"text\":\"owned?\",\"metadata\":{}}]";
        final byte[] malformed = wire.getBytes(StandardCharsets.UTF_8);
        malformed[wire.indexOf('?')] = (byte) 0xff;
        final String encoded = Base64.getEncoder().encodeToString(compress(malformed));
        assertEquals(legacy(encoded), ElementJson.create().decodeOriginals(encoded));
        assertEquals("owned�", ElementJson.create().decodeOriginals(encoded).getFirst().text());
        for (final String invalid : List.of("{}", "[] []", "[{\"bad\":true}]", "[")) {
            final String payload =
                    Base64.getEncoder().encodeToString(compress(invalid.getBytes(StandardCharsets.UTF_8)));
            final var previous = assertThrows(RuntimeException.class, () -> legacy(payload));
            final var current =
                    assertThrows(RuntimeException.class, () -> ElementJson.create().decodeOriginals(payload));
            assertEquals(previous.getClass(), current.getClass());
        }
        final byte[] broken = compress(wire.getBytes(StandardCharsets.UTF_8));
        broken[broken.length - 1] ^= 1;
        final String corrupt = Base64.getEncoder().encodeToString(broken);
        assertThrows(IllegalArgumentException.class, () -> ElementJson.create().decodeOriginals(corrupt));
        final String truncated = Base64.getEncoder().encodeToString(Arrays.copyOf(broken, broken.length - 5));
        assertThrows(IllegalArgumentException.class, () -> ElementJson.create().decodeOriginals(truncated));
    }

    /** Encoded size is rejected before validating its content; cancellation preserves the caller's flag. */
    @Test
    void encodedLimitAndCancellationRemainExplicit() {
        final var json = ElementJson.create();
        assertThrows(IllegalArgumentException.class, () -> json.decodeOriginals("A".repeat(2 * LIMIT + 1)));
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> json.decodeOriginals(""));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // Clear only the test's deliberate interrupt so the test worker can execute subsequent controls.
            Thread.interrupted();
        }
    }

    /**
     * Concurrent calls share no decoder cursor and preserve the immutable source graph.
     * @throws Exception if concurrent decoding fails or times out
     */
    @Test
    void codecReuseHasIndependentStreamCursors() throws Exception {
        final var json = ElementJson.create();
        final var original = List.of(new Element("source", ElementType.NarrativeText, "text 😀".repeat(500), Map.of()));
        final String encoded = json.encodeOriginals(original);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var futures =
                    IntStream.range(0, 8).mapToObj(i -> executor.submit(() -> json.decodeOriginals(encoded))).toList();
            for (final var result : futures) {
                assertEquals(original, result.get(5, TimeUnit.SECONDS));
            }
        }
    }

    /**
     * Independently preserves the former full-array algorithm as a compatibility oracle for owned inputs.
     * @param payload encoded source
     * @return immutable legacy-decoded elements
     * @throws Exception if compression or parsing fails
     */
    private static List<Element> legacy(final String payload) throws Exception {
        try (final var input = new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(payload)))) {
            final byte[] bytes = input.readNBytes(LIMIT + 1);
            if (bytes.length > LIMIT) {
                throw new IllegalArgumentException("Decoded original elements exceed 16 MiB");
            }
            return new DefaultElementJson(new JsonReadOptions(LIMIT, Integer.MAX_VALUE, 1000, 20_000_000))
                    .read(new String(bytes, StandardCharsets.UTF_8));
        } catch (final IOException failure) {
            throw new IllegalArgumentException("Invalid compressed original elements", failure);
        }
    }

    /**
     * Creates an independent valid zlib member without using the provenance encoder under test.
     * @param bytes complete raw JSON or owned malformed input
     * @return complete compressed bytes
     * @throws Exception if compression fails
     */
    private static byte[] compress(final byte[] bytes) throws Exception {
        final var output = new ByteArrayOutputStream();
        try (final var compressed = new DeflaterOutputStream(output)) {
            compressed.write(bytes);
        }
        return output.toByteArray();
    }
}
