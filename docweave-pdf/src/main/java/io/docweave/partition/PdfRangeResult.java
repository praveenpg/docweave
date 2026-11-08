package io.docweave.partition;

import io.docweave.core.Element;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Immutable output of partitioning one contiguous page range of a PDF, from
 * {@link PdfPartitioner#partitionRange(DocumentSource, PartitionOptions, PdfPageRange, PdfBudget,
 * PartitionProgressListener)}, and the input of {@link PdfRangeAssembler}. Assembling the results of ranges that
 * cover a document, in page order, gives exactly the elements and warnings of partitioning the whole document.
 *
 * <p>Element IDs are <em>provisional</em>: they use the whole-document formula with the range's own sequence starting
 * at zero, so they equal the final IDs only for a range that starts at page 1. Metadata fields listed in
 * {@link #idReferenceFields()} hold provisional IDs of elements in the same range; assembly rewrites them. Table
 * continuation links reach only tables inside the range; {@link #firstPageTable()} and {@link #lastPageTable()}
 * carry the evidence assembly needs to link tables across range boundaries.</p>
 *
 * <p>Identity fields let assembly reject results that do not belong together: the result format and library
 * versions, the SHA-256 of the source bytes, and a digest of the partition and strategy options. Document-level
 * checks and warnings (permissions, {@code maxPages}, source tags) always cover the whole document, so every range
 * of a document carries the same {@link #documentWarnings()}.</p>
 *
 * @param formatVersion version of this result's structure; {@link #FORMAT_VERSION} for this library
 * @param libraryVersion DocWeave version that produced the result
 * @param sourceSha256 lowercase hexadecimal SHA-256 of the complete source bytes
 * @param sourceBytes size of the complete source in bytes
 * @param filename source filename used in metadata and IDs, or null
 * @param optionsDigest lowercase hexadecimal SHA-256 identifying the partition and strategy options
 * @param documentPages physical page count of the whole document
 * @param coverage pages this result covers
 * @param documentWarnings warnings about the whole document, in order, emitted once by assembly
 * @param idReferenceFields metadata fields that hold element IDs, as {@code field} or {@code field.nested} paths
 * @param pages one entry per covered page, in page order
 * @param firstPageTable continuation evidence of the first covered page, or null when it has none
 * @param lastPageTable continuation evidence of the last covered page, or null when it has none
 */
public record PdfRangeResult(int formatVersion, String libraryVersion, String sourceSha256, int sourceBytes,
        String filename, String optionsDigest, int documentPages, PdfPageRange coverage,
        List<PartitionResult.Warning> documentWarnings, List<String> idReferenceFields, List<Page> pages,
        PdfTableBoundary firstPageTable, PdfTableBoundary lastPageTable) {
    /** Structure version written by this library. */
    public static final int FORMAT_VERSION = 1;

    /** Metadata fields of PDF elements that refer to other elements by ID. */
    public static final List<String> ID_REFERENCE_FIELDS =
            List.of("pdf_table_continuation.previous_element_id", "pdf_table_continuation.root_element_id");

    /**
     * Validates structure and copies collections.
     * @param formatVersion positive structure version
     * @param libraryVersion nonblank producing version
     * @param sourceSha256 64 lowercase hexadecimal digits
     * @param sourceBytes nonnegative source size
     * @param filename optional filename
     * @param optionsDigest 64 lowercase hexadecimal digits
     * @param documentPages document page count, at least the last covered page
     * @param coverage nonnull covered pages
     * @param documentWarnings nonnull document warnings
     * @param idReferenceFields nonnull reference fields
     * @param pages exactly one entry per covered page, in order
     * @param firstPageTable optional evidence on the first covered page
     * @param lastPageTable optional evidence on the last covered page
     * @throws NullPointerException if a required reference is null
     * @throws IllegalArgumentException if a value is inconsistent
     */
    public PdfRangeResult {
        Objects.requireNonNull(libraryVersion, "libraryVersion");
        Objects.requireNonNull(coverage, "coverage");
        documentWarnings = List.copyOf(documentWarnings);
        idReferenceFields = List.copyOf(idReferenceFields);
        pages = List.copyOf(pages);
        if (formatVersion < 1 || libraryVersion.isBlank() || !hex(sourceSha256) || !hex(optionsDigest)
                || sourceBytes < 0 || documentPages < coverage.last()) {
            throw new IllegalArgumentException("Invalid PDF range result identity");
        }
        if (pages.size() != coverage.pageCount()) {
            throw new IllegalArgumentException("A PDF range result needs one entry per covered page");
        }
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index).pageNumber() != coverage.first() + index) {
                throw new IllegalArgumentException("PDF range result pages must be consecutive and in order");
            }
        }
        checkBoundary(firstPageTable, pages.getFirst());
        checkBoundary(lastPageTable, pages.getLast());
    }

    /**
     * Returns the DocWeave version of this library, recorded in every range result it produces.
     * @return library version, or {@code "unknown"} when the build did not record it
     */
    public static String currentLibraryVersion() {
        return Version.VALUE;
    }

    /**
     * Checks that boundary evidence names a table of its page.
     * @param boundary optional evidence
     * @param page covered page
     */
    private static void checkBoundary(final PdfTableBoundary boundary, final Page page) {
        if (boundary == null) {
            return;
        }
        if (boundary.pageNumber() != page.pageNumber()
                || page.elements().stream().noneMatch(element
                        -> element.elementId().equals(boundary.elementId())
                                   && element.type() == io.docweave.core.ElementType.Table)) {
            throw new IllegalArgumentException("PDF range boundary evidence must name a table on its page");
        }
    }

    /**
     * Tests for a 64-digit lowercase hexadecimal string.
     * @param value candidate
     * @return whether it is a SHA-256 hex digest
     */
    private static boolean hex(final String value) {
        return value != null && value.length() == 64
                && value.chars().allMatch(ch -> ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f');
    }

    /**
     * One covered page.
     * @param pageNumber one-based physical page
     * @param elements the page's elements in output order, with provisional IDs
     * @param warnings the page's warnings in order
     * @param usage budget consumption charged to this page, by kind; absent kinds were not charged
     */
    public record Page(int pageNumber, List<Element> elements, List<PartitionResult.Warning> warnings,
            Map<PdfBudget.Kind, Long> usage) {
        /**
         * Validates and copies.
         * @param pageNumber one-based page
         * @param elements nonnull elements
         * @param warnings nonnull warnings
         * @param usage nonnull positive usage by kind
         * @throws NullPointerException if a reference or entry is null
         * @throws IllegalArgumentException if the page is not positive, usage is not positive or element IDs repeat
         */
        public Page {
            elements = List.copyOf(elements);
            warnings = List.copyOf(warnings);
            final var copy = new EnumMap<PdfBudget.Kind, Long>(PdfBudget.Kind.class);
            copy.putAll(usage);
            usage = java.util.Collections.unmodifiableMap(copy);
            if (pageNumber < 1 || copy.values().stream().anyMatch(value -> value <= 0)) {
                throw new IllegalArgumentException("Invalid PDF range page");
            }
            final var ids = new HashSet<String>();
            for (final var element : elements) {
                if (!ids.add(element.elementId())) {
                    throw new IllegalArgumentException("PDF range page repeats an element ID");
                }
            }
        }
    }

    /** Lazily read build version. */
    private static final class Version {
        /** Version recorded by the build, or unknown. */
        static final String VALUE = read();

        /** Holder class. */
        private Version() {
        }

        /**
         * Reads the version resource written by the build.
         * @return version or unknown
         */
        private static String read() {
            try (final InputStream input = PdfRangeResult.class.getResourceAsStream("docweave-pdf.properties")) {
                if (input == null) {
                    return "unknown";
                }
                final var properties = new Properties();
                properties.load(input);
                final String value = properties.getProperty("version", "").strip();
                return value.isEmpty() || value.startsWith("${") ? "unknown" : value;
            } catch (final IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}
