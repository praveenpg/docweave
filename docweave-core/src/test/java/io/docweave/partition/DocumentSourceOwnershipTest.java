package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #56: the copying byte source keeps its defensive-copy contract, ownership transfer avoids the constructor copy,
 * and the library reads both without copying the array again and without modifying or exposing it.
 */
class DocumentSourceOwnershipTest {
    private static final byte[] PDF = "%PDF-1.7\nopaque test bytes".getBytes(StandardCharsets.US_ASCII);

    @Test
    void copyingSourcesStillSnapshotOnConstructionAndCopyOnAccess() {
        final byte[] input = PDF.clone();
        final var source = (DocumentSource.Bytes) DocumentSource.bytes(input, "a.pdf");
        assertNotSame(input, source.snapshot());
        input[0] = '!';
        assertEquals((byte) '%', source.snapshot()[0]);
        final byte[] copy = source.content();
        assertNotSame(source.snapshot(), copy);
        copy[1] = '?';
        assertArrayEquals(PDF, source.content());
        assertEquals(PDF.length, source.size());
    }

    @Test
    void ownedSourcesTakeTheArrayWithoutCopyingButStillCopyOnAccess() {
        final byte[] input = PDF.clone();
        final var source = (DocumentSource.OwnedBytes) DocumentSource.ownedBytes(input, "a.pdf");
        assertSame(input, source.snapshot(), "ownership transfer must not copy the array");
        final byte[] copy = source.content();
        assertNotSame(input, copy);
        copy[0] = '!';
        assertArrayEquals(PDF, source.content(), "the public accessor must not expose the transferred array");
        assertEquals(PDF.length, source.size());
        assertEquals("a.pdf", source.filename());
        assertThrows(NullPointerException.class, () -> DocumentSource.ownedBytes(null, "a.pdf"));
    }

    @Test
    void ownedSourcesCompareByIdentityLikeCopyingSourcesAndDoNotPrintTheirBytes() {
        final byte[] input = PDF.clone();
        final var first = DocumentSource.ownedBytes(input, "a.pdf");
        assertEquals(first, DocumentSource.ownedBytes(input, "a.pdf"));
        assertNotEquals(first, DocumentSource.ownedBytes(PDF.clone(), "a.pdf"), "equality is by array identity");
        assertNotEquals(first, DocumentSource.ownedBytes(input, "b.pdf"));
        assertEquals("OwnedBytes[size=" + PDF.length + ", filename=a.pdf]", first.toString());
    }

    @Test
    void theLoaderUsesTheSourceArrayItselfInsteadOfACopy() throws Exception {
        final var options = PartitionOptions.defaults();
        final var copying = (DocumentSource.Bytes) DocumentSource.bytes(PDF.clone(), "a.pdf");
        assertSame(copying.snapshot(), new SourceLoader().load(copying, options).binary());
        final var owned = (DocumentSource.OwnedBytes) DocumentSource.ownedBytes(PDF.clone(), "a.pdf");
        assertSame(owned.snapshot(), new SourceLoader().load(owned, options).binary());
    }

    @Test
    void theLibraryReadsBothVariantsInPlaceWithoutModifyingOrExposingThem() throws Exception {
        for (final boolean owned : List.of(false, true)) {
            final byte[] input = PDF.clone();
            final var source = owned ? DocumentSource.ownedBytes(input, "a.pdf") : DocumentSource.bytes(input, "a.pdf");
            final var processor = processor(context -> {
                final var view = context.binaryBuffer();
                assertTrue(view.isReadOnly());
                assertFalse(view.hasArray());
                assertThrows(ReadOnlyBufferException.class, () -> view.put(0, (byte) '?'));
                final byte[] copy = context.binaryContent();
                copy[0] = '?';
                assertEquals((byte) '%', context.binaryBuffer().get(0));
            });
            // The same source can be partitioned repeatedly with the same result.
            assertEquals(processor.partition(source).elements().getFirst().text(),
                    processor.partition(source).elements().getFirst().text());
            assertArrayEquals(PDF, input, "the library must not modify the source array");
        }
    }

    @Test
    void ownedSourcesKeepTheInputBudget() {
        final var source = DocumentSource.ownedBytes(PDF.clone(), "a.pdf");
        final var options = PartitionOptions.builder().maxInputBytes(PDF.length - 1).build();
        final var failure =
                assertThrows(PartitionException.class, () -> processor(context -> {}).partition(source, options));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE, failure.code());
    }

    private static DocWeave processor(final Consumer<PartitionContext> check) {
        final Partitioner parser = new Partitioner() {
            @Override
            public DocumentFormat format() {
                return DocumentFormat.PDF;
            }

            @Override
            public void partition(final PartitionContext context) throws IOException {
                check.accept(context);
                context.emit(ElementType.UncategorizedText, "Verified", Map.of());
            }
        };
        return DocWeave.create(PartitionerRegistry.of(List.of(parser)));
    }
}
