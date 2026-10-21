package dev.docweave.json;

import dev.docweave.batch.BatchOptions;
import dev.docweave.batch.ElementFileBatch;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class JsonReadLimitsTest {
    @TempDir Path temp;

    private String input(final String text) {
        return ElementJson.create().write(List.of(new Element("a", ElementType.NarrativeText, text, Map.of())));
    }

    private ElementJson codec(final int bytes, final int count, final int depth, final int string) {
        return ElementJson.create(new JsonReadOptions(bytes, count, depth, string));
    }

    @Test
    void validatesOptionsAndFactories() {
        assertThrows(IllegalArgumentException.class, () -> new JsonReadOptions(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new JsonReadOptions(1, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new JsonReadOptions(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new JsonReadOptions(1, 1, 1, 0));
        assertThrows(NullPointerException.class, () -> ElementJson.create(null));
        assertThrows(NullPointerException.class, () -> ElementFileBatch.create(null));
    }

    @Test
    void exactUtf8ByteBoundaryAppliesToStringsAndFiles() throws Exception {
        final var json = input("😀 café");
        final int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        final var file = temp.resolve("elements.json");
        Files.writeString(file, json);
        final var exact = codec(bytes, 1, 4, 100);
        assertEquals(exact.read(json), exact.read(file));
        final var shortLimit = codec(bytes - 1, 1, 4, 100);
        assertThrows(IllegalArgumentException.class, () -> shortLimit.read(json));
        assertThrows(IllegalArgumentException.class, () -> shortLimit.read(file));
    }

    @Test
    void actualStreamBytesAreBoundedWithoutSizeMetadata() throws Exception {
        assertArrayEquals(
                new byte[] {1, 2}, BoundedJsonInput.read(new java.io.ByteArrayInputStream(new byte[] {1, 2}), 2));
        assertThrows(IllegalArgumentException.class,
                () -> BoundedJsonInput.read(new java.io.ByteArrayInputStream(new byte[3]), 2));
        final var source = new java.io.ByteArrayInputStream(new byte[3]) {
            @Override
            public synchronized int read(final byte[] bytes, final int offset, final int length) {
                final int count = super.read(bytes, offset, length);
                Thread.currentThread().interrupt();
                return count;
            }
        };
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> BoundedJsonInput.read(source, 10));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void elementCountCheckedBeforeDecodingExcessElement() {
        final var one = input("one");
        final var two = one.substring(0, one.length() - 1) + "," + one.substring(1);
        assertEquals(2, codec(2000, 2, 5, 100).read(two).size());
        assertThrows(IllegalArgumentException.class, () -> codec(2000, 1, 5, 100).read(two));
        assertTrue(codec(2, 1, 1, 100).read("[]").isEmpty());
    }

    @Test
    void nestingAndStringLimitsAreEnforced() {
        final var json = input("x".repeat(200));
        assertEquals(1, codec(2000, 2, 3, 200).read(json).size());
        assertThrows(RuntimeException.class, () -> codec(2000, 2, 2, 200).read(json));
        assertThrows(RuntimeException.class, () -> codec(2000, 2, 3, 100).read(json));
        final var nested = ElementJson.create().write(
                List.of(new Element("a", ElementType.Title, "text", Map.of("nested", List.of(Map.of("a", "b"))))));
        assertThrows(RuntimeException.class, () -> codec(2000, 2, 4, 100).read(nested));
        assertEquals(1, codec(2000, 2, 5, 100).read(nested).size());
    }

    @Test
    void strictDecodingSchemaAndTrailingContentRemainEnforced() throws Exception {
        final var codec = codec(2000, 2, 8, 100);
        for (final var malformed :
                List.of("{}", "[", "[null]", "[] []", input("ok") + "true", "[{\"type\":\"Title\"}]")) {
            assertThrows(RuntimeException.class, () -> codec.read(malformed), malformed);
        }
        assertThrows(IllegalArgumentException.class, () -> codec.read(input("ok") + '\ud800'));
        final var file = temp.resolve("bad.json");
        Files.write(file, new byte[] {'[', '"', (byte) 0xc3, '"', ']'});
        assertThrows(java.io.IOException.class, () -> codec.read(file));
    }

    @Test
    void interruptionPreservesFlagAndCodecRemainsReusable() throws Exception {
        final var codec = codec(2000, 2, 8, 100);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> codec.read("[]"));
            assertThrows(java.io.InterruptedIOException.class, () -> codec.read(temp.resolve("absent.json")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, codec.read(input("ok")).size());
    }

    @Test
    void readPolicyDoesNotLimitWritesOrProvenance() {
        final var tiny = codec(2, 1, 1, 1);
        final var elements = List.of(new Element("a", ElementType.Title, "text", Map.of()));
        assertFalse(tiny.write(elements).isEmpty());
        assertEquals(elements, tiny.decodeOriginals(tiny.encodeOriginals(elements)));
    }

    @Test
    void concurrentCodecsKeepPoliciesIsolated() throws Exception {
        final var json = input("policy isolation");
        final var tight = codec(2, 1, 1, 1);
        final var normal = ElementJson.create();
        final var results = dev.docweave.batch.BatchProcessor.create().mapOrdered(
                java.util.stream.IntStream.range(0, 30).boxed().toList(), 4, index -> {
                    assertThrows(IllegalArgumentException.class, () -> tight.read(json));
                    return normal.read(json);
                });
        assertEquals(30, results.size());
        assertTrue(results.stream().allMatch(elements -> elements.getFirst().text().equals("policy isolation")));
    }

    @Test
    void allSavedBatchPathsApplyConfiguredLimits() throws Exception {
        final var file = temp.resolve("elements.json");
        Files.writeString(file, input("Text to chunk"));
        final var batch = ElementFileBatch.create(new JsonReadOptions(2, 1, 3, 100));
        assertThrows(ExecutionException.class,
                () -> batch.chunk(List.of(file), Chunker.Strategy.BASIC, ChunkOptions.defaults(), 1));
        assertThrows(ExecutionException.class,
                () -> batch.chunk(List.of(file), Chunker.Strategy.BASIC, ChunkOptions.defaults(), new BatchOptions(1)));
        final var results = new ArrayList<ElementFileBatch.Result>();
        assertThrows(ExecutionException.class,
                ()
                        -> batch.chunkEach(List.of(file), Chunker.Strategy.BASIC, ChunkOptions.defaults(),
                                new BatchOptions(1), results::add));
        assertTrue(results.isEmpty());
        ElementFileBatch.create().chunkEach(
                List.of(file), Chunker.Strategy.BASIC, ChunkOptions.defaults(), new BatchOptions(1), results::add);
        assertEquals(1, results.size());
    }
}
