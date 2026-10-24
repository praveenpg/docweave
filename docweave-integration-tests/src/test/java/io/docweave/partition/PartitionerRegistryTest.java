package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.batch.BatchProcessor;
import io.docweave.batch.DocumentBatch;
import io.docweave.chunking.ChunkOptions;
import io.docweave.chunking.Chunker;
import io.docweave.core.ElementType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PartitionerRegistryTest {
    private Partitioner strategy(final DocumentFormat format, final String text) {
        return new Partitioner() {
            public DocumentFormat format() {
                return format;
            }
            public void partition(final PartitionContext context) throws IOException {
                context.emit(ElementType.NarrativeText, text, Map.of("vendor", "custom"));
            }
        };
    }

    @Test
    void builtInsImplementTheStrategyContractWithoutPublicImplementations() {
        final var registry = PartitionerRegistry.create();
        assertEquals(Set.of(DocumentFormat.values()), registry.supportedFormats());
        assertInstanceOf(HtmlPartitioner.class, registry.find(DocumentFormat.HTML).orElseThrow());
        assertInstanceOf(TxtPartitioner.class, registry.find(DocumentFormat.TXT).orElseThrow());
        registry.supportedFormats().forEach(format -> {
            final var parser = registry.find(format).orElseThrow();
            assertInstanceOf(Partitioner.class, parser);
            assertFalse(Modifier.isPublic(parser.getClass().getModifiers()));
        });
    }

    @Test
    void customHtmlOverridesOnlyItsFormatAndUsesSharedMetadata() throws Exception {
        final var custom = strategy(DocumentFormat.HTML, "Alternative extraction");
        final var processor = DocWeave.create(PartitionerRegistry.builder().register(custom).build());
        final var input = DocumentSource.text("<h1>Heading</h1>", "example.html");
        final var result = processor.partition(input);
        assertEquals("Alternative extraction", result.elements().getFirst().text());
        assertEquals(Map.of("filename", "example.html", "filetype", "text/html", "vendor", "custom"),
                result.elements().getFirst().metadata());
        assertEquals(result.elements(), processor.partition(input).elements());
        assertEquals(
                "Native text.", processor.partition(DocumentSource.text("Native text.")).elements().getFirst().text());
    }

    @Test
    void engineConsultsAnInjectableRegistryForEveryRuntimeDispatch() throws Exception {
        final var useSecond = new AtomicBoolean();
        final var lookups = new AtomicInteger();
        final var first = strategy(DocumentFormat.HTML, "First");
        final var second = strategy(DocumentFormat.HTML, "Second");
        final var registry = new PartitionerRegistry() {
            public Optional<Partitioner> find(final DocumentFormat format) {
                lookups.incrementAndGet();
                return format == DocumentFormat.HTML ? Optional.of(useSecond.get() ? second : first) : Optional.empty();
            }
            public Set<DocumentFormat> supportedFormats() {
                return Set.of(DocumentFormat.HTML);
            }
        };
        final var processor = DocWeave.create(registry);
        final var input = DocumentSource.text("<p>Content.</p>");
        assertEquals("First", processor.partition(input).elements().getFirst().text());
        useSecond.set(true);
        assertEquals("Second", processor.partition(input).elements().getFirst().text());
        assertEquals(2, lookups.get());
    }

    @Test
    void registryBuilderSnapshotsReplacementState() {
        final var builder = PartitionerRegistry.builder();
        final var before = builder.build();
        final var custom = strategy(DocumentFormat.HTML, "custom");
        builder.register(custom);
        final var after = builder.build();
        assertNotSame(custom, before.find(DocumentFormat.HTML).orElseThrow());
        assertSame(custom, after.find(DocumentFormat.HTML).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> after.supportedFormats().clear());
    }

    @Test
    void missingCapabilitiesFailExplicitly() {
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(strategy(DocumentFormat.TXT, "text"))));
        assertEquals(Set.of(DocumentFormat.TXT), processor.supportedFormats());
        final var failure =
                assertThrows(PartitionException.class, () -> processor.partition(DocumentSource.text("<p>HTML.</p>")));
        assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT, failure.code());
    }

    @Test
    void registryRejectsDuplicateAndNullKeys() {
        final var custom = strategy(DocumentFormat.HTML, "text");
        assertThrows(IllegalArgumentException.class, () -> PartitionerRegistry.of(List.of(custom, custom)));
        assertThrows(NullPointerException.class, () -> PartitionerRegistry.builder().register(null));
        assertThrows(NullPointerException.class, () -> DocWeave.create(null));
    }

    @Test
    void customStrategiesCannotBypassSharedLimitsOrKeepWritingAfterFailure() {
        final var retained = new AtomicReference<PartitionContext>();
        final var custom = new Partitioner() {
            public DocumentFormat format() {
                return DocumentFormat.TXT;
            }
            public void partition(final PartitionContext context) throws IOException {
                retained.set(context);
                context.emit(ElementType.Title, "First", Map.of());
                assertEquals(1, context.elementCount());
                assertEquals("First", context.elementAt(0).text());
                context.emit(ElementType.Title, "Second", Map.of());
            }
        };
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(custom)));
        final var error = assertThrows(PartitionException.class,
                ()
                        -> processor.partition(
                                DocumentSource.text("input"), PartitionOptions.builder().maxElements(1).build()));
        assertEquals(PartitionException.Code.ELEMENT_LIMIT, error.code());
        assertThrows(IllegalStateException.class, () -> retained.get().emit(ElementType.Title, "Late", Map.of()));
        assertThrows(IllegalStateException.class,
                () -> retained.get().warn(PartitionResult.WarningCode.HTML_RECOVERED, "Late"));
    }

    @Test
    void customStrategiesReceiveDecodedInputAndPreserveNonfatalWarnings() throws Exception {
        final var custom = new Partitioner() {
            public DocumentFormat format() {
                return DocumentFormat.TXT;
            }
            public void partition(final PartitionContext context) throws IOException {
                assertEquals("input.txt", context.filename());
                assertEquals(DocumentFormat.TXT, context.format());
                assertEquals(100_000, context.options().maxElements());
                context.warn(PartitionResult.WarningCode.FORMAT_MISMATCH, "Custom recovery diagnostic");
                context.emit(ElementType.NarrativeText, context.text(), Map.of());
            }
        };
        final var result = DocWeave.create(PartitionerRegistry.of(List.of(custom)))
                                   .partition(DocumentSource.text("Decoded 🧵.", "input.txt"));
        assertEquals("Decoded 🧵.", result.elements().getFirst().text());
        assertEquals(1, result.warnings().size());
    }

    @Test
    void sharedHtmlStrategyKeepsConcurrentDocumentStateIsolated() throws Exception {
        final var processor = DocWeave.create();
        final var inputs = IntStream.range(0, 50).boxed().toList();
        final var results = BatchProcessor.create().mapOrdered(inputs, 8,
                index
                -> processor.partition(DocumentSource.text(
                        "<h1>Heading " + index + "</h1><p>Body " + index + ".</p>", "doc-" + index + ".html")));
        for (int i = 0; i < results.size(); i++) {
            final var elements = results.get(i).elements();
            assertEquals("Heading " + i, elements.getFirst().text());
            assertEquals(elements.getFirst().elementId(), elements.getLast().metadata().get("parent_id"));
            assertEquals("doc-" + i + ".html", elements.getLast().metadata().get("filename"));
        }
    }

    @Test
    void customProcessorCanBeInjectedIntoRawDocumentBatches(final @TempDir Path directory) throws Exception {
        final var file = directory.resolve("input.html");
        Files.writeString(file, "<p>HTML.</p>");
        final var processor = DocWeave.create(
                PartitionerRegistry.builder().register(strategy(DocumentFormat.HTML, "Custom batch content.")).build());
        final var result = DocumentBatch.create(processor, Chunker.create())
                                   .partitionAndChunk(List.of(file), PartitionOptions.defaults(),
                                           Chunker.Strategy.BASIC, ChunkOptions.defaults(), 2);
        assertEquals("Custom batch content.", result.getFirst().chunks().getFirst().text());
    }
}
