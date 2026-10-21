package dev.docweave.chunking;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static dev.docweave.chunking.UnicodeText.*;

/**
 * Stateless, thread-safe chunking over document-order elements.
 * Ordinary element whitespace is collapsed and elements are joined with blank lines; CodeSnippet
 * whitespace is retained. Size and overlap budgets count Unicode code points, including supplementary
 * characters as one. Tables are isolated by default; disabling isolation allows mixed composites.
 * Overlap never crosses table-bearing groups, including mixed groups. Mixed composites use stored
 * table text; table HTML/cell geometry remain only in provenance when enabled.
 * Chunk metadata follows field-specific consolidation rules; original elements are retained by default, except
 * oversized singleton sources actually split into multiple chunks; their provenance omission is explicit in metadata.
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
final class DefaultChunker implements Chunker {

    /** Shared JSON codec used to encode compressed original-element provenance. */
    private final ElementJson json = ElementJson.create();

    /** Creates a reusable chunker without document-specific state. */
    DefaultChunker() {
    }

    /**
     * Groups sequential elements without title/page boundaries.
     * @param elements elements in reading order
     * @param options character budgets, overlap and provenance policy
     * @return immutable ordered chunks
     * @throws NullPointerException if arguments or an element are null
     * @throws IllegalArgumentException if metadata/HTML is malformed, a structural budget is exceeded, or escaped
     *         markup cannot fit
     */
    public List<Element> basic(final List<Element> elements, final ChunkOptions options) {
        return chunk(elements, Strategy.BASIC, options);
    }

    /**
     * Stages chunks at headings and optionally page transitions, then combines small sections.
     * Set {@code combineTextUnderCharacters} to zero to preserve staged section/page boundaries.
     * Missing page numbers continue the last known page; any different explicit number starts a boundary.
     * @param elements elements in reading order
     * @param options section, page, character and provenance policy
     * @return immutable ordered chunks
     * @throws NullPointerException if arguments or an element are null
     * @throws IllegalArgumentException if metadata/HTML is malformed, a structural budget is exceeded, or escaped
     *         markup cannot fit
     */
    public List<Element> byTitle(final List<Element> elements, final ChunkOptions options) {
        return chunk(elements, Strategy.BY_TITLE, options);
    }

    /**
     * Runs the selected strategy. Empty/nontext pre-chunks emit no narrative chunks.
     * Only oversized elements are split mid-text; continuation chunks carry {@code is_continuation}.
     * Metadata unsuitable for combined chunks, such as coordinates and parent IDs, is dropped.
     * @param elements ordered elements; caller must not mutate the list during this call
     * @param strategy basic or title-aware grouping
     * @param options immutable budgets and behavior
     * @return immutable chunks in source order
     * @throws NullPointerException if any argument or contained element is null
     * @throws IllegalArgumentException if metadata/HTML is malformed, a structural budget is exceeded, or escaped
     *         markup cannot fit
     */
    public List<Element> chunk(final List<Element> elements, final Strategy strategy, final ChunkOptions options) {
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(options, "options");
        ChunkCancellation.check();
        var preChunks = stage(elements, strategy, options);
        if (strategy == Strategy.BY_TITLE) {
            preChunks = combine(preChunks, options);
        }
        final var result = new ArrayList<Element>();
        for (final var preChunk : preChunks) {
            ChunkCancellation.check();
            emit(preChunk, options, result);
        }
        ChunkCancellation.check();
        return List.copyOf(result);
    }

    /**
     * Partitions input elements into initial pre-chunks according to strategy boundaries and character budgets.
     *
     * @param elements ordered input elements
     * @param strategy active chunking strategy
     * @param options chunking options and budgets
     * @return ordered list of staged pre-chunks
     */
    private List<PreChunk> stage(final List<Element> elements, final Strategy strategy, final ChunkOptions options) {
        final var result = new ArrayList<PreChunk>();
        final var pending = new ArrayList<Element>();
        String prefix = "";
        Object page = 1;
        boolean first = true;
        // Stage elements into pre-chunks, breaking at title/page boundaries or when appending the next element exceeds
        // character budgets.
        for (final var element : elements) {
            ChunkCancellation.check();
            Objects.requireNonNull(element, "element");
            final Object nextPage = element.metadata().get("page_number");
            final boolean pageBoundary = !first && nextPage != null && !Objects.equals(nextPage, page);
            if (first) {
                page = nextPage == null ? 1 : nextPage;
            } else if (nextPage != null) {
                page = nextPage;
            }
            first = false;
            final boolean semanticBoundary =
                    strategy == Strategy.BY_TITLE
                    && (element.type() == ElementType.Title || !options.multipageSections() && pageBoundary);
            if (semanticBoundary || !fits(pending, prefix, element, options)) {
                if (!pending.isEmpty()) {
                    final var previous = new PreChunk(List.copyOf(pending), prefix);
                    result.add(previous);
                    prefix = options.overlapAll() && !previous.tableFamily()
                                     ? strip(tail(previous.text(), options.overlap()))
                                     : "";
                    pending.clear();
                }
            }
            if (tableFamily(element)) {
                prefix = "";
            }
            pending.add(element);
        }
        if (!pending.isEmpty()) {
            result.add(new PreChunk(List.copyOf(pending), prefix));
        }
        return result;
    }

    /**
     * Determines whether {@code next} can be appended to {@code pending} within soft/hard budgets and table isolation.
     *
     * @param pending elements currently staged in the active pre-chunk
     * @param prefix overlap prefix carried from the preceding pre-chunk
     * @param next candidate element to append
     * @param options chunking options and budgets
     * @return {@code true} if {@code next} fits in the current pre-chunk
     */
    private boolean fits(final List<Element> pending, String prefix, final Element next, final ChunkOptions options) {
        if (pending.isEmpty()) {
            return true;
        }
        if (options.isolateTable() && (tableFamily(next) || pending.stream().anyMatch(DefaultChunker::tableFamily))) {
            return false;
        }
        // Upstream uses raw lengths for staging, before whitespace normalization.
        int segments = prefix.isEmpty() ? 0 : 1;
        long characters = length(prefix);
        for (final var element : pending) {
            ChunkCancellation.check();
            if (!element.text().isEmpty()) {
                segments++;
                characters += length(element.text());
            }
        }
        long current = characters + Math.max(0, segments - 1) * 2L;
        return current <= options.newAfterCharacters()
                && characters + segments * 2L + length(next.text()) <= options.maxCharacters();
    }

    /**
     * Combines adjacent small pre-chunks under {@link ChunkOptions#combineTextUnderCharacters()} when allowed.
     *
     * @param source staged pre-chunks in document order
     * @param options chunking options and budgets
     * @return combined pre-chunks in document order
     */
    private List<PreChunk> combine(final List<PreChunk> source, final ChunkOptions options) {
        final var result = new ArrayList<PreChunk>();
        PreChunk current = null;
        // Greedily merge consecutive small pre-chunks when the current chunk is below combineTextUnderCharacters and
        // the merged result fits maxCharacters.
        for (final var next : source) {
            ChunkCancellation.check();
            if (current == null) {
                current = next;
                continue;
            }
            final var joined = new ArrayList<>(current.elements());
            joined.addAll(next.elements());
            final var trial = new PreChunk(
                    List.copyOf(joined), current.tableFamily() || next.tableFamily() ? "" : current.prefix());
            if ((!options.isolateTable() || !current.tableFamily() && !next.tableFamily())
                    && length(current.text()) < options.combineTextUnderCharacters()
                    && length(trial.text()) <= options.maxCharacters()) {
                current = trial;
            } else {
                result.add(current);
                current = current.tableFamily() ? new PreChunk(next.elements(), "") : next;
            }
        }
        if (current != null) {
            result.add(current);
        }
        return result;
    }

    /**
     * Emits one or more finalized chunk elements from {@code preChunk} into {@code result}.
     *
     * @param preChunk staged pre-chunk to emit or split
     * @param options chunking options and budgets
     * @param result output list receiving emitted chunk elements
     */
    private void emit(final PreChunk preChunk, final ChunkOptions options, final List<Element> result) {
        final boolean table = preChunk.elements().size() == 1 && tableFamily(preChunk.elements().getFirst());
        if (table && options.skipTableChunking()) {
            result.add(preChunk.elements().getFirst());
            return;
        }
        String text = preChunk.text();
        if (text.isEmpty() && !table) {
            return;
        }
        final var metadata =
                new LinkedHashMap<>(table ? MetadataConsolidator.tableMetadata(preChunk.elements().getFirst())
                                          : MetadataConsolidator.consolidate(preChunk.elements()));
        boolean splitTable = table && length(text) > options.maxCharacters();
        // When an isolated table carries non-blank text_as_html, parse its grid model and either emit whole or split
        // structurally.
        if (table && metadata.containsKey("text_as_html")) {
            if (!(metadata.get("text_as_html") instanceof final String html)) {
                throw new IllegalArgumentException("text_as_html must be a string");
            }
            if (!html.isBlank()) {
                var model = HtmlTableModel.parse(html);
                if (metadata.containsKey("table_leading_header_rows")) {
                    final Object hint = metadata.get("table_leading_header_rows");
                    try {
                        if (!(hint instanceof Number)) {
                            throw new IllegalArgumentException("Invalid table_leading_header_rows");
                        }
                        model = model.withLeadingHeaders(new java.math.BigDecimal(hint.toString()).intValueExact());
                    } catch (final ArithmeticException error) {
                        throw new IllegalArgumentException("Invalid table_leading_header_rows", error);
                    }
                }
                final var whole = TableFragment.range(model, 0, model.rows().size());
                if (whole.fits(options.maxCharacters())) {
                    ChunkProvenance.attach(
                            metadata, preChunk.elements(), options.includeOriginalElements(), false, json);
                    metadata.put("text_as_html", whole.html());
                    metadata.put("table_row_count", model.rows().size());
                    metadata.put("table_column_count", model.width());
                    metadata.put("table_header_rows",
                            (int) model.rows().stream().filter(HtmlTableModel.Row::header).count());
                    metadata.put("table_leading_header_rows", model.headerRows());
                    result.add(new Element(UUID.randomUUID().toString(), ElementType.Table, whole.text, metadata));
                    return;
                }
                // Match upstream's explicit text-only fallback for impractically small HTML windows.
                if (options.maxCharacters() < 50) {
                    metadata.remove("text_as_html");
                    splitTable = true;
                } else {
                    final var fragments = new StructuredTableSplitter(model, options).split();
                    ChunkProvenance.attach(metadata, preChunk.elements(), options.includeOriginalElements(),
                            fragments.size() > 1, json);
                    final String tableId = UUID.randomUUID().toString();
                    for (int i = 0; i < fragments.size(); i++) {
                        ChunkCancellation.check();
                        final var fragment = fragments.get(i);
                        final var chunkMetadata = new LinkedHashMap<>(metadata);
                        // Whole-table offsets/cell arrays no longer describe a fragment. Source metadata remains in
                        // provenance.
                        for (final String key : List.of("links", "link_texts", "link_urls", "link_start_indexes",
                                     "image_urls", "table_as_cells", "table_column_alignments", "pdf_table_cells",
                                     "pdf_table_joined_iso_dates", "pdf_table_joined_soft_hyphens",
                                     "pdf_table_tagged_empty_cells", "pdf_table_caption_references",
                                     "pdf_table_tagged_nontext_references", "pdf_table_inline_script_glyphs",
                                     "pdf_table_header_rows", "pdf_table_header_policy",
                                     "pdf_table_retained_hyphen_wraps")) {
                            chunkMetadata.remove(key);
                        }
                        chunkMetadata.putAll(fragment.metadata());
                        chunkMetadata.put("table_id", tableId);
                        chunkMetadata.put("chunk_index", i);
                        if (i > 0) {
                            chunkMetadata.put("is_continuation", true);
                        }
                        result.add(new Element(
                                UUID.randomUUID().toString(), ElementType.TableChunk, fragment.text, chunkMetadata));
                    }
                    return;
                }
            } else {
                metadata.remove("text_as_html");
            }
        }
        // Emit narrative composites or text-only tables, splitting oversized text across separator boundaries with
        // overlap.
        final String tableId = splitTable ? UUID.randomUUID().toString() : null;
        int index = 0;
        final var splitter = new TextChunkSplitter(options);
        do {
            ChunkCancellation.check();
            final var split = splitter.split(text);
            if (index == 0) {
                ChunkProvenance.attach(metadata, preChunk.elements(), options.includeOriginalElements(),
                        !split.remainder().isEmpty(), json);
            }
            final var chunkMetadata = new LinkedHashMap<>(metadata);
            if (index > 0) {
                chunkMetadata.put("is_continuation", true);
            }
            if (splitTable) {
                for (final String key : List.of("text_as_html", "links", "link_texts", "link_urls",
                             "link_start_indexes", "image_urls", "table_as_cells", "table_column_alignments",
                             "pdf_table_cells", "pdf_table_joined_iso_dates", "pdf_table_joined_soft_hyphens",
                             "pdf_table_tagged_empty_cells", "pdf_table_caption_references",
                             "pdf_table_tagged_nontext_references", "pdf_table_inline_script_glyphs",
                             "pdf_table_header_rows", "pdf_table_header_policy", "pdf_table_retained_hyphen_wraps",
                             "table_row_count", "table_column_count", "table_header_rows", "table_leading_header_rows",
                             "table_row_start", "table_row_end", "table_column_start", "table_column_end",
                             "table_cell_fragments")) {
                    chunkMetadata.remove(key);
                }
                chunkMetadata.put("table_id", tableId);
                chunkMetadata.put("chunk_index", index);
                chunkMetadata.put("num_carried_over_header_rows", 0);
            }
            final ElementType type =
                    table ? (splitTable ? ElementType.TableChunk : ElementType.Table) : ElementType.CompositeElement;
            result.add(new Element(UUID.randomUUID().toString(), type, split.fragment(), chunkMetadata));
            text = split.remainder();
            index++;
        } while (!text.isEmpty());
    }

    /**
     * Checks whether {@code element} is a {@link ElementType#Table} or {@link ElementType#TableChunk}.
     *
     * @param element element to inspect
     * @return {@code true} if the element belongs to the table family
     */
    private static boolean tableFamily(final Element element) {
        return element.type() == ElementType.Table || element.type() == ElementType.TableChunk;
    }

    /**
     * Staged group of source elements and an optional overlap prefix awaiting final emission or splitting.
     *
     * @param elements ordered source elements in this pre-chunk
     * @param prefix overlap text carried over from the preceding pre-chunk
     */
    private record PreChunk(List<Element> elements, String prefix) {
        /**
         * Checks whether any element in this pre-chunk belongs to the table family.
         *
         * @return {@code true} if at least one element is a table or table chunk
         */
        boolean tableFamily() {
            return elements.stream().anyMatch(DefaultChunker::tableFamily);
        }

        /**
         * Joins the overlap prefix and normalized element texts with blank lines.
         *
         * @return joined pre-chunk text
         */
        String text() {
            var segments = new ArrayList<String>();
            if (!prefix.isEmpty()) {
                segments.add(prefix);
            }
            for (final var element : elements) {
                ChunkCancellation.check();
                final String value =
                        element.type() == ElementType.CodeSnippet ? element.text() : normalize(element.text());
                if (!value.isEmpty()) {
                    segments.add(value);
                }
            }
            return String.join("\n\n", segments);
        }
    }
}
