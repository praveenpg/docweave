package dev.docweave;

import dev.docweave.partition.DocumentFormat;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionEngine;
import dev.docweave.partition.PartitionOptions;
import dev.docweave.partition.PartitionResult;
import dev.docweave.partition.PartitionerRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Framework-independent, thread-safe facade for formats supplied by installed parser modules.
 * Each call owns its parser state; a single instance can be reused by virtual-thread batches.
 * File reads and CPU parsing execute synchronously on the calling thread. No external resources
 * referenced by HTML are fetched and no source is modified.
 *
 * <pre>{@code
 * var processor = DocWeave.create();
 * var result = processor.partition(DocumentSource.of(Path.of("report.html")),
 *     PartitionOptions.builder().maxInputBytes(5_000_000).build());
 * var chunks = Chunker.create().byTitle(result.elements(), ChunkOptions.defaults());
 * }</pre>
 *
 */
public interface DocWeave {
    /**
     * Creates a thread-safe native processor for format modules installed on the classpath.
     * Custom implementations can implement this interface for dependency injection.
     * @return a reusable processor with bounded defaults; core alone has no installed parsers
     * @throws java.util.ServiceConfigurationError if an installed provider cannot be loaded
     * @throws IllegalStateException if installed providers have duplicate or inconsistent keys
     */
    static DocWeave create() {
        return PartitionEngine.create();
    }

    /**
     * Creates native orchestration with an injectable runtime strategy registry.
     * Loading, decoding, IDs and resource limits remain outside the selected parser.
     * @param registry thread-safe lookup with stateless or thread-safe strategies
     * @return reusable processor advertising the registry's capabilities
     * @throws NullPointerException if registry is null
     */
    static DocWeave create(final PartitionerRegistry registry) {
        return PartitionEngine.create(registry);
    }

    /**
     * Returns the formats implemented by this release.
     * @return an immutable set of native document formats; registry-backed implementations report registered
     *         capabilities
     */
    default Set<DocumentFormat> supportedFormats() {
        return Set.of(DocumentFormat.TXT, DocumentFormat.HTML, DocumentFormat.MARKDOWN, DocumentFormat.CSV,
                DocumentFormat.TSV, DocumentFormat.XML, DocumentFormat.DOCX);
    }

    /**
     * Partitions a local file with automatic format/encoding detection and default limits.
     * @param path file to open; the filename is included in element metadata
     * @return ordered elements, diagnostics and recovery warnings
     * @throws IOException if reading, decoding, limits or unsupported-format validation fails
     * @throws NullPointerException if path is null
     */
    default PartitionResult partition(final Path path) throws IOException {
        return partition(DocumentSource.of(path), PartitionOptions.defaults());
    }

    /**
     * Partitions an input with {@link PartitionOptions#defaults()}.
     * @param source local file, bytes or decoded text; never fetched remotely
     * @return an immutable partition result, possibly with an empty element list
     * @throws IOException if input cannot be read or processed within the default limits
     * @throws NullPointerException if source is null
     */
    default PartitionResult partition(final DocumentSource source) throws IOException {
        return partition(source, PartitionOptions.defaults());
    }

    /**
     * Partitions an input with explicit immutable options.
     * TXT groups wrapped lines between blank lines by default. HTML emits semantic blocks in DOM
     * order and builds heading/list parent references. Stable IDs depend on filename, text and position;
     * they are not a document fingerprint. No language or page-number metadata is fabricated.
     * @param source source to process
     * @param options format, encoding, paragraph and resource policies
     * @return ordered elements and observed diagnostics; callers should inspect warnings
     * @throws dev.docweave.partition.PartitionException for unsupported content, invalid encoding or exceeded limits
     * @throws java.io.InterruptedIOException if the caller is interrupted at a processing checkpoint
     * @throws IOException if opening or reading a file fails
     * @throws NullPointerException if either argument is null
     */
    PartitionResult partition(final DocumentSource source, final PartitionOptions options) throws IOException;
}
