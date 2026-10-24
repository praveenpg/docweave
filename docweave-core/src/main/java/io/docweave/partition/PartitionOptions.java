package io.docweave.partition;

import java.net.URI;
import java.nio.charset.Charset;

/**
 * Immutable native document policies, reusable concurrently.
 * Defaults cap input at 10 MiB, output at 100,000 elements, and body DOM depth at 256.
 * Byte decoding uses an explicit charset, a BOM, an XML declaration/signature or HTML meta charset, then UTF-8 in that
 * order; a conflicting explicit charset/BOM fails. Already-decoded Text sources and binary DOCX payloads ignore charset
 * policies.
 *
 * <p>HTML parsing is semantic DOM extraction, not browser rendering: no assets are fetched, styles
 * are not evaluated, and no page coordinates or languages are invented. TXT classification uses
 * simple length/punctuation/list heuristics, not upstream NLP parity.</p>
 *
 * @param format explicit format, or null for detection; structured filename hints precede HTML sniffing
 * @param charset explicit byte charset, or null for BOM/meta/UTF-8 detection
 * @param maxInputBytes positive byte budget; decoded text uses its UTF-8 footprint
 * @param maxElements maximum emitted semantic elements
 * @param maxHtmlDepth body-relative nesting limit in [1, 256]
 * @param skipHeadersAndFooters whether HTML header/footer subtrees are excluded
 * @param paragraphMode TXT paragraph boundary policy
 * @param classifyText whether TXT heuristics are enabled; false emits UncategorizedText
 * @param baseUri optional absolute HTTP(S) URI used only to resolve links
 * @param markdownOptions immutable CommonMark extraction/depth policies
 * @param docxOptions immutable DOCX archive/extraction policies
 * @param xmlOptions immutable XML extraction and resource policies
 * @param delimitedOptions immutable CSV/TSV header/width/cell budgets
 * @param txtOptions immutable TXT minimum/maximum partition sizes
 * @param nestedTableOptions immutable nested-table representation and preservation budgets
 */
public record PartitionOptions(DocumentFormat format, Charset charset, int maxInputBytes, int maxElements,
        int maxHtmlDepth, boolean skipHeadersAndFooters, ParagraphMode paragraphMode, boolean classifyText, URI baseUri,
        MarkdownOptions markdownOptions, DelimitedOptions delimitedOptions, XmlOptions xmlOptions,
        DocxOptions docxOptions, TxtOptions txtOptions, NestedTableOptions nestedTableOptions) {
    /** TXT paragraph grouping policy. */
    public enum ParagraphMode {
        /** Groups wrapped lines until a blank line; list items remain separate. */ BLANK_LINES,
        /** Emits each nonblank line independently. */ LINE_BREAKS,
        /**
         * Joins soft wraps, separating short title-case/all-capital lines and completed sentences
         * followed by an unindented uppercase, title-case or uncased-letter line. Blank lines and
         * list boundaries remain significant. This is deterministic grouping without NLP.
         */
        AUTO
    }

    /**
     * Validates limits and resolution URI without reading any source.
     * @param format explicit format or null
     * @param charset explicit byte charset or null
     * @param maxInputBytes positive budget less than Integer.MAX_VALUE
     * @param maxElements positive emitted-element limit
     * @param maxHtmlDepth HTML nesting bound in [1, 256]
     * @param skipHeadersAndFooters whether to skip HTML header/footer subtrees
     * @param paragraphMode nonnull TXT grouping policy
     * @param classifyText whether TXT heuristic categories are enabled
     * @param baseUri optional HTTP(S) resolution URI
     * @param markdownOptions nonnull CommonMark policies
     * @param docxOptions nonnull DOCX policies
     * @param xmlOptions nonnull XML policies
     * @param delimitedOptions nonnull CSV/TSV policies
     * @param txtOptions nonnull TXT size policies
     * @param nestedTableOptions nonnull nested-table policies
     * @throws IllegalArgumentException if limits or the resolution URI are invalid
     * @throws NullPointerException if a required policy is null
     */
    public PartitionOptions {
        if (maxInputBytes < 1 || maxInputBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxInputBytes must be in [1, Integer.MAX_VALUE)");
        }
        if (maxElements < 1) {
            throw new IllegalArgumentException("maxElements must be positive");
        }
        if (maxHtmlDepth < 1 || maxHtmlDepth > 256) {
            throw new IllegalArgumentException("maxHtmlDepth must be in [1, 256]");
        }
        if (paragraphMode == null) {
            throw new NullPointerException("paragraphMode");
        }
        java.util.Objects.requireNonNull(markdownOptions, "markdownOptions");
        java.util.Objects.requireNonNull(delimitedOptions, "delimitedOptions");
        java.util.Objects.requireNonNull(xmlOptions, "xmlOptions");
        java.util.Objects.requireNonNull(docxOptions, "docxOptions");
        java.util.Objects.requireNonNull(txtOptions, "txtOptions");
        java.util.Objects.requireNonNull(nestedTableOptions, "nestedTableOptions");
        if (baseUri != null
                && (!baseUri.isAbsolute()
                        || !("https".equalsIgnoreCase(baseUri.getScheme())
                                || "http".equalsIgnoreCase(baseUri.getScheme())))) {
            throw new IllegalArgumentException("baseUri must be an absolute HTTP(S) URI");
        }
    }

    /**
     * Constructs options with the previous policy set and default nested-table flattening.
     * @param format explicit format or null
     * @param charset explicit charset or null
     * @param maxInputBytes positive encoded-input limit
     * @param maxElements positive output limit
     * @param maxHtmlDepth HTML nesting bound in [1, 256]
     * @param skipHeadersAndFooters whether HTML header/footer subtrees are excluded
     * @param paragraphMode nonnull TXT grouping policy
     * @param classifyText whether TXT classification is enabled
     * @param baseUri optional absolute HTTP(S) link-resolution URI
     * @param markdownOptions nonnull Markdown policies
     * @param delimitedOptions nonnull delimited-file policies
     * @param xmlOptions nonnull XML policies
     * @param docxOptions nonnull DOCX policies
     * @param txtOptions nonnull TXT size policies
     * @throws IllegalArgumentException if a limit or URI is invalid
     * @throws NullPointerException if a required policy is null
     */
    public PartitionOptions(final DocumentFormat format, final Charset charset, final int maxInputBytes,
            final int maxElements, final int maxHtmlDepth, final boolean skipHeadersAndFooters,
            final ParagraphMode paragraphMode, final boolean classifyText, final URI baseUri,
            final MarkdownOptions markdownOptions, final DelimitedOptions delimitedOptions, final XmlOptions xmlOptions,
            final DocxOptions docxOptions, final TxtOptions txtOptions) {
        this(format, charset, maxInputBytes, maxElements, maxHtmlDepth, skipHeadersAndFooters, paragraphMode,
                classifyText, baseUri, markdownOptions, delimitedOptions, xmlOptions, docxOptions, txtOptions,
                NestedTableOptions.defaults());
    }

    /**
     * Constructs options with the original policy set and unrestricted TXT partition sizes.
     * @param format explicit format or null
     * @param charset explicit charset or null
     * @param maxInputBytes positive encoded-input limit
     * @param maxElements positive output limit
     * @param maxHtmlDepth HTML nesting bound in [1, 256]
     * @param skipHeadersAndFooters whether HTML header/footer subtrees are excluded
     * @param paragraphMode nonnull TXT grouping policy
     * @param classifyText whether TXT classification is enabled
     * @param baseUri optional absolute HTTP(S) link-resolution URI
     * @param markdownOptions nonnull Markdown policies
     * @param delimitedOptions nonnull delimited-file policies
     * @param xmlOptions nonnull XML policies
     * @param docxOptions nonnull DOCX policies
     * @throws IllegalArgumentException if a limit or URI is invalid
     * @throws NullPointerException if a required policy is null
     */
    public PartitionOptions(final DocumentFormat format, final Charset charset, final int maxInputBytes,
            final int maxElements, final int maxHtmlDepth, final boolean skipHeadersAndFooters,
            final ParagraphMode paragraphMode, final boolean classifyText, final URI baseUri,
            final MarkdownOptions markdownOptions, final DelimitedOptions delimitedOptions, final XmlOptions xmlOptions,
            final DocxOptions docxOptions) {
        this(format, charset, maxInputBytes, maxElements, maxHtmlDepth, skipHeadersAndFooters, paragraphMode,
                classifyText, baseUri, markdownOptions, delimitedOptions, xmlOptions, docxOptions,
                TxtOptions.defaults());
    }

    /**
     * Returns the bounded default processing policy.
     * @return automatic detection, bounded defaults, blank-line paragraphs and TXT classification
     */
    public static PartitionOptions defaults() {
        return builder().build();
    }

    /**
     * Creates a mutable options builder behind an interface.
     * @return a new caller-owned options builder
     */
    public static Builder builder() {
        return new DefaultPartitionOptionsBuilder();
    }

    /** Mutable non-thread-safe builder; {@link #build()} snapshots and validates its values. */
    public interface Builder {
        /**
         * Sets explicit format routing.
         * @param value explicit native format, or null for detection
         * @return this builder
         */
        Builder format(final DocumentFormat value);
        /**
         * Sets strict byte decoding; Text sources are already decoded.
         * @param value charset, or null for BOM/meta/UTF-8 detection
         * @return this builder
         */
        Builder charset(final Charset value);
        /**
         * Sets the maximum encoded input footprint.
         * @param value positive byte budget less than Integer.MAX_VALUE
         * @return this builder
         */
        Builder maxInputBytes(final int value);
        /**
         * Sets the maximum output element count.
         * @param value positive element budget
         * @return this builder
         */
        Builder maxElements(final int value);
        /**
         * Sets body-relative DOM depth.
         * @param value nesting bound in [1, 256]
         * @return this builder
         */
        Builder maxHtmlDepth(final int value);
        /**
         * Controls HTML header/footer exclusion.
         * @param value true to discard both subtrees, false to emit Header/Footer content
         * @return this builder
         */
        Builder skipHeadersAndFooters(final boolean value);
        /**
         * Sets TXT paragraph boundaries.
         * @param value nonnull grouping mode
         * @return this builder
         */
        Builder paragraphMode(final ParagraphMode value);
        /**
         * Controls heuristic categories for TXT only.
         * @param value false to emit UncategorizedText without removing list prefixes
         * @return this builder
         */
        Builder classifyText(final boolean value);
        /**
         * Configures relative-link resolution without fetching anything.
         * @param value absolute HTTP(S) URI, or null to retain unresolved relative links
         * @return this builder
         */
        Builder baseUri(final URI value);
        /**
         * Sets TXT partition sizes without changing grouping or classification.
         * @param value nonnull minimum/maximum code-point policy
         * @return this builder
         */
        Builder txtOptions(final TxtOptions value);

        /**
         * Selects nested-table representation and budgets. Legacy custom builders may accept
         * FLATTEN without storing inactive budgets, but must explicitly reject preservation.
         * @param value nonnull immutable nested-table policy
         * @return this builder
         * @throws NullPointerException if value is null
         * @throws UnsupportedOperationException if a custom builder does not implement preservation
         */
        default Builder nestedTableOptions(final NestedTableOptions value) {
            java.util.Objects.requireNonNull(value, "value");
            if (value.mode() != NestedTableOptions.Mode.FLATTEN) {
                throw new UnsupportedOperationException(
                        "This options builder does not implement nested-table preservation");
            }
            return this;
        }

        /**
         * Sets immutable CommonMark policies.
         * @param value nonnull Markdown extraction/depth policies
         * @return this builder
         */
        Builder markdownOptions(final MarkdownOptions value);
        /**
         * Sets immutable CSV/TSV policies.
         * @param value nonnull header, consistency and resource policies
         * @return this builder
         */
        Builder delimitedOptions(final DelimitedOptions value);
        /**
         * Sets XML policies.
         * @param value nonnull extraction and resource policies
         * @return this builder
         */
        Builder xmlOptions(final XmlOptions value);
        /**
         * Sets DOCX archive and extraction policies.
         * @param value nonnull DOCX policies
         * @return this builder
         */
        Builder docxOptions(final DocxOptions value);
        /**
         * Builds validated options without mutating this builder.
         * @return immutable processing policy
         * @throws IllegalArgumentException if any limit or URI is invalid
         * @throws NullPointerException if a required policy is null
         */
        PartitionOptions build();
    }
}
