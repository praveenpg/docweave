package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** #65: DOCX partitioning reads the context's bytes in place instead of a defensive copy of the whole input. */
class DocxInPlaceInputTest {
    @Test
    void docxPartitioningNeverCopiesTheWholeInput() throws Exception {
        final byte[] bytes = docx();
        final byte[] original = bytes.clone();
        // The same parser, run through a context that refuses the copying accessor.
        final Partitioner noCopy = new Partitioner() {
            @Override
            public DocumentFormat format() {
                return DocumentFormat.DOCX;
            }

            @Override
            public void partition(final PartitionContext context) throws IOException {
                new DocxPartitioner().partition(new NoCopyContext(context));
            }
        };
        final var inPlace = DocWeave.create(PartitionerRegistry.of(List.of(noCopy)))
                                    .partition(DocumentSource.ownedBytes(bytes, "a.docx"));
        final var reference = DocWeave.create().partition(DocumentSource.bytes(original, "a.docx"));
        assertEquals(reference.elements().stream().map(Element::text).toList(),
                inPlace.elements().stream().map(Element::text).toList());
        assertArrayEquals(original, bytes, "the input must not be modified");
    }

    @Test
    void eachStreamReadsItsOwnViewOfTheBuffer() throws Exception {
        final ByteBuffer input = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5}).asReadOnlyBuffer();
        try (final var first = new DocxBufferInput(input); final var second = new DocxBufferInput(input)) {
            assertEquals(5, first.available());
            assertEquals(1, first.read());
            assertEquals(2, first.skip(2));
            final byte[] rest = new byte[4];
            assertEquals(2, first.read(rest, 0, 4), "only the remaining bytes are read");
            assertEquals(4, rest[0]);
            assertEquals(5, rest[1]);
            assertEquals(0, first.available());
            assertEquals(-1, first.read());
            assertEquals(-1, first.read(rest, 0, 4));
            assertEquals(0, first.read(rest, 0, 0));
            assertEquals(1, second.read(), "a second stream starts at the beginning");
        }
        assertEquals(0, input.position(), "the source buffer's position is unchanged");
        assertThrows(IndexOutOfBoundsException.class,
                () -> new DocxBufferInput(input).read(new byte[2], 1, 2));
    }

    private static byte[] docx() throws IOException {
        try (final var document = new XWPFDocument(); final var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("First paragraph.");
            document.createTable(1, 2).getRow(0).getCell(1).setText("Cell");
            document.createParagraph().createRun().setText("Last paragraph.");
            document.write(output);
            return output.toByteArray();
        }
    }

    /** Delegates to the library's context but fails if the whole input is requested as a copy. */
    private record NoCopyContext(PartitionContext delegate) implements PartitionContext {
        @Override
        public String text() {
            return delegate.text();
        }

        @Override
        public byte[] binaryContent() {
            throw new AssertionError("DOCX partitioning must read binaryBuffer() instead of copying the input");
        }

        @Override
        public ByteBuffer binaryBuffer() {
            return delegate.binaryBuffer();
        }

        @Override
        public PartitionProgressReporter progress() {
            return delegate.progress();
        }

        @Override
        public String filename() {
            return delegate.filename();
        }

        @Override
        public DocumentFormat format() {
            return delegate.format();
        }

        @Override
        public PartitionOptions options() {
            return delegate.options();
        }

        @Override
        public int elementCount() {
            return delegate.elementCount();
        }

        @Override
        public Element elementAt(final int index) {
            return delegate.elementAt(index);
        }

        @Override
        public Element emit(final ElementType type, final String text, final Map<String, Object> metadata)
                throws IOException {
            return delegate.emit(type, text, metadata);
        }

        @Override
        public void warn(final PartitionResult.WarningCode code, final String message) {
            delegate.warn(code, message);
        }
    }
}
