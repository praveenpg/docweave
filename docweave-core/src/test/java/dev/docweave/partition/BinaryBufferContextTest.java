package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Public context controls for immutable binary snapshots and additive custom-context compatibility. */
class BinaryBufferContextTest {
    /** Creates owned snapshot/view controls without installing a binary parser dependency. */
    BinaryBufferContextTest() {
    }

    /**
     * Independent read-only cursors preserve the caller snapshot and existing defensive-copy API.
     * @throws Exception if the explicit core-only inspection strategy fails
     */
    @Test
    void frameworkViewsAreReadOnlyIndependentAndDoNotExposeSnapshotArrays() throws Exception {
        final byte[] input = "%PDF-1.7\nOwned opaque bytes".getBytes(StandardCharsets.US_ASCII);
        final var source = DocumentSource.bytes(input, "owned.pdf");
        input[0] = '!';
        final var result = processor(DocumentFormat.PDF, context -> {
            final var first = context.binaryBuffer();
            final var second = context.binaryBuffer();
            assertTrue(first.isReadOnly());
            assertFalse(first.hasArray());
            assertEquals(input.length, first.remaining());
            assertEquals((byte) '%', first.get());
            assertEquals(1, first.position());
            assertEquals(0, second.position());
            first.limit(1);
            assertEquals(input.length, second.limit());
            assertThrows(ReadOnlyBufferException.class, () -> first.put(0, (byte) '?'));
            assertThrows(ReadOnlyBufferException.class, first::array);
            final byte[] copy = context.binaryContent();
            copy[0] = '?';
            assertEquals((byte) '%', second.get(0));
            assertEquals((byte) '%', context.binaryBuffer().get(0));
        }).partition(source);
        assertEquals("Verified", result.elements().getFirst().text());
    }

    /** The new default works with contexts implementing only the historical byte-array method. */
    @Test
    void customContextsNeedNoNewOverrideAndKeepTheirDefensiveCopyBehavior() {
        final byte[] input = {1, 2, 3};
        final var copies = new AtomicInteger();
        final var context = (PartitionContext) Proxy.newProxyInstance(PartitionContext.class.getClassLoader(),
                new Class<?>[] {PartitionContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("binaryContent")) {
                        copies.incrementAndGet();
                        return input.clone();
                    }
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args == null ? new Object[0] : args);
                    }
                    throw new UnsupportedOperationException("Unused custom context method");
                });
        final var first = context.binaryBuffer();
        final var second = context.binaryBuffer();
        assertEquals(2, copies.get());
        assertTrue(first.isReadOnly());
        first.get();
        assertEquals(0, second.position());
        assertThrows(ReadOnlyBufferException.class, () -> second.put(0, (byte) 9));
        assertEquals((byte) 1, input[0]);
    }

    /**
     * Text-only contexts preserve their historical no-binary failure.
     * @throws Exception if explicit text inspection or emission fails
     */
    @Test
    void textOnlySourcesStillRejectBinaryAccess() throws Exception {
        final var result = processor(DocumentFormat.TXT, context -> {
            assertEquals("Plain", context.text());
            assertThrows(IllegalStateException.class, context::binaryBuffer);
        }).partition(DocumentSource.text("Plain"));
        assertEquals("Verified", result.elements().getFirst().text());
    }

    /**
     * Creates one explicit JDK-only source inspection strategy; opaque bytes are not represented as a parsed PDF.
     * @param format source registry key
     * @param check synchronous snapshot assertions
     * @return reusable public facade
     */
    private static DocWeave processor(final DocumentFormat format, final Consumer<PartitionContext> check) {
        final Partitioner parser = new Partitioner() {
            /**
             * Supplies the independently selected format key.
             * @return source registry key
             */
            @Override
            public DocumentFormat format() {
                return format;
            }

            /**
             * Inspects the loaded context before emitting one owned confirmation.
             * @param context synchronous document context
             * @throws IOException if normal bounded emission fails
             */
            @Override
            public void partition(final PartitionContext context) throws IOException {
                check.accept(context);
                context.emit(ElementType.UncategorizedText, "Verified", Map.of());
            }
        };
        return DocWeave.create(PartitionerRegistry.of(List.of(parser)));
    }
}
