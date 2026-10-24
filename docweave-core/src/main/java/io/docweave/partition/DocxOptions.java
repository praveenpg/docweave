package io.docweave.partition;

/**
 * Immutable DOCX policies. Archive budgets apply to all ZIP entries before Apache POI builds its
 * document model. XML budgets apply cumulatively to element starts and independently to nesting.
 * DTDs, custom entities, macros and non-DOCX packages are rejected. No external target is fetched.
 * POI's process-wide protection settings are not changed by this library.
 *
 * @param maxArchiveEntries positive ZIP-entry budget including directories
 * @param maxExpandedBytes positive sum of decompressed entry bytes
 * @param maxEntryBytes positive per-entry decompressed budget, no greater than the total
 * @param maxXmlDepth root-inclusive XML depth in [1, 256]
 * @param maxXmlNodes positive total XML element-start budget across all parts
 * @param includeHeadersAndFooters whether to emit stored headers before and footers after body content
 * @param contentOptions nonnull supplemental-content and revision policies
 * @param tableLimits nonnull budgets per emitted table
 */
public record DocxOptions(int maxArchiveEntries, long maxExpandedBytes, int maxEntryBytes, int maxXmlDepth,
        int maxXmlNodes, boolean includeHeadersAndFooters, TableLimits tableLimits, DocxContentOptions contentOptions) {
    /**
     * Validates archive and extraction budgets.
     * @param maxArchiveEntries positive entry budget
     * @param maxExpandedBytes positive total decompressed budget
     * @param maxEntryBytes positive entry budget no greater than total
     * @param maxXmlDepth XML nesting in [1, 256]
     * @param maxXmlNodes positive cumulative XML-node budget
     * @param includeHeadersAndFooters whether to emit stored header/footer parts
     * @param contentOptions nonnull content policies
     * @param tableLimits nonnull per-table budgets
     * @throws IllegalArgumentException if a limit is invalid
     * @throws NullPointerException if tableLimits is null
     */
    public DocxOptions {
        if (maxArchiveEntries < 1 || maxExpandedBytes < 1 || maxEntryBytes < 1 || maxEntryBytes > maxExpandedBytes
                || maxXmlDepth < 1 || maxXmlDepth > 256 || maxXmlNodes < 1) {
            throw new IllegalArgumentException("Invalid DOCX resource limits");
        }
        java.util.Objects.requireNonNull(tableLimits, "tableLimits");
        java.util.Objects.requireNonNull(contentOptions, "contentOptions");
    }

    /**
     * Creates archive/table policies with default supplemental-content extraction.
     * @param maxArchiveEntries positive entry limit
     * @param maxExpandedBytes positive expanded-byte limit
     * @param maxEntryBytes positive per-entry limit no greater than total
     * @param maxXmlDepth root-inclusive depth in [1, 256]
     * @param maxXmlNodes positive XML-node limit
     * @param includeHeadersAndFooters whether to emit stored header/footer parts
     * @param tableLimits nonnull per-table budgets
     * @throws IllegalArgumentException if a limit is invalid
     * @throws NullPointerException if tableLimits is null
     */
    public DocxOptions(final int maxArchiveEntries, final long maxExpandedBytes, final int maxEntryBytes,
            final int maxXmlDepth, final int maxXmlNodes, final boolean includeHeadersAndFooters,
            final TableLimits tableLimits) {
        this(maxArchiveEntries, maxExpandedBytes, maxEntryBytes, maxXmlDepth, maxXmlNodes, includeHeadersAndFooters,
                tableLimits, DocxContentOptions.defaults());
    }

    /**
     * Copies archive/table policies with a new supplemental-content policy.
     * @param value nonnull content and revision policies
     * @return immutable replacement options
     * @throws NullPointerException if value is null
     */
    public DocxOptions withContentOptions(final DocxContentOptions value) {
        return new DocxOptions(maxArchiveEntries, maxExpandedBytes, maxEntryBytes, maxXmlDepth, maxXmlNodes,
                includeHeadersAndFooters, tableLimits, value);
    }

    /**
     * Returns 2,000 entries, 64 MiB expanded, 16 MiB per entry, depth 256 and 1,000,000 XML nodes.
     * Stored headers/footers are excluded by default; tables use shared default budgets.
     * @return immutable default policy
     */
    public static DocxOptions defaults() {
        return new DocxOptions(
                2_000, 64L * 1024 * 1024, 16 * 1024 * 1024, 256, 1_000_000, false, TableLimits.defaults());
    }
}
