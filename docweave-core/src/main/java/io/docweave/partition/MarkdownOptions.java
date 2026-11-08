package io.docweave.partition;

import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable CommonMark extraction policy. Heading/list structure, inline code, links and fenced or
 * indented code blocks are extracted from the AST by default. In EXTRACT_DOM mode, the complete
 * parsed document is rendered to HTML and extracted with the HTML strategy. Raw HTML is never
 * executed or fetched.
 *
 * <p>AST mode reports bold/italic spans as {@code emphasized_text_contents}, {@code emphasized_text_tags}
 * ({@code "b"}, {@code "i"} or {@code "bi"}) and code-point {@code emphasized_text_start_indexes}. Extracted text and
 * element order are unaffected. EXTRACT_DOM mode follows the HTML strategy and does not emit emphasis metadata.</p>
 *
 * <p>Syntax beyond CommonMark and pipe tables is opt-in through {@link Extension}; the default set is empty, so
 * default output is unchanged.</p>
 * These are extraction policies, not byte-exact equivalence to upstream's Markdown-to-HTML pipeline.
 * @param tables whether GitHub-style pipe tables are parsed as Table elements, default true
 * @param maxNesting positive maximum AST depth in [1, 256], default 128; checked after parsing
 * @param rawHtml raw-HTML block/inline handling, default PRESERVE_TEXT
 * @param tableLimits immutable row/cell budgets for each pipe table and, in EXTRACT_DOM mode, each HTML table
 * @param extensions immutable opt-in syntax extensions, default empty
 */
public record MarkdownOptions(
        boolean tables, int maxNesting, RawHtml rawHtml, TableLimits tableLimits, Set<Extension> extensions) {
    /** Raw HTML handling and selection of the extraction pipeline. */
    public enum RawHtml {
        /** Retains literal markup and reports one warning per document. */ PRESERVE_TEXT,
        /** Omits raw HTML nodes; surrounding Markdown text remains. */ SKIP,
        /**
         * Requires the optional {@code docweave-html} module; without it extraction fails with
         * {@link PartitionException.Code#UNSUPPORTED_FORMAT}. Renders CommonMark and raw HTML together, then extracts
         * their combined DOM. Heading/list hierarchy, links, code and tables follow HTML extraction rules; prose
         * classification may differ from AST mode. Images contribute alt text, without AST image-reference metadata;
         * code-info, pipe-table alignment, emphasis, strikethrough and task-state metadata are not retained. HTML
         * depth, visibility and header/footer policies apply in addition to Markdown AST and table budgets.
         * Nested-table metadata preservation is supported only in this mode and requires an HTML service advertising
         * that capability; other modes or incapable services fail with UNSUPPORTED_OPTION when preservation is
         * selected.
         */
        EXTRACT_DOM
    }

    /**
     * Opt-in syntax supported by the bundled CommonMark parser extensions. Each is disabled by default; without it the
     * syntax stays literal CommonMark text. In EXTRACT_DOM mode the same syntax is parsed and rendered to HTML, so text
     * and autolink metadata follow the HTML strategy, but the AST-only metadata described below is not emitted.
     */
    public enum Extension {
        /**
         * GitHub task-list items: a leading {@code [ ]} or {@code [x]} marker is removed from the item text and AST
         * mode records {@code task_list_item_checked} on the item's first element.
         */
        TASK_LIST_ITEMS,
        /**
         * GitHub strikethrough with double tildes only ({@code ~~text~~}); single tildes stay literal. Markers are
         * removed from the text and AST mode records {@code strikethrough_texts} and code-point
         * {@code strikethrough_start_indexes}.
         */
        STRIKETHROUGH,
        /**
         * Bare {@code http(s)://} URLs, {@code www.} hosts and e-mail addresses become links. Text is unchanged; links
         * use the existing link metadata, with {@code http://} prepended to {@code www.} hosts and {@code mailto:} to
         * addresses.
         */
        AUTOLINKS,
        /**
         * Footnote references {@code [^label]} and block definitions {@code [^label]: text}, with continuation blocks
         * indented by four spaces. A reference is replaced by {@code [n]}, numbered in order of first reference, and AST
         * mode records {@code footnote_reference_labels}, {@code footnote_reference_numbers} and code-point
         * {@code footnote_reference_start_indexes}. A definition stays where it is written; its first element carries
         * {@code footnote_label} and, when referenced, {@code footnote_number}, and its later blocks take that element
         * as {@code parent_id}. A reference without a definition stays literal. Inline {@code ^[...]} notes are not
         * supported. EXTRACT_DOM renders the notes as an ordered list at the end, without footnote metadata.
         */
        FOOTNOTES,
        /**
         * Definition lists in the PHP Markdown Extra form: one or more term lines followed by a line starting with
         * {@code :} and a space; a blank line may separate them. Each paragraph line before the first marker is a term.
         * Definition content continues on lines indented to the content column. Terms become {@code ListItem}
         * elements with {@code definition_list_role="term"}; a definition's first element is a {@code ListItem} (when
         * it is a paragraph) with {@code definition_list_role="definition"}, {@code definition_term_ids} and the first
         * term as {@code parent_id}. Its later blocks take that element as {@code parent_id}. EXTRACT_DOM renders
         * {@code <dl>} markup and follows the HTML strategy.
         */
        DEFINITION_LISTS,
        /**
         * A leading front-matter block delimited by {@code ---} lines (closed by {@code ---} or {@code ...}), parsed as
         * a restricted, data-only YAML subset: string scalars, plain/quoted values, block and flow sequences of scalars,
         * and literal or folded block scalars. Nothing is resolved, typed or instantiated. Well-formed values are added
         * to every element as the {@code front_matter} object, and the block contributes no element. Malformed or
         * oversized front matter is emitted unchanged as one {@code UncategorizedText} element with a
         * {@link PartitionResult.WarningCode#MARKDOWN_FRONT_MATTER_INVALID} warning. Without a closing delimiter the
         * input is not front matter and is parsed as ordinary Markdown.
         */
        FRONT_MATTER
    }

    /**
     * Validates AST policies without parsing input.
     * @param tables enables pipe tables
     * @param maxNesting AST depth limit in [1, 256]
     * @param rawHtml nonnull raw-HTML handling
     * @param tableLimits nonnull per-table budgets
     * @param extensions nonnull opt-in syntax extensions, copied
     * @throws IllegalArgumentException if nesting is outside its allowed range
     * @throws NullPointerException if a required policy or extension is null
     */
    public MarkdownOptions {
        if (maxNesting < 1 || maxNesting > 256) {
            throw new IllegalArgumentException("maxNesting must be in [1, 256]");
        }
        java.util.Objects.requireNonNull(rawHtml, "rawHtml");
        java.util.Objects.requireNonNull(tableLimits, "tableLimits");
        java.util.Objects.requireNonNull(extensions, "extensions");
        // Copy into declaration order so equality, iteration and toString are deterministic.
        final var copy = EnumSet.noneOf(Extension.class);
        copy.addAll(extensions);
        extensions = java.util.Collections.unmodifiableSet(copy);
    }

    /**
     * Constructs policies with the original component set and no syntax extensions.
     * @param tables enables pipe tables
     * @param maxNesting AST depth limit in [1, 256]
     * @param rawHtml nonnull raw-HTML handling
     * @param tableLimits nonnull per-table budgets
     * @throws IllegalArgumentException if nesting is outside its allowed range
     * @throws NullPointerException if a required policy is null
     */
    public MarkdownOptions(
            final boolean tables, final int maxNesting, final RawHtml rawHtml, final TableLimits tableLimits) {
        this(tables, maxNesting, rawHtml, tableLimits, Set.of());
    }

    /**
     * Creates default CommonMark extraction policies.
     * @return pipe tables enabled, depth 128, raw markup preserved as text, no syntax extensions
     */
    public static MarkdownOptions defaults() {
        return new MarkdownOptions(true, 128, RawHtml.PRESERVE_TEXT, TableLimits.defaults());
    }

    /**
     * Returns a copy with the given opt-in syntax extensions, replacing any previous selection.
     * @param selected extensions to enable; empty disables all
     * @return new policies with otherwise identical settings
     * @throws NullPointerException if the set or an element is null
     */
    public MarkdownOptions withExtensions(final Set<Extension> selected) {
        return new MarkdownOptions(tables, maxNesting, rawHtml, tableLimits, selected);
    }
}
