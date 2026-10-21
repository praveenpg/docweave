package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NestedTableOptionsTest {
    @Test
    void policiesValidateLimitsAndBuildersKeepIndependentSnapshots() {
        assertThrows(NullPointerException.class, () -> new NestedTableOptions(null, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class, () -> new NestedTableOptions(NestedTableOptions.Mode.FLATTEN, 0, 1, 1));
        assertThrows(
                IllegalArgumentException.class, () -> new NestedTableOptions(NestedTableOptions.Mode.FLATTEN, 1, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new NestedTableOptions(NestedTableOptions.Mode.FLATTEN, 1, 257, 1));
        assertThrows(
                IllegalArgumentException.class, () -> new NestedTableOptions(NestedTableOptions.Mode.FLATTEN, 1, 1, 0));
        final var builder = PartitionOptions.builder();
        final var before = builder.build();
        final var after = builder.nestedTableOptions(NestedTableOptions.preserveMetadata()).build();
        assertEquals(NestedTableOptions.defaults(), before.nestedTableOptions());
        assertEquals(NestedTableOptions.preserveMetadata(), after.nestedTableOptions());
        assertThrows(NullPointerException.class, () -> builder.nestedTableOptions(null));
    }

    @Test
    void previousConstructorsRetainDefaultFlattening() {
        final var base = PartitionOptions.defaults();
        final var previous = new PartitionOptions(base.format(), base.charset(), base.maxInputBytes(),
                base.maxElements(), base.maxHtmlDepth(), base.skipHeadersAndFooters(), base.paragraphMode(),
                base.classifyText(), base.baseUri(), base.markdownOptions(), base.delimitedOptions(), base.xmlOptions(),
                base.docxOptions(), base.txtOptions());
        final var original = new PartitionOptions(base.format(), base.charset(), base.maxInputBytes(),
                base.maxElements(), base.maxHtmlDepth(), base.skipHeadersAndFooters(), base.paragraphMode(),
                base.classifyText(), base.baseUri(), base.markdownOptions(), base.delimitedOptions(), base.xmlOptions(),
                base.docxOptions());
        assertEquals(base, previous);
        assertEquals(base, original);
    }

    @Test
    void legacyBuilderDefaultAcceptsFlatteningAndRejectsUnsupportedPreservation() {
        final var builder = (PartitionOptions.Builder) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PartitionOptions.Builder.class},
                (proxy, method, arguments) -> InvocationHandler.invokeDefault(proxy, method, arguments));
        assertSame(builder, builder.nestedTableOptions(NestedTableOptions.defaults()));
        assertThrows(UnsupportedOperationException.class,
                () -> builder.nestedTableOptions(NestedTableOptions.preserveMetadata()));
        assertThrows(NullPointerException.class, () -> builder.nestedTableOptions(null));
    }

    @Test
    void failedReservationsDoNotConsumeCountOrPayload() throws Exception {
        final var budget =
                new NestedTableBudget(new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 2, 2, 3));
        budget.reserve(1, 2);
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> budget.reserve(3, 0)).code());
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> budget.reserve(1, 2)).code());
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(0, 0));
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(1, -1));
        budget.reserve(2, 1);
        assertThrows(PartitionException.class, () -> budget.reserve(1, 0));
    }

    @Test
    void zeroPayloadStillCountsAndCountersCannotOverflow() throws Exception {
        final var zero =
                new NestedTableBudget(new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 1, 1, 1));
        zero.reserve(1, 0);
        assertThrows(PartitionException.class, () -> zero.reserve(1, 0));
        final var large = new NestedTableBudget(
                new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 3, 1, Long.MAX_VALUE));
        large.reserve(1, Long.MAX_VALUE - 1);
        assertThrows(PartitionException.class, () -> large.reserve(1, Long.MAX_VALUE));
        large.reserve(1, 1);
        assertThrows(PartitionException.class, () -> large.reserve(1, 1));
    }

    @Test
    void budgetIsDocumentLocalAndInterruptionPreservesItsState() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new NestedTableBudget(NestedTableOptions.defaults()));
        final var options = new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 1, 1, 1);
        final var interrupted = new NestedTableBudget(options);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> interrupted.reserve(1, 1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        interrupted.reserve(1, 1);
        new NestedTableBudget(options).reserve(1, 1);
    }

    @Test
    void capabilityGatesDispatchAndSupportedCustomStrategiesReceiveThePolicy() throws Exception {
        final var calls = new AtomicInteger();
        for (final boolean supported : new boolean[] {false, true}) {
            final var strategy = new Partitioner() {
                public DocumentFormat format() {
                    return DocumentFormat.TXT;
                }
                public boolean supportsNestedTablePreservation() {
                    return supported;
                }
                public void partition(final PartitionContext context) throws java.io.IOException {
                    calls.incrementAndGet();
                    assertEquals(NestedTableOptions.preserveMetadata(), context.options().nestedTableOptions());
                    context.emit(ElementType.NarrativeText, "custom", Map.of());
                }
            };
            final var engine = DocWeave.create(PartitionerRegistry.builder().register(strategy).build());
            final var options =
                    PartitionOptions.builder().nestedTableOptions(NestedTableOptions.preserveMetadata()).build();
            if (supported) {
                assertEquals(
                        "custom", engine.partition(DocumentSource.text("input"), options).elements().getFirst().text());
            } else {
                assertEquals(PartitionException.Code.UNSUPPORTED_OPTION,
                        assertThrows(
                                PartitionException.class, () -> engine.partition(DocumentSource.text("input"), options))
                                .code());
            }
        }
        assertEquals(1, calls.get());
    }
}
