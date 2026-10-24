package io.docweave.partition;

import io.docweave.partition.PartitionOptions.Builder;
import io.docweave.partition.PartitionOptions.ParagraphMode;

import java.net.URI;
import java.nio.charset.Charset;

/** Mutable non-thread-safe builder; {@link #build()} snapshots and validates its values. */
final class DefaultPartitionOptionsBuilder implements PartitionOptions.Builder {
    /** Explicit document format override, or {@code null} for automatic format detection. */
    private DocumentFormat format;
    /** Explicit character encoding override, or {@code null} for BOM/meta/UTF-8 detection. */
    private Charset charset;
    /** Maximum allowed input byte footprint. */
    private int maxBytes = 10 * 1024 * 1024;
    /** Maximum allowed emitted elements per document. */
    private int maxElements = 100_000;
    /** Maximum allowed HTML body-relative DOM nesting depth. */
    private int maxDepth = 256;
    /** Whether HTML header and footer subtrees are excluded. */
    private boolean skipHeaders;
    /** Plain-text paragraph grouping mode. */
    private ParagraphMode mode = ParagraphMode.BLANK_LINES;
    /** Whether heuristic text classification is enabled for TXT documents. */
    private boolean classify = true;
    /** Optional base URI used to resolve relative links in HTML and Markdown. */
    private URI base;
    /** Format-specific options for Markdown partitioning. */
    private MarkdownOptions markdown = MarkdownOptions.defaults();
    /** Format-specific options for CSV and TSV delimited table partitioning. */
    private DelimitedOptions delimited = DelimitedOptions.defaults();
    /** Format-specific options for XML partitioning. */
    private XmlOptions xml = XmlOptions.defaults();
    /** Format-specific options for DOCX partitioning. */
    private DocxOptions docx = DocxOptions.defaults();
    /** Format-specific options for plain-text partitioning. */
    private TxtOptions txt = TxtOptions.defaults();
    /** Nested-table handling and metadata preservation options. */
    private NestedTableOptions nested = NestedTableOptions.defaults();

    /** Creates a builder with bounded default policies. */
    DefaultPartitionOptionsBuilder() {
    }

    /**
     * Sets explicit format routing.
     * @param value native text format, or null for detection
     * @return this builder
     */
    public Builder format(final DocumentFormat value) {
        format = value;
        return this;
    }

    /**
     * Sets strict byte decoding; Text sources are already decoded.
     * @param value charset, or null for BOM/meta/UTF-8 detection
     * @return this builder
     */
    public Builder charset(final Charset value) {
        charset = value;
        return this;
    }

    /**
     * Sets the maximum encoded input footprint.
     * @param value positive byte budget less than Integer.MAX_VALUE
     * @return this builder
     */
    public Builder maxInputBytes(final int value) {
        maxBytes = value;
        return this;
    }

    /**
     * Sets the maximum output element count.
     * @param value positive element budget
     * @return this builder
     */
    public Builder maxElements(final int value) {
        maxElements = value;
        return this;
    }

    /**
     * Sets body-relative DOM depth.
     * @param value nesting bound in [1, 256]
     * @return this builder
     */
    public Builder maxHtmlDepth(final int value) {
        maxDepth = value;
        return this;
    }

    /**
     * Controls HTML header/footer exclusion.
     * @param value true to discard both subtrees, false to emit Header/Footer content
     * @return this builder
     */
    public Builder skipHeadersAndFooters(final boolean value) {
        skipHeaders = value;
        return this;
    }

    /**
     * Sets TXT paragraph boundaries.
     * @param value nonnull grouping mode
     * @return this builder
     */
    public Builder paragraphMode(final ParagraphMode value) {
        mode = value;
        return this;
    }

    /**
     * Controls heuristic categories for TXT only.
     * @param value false to emit UncategorizedText without removing list prefixes
     * @return this builder
     */
    public Builder classifyText(final boolean value) {
        classify = value;
        return this;
    }

    /**
     * Configures relative-link resolution without fetching anything.
     * @param value absolute HTTP(S) URI, or null to retain unresolved relative links
     * @return this builder
     */
    public Builder baseUri(final URI value) {
        base = value;
        return this;
    }

    /**
     * Sets plain-text partitioning options.
     *
     * @param value non-null TXT options
     * @return this builder
     */
    public Builder txtOptions(final TxtOptions value) {
        txt = value;
        return this;
    }

    /**
     * Sets nested-table handling and metadata preservation options.
     *
     * @param value non-null nested-table options
     * @return this builder
     */
    @Override
    public Builder nestedTableOptions(final NestedTableOptions value) {
        nested = java.util.Objects.requireNonNull(value, "value");
        return this;
    }

    /**
     * Sets Markdown partitioning options.
     *
     * @param value non-null Markdown options
     * @return this builder
     */
    public Builder markdownOptions(final MarkdownOptions value) {
        markdown = value;
        return this;
    }

    /**
     * Sets DOCX partitioning options.
     *
     * @param value non-null DOCX options
     * @return this builder
     */
    public Builder docxOptions(final DocxOptions value) {
        docx = value;
        return this;
    }

    /**
     * Sets XML partitioning options.
     *
     * @param value non-null XML options
     * @return this builder
     */
    public Builder xmlOptions(final XmlOptions value) {
        xml = value;
        return this;
    }

    /**
     * Sets CSV and TSV delimited table partitioning options.
     *
     * @param value non-null delimited options
     * @return this builder
     */
    public Builder delimitedOptions(final DelimitedOptions value) {
        delimited = value;
        return this;
    }

    /**
     * Builds validated options without mutating this builder.
     * @return immutable processing policy
     * @throws IllegalArgumentException if any limit or URI is invalid
     * @throws NullPointerException if paragraphMode is null
     */
    public PartitionOptions build() {
        return new PartitionOptions(format, charset, maxBytes, maxElements, maxDepth, skipHeaders, mode, classify, base,
                markdown, delimited, xml, docx, txt, nested);
    }
}
