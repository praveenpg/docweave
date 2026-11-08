package io.docweave.partition;

import java.util.List;

/**
 * A PDF whose document-wide work has been done once, from {@link PdfPartitioner#prepare(DocumentSource,
 * PartitionOptions)}, so that several page ranges of it can be partitioned without repeating that work: loading and
 * the {@code maxInputBytes} limit, the extraction permission and {@code maxPages} checks, the SHA-256 of the source
 * (also used as the paragraph identity), the source-tag pass with its per-page tables and paragraphs, and the document
 * warnings. Results are identical to partitioning ranges from the source directly.
 *
 * <p>A prepared document holds the source bytes and the tag data in memory. It is immutable, thread-safe and valid
 * only in this process, for the partitioner configuration that prepared it; other configurations reject it.</p>
 */
public interface PdfPreparedDocument {
    /**
     * Returns the physical page count of the document.
     * @return page count
     */
    int pageCount();

    /**
     * Returns the SHA-256 of the complete source bytes.
     * @return 64 lowercase hexadecimal digits
     */
    String sourceSha256();

    /**
     * Returns the size of the source in bytes.
     * @return source size
     */
    int sourceBytes();

    /**
     * Returns the source filename used in metadata and IDs.
     * @return filename, or null
     */
    String filename();

    /**
     * Returns the document options the document was prepared with; ranges use the same options.
     * @return options
     */
    PartitionOptions options();

    /**
     * Returns the warnings about the whole document, in order, as every range result of it carries them.
     * @return immutable warnings
     */
    List<PartitionResult.Warning> documentWarnings();
}
