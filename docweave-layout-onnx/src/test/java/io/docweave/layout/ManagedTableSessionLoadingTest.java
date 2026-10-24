package io.docweave.layout;

import io.docweave.partition.PartitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Graph-loading lifetime controls: the graph that is hashed and handed to ONNX Runtime is one exact, read-only,
 * off-heap snapshot of the file, still bounded by {@code maxModelBytes} and the declared SHA-256.
 */
class ManagedTableSessionLoadingTest {
    /** Creates the owned loading suite. */
    ManagedTableSessionLoadingTest() {
    }

    /**
     * The snapshot is a direct, read-only buffer holding exactly the file's bytes, positioned at zero.
     * Expected bytes come from an independent full-file read.
     * @throws Exception if the fixture cannot be read
     */
    @Test
    void snapshotIsAnExactReadOnlyOffHeapCopy() throws Exception {
        final var options = options(fixture());
        final byte[] expected = Files.readAllBytes(options.model());
        final ByteBuffer snapshot = ManagedTableSession.snapshot(options);
        assertTrue(snapshot.isDirect(), "graph bytes must not be held in a Java heap array");
        assertTrue(snapshot.isReadOnly());
        assertFalse(snapshot.hasArray());
        assertEquals(0, snapshot.position());
        assertEquals(expected.length, snapshot.remaining());
        final byte[] actual = new byte[snapshot.remaining()];
        snapshot.duplicate().get(actual);
        assertArrayEquals(expected, actual);
    }

    /**
     * The encoded-byte ceiling admits a graph of exactly its size and rejects one byte more, as a typed failure
     * before any native session exists; the admitted provider still infers and closes normally.
     * @throws Exception if the fixture cannot be read or inference fails unexpectedly
     */
    @Test
    void modelByteCeilingIsExactAndTyped() throws Exception {
        final var base = options(fixture());
        final int size = (int) Files.size(base.model());
        try (final var provider = OnnxTableLayout.create(withMaxModelBytes(base, size))) {
            assertEquals(1, provider.locate(new BufferedImage(300, 180, BufferedImage.TYPE_INT_RGB)).size());
        }
        final var failure =
                assertThrows(PartitionException.class, () -> OnnxTableLayout.create(withMaxModelBytes(base, size - 1)));
        assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
        assertTrue(failure.getMessage().contains("maxModelBytes"));
    }

    /**
     * Identity is checked over the snapshot's exact bytes: a graph differing by one appended byte is rejected
     * under the original digest, and a missing file fails without creating a session.
     * @param directory owned temporary directory
     * @throws Exception if fixture copying fails
     */
    @Test
    void identityIsVerifiedOverTheLoadedBytes(@TempDir final Path directory) throws Exception {
        final var base = options(fixture());
        final var altered = directory.resolve("altered.onnx");
        final byte[] bytes = Files.readAllBytes(base.model());
        final byte[] longer = java.util.Arrays.copyOf(bytes, bytes.length + 1);
        Files.write(altered, longer);
        assertThrows(PartitionException.class,
                () -> OnnxTableLayout.create(OnnxTableOptions.defaults(altered, base.sha256())));
        final var snapshot = ManagedTableSession.snapshot(OnnxTableOptions.defaults(altered, base.sha256()));
        assertEquals(longer.length, snapshot.remaining());
        assertThrows(java.io.IOException.class,
                ()
                        -> OnnxTableLayout.create(
                                OnnxTableOptions.defaults(directory.resolve("absent.onnx"), base.sha256())));
    }

    /**
     * Loads the owned fixed-structure fixture path.
     * @return fixture path
     * @throws Exception if the resource cannot be resolved
     */
    private static Path fixture() throws Exception {
        return Path.of(ManagedTableSessionLoadingTest.class.getResource("/onnx/fixed-table.onnx").toURI());
    }

    /**
     * Builds default options pinned to an independently computed digest of the file.
     * @param path graph file
     * @return default single-call options
     * @throws Exception if the file cannot be hashed
     */
    private static OnnxTableOptions options(final Path path) throws Exception {
        final var hash =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        return OnnxTableOptions.defaults(path, hash);
    }

    /**
     * Copies options with an explicit encoded-byte ceiling and one native thread.
     * @param base source options
     * @param maxModelBytes ceiling to apply
     * @return otherwise identical options
     */
    private static OnnxTableOptions withMaxModelBytes(final OnnxTableOptions base, final int maxModelBytes) {
        return new OnnxTableOptions(base.model(), base.sha256(), base.timeout(), base.maxConcurrentCalls(),
                maxModelBytes, base.maxInputPixels(), base.maxQueries(), base.tableLimits(), base.minimumScore(), 1);
    }
}
