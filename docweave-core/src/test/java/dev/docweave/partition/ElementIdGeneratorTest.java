package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ElementIdGeneratorTest {
    private String legacy(final String filename, final String text, int sequence) throws Exception {
        final String data = (filename == null ? "None" : filename) + text + "None" + sequence;
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 32);
    }

    @Test
    void reuseMatchesLegacyHashAcrossUnicodeAndStringComponentBoundaries() throws Exception {
        final var generator = new ElementIdGenerator();
        for (final String filename :
                new String[] {null, "", "None", "report.docx", "Café漢🧵", "\ud800", "name\ud83e"}) {
            for (final String text : List.of("", "ASCII", "Café漢🧵", "\u0000\r\n", "\ud800", "\udc00", "\uddf5text")) {
                for (int sequence : List.of(0, 9, 10, 999, Integer.MAX_VALUE)) {
                    assertEquals(legacy(filename, text, sequence), generator.create(filename, text, sequence));
                }
            }
        }
    }

    @Test
    void seededMessagesDoNotAccumulateStateAcrossDigestReuse() throws Exception {
        final var generator = new ElementIdGenerator();
        final var random = new Random(244);
        for (int sequence = 0; sequence < 1000; sequence++) {
            final var text = new StringBuilder();
            for (int i = 0; i < random.nextInt(100); i++) {
                text.appendCodePoint(random.nextInt(0x110000));
            }
            assertEquals(legacy("report.txt", text.toString(), sequence),
                    generator.create("report.txt", text.toString(), sequence));
        }
    }

    @Test
    void failedMetadataSnapshotDoesNotAdvanceSequenceOrCorruptHashState() throws Exception {
        final var collector = new ElementCollector("report.txt", DocumentFormat.TXT, 10);
        assertEquals(legacy("report.txt", "first", 0),
                collector.add(ElementType.NarrativeText, "first", Map.of()).elementId());
        assertThrows(IllegalArgumentException.class,
                () -> collector.add(ElementType.NarrativeText, "invalid", Map.of("bad", new Object())));
        assertEquals(1, collector.size());
        assertEquals(legacy("report.txt", "second", 1),
                collector.add(ElementType.NarrativeText, "second", Map.of()).elementId());
        final var nested = new ArrayList<>(List.of("original"));
        final var element = collector.add(ElementType.NarrativeText, "third", Map.of("nested", nested));
        nested.add("later mutation");
        assertEquals(List.of("original"), element.metadata().get("nested"));
        assertThrows(UnsupportedOperationException.class, () -> element.metadata().put("new", "value"));
    }

    @Test
    void sharedFacadeKeepsDocumentDigestStateIsolatedAcrossVirtualThreads() throws Exception {
        final var strategy = new Partitioner() {
            public DocumentFormat format() {
                return DocumentFormat.TXT;
            }
            public void partition(final PartitionContext context) throws java.io.IOException {
                for (int i = 0; i < 25; i++) {
                    context.emit(ElementType.NarrativeText, context.text(), Map.of());
                }
            }
        };
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(strategy)));
        final var tasks = new ArrayList<Callable<Void>>();
        for (int doc = 0; doc < 100; doc++) {
            final String filename = "report-" + doc + ".txt";
            tasks.add(() -> {
                assertTrue(Thread.currentThread().isVirtual());
                final var result = processor.partition(DocumentSource.text("Café 漢 🧵.", filename));
                for (int index = 0; index < result.elements().size(); index++) {
                    assertEquals(legacy(filename, "Café 漢 🧵.", index), result.elements().get(index).elementId());
                }
                return null;
            });
        }
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (final var result : executor.invokeAll(tasks)) {
                result.get();
            }
        }
    }

    @Test
    void shortMessagePromotionAndBoundarySizesKeepLegacyIds() throws Exception {
        final var generator = new ElementIdGenerator();
        for (int sequence = 0; sequence < 20; sequence++) {
            assertEquals(
                    legacy("small.txt", "short 🧵", sequence), generator.create("small.txt", "short 🧵", sequence));
        }
        for (final int length : List.of(230, 231, 255, 256, 257, 1024)) {
            final var fresh = new ElementIdGenerator();
            final String text = "a".repeat(length);
            assertEquals(legacy(null, text, 0), fresh.create(null, text, 0));
        }
    }

    @Test
    void largeMessagesMatchLegacyWithOneReusableGenerator() throws Exception {
        final var generator = new ElementIdGenerator();
        for (final String text : List.of("a".repeat(1_048_576), "é漢🧵".repeat(262_144))) {
            assertEquals(legacy("large.txt", text, 0), generator.create("large.txt", text, 0));
            assertEquals(legacy(null, "small afterwards", 1), generator.create(null, "small afterwards", 1));
        }
    }

    @Test
    void interruptionPreservesFlagAndAllowsReuseAfterCallerClearsIt() throws Exception {
        final var generator = new ElementIdGenerator();
        assertEquals(legacy(null, "first", 0), generator.create(null, "first", 0));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> generator.create(null, "cancelled", 1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(legacy(null, "second", 1), generator.create(null, "second", 1));
    }
}
