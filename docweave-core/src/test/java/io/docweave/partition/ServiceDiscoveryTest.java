package io.docweave.partition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ServiceDiscoveryTest {
    @TempDir Path directory;

    public static class First implements PartitionerProvider {
        public First() {
        }

        public DocumentFormat format() {
            return DocumentFormat.TXT;
        }

        public Partitioner create() {
            return new Partitioner() {
                public DocumentFormat format() {
                    return DocumentFormat.TXT;
                }
                public void partition(final PartitionContext context) throws java.io.IOException {
                    context.emit(
                            io.docweave.core.ElementType.NarrativeText, "Discovered strategy", java.util.Map.of());
                }
            };
        }
    }

    public static class Duplicate extends First {
        public Duplicate() {
        }
    }

    public static class Mismatched extends First {
        public Mismatched() {
        }

        @Override
        public DocumentFormat format() {
            return DocumentFormat.HTML;
        }
    }

    private void installed(final List<Class<? extends PartitionerProvider>> providers, final Runnable assertions)
            throws Exception {
        final Path descriptor = directory.resolve("META-INF/services/io.docweave.partition.PartitionerProvider");
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, String.join("\n", providers.stream().map(Class::getName).toList()) + "\n");
        final var thread = Thread.currentThread();
        final var original = thread.getContextClassLoader();
        try (final var loader = new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()}, original)) {
            thread.setContextClassLoader(loader);
            try {
                assertions.run();
            } finally {
                thread.setContextClassLoader(original);
            }
        }
    }

    @Test
    void installedServicesAreDiscoveredAndExplicitOverridesWin() throws Exception {
        installed(List.of(First.class), () -> {
            final var registry = PartitionerRegistry.create();
            assertEquals(java.util.Set.of(DocumentFormat.TXT), registry.supportedFormats());
            final var replacement = new First().create();
            assertSame(replacement,
                    PartitionerRegistry.builder().register(replacement).build().find(DocumentFormat.TXT).orElseThrow());
        });
        assertTrue(PartitionerRegistry.create().supportedFormats().isEmpty());
    }

    @Test
    void duplicateInstalledKeysAreRejected() throws Exception {
        installed(List.of(First.class, Duplicate.class), () -> {
            final var error = assertThrows(IllegalStateException.class, PartitionerRegistry::create);
            assertTrue(error.getMessage().contains("Duplicate installed format"));
        });
    }

    @Test
    void mismatchedProviderKeyIsRejected() throws Exception {
        installed(List.of(Mismatched.class), () -> {
            final var error = assertThrows(IllegalStateException.class, PartitionerRegistry::create);
            assertTrue(error.getMessage().contains("Provider format mismatch"));
        });
    }
}
