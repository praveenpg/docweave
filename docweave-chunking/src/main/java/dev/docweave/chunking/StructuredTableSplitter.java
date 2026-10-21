package dev.docweave.chunking;

import java.util.ArrayList;
import java.util.List;

/** Per-call row-group, cell and escaped-text packing. Carry cells have geometry but never duplicate data. */
final class StructuredTableSplitter {
    /** Parsed logical HTML table model being split. */
    private final HtmlTableModel model;
    /** Active chunking options and header repetition policy. */
    private final ChunkOptions options;
    /** Hard maximum Unicode code-point budget for both text and HTML representations. */
    private final int limit;
    /** Pre-built fragment containing the table's contiguous leading header rows. */
    private final TableFragment header;
    /** Finalized table fragments in emission order. */
    private final List<TableFragment> output = new ArrayList<>();
    /** Rows staged for the current pending fragment. */
    private final List<TableFragment.Row> pending = new ArrayList<>();
    /** Accumulated inner HTML code-point length for {@link #pending}. */
    private int pendingHtml;
    /** Accumulated normalized text code-point length for {@link #pending}. */
    private int pendingText;
    /** Whether {@link #header} rows should be prepended when {@link #pending} is flushed. */
    private boolean pendingHeaders;

    /**
     * Creates a stateful splitter for the given table model and chunking options.
     *
     * @param model parsed logical HTML table model
     * @param options chunking options and budgets
     */
    StructuredTableSplitter(final HtmlTableModel model, final ChunkOptions options) {
        this.model = model;
        this.options = options;
        limit = options.maxCharacters();
        header = TableFragment.range(model, 0, model.headerRows());
    }

    /**
     * Splits the table into ordered fragments that fit within {@link #limit}.
     *
     * @return immutable list of table fragments
     */
    List<TableFragment> split() {
        // Group transitively connected rowspan ranges and pack them whole when they fit, or fall back to row/cell
        // splitting.
        for (int start = 0; start < model.rows().size();) {
            ChunkCancellation.check();
            int end = start + 1;
            for (int r = start; r < end; r++) {
                ChunkCancellation.check();
                for (final var cell : model.rows().get(r).cells()) {
                    ChunkCancellation.check();
                    if (cell.row() == r) {
                        end = Math.max(end, cell.row() + cell.rows());
                    }
                }
            }
            final var group = TableFragment.range(model, start, end);
            if (group.fits(limit)) {
                add(group);
            } else {
                flush();
                for (int r = start; r < end; r++) {
                    ChunkCancellation.check();
                    final var row = TableFragment.range(model, r, r + 1);
                    if (row.fits(limit)) {
                        add(row);
                    } else {
                        flush();
                        splitRow(row.rows.getFirst());
                    }
                }
            }
            start = end;
        }
        flush();
        return List.copyOf(output);
    }

    /**
     * Appends {@code fragment} to the pending row buffer, flushing first if it would exceed {@link #limit}.
     *
     * @param fragment whole row or row-span group fragment that fits within {@link #limit}
     */
    private void add(final TableFragment fragment) {
        if (!pending.isEmpty() && !fitsPending(fragment)) {
            flush();
        }
        if (pending.isEmpty()) {
            pendingHeaders = wantsHeaders(fragment) && withHeaderFits(fragment);
        }
        pending.addAll(fragment.rows);
        pendingHtml += fragment.htmlLength - 15;
        pendingText += (pendingText > 0 && fragment.textLength > 0 ? 1 : 0) + fragment.textLength;
    }

    /**
     * Checks whether appending {@code next} to {@link #pending} stays within both HTML and text budgets.
     *
     * @param next candidate fragment to append
     * @return {@code true} if the combined pending fragment fits within {@link #limit}
     */
    private boolean fitsPending(final TableFragment next) {
        final int headerHtml = pendingHeaders ? header.htmlLength - 15 + 15 : 0; // thead wrapper
        final int headerText = pendingHeaders ? header.textLength : 0;
        final long text = pendingText + (pendingText > 0 && next.textLength > 0 ? 1L : 0) + next.textLength;
        return 15L + pendingHtml + next.htmlLength - 15 + headerHtml <= limit
                && headerText + (headerText > 0 && text > 0 ? 1L : 0) + text <= limit;
    }

    /**
     * Flushes any staged rows in {@link #pending} into {@link #output}, prepending carried headers if applicable.
     */
    private void flush() {
        if (pending.isEmpty()) {
            return;
        }
        final var rows = new ArrayList<TableFragment.Row>();
        if (pendingHeaders) {
            rows.addAll(header.rows);
        }
        rows.addAll(pending);
        output.add(new TableFragment(rows, pendingHeaders ? model.headerRows() : 0));
        pending.clear();
        pendingHtml = 0;
        pendingText = 0;
        pendingHeaders = false;
    }

    /**
     * Determines whether {@code fragment} is a non-initial body fragment aligned with column zero that should carry
     * headers.
     *
     * @param fragment candidate table fragment
     * @return {@code true} if leading header rows should be repeated for {@code fragment}
     */
    private boolean wantsHeaders(final TableFragment fragment) {
        return !output.isEmpty() && options.repeatTableHeaders() && model.headerRows() > 0 && !fragment.rows.isEmpty()
                && fragment.rows.getFirst().sourceIndex() >= model.headerRows()
                && (fragment.rows.getFirst().cells().isEmpty()
                        || fragment.rows.getFirst().cells().getFirst().source().column() == 0);
    }

    /**
     * Checks whether {@code fragment} combined with {@link #header} fits within {@link #limit}.
     *
     * @param fragment candidate table fragment
     * @return {@code true} if prepending carried header rows stays within {@link #limit}
     */
    private boolean withHeaderFits(final TableFragment fragment) {
        return fragment.htmlLength + (long) header.htmlLength <= limit // header inner + thead = header full size
                && header.textLength + (header.textLength > 0 && fragment.textLength > 0 ? 1L : 0) + fragment.textLength
                           <= limit;
    }

    /**
     * Emits a single split-row or split-cell fragment directly to {@link #output}, repeating headers if they fit.
     *
     * @param fragment single-row fragment to emit
     */
    private void emit(final TableFragment fragment) {
        final boolean repeat = wantsHeaders(fragment) && withHeaderFits(fragment);
        if (!repeat) {
            output.add(fragment);
            return;
        }
        final var rows = new ArrayList<TableFragment.Row>(header.rows);
        rows.addAll(fragment.rows);
        output.add(new TableFragment(rows, model.headerRows()));
    }

    /**
     * Wraps a subset of cells from {@code row} into a single-row {@link TableFragment}.
     *
     * @param row source row descriptor
     * @param cells subset of cells to include in the fragment
     * @return single-row table fragment
     */
    private TableFragment rowFragment(final TableFragment.Row row, final List<TableFragment.Cell> cells) {
        return new TableFragment(List.of(new TableFragment.Row(row.sourceIndex(), List.copyOf(cells), row.header())));
    }

    /**
     * Splits an oversized row horizontally across cell boundaries and delegates oversized individual cells to {@link
     * #splitCell}.
     *
     * @param row oversized table row to split
     */
    private void splitRow(final TableFragment.Row row) {
        final var cells = new ArrayList<TableFragment.Cell>();
        int htmlLength = 24; // <table><tr></tr></table>
        int textLength = 0;
        // Pack consecutive cells from the row until the next cell would overflow limit, then flush or split the
        // individual cell.
        for (final var cell : row.cells()) {
            ChunkCancellation.check();
            final var single = rowFragment(row, List.of(cell));
            final int cellHtml = single.htmlLength - 24;
            final int cellText = single.textLength;
            if (!cells.isEmpty()
                    && (htmlLength + (long) cellHtml > limit
                            || textLength + (textLength > 0 && cellText > 0 ? 1L : 0) + cellText > limit)) {
                emit(rowFragment(row, cells));
                cells.clear();
                htmlLength = 24;
                textLength = 0;
            }
            if (single.fits(limit)) {
                cells.add(cell);
                htmlLength += cellHtml;
                textLength += (textLength > 0 && cellText > 0 ? 1 : 0) + cellText;
            } else {
                if (!cells.isEmpty()) {
                    emit(rowFragment(row, cells));
                    cells.clear();
                    htmlLength = 24;
                    textLength = 0;
                }
                splitCell(row, cell);
            }
        }
        if (!cells.isEmpty()) {
            emit(rowFragment(row, cells));
        }
    }

    /**
     * Splits an oversized single cell across Unicode code-point ranges using binary search over escaped HTML length.
     *
     * @param row owning table row descriptor
     * @param cell oversized cell to slice
     */
    private void splitCell(final TableFragment.Row row, final TableFragment.Cell cell) {
        final int count = UnicodeText.length(cell.text());
        if (count == 0) {
            throw new IllegalArgumentException("Chunk budget cannot hold this cell's structural markup; increase "
                                               + "maxCharacters or skip table chunking");
        }
        for (int start = 0; start < count;) {
            ChunkCancellation.check();
            final var probe = rowFragment(row, List.of(cell.slice(start, Math.min(count, start + 2))));
            final boolean reserveHeaders = wantsHeaders(probe) && withHeaderFits(probe);
            int low = start + 1, high = Math.min(count, start + limit), best = start;
            // Escape expansion is monotonic, so binary search never cuts an entity or surrogate.
            while (low <= high) {
                ChunkCancellation.check();
                final int middle = low + (high - low) / 2;
                final var candidate = rowFragment(row, List.of(cell.slice(start, middle)));
                if (candidate.fits(limit) && (!reserveHeaders || withHeaderFits(candidate))) {
                    best = middle;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            if (best == start) {
                throw new IllegalArgumentException("Chunk budget cannot hold one escaped cell character and its spans; "
                                                   + "increase maxCharacters or skip table chunking");
            }
            // Back off to the last space within the fitting slice when possible so words are not split mid-token.
            int end = best;
            if (best < count) {
                final String prefix = UnicodeText.slice(cell.text(), start, best);
                final int space = prefix.lastIndexOf(' ');
                if (space > 0) {
                    end = start + UnicodeText.length(prefix.substring(0, space + 1));
                }
            }
            emit(rowFragment(row, List.of(cell.slice(start, end))));
            start = end;
        }
    }
}
