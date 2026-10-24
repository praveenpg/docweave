package io.docweave.ocr;

import io.docweave.partition.PartitionException;

import java.awt.image.BufferedImage;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Owned temporary PNG directory; closure removes the input even after process failure. */
final class TesseractImage implements AutoCloseable {
    /** Private directory created by the operating system's temporary-file facility. */
    private final Path directory;
    /** Only file written by the adapter. */
    private final Path file;

    /**
     * Allocates the directory without retaining the caller image.
     * @throws IOException if temporary storage is unavailable
     */
    TesseractImage() throws IOException {
        directory = Files.createTempDirectory("docweave-ocr-");
        file = directory.resolve("page.png");
    }

    /**
     * Encodes a bounded PNG without changing process-wide ImageIO cache settings.
     * @param image borrowed image
     * @param limit maximum encoded bytes written to disk
     * @param deadline cooperative encoding deadline
     * @return owned PNG path
     * @throws IOException if encoding, cancellation or a limit fails
     */
    Path encode(final BufferedImage image, final long limit, final OcrDeadline deadline) throws IOException {
        deadline.remaining();
        try (final var output = new LimitedOutput(Files.newOutputStream(file), limit, deadline);
                final var encoded = new MemoryCacheImageOutputStream(output)) {
            if (!ImageIO.write(image, "png", encoded)) {
                throw new IOException("PNG encoder unavailable");
            }
        }
        deadline.remaining();
        return file;
    }

    /**
     * Removes only the exact file and directory created by this instance.
     * @throws IOException if cleanup fails
     */
    @Override
    public void close() throws IOException {
        try {
            Files.deleteIfExists(file);
        } finally {
            Files.deleteIfExists(directory);
        }
    }

    /** Counts bytes before forwarding writes; checks cancellation at encoding write boundaries. */
    private static final class LimitedOutput extends FilterOutputStream {
        /** Encoded-byte ceiling. */
        private final long limit;
        /** Shared call deadline. */
        private final OcrDeadline deadline;
        /** Bytes successfully admitted. */
        private long count;

        /**
         * Wraps the owned file stream.
         * @param output owned destination
         * @param limit encoded-byte ceiling
         * @param deadline call deadline
         */
        LimitedOutput(final OutputStream output, final long limit, final OcrDeadline deadline) {
            super(output);
            this.limit = limit;
            this.deadline = deadline;
        }

        /**
         * Checks space without overflow.
         * @param length next write size
         * @throws IOException if cancelled or over budget
         */
        private void reserve(final int length) throws IOException {
            deadline.remaining();
            if (length > limit - count) {
                throw new PartitionException(PartitionException.Code.OCR_LIMIT, "Tesseract input exceeds maxPngBytes");
            }
            count += length;
        }

        /**
         * Writes one bounded byte.
         * @param value byte value
         * @throws IOException if writing fails
         */
        @Override
        public void write(final int value) throws IOException {
            reserve(1);
            out.write(value);
        }

        /**
         * Writes a bounded byte range without FilterOutputStream's byte-at-a-time path.
         * @param bytes source bytes
         * @param offset first byte
         * @param length byte count
         * @throws IOException if writing fails
         */
        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            reserve(length);
            out.write(bytes, offset, length);
        }
    }
}
