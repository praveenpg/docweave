package dev.docweave.chunking;

import dev.docweave.core.Element;

import java.util.List;

/**
 * Stateless, thread-safe chunking over document-order elements.
 * Ordinary element whitespace is collapsed and elements are joined with blank lines; CodeSnippet
 * whitespace is retained. Size and overlap budgets count Unicode code points, including supplementary
 * characters as one. Tables are isolated by default; disabling isolation allows mixed composites.
 * Overlap never crosses table-bearing groups, including mixed groups. Mixed composites use stored
 * table text; table HTML/cell geometry remain only in provenance when enabled.
 * Chunk metadata follows field-specific consolidation rules; original elements are retained by default.
 *
 * <p>All processing is synchronous and stages input elements in memory. Generated chunk and
 * split-table IDs are UUIDs, so compare content/metadata rather than byte-exact output across runs.
 * HTML-backed tables use authoritative cell text and compact markup. Rows and span-connected
 * groups stay intact when they fit; larger rows split at cells, then at escaped Unicode text.
 * Each structural fragment fits both the text and HTML hard limit. Below 50 code points, oversized
 * HTML uses text-only fallback. Explicit skip-table-chunking passes tables through unchanged and
 * exempts them from budgets. Structural splits do not apply narrative overlap.</p>
 *
 * <p>Leading headers repeat by default when they fit and align with the fragment's column origin;
 * otherwise repetition is omitted and reported by num_carried_over_header_rows. Oversized span
 * groups use clipped spans and blank covering cells without duplicating owned data. Source-cell
 * coordinates, effective spans and normalized-cell text offsets are available in the extension
 * metadata table_cell_fragments; offsets are half-open Unicode code-point ranges. Synthetic
 * repeated headers are excluded from that ownership list. Original raw HTML/metadata remain in
 * provenance when enabled. Whole-table links/cell arrays are removed from split metadata.</p>
 *
 * <p>Structural parsing caps input at 16,777,216 code points, 100,000 rows, 1,000,000 source cells
 * and materialized logical positions, 10,000 columns and DOM depth 256. These are validation
 * budgets, not heap limits or deadlines. Nested tables and nonempty captions must first be
 * partitioned/flattened or passed through with skip-table-chunking. Inline markup and cell
 * whitespace normalize to text; semantic cell boundaries and effective spans are preserved.</p>
 *
 * <pre>{@code
 * var options = ChunkOptions.builder().maxCharacters(1500)
 *     .newAfterCharacters(1000).combineTextUnderCharacters(0).build();
 * var chunks = Chunker.create().byTitle(elements, options);
 * }</pre>
 */
public interface Chunker {
    /**
     * Creates the built-in thread-safe implementation behind this contract.
     * @return a reusable Chunker service
     */
    static Chunker create() {
        return new DefaultChunker();
    }

    /** Supported character-budget grouping strategies. */
    enum Strategy {
        /** Groups sequential elements subject to size and table boundaries. */ BASIC,
        /** Adds heading and optional page boundaries, then combines small sections if configured. */ BY_TITLE
    }

    /**
     * Groups sequential elements without title/page boundaries.
     * @param elements elements in reading order
     * @param options character budgets, overlap and provenance policy
     * @throws java.util.concurrent.CancellationException if interrupted at a cooperative checkpoint;
     *         the interrupt flag remains set and no partial list is returned
     * @return immutable ordered chunks
     * @throws NullPointerException if arguments or an element are null
     * @throws IllegalArgumentException if metadata/HTML is malformed, a structural budget is exceeded, or escaped
     *         markup cannot fit
     */
    default List<Element> basic(final List<Element> elements, final ChunkOptions options) {
        return chunk(elements, Strategy.BASIC, options);
    }

    /**
     * Stages chunks at headings and optionally page transitions, then combines small sections.
     * Set {@code combineTextUnderCharacters} to zero to preserve staged section/page boundaries.
     * Missing page numbers continue the last known page; any different explicit number starts a boundary.
     * @param elements elements in reading order
     * @param options section, page, character and provenance policy
     * @throws java.util.concurrent.CancellationException if interrupted at a cooperative checkpoint;
     *         the interrupt flag remains set and no partial list is returned
     * @return immutable ordered chunks
     * @throws NullPointerException if arguments or an element are null
     * @throws IllegalArgumentException if metadata/HTML is malformed, a structural budget is exceeded, or escaped
     *         markup cannot fit
     */
    default List<Element> byTitle(final List<Element> elements, final ChunkOptions options) {
        return chunk(elements, Strategy.BY_TITLE, options);
    }

    /**
     * Runs the selected strategy. Empty/nontext pre-chunks emit no narrative chunks.
     * Only oversized elements are split mid-text; continuation chunks carry {@code is_continuation}.
     * Metadata unsuitable for combined chunks, such as coordinates and parent IDs, is dropped.
     * @param elements ordered elements; caller must not mutate the list during this call
     * @param strategy basic or title-aware grouping
     * @param options immutable budgets and behavior
     * @throws java.util.concurrent.CancellationException if interrupted at a cooperative checkpoint;
     *         the interrupt flag remains set and no partial list is returned
     * @return immutable chunks in source order
     * @throws NullPointerException if any argument or contained element is null
     * @throws IllegalArgumentException if metadata/HTML is malformed, a structural budget is exceeded, or escaped
     *         markup cannot fit
     */
    List<Element> chunk(final List<Element> elements, final Strategy strategy, final ChunkOptions options);
}
