package io.docweave.layout;

import io.docweave.partition.PartitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** JVM-authored graph and source-pixel controls for managed YOLOX/TATR composition. */
class YoloXTableLayoutTest {
    /** Creates isolated local graph controls. */
    YoloXTableLayoutTest() {
    }

    /**
     * Published diagnostic pixel vectors and alpha composition retain the borrowed source unchanged.
     * @throws Exception if preprocessing fails
     */
    @Test
    void rgbLetterboxInterpolationAndAlphaAreExplicit() throws Exception {
        final var image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        image.setRGB(1, 0, 0xff0000ff);
        final var options = OnnxTableOptions.yoloXDefaults(Path.of("not-read.onnx"), "0".repeat(64));
        final var prepared = YoloXImageTransform.prepare(image, options, deadline());
        final var values = prepared.values();
        final int plane = 1024 * 768;
        assertEquals(255, values.get(0));
        assertEquals(0, values.get(plane));
        assertEquals(0, values.get(2 * plane));
        assertEquals(128, values.get(383));
        assertEquals(127, values.get(2 * plane + 383));
        assertEquals(114, values.get(384 * 768));
        assertEquals(0xffff0000, image.getRGB(0, 0));
        assertEquals(0xff0000ff, image.getRGB(1, 0));
        image.setRGB(0, 0, 0x00ff0000);
        final var transparent = YoloXImageTransform.prepare(image, options, deadline()).values();
        assertEquals(255, transparent.get(0));
        assertEquals(255, transparent.get(plane));
        assertEquals(255, transparent.get(2 * plane));
        assertEquals(0x00ff0000, image.getRGB(0, 0));
        image.flush();
    }

    /**
     * A real owned ONNX graph drives two translated complete child layouts and supports concurrent calls.
     * @param directory isolated generated graph storage
     * @throws Exception if graph construction or native composition fails
     */
    @Test
    void managedDetectorProducesDisjointTranslatedStructures(final @TempDir Path directory) throws Exception {
        final var detector = fixture(directory, false);
        final var structure = structure();
        final var image = new BufferedImage(768, 1024, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xff123456);
        final var provider = OnnxTableLayout.createWithYoloX(detector.withConcurrency(2), structure.withConcurrency(2));
        try (provider; final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var expected = provider.locate(image);
            assertEquals(2, expected.size());
            for (final var table : expected) {
                assertEquals(3, table.rows());
                assertEquals(2, table.columns());
                assertEquals(5, table.anchors().size());
                assertEquals(2, table.anchors().getFirst().columnSpan());
                assertTrue(table.provenance().contains(detector.sha256()));
                assertTrue(table.provenance().contains(structure.sha256()));
            }
            assertTrue(expected.getFirst().bounds().right() < expected.getLast().bounds().left());
            final var tasks =
                    IntStream.range(0, 4).mapToObj(index -> executor.submit(() -> provider.locate(image))).toList();
            for (final var task : tasks) {
                assertEquals(expected, task.get());
            }
            assertEquals(0xff123456, image.getRGB(0, 0));
        } finally {
            image.flush();
        }
        provider.close();
        assertThrows(
                PartitionException.class, () -> provider.locate(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));
    }

    /**
     * Competing non-table classes suppress duplicate table proposals before table-class selection.
     * @param directory isolated generated graph storage
     * @throws Exception if native graph execution fails
     */
    @Test
    void classAgnosticSuppressionPrecedesTableSelection(final @TempDir Path directory) throws Exception {
        final var image = new BufferedImage(768, 1024, BufferedImage.TYPE_INT_RGB);
        try (final var provider = OnnxTableLayout.createWithYoloX(fixture(directory, true), structure())) {
            final var tables = provider.locate(image);
            assertEquals(1, tables.size());
            assertTrue(tables.getFirst().bounds().left() > 480);
        } finally {
            image.flush();
        }
    }

    /**
     * Background coordinates/probabilities remain validated and dense outputs fail before quadratic suppression.
     * @throws Exception if numerical controls fail
     */
    @Test
    void invalidBackgroundOutputsAndCandidateBudgetsRemainTyped() throws Exception {
        final var options = OnnxTableOptions.yoloXDefaults(Path.of("not-read.onnx"), "0".repeat(64));
        final var pixels = new YoloXPixels(FloatBuffer.allocate(0), 768, 1024, 1);
        for (final int field : new int[] {0, 4, 15}) {
            final var raw = FloatBuffer.allocate(YoloXObjectDecoder.QUERIES * 16);
            raw.put((YoloXObjectDecoder.QUERIES - 1) * 16 + field, Float.NaN);
            final var deadline = deadline();
            assertEquals(PartitionException.Code.OCR_FAILURE,
                    assertThrows(PartitionException.class,
                            () -> YoloXObjectDecoder.decode(raw, pixels, options, deadline, new LayoutWork(deadline)))
                            .code());
        }
        final var dense = FloatBuffer.allocate(YoloXObjectDecoder.QUERIES * 16);
        for (int query = 0; query < 2049; query++) {
            dense.put(query * 16 + 4, 1);
            dense.put(query * 16 + 5, 1);
        }
        final var deadline = deadline();
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        () -> YoloXObjectDecoder.decode(dense, pixels, options, deadline, new LayoutWork(deadline)))
                        .code());
    }

    /**
     * Explicit raw-query limits, wrong tensor schemas, interruption and total deadlines stay visible.
     * @param directory isolated generated graph storage
     * @throws Exception if native model/resource controls fail
     */
    @Test
    void querySchemaAndCancellationPoliciesRemainExplicit(final @TempDir Path directory) throws Exception {
        final var detector = fixture(directory, false);
        final var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        try (final var provider = OnnxTableLayout.createWithYoloX(
                     OnnxTableOptions.defaults(detector.model(), detector.sha256()), structure())) {
            assertEquals(PartitionException.Code.OCR_LIMIT,
                    assertThrows(PartitionException.class, () -> provider.locate(image)).code());
        }
        assertEquals(PartitionException.Code.OCR_FAILURE,
                assertThrows(PartitionException.class, () -> OnnxTableLayout.createWithYoloX(structure(), structure()))
                        .code());
        try (final var provider = OnnxTableLayout.createWithYoloX(detector, structure())) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> provider.locate(image));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                // This test owns the interrupt and clears it before the runner reuses the thread.
                Thread.interrupted();
            }
            assertEquals(2, provider.locate(image).size());
        }
        try (final var provider =
                        OnnxTableLayout.createWithYoloX(detector.withTimeout(Duration.ofNanos(1)), structure())) {
            assertEquals(PartitionException.Code.OCR_TIMEOUT,
                    assertThrows(PartitionException.class, () -> provider.locate(image)).code());
        } finally {
            image.flush();
        }
    }

    /**
     * Valid long score representations retain both graph hashes without overflowing provider provenance.
     * @param directory isolated generated detector graph storage
     * @throws Exception if graph construction or inference fails
     */
    @Test
    void longValidScorePoliciesRetainCompleteBoundedProvenance(final @TempDir Path directory) throws Exception {
        final var base = structure();
        final var child = new OnnxTableOptions(base.model(), base.sha256(), base.timeout(), base.maxConcurrentCalls(),
                base.maxModelBytes(), base.maxInputPixels(), base.maxQueries(), base.tableLimits(), Double.MIN_NORMAL,
                base.nativeThreads(), OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
        final var tatrPath = Path.of(YoloXTableLayoutTest.class.getResource("/onnx/fixed-detections.onnx").toURI());
        final var tatr = OnnxTableOptions.defaults(tatrPath, hash(Files.readAllBytes(tatrPath)));
        final var yoloX = fixture(directory, false);
        final var image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        try (final var first = OnnxTableLayout.create(tatr, child);
                final var second = OnnxTableLayout.createWithYoloX(yoloX, child)) {
            for (final var provider : new OnnxTableLayout[] {first, second}) {
                final var tables = provider.locate(image);
                assertEquals(2, tables.size());
                for (final var table : tables) {
                    assertTrue(table.provenance().length() <= 256);
                    assertTrue(table.provenance().contains(child.sha256()));
                    assertTrue(table.provenance().contains(provider == first ? tatr.sha256() : yoloX.sha256()));
                    assertTrue(table.provenance().contains(Double.toString(Double.MIN_NORMAL)));
                    assertTrue(table.provenance().contains("merge_conflicts=keep_higher_score"));
                }
            }
        } finally {
            image.flush();
        }
    }

    /**
     * Returns a bounded independent test deadline.
     * @return fresh total-call deadline
     */
    private static LayoutDeadline deadline() {
        return new LayoutDeadline(Duration.ofSeconds(30));
    }

    /**
     * Loads the existing owned structure graph's immutable identity.
     * @return ordinary TATR structure policy
     * @throws Exception if the fixture cannot be located or hashed
     */
    private static OnnxTableOptions structure() throws Exception {
        final var path = Path.of(YoloXTableLayoutTest.class.getResource("/onnx/fixed-table.onnx").toURI());
        return OnnxTableOptions.defaults(path, hash(Files.readAllBytes(path)));
    }

    /**
     * Encodes a project-owned constant detector graph directly on the JVM, without Python/protobuf dependencies.
     * Field numbers follow the pinned ONNX1.19.1 schema; this is plumbing evidence, not learned accuracy.
     * @param directory isolated test graph directory
     * @param competitor whether a stronger non-table candidate overlaps the first table
     * @return explicit hash-pinned detector policy
     * @throws Exception if source graph writing fails
     */
    private static OnnxTableOptions fixture(final Path directory, final boolean competitor) throws Exception {
        final var raw =
                ByteBuffer.allocate(YoloXObjectDecoder.QUERIES * 16 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        prediction(raw, 0, 200, 200, 320, 180, 8, .9f);
        prediction(raw, 1, 600, 200, 240, 180, 8, .9f);
        if (competitor) {
            prediction(raw, 2, 200, 200, 320, 180, 1, .95f);
        }
        final var tensor = new Proto()
                                   .integer(1, 1)
                                   .integer(1, YoloXObjectDecoder.QUERIES)
                                   .integer(1, 16)
                                   .integer(2, 1)
                                   .text(8, "raw")
                                   .bytes(9, raw.array());
        final var node = new Proto().text(1, "raw").text(2, "output").text(4, "Identity");
        final var graph = new Proto()
                                  .bytes(1, node.toByteArray())
                                  .text(2, "owned-yolox")
                                  .bytes(5, tensor.toByteArray())
                                  .bytes(11, value("images", 1, 3, 1024, 768))
                                  .bytes(12, value("output", 1, YoloXObjectDecoder.QUERIES, 16));
        final var model = new Proto()
                                  .integer(1, 9)
                                  .text(2, "docweave-java-owned-fixture")
                                  .bytes(7, graph.toByteArray())
                                  .bytes(8, new Proto().integer(2, 17).toByteArray())
                                  .toByteArray();
        final var path = directory.resolve(competitor ? "competing-yolox.onnx" : "fixed-yolox.onnx");
        Files.write(path, model, StandardOpenOption.CREATE_NEW);
        return OnnxTableOptions.yoloXDefaults(path, hash(model));
    }

    /**
     * Places an independently chosen source-network rectangle in one stride8 query.
     * @param raw owned output bytes
     * @param query fixed first-row query index
     * @param x desired network center x
     * @param y desired network center y
     * @param width desired network box width
     * @param height desired network box height
     * @param label published class index
     * @param score desired class score with unit objectness
     */
    private static void prediction(final ByteBuffer raw, final int query, final float x, final float y,
            final float width, final float height, final int label, final float score) {
        final int offset = query * 16 * Float.BYTES;
        raw.putFloat(offset, x / 8 - query);
        raw.putFloat(offset + 4, y / 8);
        raw.putFloat(offset + 8, (float) Math.log(width / 8));
        raw.putFloat(offset + 12, (float) Math.log(height / 8));
        raw.putFloat(offset + 16, 1);
        raw.putFloat(offset + (5 + label) * 4, score);
    }

    /**
     * Encodes an exact FLOAT tensor value-info declaration using the public ONNX field numbers.
     * @param name graph value name
     * @param dimensions fixed positive shape
     * @return owned protobuf message bytes
     */
    private static byte[] value(final String name, final int... dimensions) {
        final var shape = new Proto();
        for (final int dimension : dimensions) {
            shape.bytes(1, new Proto().integer(1, dimension).toByteArray());
        }
        final var tensor = new Proto().integer(1, 1).bytes(2, shape.toByteArray());
        return new Proto()
                .text(1, name)
                .bytes(2, new Proto().bytes(1, tensor.toByteArray()).toByteArray())
                .toByteArray();
    }

    /**
     * Hashes exact local graph bytes.
     * @param bytes owned encoded model
     * @return lowercase SHA256
     * @throws Exception if SHA256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Minimal JVM-only writer for the two protobuf wire types used by this owned fixture. */
    private static final class Proto extends ByteArrayOutputStream {
        /** Creates one isolated message. */
        private Proto() {
        }

        /**
         * Appends one nonnegative integer field.
         * @param field schema field number
         * @param value nonnegative value
         * @return this owned message
         */
        private Proto integer(final int field, final long value) {
            varint((long) field << 3);
            varint(value);
            return this;
        }

        /**
         * Appends one UTF8 field.
         * @param field schema field number
         * @param value owned fixture text
         * @return this owned message
         */
        private Proto text(final int field, final String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Appends bytes or an already encoded nested message.
         * @param field schema field number
         * @param value owned bytes
         * @return this owned message
         */
        private Proto bytes(final int field, final byte[] value) {
            varint(((long) field << 3) | 2);
            varint(value.length);
            writeBytes(value);
            return this;
        }

        /**
         * Writes the unsigned base128 representation used by protobuf.
         * @param value nonnegative fixture value
         */
        private void varint(long value) {
            while ((value & ~127L) != 0) {
                write((int) (value & 127) | 128);
                value >>>= 7;
            }
            write((int) value);
        }
    }
}
