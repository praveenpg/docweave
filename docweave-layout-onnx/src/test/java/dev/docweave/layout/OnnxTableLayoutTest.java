package dev.docweave.layout;

import dev.docweave.partition.PartitionException;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class OnnxTableLayoutTest {
    private static OnnxTableOptions options() throws Exception {
        return options("fixed-table.onnx");
    }

    private static OnnxTableOptions options(final String fixture) throws Exception {
        final var path = Path.of(OnnxTableLayoutTest.class.getResource("/onnx/" + fixture).toURI());
        final var hash =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        return OnnxTableOptions.defaults(path, hash);
    }

    @Test
    void nativeGraphProducesMergedCompleteSourcePixelGeometryAndCloses() throws Exception {
        final var provider = OnnxTableLayout.create(options());
        final var image = new BufferedImage(300, 180, BufferedImage.TYPE_INT_RGB);
        try (provider) {
            final var tables = provider.locate(image);
            assertEquals(1, tables.size());
            final var table = tables.getFirst();
            assertEquals(3, table.rows());
            assertEquals(2, table.columns());
            assertEquals(5, table.anchors().size());
            assertEquals(2, table.anchors().getFirst().columnSpan());
            assertEquals(20, table.bounds().left(), .001);
            assertEquals(80, table.bounds().bottom(), .001);
            assertTrue(table.provenance().contains(options().sha256()));
            assertEquals(0xff000000, image.getRGB(0, 0));
        }
        provider.close();
        assertThrows(PartitionException.class, () -> provider.locate(image));
    }

    @Test
    void boundedConcurrentVirtualCallsShareSessionAndReturnIdenticalGeometry() throws Exception {
        try (final var provider = OnnxTableLayout.create(options().withConcurrency(2));
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var image = new BufferedImage(300, 180, BufferedImage.TYPE_INT_RGB);
            final var expected = provider.locate(image);
            final var futures =
                    IntStream.range(0, 12).mapToObj(index -> executor.submit(() -> provider.locate(image))).toList();
            for (final var future : futures) {
                assertEquals(expected, future.get());
            }
        }
    }

    @Test
    void interruptionAndDeadlineAreTypedAndSessionRemainsReusable() throws Exception {
        final var image = new BufferedImage(300, 180, BufferedImage.TYPE_INT_RGB);
        try (final var provider = OnnxTableLayout.create(options())) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> provider.locate(image));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertEquals(1, provider.locate(image).size());
        }
        try (final var provider = OnnxTableLayout.create(options().withTimeout(Duration.ofNanos(1)))) {
            final var failure = assertThrows(PartitionException.class, () -> provider.locate(image));
            assertEquals(PartitionException.Code.OCR_TIMEOUT, failure.code());
        }
    }

    @Test
    void nativeLoopIsCooperativelyTerminatedByDeadline() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (final var provider =
                            OnnxTableLayout.create(options("finite-loop.onnx").withTimeout(Duration.ofMillis(500)))) {
                final var failure = assertThrows(PartitionException.class,
                        () -> provider.locate(new BufferedImage(300, 180, BufferedImage.TYPE_INT_RGB)));
                assertEquals(PartitionException.Code.OCR_TIMEOUT, failure.code());
            }
        });
    }

    @Test
    void interruptedNativeCallTerminatesWithoutClearingOwnerFlag() throws Exception {
        try (final var provider =
                        OnnxTableLayout.create(options("finite-loop.onnx").withTimeout(Duration.ofSeconds(5)))) {
            final var outcome = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            final var preserved = new java.util.concurrent.atomic.AtomicBoolean();
            final var entered = new java.util.concurrent.CountDownLatch(1);
            final var caller = Thread.ofVirtual().start(() -> {
                entered.countDown();
                try {
                    provider.locate(new BufferedImage(300, 180, BufferedImage.TYPE_INT_RGB));
                } catch (final Throwable failure) {
                    outcome.set(failure);
                    preserved.set(Thread.currentThread().isInterrupted());
                }
            });
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            Thread.sleep(200);
            caller.interrupt();
            assertTrue(caller.join(Duration.ofSeconds(3)),
                    "Native call must observe interruption before five-second deadline");
            assertInstanceOf(InterruptedIOException.class, outcome.get());
            assertTrue(preserved.get());
        }
    }

    @Test
    void queryCeilingRejectsRawOutputBeforeTopology() throws Exception {
        final var defaults = options();
        final var capped = new OnnxTableOptions(defaults.model(), defaults.sha256(), defaults.timeout(), 1,
                defaults.maxModelBytes(), defaults.maxInputPixels(), 6, defaults.tableLimits(), .5, 1);
        try (final var provider = OnnxTableLayout.create(capped)) {
            final var failure = assertThrows(PartitionException.class,
                    () -> provider.locate(new BufferedImage(300, 180, BufferedImage.TYPE_INT_RGB)));
            assertEquals(PartitionException.Code.OCR_LIMIT, failure.code());
        }
    }

    @Test
    void identityAndInputCapsAreEnforcedBeforeInference() throws Exception {
        final var defaults = options();
        assertThrows(PartitionException.class,
                () -> OnnxTableLayout.create(OnnxTableOptions.defaults(defaults.model(), "0".repeat(64))));
        final var capped = new OnnxTableOptions(defaults.model(), defaults.sha256(), defaults.timeout(), 1,
                defaults.maxModelBytes(), 100, defaults.maxQueries(), defaults.tableLimits(), .5, 1);
        try (final var provider = OnnxTableLayout.create(capped)) {
            assertThrows(PartitionException.class,
                    () -> provider.locate(new BufferedImage(300, 180, BufferedImage.TYPE_INT_RGB)));
        }
    }
}
