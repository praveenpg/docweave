package io.docweave.partition;

import io.docweave.DocWeave;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoundedFileInputTest {
    @TempDir Path directory;

    /** Stale size hints and short/zero reads model changes without timing-dependent file races. */
    private static class Source implements SeekableByteChannel {
        final byte[] content;
        final long hint;
        int position;
        boolean open = true;
        boolean zero;

        Source(final byte[] content, final long hint) {
            this.content = content;
            this.hint = hint;
        }

        public int read(final ByteBuffer target) throws IOException {
            assertTrue(target.remaining() <= 8192);
            if (zero) {
                zero = false;
                return 0;
            }
            if (position == content.length) {
                return -1;
            }
            final int count = Math.min(73, Math.min(target.remaining(), content.length - position));
            target.put(content, position, count);
            position += count;
            return count;
        }

        public int write(final ByteBuffer source) {
            throw new UnsupportedOperationException();
        }

        public long position() {
            return position;
        }

        public SeekableByteChannel position(final long value) {
            throw new UnsupportedOperationException();
        }

        public long size() {
            return hint;
        }

        public SeekableByteChannel truncate(final long value) {
            throw new UnsupportedOperationException();
        }

        public boolean isOpen() {
            return open;
        }

        public void close() {
            open = false;
        }
    }

    @Test
    void stableGrowingShrinkingAndUnknownSizesReturnExactBytesWithoutClosingChannel() throws Exception {
        final byte[] content = "🧵a".repeat(5000).getBytes(StandardCharsets.UTF_8);
        for (final long hint :
                List.of(0L, 1L, 8192L, (long) content.length, (long) content.length + 2000, Long.MAX_VALUE)) {
            final var source = new Source(content, hint);
            source.zero = true;
            assertArrayEquals(content, BoundedFileInput.read(source, content.length + 10000));
            assertTrue(source.isOpen());
        }
        for (final long hint : List.of(0L, 20L)) {
            assertEquals(0, BoundedFileInput.read(new Source(new byte[0], hint), 30).length);
        }
    }

    @Test
    void exactBudgetAndActualOverflowAreCheckedRegardlessOfSizeHint() throws Exception {
        final byte[] content = new byte[10000];
        Arrays.fill(content, (byte) 'a');
        for (final long hint : List.of(0L, 10000L, Long.MAX_VALUE)) {
            assertArrayEquals(content, BoundedFileInput.read(new Source(content, hint), content.length));
            final var source = new Source(content, hint);
            final var error =
                    assertThrows(PartitionException.class, () -> BoundedFileInput.read(source, content.length - 1));
            assertEquals(PartitionException.Code.INPUT_TOO_LARGE, error.code());
            assertEquals(content.length, source.position, "At most one byte beyond the limit is consumed");
            assertTrue(source.isOpen());
        }
    }

    @Test
    void interruptionBeforeAndDuringReadsPreservesFlagAndCallerOwnership() throws Exception {
        final var source = new Source(new byte[30000], 30000) {
            @Override
            public int read(final ByteBuffer target) throws IOException {
                final int read = super.read(target);
                if (position >= 146) {
                    Thread.currentThread().interrupt();
                }
                return read;
            }
        };
        try {
            assertThrows(InterruptedIOException.class, () -> BoundedFileInput.read(source, 30000));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(source.position < source.content.length);
            assertThrows(InterruptedIOException.class, () -> BoundedFileInput.read(new Source(new byte[0], 0), 1));
            assertTrue(source.isOpen());
        } finally {
            Thread.interrupted();
        }
        assertArrayEquals(new byte[] {1}, BoundedFileInput.read(new Source(new byte[] {1}, 1), 1));
    }

    @Test
    void readFailuresPropagateWithoutClosingCallerOwnedChannels() {
        final var source = new Source(new byte[1], 1) {
            @Override
            public int read(final ByteBuffer target) throws IOException {
                throw new IOException("read failed");
            }
        };
        assertEquals(
                "read failed", assertThrows(IOException.class, () -> BoundedFileInput.read(source, 10)).getMessage());
        assertTrue(source.isOpen());
    }

    @Test
    void realFilesMatchByteSourcesAcrossEncodingsBomRoutingAndEmptyInputs() throws Exception {
        final var processor = DocWeave.create();
        for (final String charset : List.of("UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "windows-1252")) {
            final var encoding = java.nio.charset.Charset.forName(charset);
            for (final String text : List.of("", "a".repeat(20000), "Café.", "<p>Café.</p>")) {
                final byte[] bytes = text.getBytes(encoding);
                final Path path = directory.resolve("sample.txt");
                Files.write(path, bytes);
                final var options = PartitionOptions.builder().charset(encoding).build();
                final var file = processor.partition(DocumentSource.of(path), options);
                final var memory = processor.partition(DocumentSource.bytes(bytes, "sample.txt"), options);
                assertEquals(memory.elements(), file.elements());
                assertEquals(memory.format(), file.format());
                assertEquals(memory.encoding(), file.encoding());
                assertEquals(memory.inputBytes(), file.inputBytes());
                assertEquals(memory.warnings(), file.warnings());
            }
        }
        final Path path = directory.resolve("bom.txt");
        final byte[] bom = "\ufeffCafé 🧵.".getBytes(StandardCharsets.UTF_8);
        Files.write(path, bom);
        assertEquals(processor.partition(DocumentSource.bytes(bom, "bom.txt")).elements(),
                processor.partition(DocumentSource.of(path)).elements());
    }

    @Test
    void binaryDocxFilesRetainExactPayloadAndMatchByteSources() throws Exception {
        final Path path = directory.resolve("sample.docx");
        try (final var document = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            document.createParagraph().createRun().setText("Café 🧵.");
            try (final var output = Files.newOutputStream(path)) {
                document.write(output);
            }
        }
        final byte[] bytes = Files.readAllBytes(path);
        final var loaded = new SourceLoader().load(DocumentSource.of(path), PartitionOptions.defaults());
        assertArrayEquals(bytes, loaded.binary());
        assertEquals(bytes.length, loaded.bytes());
        final var processor = DocWeave.create();
        assertEquals(processor.partition(DocumentSource.bytes(bytes, "sample.docx")).elements(),
                processor.partition(DocumentSource.of(path)).elements());
        Files.delete(path);
    }

    @Test
    void realFileLimitAndMalformedEncodingRemainTypedFailures() throws Exception {
        final Path path = directory.resolve("sample.txt");
        Files.write(path, new byte[] {(byte) 0xc3});
        final var processor = DocWeave.create();
        assertEquals(PartitionException.Code.INVALID_ENCODING,
                assertThrows(PartitionException.class, () -> processor.partition(DocumentSource.of(path))).code());
        Files.writeString(path, "abcd");
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                assertThrows(PartitionException.class,
                        ()
                                -> processor.partition(
                                        DocumentSource.of(path), PartitionOptions.builder().maxInputBytes(3).build()))
                        .code());
        assertEquals(
                4, processor.partition(DocumentSource.of(path), PartitionOptions.builder().maxInputBytes(4).build())
                           .inputBytes());
    }
}
