package dev.docweave.chunking;

import java.util.List;

/**
 * Immutable character-budget options shared safely between chunking calls.
 * All character counts are Unicode code points, not UTF-16 units. A chunk already greater than
 * the soft limit will not accept another element; equality still permits an element that fits.
 * Overlap never crosses table-bearing groups, including mixed groups.
 *
 * Structured tables apply the hard limit to both synchronized text and compact HTML; their
 * splitting ignores the narrative soft limit and overlap. If an oversized HTML table cannot
 * practically retain markup below 50 code points, text-only fallback retains provenance instead.
 * Leading-header repetition is budget-aware; skip-table-chunking is an explicit budget exemption.
 *
 * <p>Text separators are literal, ordered preferences used only when oversized text must split.
 * Whitespace-only delimiters are consumed; visible delimiters stay at the fragment end within
 * the hard budget. Empty lists use code-point splitting. Empty or malformed Unicode delimiters
 * are rejected. The list is defensively copied; see {@link Builder#textSplittingSeparators(List)}
 * for normalization, overlap and structural-table boundaries.</p>
 *
 * @param maxCharacters positive hard limit for text and structural table HTML, default 500
 * @param newAfterCharacters nonnegative soft limit, clamped to the hard limit
 * @param overlap overlap budget in [0, hard limit), default zero
 * @param overlapAll whether normal pre-chunk boundaries also receive overlap, default false
 * @param multipageSections whether by-title sections may span pages, default true
 * @param combineTextUnderCharacters by-title small-section threshold; zero disables combining
 * @param includeOriginalElements whether compressed source provenance is attached, default true
 * @param repeatTableHeaders whether leading table headers may repeat when they fit and align with fragment columns,
 *         default true
 * @param skipTableChunking whether isolated tables pass through unchanged, exempt from chunk budgets
 * @param textSplittingSeparators ordered literal oversized-text delimiters; defaults to newline then space
 * @param isolateTable whether table-family elements must occupy separate chunks, default true
 */
public record ChunkOptions(int maxCharacters, int newAfterCharacters, int overlap, boolean overlapAll,
        boolean multipageSections, int combineTextUnderCharacters, boolean includeOriginalElements,
        boolean repeatTableHeaders, boolean skipTableChunking, List<String> textSplittingSeparators,
        boolean isolateTable) {
    /**
     * Validates limits and clamps the soft maximum.
     * @param maxCharacters positive hard character limit
     * @param newAfterCharacters nonnegative soft character limit
     * @param overlap nonnegative overlap smaller than the hard maximum
     * @param overlapAll enables normal-boundary overlap
     * @param multipageSections allows by-title sections to span pages
     * @param combineTextUnderCharacters nonnegative combining threshold
     * @param includeOriginalElements enables provenance
     * @param repeatTableHeaders enables budget-aware leading-header repetition
     * @param skipTableChunking passes table-family elements through unchanged
     * @param textSplittingSeparators nonnull ordered list of nonempty, well-formed Unicode literals
     * @param isolateTable whether tables are staged and combined separately from other elements
     * @throws IllegalArgumentException if limits/separators are invalid or pass-through conflicts with isolation
     * @throws NullPointerException if the separator list or an entry is null
     */
    public ChunkOptions {
        if (maxCharacters <= 0) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        if (newAfterCharacters < 0) {
            throw new IllegalArgumentException("newAfterCharacters must be nonnegative");
        }
        if (overlap < 0 || overlap >= maxCharacters) {
            throw new IllegalArgumentException("overlap must be in [0, maxCharacters)");
        }
        if (combineTextUnderCharacters < 0) {
            throw new IllegalArgumentException("combineTextUnderCharacters must be nonnegative");
        }
        if (skipTableChunking && !isolateTable) {
            throw new IllegalArgumentException("skipTableChunking requires isolateTable=true");
        }
        newAfterCharacters = Math.min(newAfterCharacters, maxCharacters);
        textSplittingSeparators = List.copyOf(textSplittingSeparators);
        for (final String separator : textSplittingSeparators) {
            if (separator.isEmpty()) {
                throw new IllegalArgumentException("Separators must be nonempty; use an empty list for hard splits");
            }
            for (int i = 0; i < separator.length(); i++) {
                final char unit = separator.charAt(i);
                if (Character.isHighSurrogate(unit)) {
                    if (++i >= separator.length() || !Character.isLowSurrogate(separator.charAt(i))) {
                        throw new IllegalArgumentException("Separator contains an unpaired surrogate");
                    }
                } else if (Character.isLowSurrogate(unit)) {
                    throw new IllegalArgumentException("Separator contains an unpaired surrogate");
                }
            }
        }
    }

    /**
     * Constructs the original character policy with newline/space splitting.
     * @param maxCharacters positive hard limit
     * @param newAfterCharacters nonnegative soft limit
     * @param overlap nonnegative overlap below the hard limit
     * @param overlapAll whether ordinary boundaries receive overlap
     * @param multipageSections whether title sections span pages
     * @param combineTextUnderCharacters nonnegative combining threshold
     * @param includeOriginalElements whether provenance is attached
     * @param repeatTableHeaders whether table headers may repeat
     * @param skipTableChunking whether tables pass through unchanged
     * @throws IllegalArgumentException if a numeric limit is invalid
     */
    public ChunkOptions(final int maxCharacters, int newAfterCharacters, final int overlap, final boolean overlapAll,
            final boolean multipageSections, final int combineTextUnderCharacters,
            final boolean includeOriginalElements, final boolean repeatTableHeaders, final boolean skipTableChunking) {
        this(maxCharacters, newAfterCharacters, overlap, overlapAll, multipageSections, combineTextUnderCharacters,
                includeOriginalElements, repeatTableHeaders, skipTableChunking, List.of("\n", " "));
    }

    /**
     * Constructs a literal separator policy with table isolation enabled.
     * @param maxCharacters positive hard limit
     * @param newAfterCharacters nonnegative soft limit
     * @param overlap nonnegative overlap below the hard limit
     * @param overlapAll whether ordinary boundaries receive overlap
     * @param multipageSections whether title sections span pages
     * @param combineTextUnderCharacters nonnegative combining threshold
     * @param includeOriginalElements whether provenance is attached
     * @param repeatTableHeaders whether table headers may repeat
     * @param skipTableChunking whether tables pass through unchanged
     * @param textSplittingSeparators ordered nonempty Unicode literals; list may be empty
     * @throws IllegalArgumentException if limits or separator literals are invalid
     * @throws NullPointerException if the separator list or an entry is null
     */
    public ChunkOptions(final int maxCharacters, int newAfterCharacters, final int overlap, final boolean overlapAll,
            final boolean multipageSections, final int combineTextUnderCharacters,
            final boolean includeOriginalElements, final boolean repeatTableHeaders, final boolean skipTableChunking,
            List<String> textSplittingSeparators) {
        this(maxCharacters, newAfterCharacters, overlap, overlapAll, multipageSections, combineTextUnderCharacters,
                includeOriginalElements, repeatTableHeaders, skipTableChunking, textSplittingSeparators, true);
    }

    /**
     * Returns the default character policy.
     * @return options with 500-character hard/soft limits, no overlap and provenance enabled
     */
    public static ChunkOptions defaults() {
        return builder().build();
    }

    /**
     * Creates a mutable options builder behind an interface.
     * @return a new mutable builder; each builder belongs to one caller
     */
    public static Builder builder() {
        return new DefaultChunkOptionsBuilder();
    }

    /** Fluent, non-thread-safe builder. Unspecified soft/combining limits follow the hard limit at build time. */
    public interface Builder {
        /**
         * Sets the hard chunk budget.
         * @param value positive hard code-point limit
         * @return this builder
         */
        Builder maxCharacters(final int value);
        /**
         * Sets the preferred chunk budget.
         * @param value nonnegative soft limit, clamped at build time
         * @return this builder
         */
        Builder newAfterCharacters(final int value);
        /**
         * Sets the text repeated when splitting oversized elements.
         * @param value overlap smaller than the hard maximum
         * @return this builder
         */
        Builder overlap(final int value);
        /**
         * Extends overlap to normal pre-chunk boundaries.
         * @param value whether ordinary chunk boundaries also overlap
         * @return this builder
         */
        Builder overlapAll(final boolean value);
        /**
         * Configures page-boundary staging for by-title chunking.
         * @param value whether by-title staging ignores page boundaries
         * @return this builder
         */
        Builder multipageSections(final boolean value);
        /**
         * Configures combining of adjacent small sections.
         * @param value by-title combining threshold; zero preserves boundaries
         * @return this builder
         */
        Builder combineTextUnderCharacters(final int value);
        /**
         * Configures compressed original-element provenance.
         * @param value whether to include compressed source elements
         * @return this builder
         */
        Builder includeOriginalElements(final boolean value);
        /**
         * Repeats leading headers on continuation fragments when the complete fragment fits.
         * Header rows come from leading thead rows or rows containing th cells
         * and optional empty td corner cells. Repetition never forces hard-budget overflow or applies
         * mismatched header columns to a cell fragment starting after column zero.
         * @param value whether header repetition is enabled; default true
         * @return this builder
         */
        Builder repeatTableHeaders(final boolean value);
        /**
         * Passes Table/TableChunk elements through with their IDs, text and metadata unchanged.
         * Such tables remain isolated and may exceed maxCharacters. This also bypasses HTML parsing
         * and provenance changes, regardless of includeOriginalElements.
         * @param value whether tables bypass chunking; default false
         * @return this builder
         */
        Builder skipTableChunking(final boolean value);
        /**
         * Controls whether table-family elements can share chunks with neighboring elements.
         * When false, staging and by-title small-section combining use ordinary text size rules;
         * mixed groups emit CompositeElement with normalized stored table text. Their table HTML,
         * cell arrays and table extraction metadata are retained only in original-element provenance
         * when enabled. A single-table group still uses structural Table/TableChunk processing.
         * Overlap never crosses a table-bearing group, including mixed groups, in either mode.
         * Header repetition applies only to structural single-table groups. Disabling isolation
         * cannot be combined with skipTableChunking, which guarantees exact table pass-through.
         * @param value whether tables remain separate, default true
         * @return this builder
         */
        Builder isolateTable(final boolean value);
        /**
         * Selects literal delimiters for oversized narrative, code and text-only table splitting.
         * Tries delimiters in priority order, selecting the last eligible occurrence of the first
         * usable delimiter within the hard window. Whitespace-only delimiters are consumed and
         * fragment edges trimmed. Delimiters containing non-whitespace stay at the end of the
         * fragment and count against its budget. Existing overlap rules still apply. If none fits,
         * split at a Unicode code-point boundary. This does not change element-joining separators,
         * narrative whitespace normalization, or structural HTML table splitting.
         * @param value nonnull ordered nonempty Unicode literals; an empty list forces hard splits
         * @return this builder
         * @throws NullPointerException if the list or any entry is null
         */
        Builder textSplittingSeparators(final List<String> value);
        /**
         * Resolves defaults without changing this builder.
         * @return an immutable options snapshot
         * @throws IllegalArgumentException if configured limits or separator literals are invalid
         * @throws NullPointerException if a required policy is null
         */
        ChunkOptions build();
    }
}
