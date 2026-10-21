package dev.docweave.partition;

/**
 * Immutable CommonMark extraction policy. Heading/list structure, inline code, links and fenced or
 * indented code blocks are extracted from the AST by default. In EXTRACT_DOM mode, the complete
 * parsed document is rendered to HTML and extracted with the HTML strategy. Raw HTML is never
 * executed or fetched.
 * These are extraction policies, not byte-exact equivalence to upstream's Markdown-to-HTML pipeline.
 * @param tables whether GitHub-style pipe tables are parsed as Table elements, default true
 * @param maxNesting positive maximum AST depth in [1, 256], default 128; checked after parsing
 * @param rawHtml raw-HTML block/inline handling, default PRESERVE_TEXT
 * @param tableLimits immutable row/cell budgets for each pipe table and, in EXTRACT_DOM mode, each HTML table
 */
public record MarkdownOptions(boolean tables, int maxNesting, RawHtml rawHtml, TableLimits tableLimits) {
    /** Raw HTML handling and selection of the extraction pipeline. */
    public enum RawHtml {
        /** Retains literal markup and reports one warning per document. */ PRESERVE_TEXT,
        /** Omits raw HTML nodes; surrounding Markdown text remains. */ SKIP,
        /**
         * Requires the optional {@code docweave-html} module; without it extraction fails with
         * {@link PartitionException.Code#UNSUPPORTED_FORMAT}. Renders CommonMark and raw HTML together, then extracts
         * their combined DOM. Heading/list hierarchy, links, code and tables follow HTML extraction rules; prose
         * classification may differ from AST mode. Images contribute alt text, without AST image-reference metadata;
         * code-info and pipe-table alignment metadata are not retained. HTML depth, visibility and
         * header/footer policies apply in addition to Markdown AST and table budgets. Nested-table metadata
         * preservation is supported only in this mode and requires an HTML service advertising that capability;
         * other modes or incapable services fail with UNSUPPORTED_OPTION when preservation is selected.
         */
        EXTRACT_DOM
    }

    /**
     * Validates AST policies without parsing input.
     * @param tables enables pipe tables
     * @param maxNesting AST depth limit in [1, 256]
     * @param rawHtml nonnull raw-HTML handling
     * @param tableLimits nonnull per-table budgets
     * @throws IllegalArgumentException if nesting is outside its allowed range
     * @throws NullPointerException if a required policy is null
     */
    public MarkdownOptions {
        if (maxNesting < 1 || maxNesting > 256) {
            throw new IllegalArgumentException("maxNesting must be in [1, 256]");
        }
        java.util.Objects.requireNonNull(rawHtml, "rawHtml");
        java.util.Objects.requireNonNull(tableLimits, "tableLimits");
    }

    /**
     * Creates default CommonMark extraction policies.
     * @return pipe tables enabled, depth 128, raw markup preserved as text
     */
    public static MarkdownOptions defaults() {
        return new MarkdownOptions(true, 128, RawHtml.PRESERVE_TEXT, TableLimits.defaults());
    }
}
