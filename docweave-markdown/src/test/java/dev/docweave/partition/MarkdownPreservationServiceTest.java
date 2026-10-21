package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Tests real optional service discovery without installing an HTML/parser dependency. */
public class MarkdownPreservationServiceTest {
    @TempDir Path directory;
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final TableLimits LIMITS = new TableLimits(5, 6, 7, 80);

    public static class Legacy implements HtmlExtraction {
        public Legacy() {
        }

        public void partition(final PartitionContext context, final String html, final TableLimits limits)
                throws IOException {
            CALLS.incrementAndGet();
            assertEquals(LIMITS, limits);
            assertTrue(html.contains("<h1>Heading</h1>"));
            context.emit(ElementType.Title, "delegated", Map.of());
        }
    }

    public static class Capable extends Legacy {
        public Capable() {
        }

        public boolean supportsNestedTablePreservation() {
            return true;
        }

        public void partition(final PartitionContext context, final String html, final TableLimits limits)
                throws IOException {
            assertEquals(NestedTableOptions.preserveMetadata(), context.options().nestedTableOptions());
            super.partition(context, html, limits);
        }
    }

    private PartitionOptions options(final MarkdownOptions.RawHtml mode, final boolean preserve) {
        return PartitionOptions.builder()
                .format(DocumentFormat.MARKDOWN)
                .markdownOptions(new MarkdownOptions(true, 128, mode, LIMITS))
                .nestedTableOptions(preserve ? NestedTableOptions.preserveMetadata() : NestedTableOptions.defaults())
                .build();
    }

    private void installed(final List<Class<? extends HtmlExtraction>> providers, final Runnable assertions)
            throws Exception {
        final var descriptor = directory.resolve("META-INF/services/" + HtmlExtraction.class.getName());
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, String.join("\n", providers.stream().map(Class::getName).toList()));
        final var thread = Thread.currentThread();
        final var original = thread.getContextClassLoader();
        try (final var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, original)) {
            thread.setContextClassLoader(loader);
            try {
                CALLS.set(0);
                assertions.run();
            } finally {
                thread.setContextClassLoader(original);
            }
        }
    }

    @Test
    void astModesRejectPreservationAtFacadeAndDirectEntryBeforeReadingContent() throws Exception {
        for (final var mode : List.of(MarkdownOptions.RawHtml.PRESERVE_TEXT, MarkdownOptions.RawHtml.SKIP)) {
            final var options = options(mode, true);
            assertEquals(PartitionException.Code.UNSUPPORTED_OPTION,
                    assertThrows(PartitionException.class,
                            () -> DocWeave.create().partition(DocumentSource.text("# Heading", "a.md"), options))
                            .code());
            final var context = (PartitionContext) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {PartitionContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("options")) {
                            return options;
                        }
                        throw new AssertionError("Read content before checking mode");
                    });
            assertEquals(PartitionException.Code.UNSUPPORTED_OPTION,
                    assertThrows(PartitionException.class, () -> new MarkdownPartitioner().partition(context)).code());
        }
    }

    @Test
    void missingProviderRejectsPreservationBeforeReadingContent() {
        final var options = options(MarkdownOptions.RawHtml.EXTRACT_DOM, true);
        assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT,
                assertThrows(PartitionException.class,
                        () -> DocWeave.create().partition(DocumentSource.text("# Heading", "a.md"), options))
                        .code());
        final var context = (PartitionContext) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {PartitionContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("options")) {
                        return options;
                    }
                    throw new AssertionError("Read content before checking optional provider");
                });
        assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT,
                assertThrows(PartitionException.class, () -> new MarkdownPartitioner().partition(context)).code());
    }

    @Test
    void legacyProviderRemainsUsableForFlatteningButCannotSilentlyIgnorePreservation() throws Exception {
        assertFalse(new Legacy().supportsNestedTablePreservation());
        installed(List.of(Legacy.class, Capable.class), () -> {
            final var processor = DocWeave.create();
            final var source = DocumentSource.text("# Heading", "a.md");
            assertEquals("delegated",
                    assertDoesNotThrow(
                            () -> processor.partition(source, options(MarkdownOptions.RawHtml.EXTRACT_DOM, false)))
                            .elements()
                            .getFirst()
                            .text());
            assertEquals(1, CALLS.get());
            assertEquals(PartitionException.Code.UNSUPPORTED_OPTION,
                    assertThrows(PartitionException.class,
                            () -> processor.partition(source, options(MarkdownOptions.RawHtml.EXTRACT_DOM, true)))
                            .code());
            assertEquals(1, CALLS.get());
        });
    }

    @Test
    void interruptionPrecedesProviderDiscoveryAndPreservesTheFlag() {
        final var options = options(MarkdownOptions.RawHtml.EXTRACT_DOM, true);
        final var context = (PartitionContext) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {PartitionContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("options")) {
                        return options;
                    }
                    throw new AssertionError("Read content during interrupted dispatch");
                });
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> new MarkdownPartitioner().partition(context));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void capableProviderReceivesOriginalPolicyRenderedHtmlAndCustomLimits() throws Exception {
        installed(List.of(Capable.class), () -> {
            assertEquals("delegated",
                    assertDoesNotThrow(()
                                               -> DocWeave.create().partition(DocumentSource.text("# Heading", "a.md"),
                                                       options(MarkdownOptions.RawHtml.EXTRACT_DOM, true)))
                            .elements()
                            .getFirst()
                            .text());
            assertEquals(1, CALLS.get());
        });
    }
}
