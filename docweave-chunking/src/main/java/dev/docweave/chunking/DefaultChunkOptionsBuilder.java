package dev.docweave.chunking;

import dev.docweave.chunking.ChunkOptions.Builder;

import java.util.List;

/** Fluent, non-thread-safe builder. Unspecified soft/combining limits follow the hard limit at build time. */
final class DefaultChunkOptionsBuilder implements ChunkOptions.Builder {
    /** Hard maximum number of Unicode code points per chunk. */
    private int max = 500;
    /** Optional preferred soft code-point threshold before starting a new chunk. */
    private Integer soft;
    /** Number of trailing code points repeated when splitting oversized text. */
    private int overlap;
    /** Whether overlap is applied across ordinary pre-chunk boundaries as well as mid-element splits. */
    private boolean overlapAll;
    /** Whether by-title staging allows sections to span across page number transitions. */
    private boolean multipage = true;
    /** Optional code-point threshold under which adjacent by-title pre-chunks are combined. */
    private Integer combine;
    /** Whether compressed original source elements are encoded in chunk metadata. */
    private boolean originals = true;
    /** Whether leading table header rows are repeated in continuation table fragments. */
    private boolean repeatHeaders = true;
    /** Whether table elements bypass chunking and pass through unchanged. */
    private boolean skipTables;
    /** Whether tables are staged into dedicated pre-chunks rather than combined with prose. */
    private boolean isolateTables = true;
    /** Ordered separator strings tried when splitting oversized text. */
    private List<String> separators = List.of("\n", " ");

    /** Creates a builder with upstream-compatible character defaults. */
    DefaultChunkOptionsBuilder() {
    }

    /**
     * Sets the hard chunk budget.
     * @param value positive hard code-point limit
     * @return this builder
     */
    public Builder maxCharacters(final int value) {
        max = value;
        return this;
    }

    /**
     * Sets the preferred chunk budget.
     * @param value nonnegative soft limit, clamped at build time
     * @return this builder
     */
    public Builder newAfterCharacters(final int value) {
        soft = value;
        return this;
    }

    /**
     * Sets the text repeated when splitting oversized elements.
     * @param value overlap smaller than the hard maximum
     * @return this builder
     */
    public Builder overlap(final int value) {
        overlap = value;
        return this;
    }

    /**
     * Extends overlap to normal pre-chunk boundaries.
     * @param value whether ordinary chunk boundaries also overlap
     * @return this builder
     */
    public Builder overlapAll(final boolean value) {
        overlapAll = value;
        return this;
    }

    /**
     * Configures page-boundary staging for by-title chunking.
     * @param value whether by-title staging ignores page boundaries
     * @return this builder
     */
    public Builder multipageSections(final boolean value) {
        multipage = value;
        return this;
    }

    /**
     * Configures combining of adjacent small sections.
     * @param value by-title combining threshold; zero preserves boundaries
     * @return this builder
     */
    public Builder combineTextUnderCharacters(final int value) {
        combine = value;
        return this;
    }

    /**
     * Configures compressed original-element provenance.
     * @param value whether to include compressed source elements
     * @return this builder
     */
    public Builder includeOriginalElements(final boolean value) {
        originals = value;
        return this;
    }

    /**
     * Configures whether leading table header rows repeat in split table chunks.
     *
     * @param value whether to repeat leading table headers when they fit
     * @return this builder
     */
    public Builder repeatTableHeaders(final boolean value) {
        repeatHeaders = value;
        return this;
    }

    /**
     * Configures whether tables are isolated into dedicated pre-chunks.
     *
     * @param value whether to prevent tables from combining with surrounding prose
     * @return this builder
     */
    public Builder isolateTable(final boolean value) {
        isolateTables = value;
        return this;
    }

    /**
     * Configures whether table elements bypass chunking and pass through unchanged.
     *
     * @param value whether to skip table chunking
     * @return this builder
     */
    public Builder skipTableChunking(final boolean value) {
        skipTables = value;
        return this;
    }

    /**
     * Sets the ordered separator candidates used when splitting oversized text.
     *
     * @param value ordered list of non-empty separator strings
     * @return this builder
     */
    public Builder textSplittingSeparators(final List<String> value) {
        separators = List.copyOf(value);
        return this;
    }

    /**
     * Resolves defaults without changing this builder.
     * @return immutable options snapshot
     * @throws IllegalArgumentException if configured limits are invalid
     */
    public ChunkOptions build() {
        return new ChunkOptions(max, soft == null ? max : soft, overlap, overlapAll, multipage,
                combine == null ? max : combine, originals, repeatHeaders, skipTables, separators, isolateTables);
    }
}
