package io.docweave.partition;

import io.docweave.core.ElementType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Conservative native paragraph grouping and fixed-gutter two-column order; no tagged-PDF or language semantics. */
final class GeometricPdfReadingOrder implements PdfReadingOrder {
    /** Bullet/numbered starts remain separate; no speculative list-wrap reconstruction. */
    private static final Pattern LIST_START =
            Pattern.compile("^(?:[-*•]|[0-9]+[.)])\\s+.*", Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);
    /** Explicit immutable layout selection. */
    private final PdfOptions.TextLayout layout;
    /** Explicit native separator interpretation; historical construction preserves all generated spaces. */
    private final PdfParagraphWraps wraps;
    /** Explicit side-stream grouping on pages without an accepted gutter. */
    private final PdfOptions.SideStreams sideStreams;

    /**
     * A source-supported whitespace interval between two paired native baselines.
     * @param left inclusive candidate gutter start
     * @param right inclusive candidate gutter end
     */
    private record Gap(float left, float right) {}

    /**
     * Captures a caller-selected native layout mode.
     * @param layout paragraph or column-paragraph policy
     */
    GeometricPdfReadingOrder(final PdfOptions.TextLayout layout) {
        this(layout, group -> java.util.Set.of());
    }

    /**
     * Captures immutable grouping and call-confined source-separator strategies.
     * @param layout explicit paragraph/column policy
     * @param wraps original native endpoint interpretation
     */
    GeometricPdfReadingOrder(final PdfOptions.TextLayout layout, final PdfParagraphWraps wraps) {
        this(layout, wraps, PdfOptions.SideStreams.PRESERVE);
    }

    /**
     * Captures immutable grouping, separator and side-stream strategies.
     * @param layout explicit paragraph/column policy
     * @param wraps original native endpoint interpretation
     * @param sideStreams explicit side-stream grouping policy
     */
    GeometricPdfReadingOrder(final PdfOptions.TextLayout layout, final PdfParagraphWraps wraps,
            final PdfOptions.SideStreams sideStreams) {
        this.layout = layout;
        this.wraps = wraps;
        this.sideStreams = sideStreams;
    }

    /**
     * Applies grouping within page-local bands; spanning text/tables/images cannot be merged across.
     * @param items native elements
     * @return ordered and optionally grouped elements
     * @throws IOException if cancelled or over the fixed page work ceiling
     */
    @Override
    public List<PdfLayoutItem> apply(final List<PdfLayoutItem> items) throws IOException {
        ElementCollector.checkInterrupted();
        final var work = new Work();
        final var sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble((PdfLayoutItem item) -> item.box().top())
                        .thenComparingDouble(item -> item.box().left()));
        final Float gutter = layout.columns() ? gutter(sorted, work) : null;
        if (gutter == null) {
            return switch (sideStreams) {
                case PRESERVE -> paragraphs(sorted, work);
                case SMALLER_TYPE_BLOCKS -> sideStreamParagraphs(sorted, work, false);
                case SMALLER_TYPE_BLOCKS_ROW_TIES ->
                    layout.columns() ? rowTies(sideStreamParagraphs(sorted, work, false), work)
                                     : sideStreamParagraphs(sorted, work, false);
                case SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS ->
                    layout.columns() ? rowTies(sideStreamParagraphs(sorted, work, true), work)
                                     : sideStreamParagraphs(sorted, work, false);
            };
        }
        final var result = new ArrayList<PdfLayoutItem>();
        final var band = new ArrayList<PdfLayoutItem>();
        float lower = Float.NEGATIVE_INFINITY;
        int bandIndex = 0;
        for (final var item : sorted) {
            work.step();
            if (ordinary(item) && side(item, gutter) != 0) {
                band.add(item);
            } else {
                result.addAll(band(band, gutter, lower, item.box().top(), bandIndex++, work));
                band.clear();
                result.add(item);
                lower = Math.max(lower, item.box().bottom());
            }
        }
        result.addAll(band(band, gutter, lower, Float.POSITIVE_INFINITY, bandIndex, work));
        return List.copyOf(result);
    }

    /**
     * Requires at least three paired baselines and 80percent agreement on a shared gutter candidate.
     * @param items source items
     * @param work page work counter
     * @return gutter x or null for geometric fallback
     * @throws IOException if interrupted or over budget
     */
    private static Float gutter(final List<PdfLayoutItem> items, final Work work) throws IOException {
        final var text = new ArrayList<PdfLayoutItem>();
        for (final var item : items) {
            work.step();
            if (ordinary(item)) {
                text.add(item);
            }
        }
        text.sort(Comparator.comparingDouble((PdfLayoutItem item) -> item.box().bottom())
                        .thenComparingDouble(item -> item.box().left()));
        final var gaps = new ArrayList<Gap>();
        final var row = new ArrayList<PdfLayoutItem>();
        float baseline = Float.NaN;
        for (final var item : text) {
            work.step();
            if (!row.isEmpty() && Math.abs(item.box().bottom() - baseline) > 2) {
                gap(row, gaps);
                row.clear();
            }
            if (row.isEmpty()) {
                baseline = item.box().bottom();
            }
            row.add(item);
        }
        gap(row, gaps);
        if (gaps.size() < 3) {
            return null;
        }
        final var events = new java.util.TreeMap<Float, int[]>();
        for (final var gap : gaps) {
            work.step();
            events.computeIfAbsent(gap.left(), unused -> new int[2])[0]++;
            events.computeIfAbsent(gap.right(), unused -> new int[2])[1]++;
        }
        final var points = new ArrayList<>(events.entrySet());
        int active = 0;
        int agreed = 0;
        float candidate = Float.NaN;
        float width = -1;
        for (int index = 0; index < points.size(); index++) {
            work.step();
            final var event = points.get(index);
            active += event.getValue()[0];
            if (active > agreed) {
                agreed = active;
                candidate = event.getKey();
                width = 0;
            }
            active -= event.getValue()[1];
            if (index + 1 < points.size()) {
                final float interval = points.get(index + 1).getKey() - event.getKey();
                if (active > agreed || active == agreed && interval > width) {
                    agreed = active;
                    candidate = event.getKey() + interval / 2;
                    width = interval;
                }
            }
        }
        return agreed >= 3 && (long) agreed * 5 >= (long) gaps.size() * 4 ? candidate : null;
    }

    /**
     * Retains an inset whitespace gap for a baseline containing exactly two disjoint runs.
     * @param row native baseline items
     * @param gaps supported whitespace intervals
     */
    private static void gap(final List<PdfLayoutItem> row, final List<Gap> gaps) {
        if (row.size() == 2) {
            final var first = row.getFirst().box();
            final var second = row.getLast().box();
            if (second.left() - first.right() >= 24) {
                gaps.add(new Gap(first.right() + 8, second.left() - 8));
            }
        }
    }

    /**
     * Assigns only wholly separated text to a gutter side.
     * @param item native text
     * @param gutter candidate x
     * @return -1 left, 1 right, 0 spanning/ambiguous
     */
    private static int side(final PdfLayoutItem item, final float gutter) {
        return item.box().right() <= gutter - 4 ? -1 : item.box().left() >= gutter + 4 ? 1 : 0;
    }

    /**
     * Orders left then right only with three lines per side and vertically overlapping column extents.
     * @param input barrier-separated text band
     * @param gutter page gutter
     * @param lower previous barrier bottom
     * @param upper next barrier top
     * @param index page-local band index
     * @param work page counter
     * @return grouped columns or grouped geometric fallback
     * @throws IOException if cancelled or over budget
     */
    private List<PdfLayoutItem> band(final List<PdfLayoutItem> input, final float gutter, final float lower,
            final float upper, final int index, final Work work) throws IOException {
        final var left = new ArrayList<PdfLayoutItem>();
        final var right = new ArrayList<PdfLayoutItem>();
        for (final var item : input) {
            work.step();
            if (item.box().top() < lower - 0.5 || item.box().bottom() > upper + 0.5) {
                return paragraphs(input, work);
            }
            (side(item, gutter) < 0 ? left : right).add(item);
        }
        if (left.size() < 3 || right.size() < 3
                || Math.max(left.getFirst().box().top(), right.getFirst().box().top())
                           >= Math.min(left.getLast().box().bottom(), right.getLast().box().bottom())) {
            return paragraphs(input, work);
        }
        final var ordered = new ArrayList<PdfLayoutItem>();
        final var columns = List.of(left, right);
        for (int column = 0; column < columns.size(); column++) {
            final var marked = new ArrayList<PdfLayoutItem>();
            for (final var item : columns.get(column)) {
                work.step();
                final var metadata = new LinkedHashMap<>(item.metadata());
                metadata.put("pdf_reading_order", "two_column_ltr_v1");
                metadata.put("pdf_column", column + 1);
                metadata.put("pdf_reading_band", index);
                marked.add(new PdfLayoutItem(item.type(), item.text(), item.box(), metadata, item.signature()));
            }
            ordered.addAll(paragraphs(marked, work));
        }
        return ordered;
    }

    /**
     * Joins adjacent ordinary lines only when geometry, source metadata and conservative size checks agree.
     * Structural items, indents, overlapping baselines, bullets and larger gaps end a paragraph.
     * @param input source order within a column or geometric fallback
     * @param work page counter
     * @return text groups with union bounds; structural items are retained unchanged
     * @throws IOException if cancelled or over budget
     */
    private List<PdfLayoutItem> paragraphs(final List<PdfLayoutItem> input, final Work work) throws IOException {
        final var result = new ArrayList<PdfLayoutItem>();
        final var group = new ArrayList<PdfLayoutItem>();
        for (final var item : input) {
            work.step();
            if (!group.isEmpty() && !joins(group.getLast(), item)) {
                flush(result, group, work);
                group.clear();
            }
            if (ordinary(item)) {
                group.add(item);
            } else {
                result.add(item);
            }
        }
        flush(result, group, work);
        return List.copyOf(result);
    }

    /**
     * Groups a smaller-type side stream into blocks among its own lines, keeping all other grouping historical.
     * A side line still ends any adjacent main-stream group, exactly as in historical grouping, so main-stream
     * membership and order are unchanged; each side block is emitted where its first line occurs. A block never
     * crosses an intervening item that overlaps the side column, so it cannot move text past such a barrier.
     * With {@code fragments}, a block whose first line starts on the row of the last line of a multi-line main group
     * is placed between that line and the group's earlier lines, as two marked fragments, when the split is lossless.
     * @param sorted page items in top/left order
     * @param work page counter
     * @param fragments whether row-tie paragraph fragments are emitted
     * @return grouped items
     * @throws IOException if cancelled or over budget
     */
    private List<PdfLayoutItem> sideStreamParagraphs(
            final List<PdfLayoutItem> sorted, final Work work, final boolean fragments) throws IOException {
        final var side = sideLines(sorted, work);
        if (side.isEmpty()) {
            return paragraphs(sorted, work);
        }
        // Group side lines among themselves with the historical join rule, remembering each block's first line.
        final var blocks = new IdentityHashMap<PdfLayoutItem, PdfLayoutItem>();
        final var lines = new ArrayList<PdfLayoutItem>();
        // Non-side items met since the previous side line; any that crosses the side column divides the block.
        final var between = new ArrayList<PdfLayoutItem.Bounds>();
        PdfLayoutItem first = null;
        for (final var item : sorted) {
            work.step();
            if (!side.containsKey(item)) {
                between.add(item.box());
                continue;
            }
            final var metadata = new LinkedHashMap<>(item.metadata());
            metadata.put("pdf_reading_order", "side_stream_blocks_v1");
            final var marked = new PdfLayoutItem(item.type(), item.text(), item.box(), metadata, item.signature());
            final boolean divided = !lines.isEmpty() && crossed(between, lines.getLast().box(), item.box(), work);
            between.clear();
            if (!lines.isEmpty() && (divided || !joins(lines.getLast(), marked))) {
                blocks.put(first, block(lines, work));
                lines.clear();
            }
            if (lines.isEmpty()) {
                first = item;
            }
            lines.add(marked);
        }
        blocks.put(first, block(lines, work));
        final var result = new ArrayList<PdfLayoutItem>();
        final var group = new ArrayList<PdfLayoutItem>();
        int fragmentGroups = 0;
        for (final var item : sorted) {
            work.step();
            if (side.containsKey(item)) {
                final var block = blocks.get(item);
                if (fragments && block != null && group.size() >= 2
                        && fragment(result, group, block, fragmentGroups, work)) {
                    fragmentGroups++;
                    group.clear();
                    continue;
                }
                flush(result, group, work);
                group.clear();
                if (block != null) {
                    result.add(block);
                }
                continue;
            }
            if (!group.isEmpty() && !joins(group.getLast(), item)) {
                flush(result, group, work);
                group.clear();
            }
            if (ordinary(item)) {
                group.add(item);
            } else {
                result.add(item);
            }
        }
        flush(result, group, work);
        return List.copyOf(result);
    }

    /**
     * Emits a main group as two fragments around a side block that starts on its last line's row, when lossless.
     * @param result ordered destination
     * @param group current main group of at least two ordinary lines
     * @param block side block whose first line ends the group
     * @param ordinal page-local fragment group ordinal
     * @param work page counter
     * @return whether the fragments and the block were emitted; nothing is emitted otherwise
     * @throws IOException if cancelled or over budget
     */
    private boolean fragment(final List<PdfLayoutItem> result, final List<PdfLayoutItem> group,
            final PdfLayoutItem block, final int ordinal, final Work work) throws IOException {
        final var last = group.getLast();
        if (!tie(block.box(), last)) {
            return false;
        }
        final var whole = new ArrayList<PdfLayoutItem>(1);
        flush(whole, group, work);
        final var head = new ArrayList<PdfLayoutItem>(1);
        flush(head, group.subList(0, group.size() - 1), work);
        if (!whole.getFirst().text().equals(head.getFirst().text() + " " + last.text())) {
            return false;
        }
        result.add(marked(head.getFirst(), ordinal, 1));
        result.add(block);
        result.add(marked(last, ordinal, 2));
        return true;
    }

    /**
     * Copies an item with explicit paragraph-fragment provenance.
     * @param item fragment content
     * @param ordinal page-local fragment group ordinal
     * @param index one-based fragment index
     * @return marked copy with unchanged text and bounds
     */
    private static PdfLayoutItem marked(final PdfLayoutItem item, final int ordinal, final int index) {
        final var metadata = new LinkedHashMap<>(item.metadata());
        metadata.put("pdf_reading_order_fragment_policy", "row_tie_fragments_v1");
        metadata.put("pdf_paragraph_fragment_group", ordinal);
        metadata.put("pdf_paragraph_fragment_index", index);
        metadata.put("pdf_paragraph_fragment_count", 2);
        return new PdfLayoutItem(item.type(), item.text(), item.box(), metadata, item.signature());
    }

    /**
     * Moves intact side-stream elements back past an adjacent one-line main element whose visual row they start on.
     * One in-order pass makes at most one adjacent swap per side element; a moved element is never visited again.
     * Element objects are reused unchanged, so grouping, text, bounds and metadata are those of the input, and the
     * relative order of every non-side element is kept.
     * @param grouped side-stream policy output for one page
     * @param work page counter
     * @return the same elements with same-row ties resolved
     * @throws IOException if cancelled or over budget
     */
    private static List<PdfLayoutItem> rowTies(final List<PdfLayoutItem> grouped, final Work work) throws IOException {
        final var result = new ArrayList<>(grouped);
        for (int index = 1; index < result.size(); index++) {
            work.step();
            final var item = result.get(index);
            final var previous = result.get(index - 1);
            if (sideStream(item) && tie(item.box(), previous)) {
                result.set(index - 1, item);
                result.set(index, previous);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Tests whether a side element starts on the row of an eligible predecessor and lies wholly to its left.
     * @param side side element bounds
     * @param previous immediately preceding element
     * @return whether the side element may move before it
     */
    private static boolean tie(final PdfLayoutItem.Bounds side, final PdfLayoutItem previous) {
        final var box = previous.box();
        return ordinary(previous) && !sideStream(previous)
                && (!(previous.metadata().get("pdf_line_count") instanceof Number count) || count.intValue() == 1)
                && side.right() <= box.left() && box.top() <= side.top() && side.top() < box.bottom();
    }

    /**
     * Tests for side-stream provenance.
     * @param item grouped element
     * @return whether the side-stream policy emitted it as a side element
     */
    private static boolean sideStream(final PdfLayoutItem item) {
        return "side_stream_blocks_v1".equals(item.metadata().get("pdf_reading_order"));
    }

    /**
     * Tests whether any intervening item horizontally overlaps the union extent of two consecutive side lines.
     * Main-stream lines lie at least four units outside the side extent and never qualify; images, tables and
     * spanning or other text crossing the side column do.
     * @param between boxes of non-side items between the two side lines in top/left order
     * @param previous previous side line
     * @param next next side line
     * @param work page counter
     * @return whether a structural barrier divides the two lines
     * @throws IOException if cancelled or over budget
     */
    private static boolean crossed(final List<PdfLayoutItem.Bounds> between, final PdfLayoutItem.Bounds previous,
            final PdfLayoutItem.Bounds next, final Work work) throws IOException {
        final float left = Math.min(previous.left(), next.left());
        final float right = Math.max(previous.right(), next.right());
        for (final var box : between) {
            work.step();
            if (box.left() < right && box.right() > left) {
                return true;
            }
        }
        return false;
    }

    /**
     * Combines one side-stream group with the historical paragraph rule.
     * @param lines adjacent marked side lines
     * @param work page counter
     * @return the single line or combined paragraph
     * @throws IOException if cancelled or over budget
     */
    private PdfLayoutItem block(final List<PdfLayoutItem> lines, final Work work) throws IOException {
        final var output = new ArrayList<PdfLayoutItem>(1);
        flush(output, lines, work);
        return output.getFirst();
    }

    /**
     * Finds the smaller-type side-stream lines of one page, keyed by identity; empty when the evidence is absent.
     * Clusters whose lines mostly share main-stream baselines form table-like rows and are rejected.
     * @param sorted page items in top/left order
     * @param work page counter
     * @return side lines, keyed by identity
     * @throws IOException if cancelled or over budget
     */
    private static IdentityHashMap<PdfLayoutItem, Boolean> sideLines(final List<PdfLayoutItem> sorted, final Work work)
            throws IOException {
        final var lines = new ArrayList<PdfLayoutItem>();
        for (final var item : sorted) {
            work.step();
            if (ordinary(item) && item.box().bottom() > item.box().top()) {
                lines.add(item);
            }
        }
        final var result = new IdentityHashMap<PdfLayoutItem, Boolean>();
        if (lines.size() < 6) {
            return result;
        }
        final var byLeft = new ArrayList<>(lines);
        byLeft.sort(Comparator.comparingDouble(item -> item.box().left()));
        final var clusters = new ArrayList<List<PdfLayoutItem>>();
        float previous = Float.NaN;
        for (final var item : byLeft) {
            work.step();
            if (clusters.isEmpty() || item.box().left() - previous > 4) {
                clusters.add(new ArrayList<>());
            }
            clusters.getLast().add(item);
            previous = item.box().left();
        }
        List<PdfLayoutItem> main = clusters.getFirst();
        for (final var cluster : clusters) {
            if (cluster.size() > main.size()) {
                main = cluster;
            }
        }
        float mainLeft = Float.POSITIVE_INFINITY;
        float mainRight = Float.NEGATIVE_INFINITY;
        for (final var item : main) {
            work.step();
            mainLeft = Math.min(mainLeft, item.box().left());
            mainRight = Math.max(mainRight, item.box().right());
        }
        final double mainHeight = medianHeight(main);
        final double[] mainBaselines = main.stream().mapToDouble(item -> item.box().bottom()).sorted().toArray();
        for (final var cluster : clusters) {
            if (cluster == main) {
                continue;
            }
            final var outside = new ArrayList<PdfLayoutItem>();
            for (final var item : cluster) {
                work.step();
                if (item.box().right() <= mainLeft - 4 || item.box().left() >= mainRight + 4) {
                    outside.add(item);
                }
            }
            if (outside.size() < 3 || medianHeight(outside) >= mainHeight * 0.9) {
                continue;
            }
            float top = Float.POSITIVE_INFINITY;
            float bottom = Float.NEGATIVE_INFINITY;
            for (final var item : outside) {
                top = Math.min(top, item.box().top());
                bottom = Math.max(bottom, item.box().bottom());
            }
            int spanned = 0;
            for (final var item : main) {
                work.step();
                if (item.box().top() >= top && item.box().bottom() <= bottom) {
                    spanned++;
                }
            }
            int rowBound = 0;
            for (final var item : outside) {
                work.step();
                if (sharesBaseline(mainBaselines, item.box().bottom())) {
                    rowBound++;
                }
            }
            // Lines sharing baselines with the main stream form rows, such as statement labels and values; a prose
            // sidebar runs on its own baseline grid. Row-bound clusters keep historical grouping.
            if (spanned >= 3 && rowBound * 2 < outside.size()) {
                outside.forEach(item -> result.put(item, Boolean.TRUE));
            }
        }
        return result;
    }

    /**
     * Tests whether a main-stream line's bottom lies within the two-unit native baseline tolerance of a bottom.
     * @param baselines ascending main-stream bottoms
     * @param bottom tested bottom
     * @return whether a baseline is shared
     */
    private static boolean sharesBaseline(final double[] baselines, final double bottom) {
        int index = Arrays.binarySearch(baselines, bottom - 2);
        if (index < 0) {
            index = -index - 1;
        }
        return index < baselines.length && baselines[index] <= bottom + 2;
    }

    /**
     * Returns the median box height of nonempty lines.
     * @param lines ordinary lines
     * @return median height
     */
    private static double medianHeight(final List<PdfLayoutItem> lines) {
        final double[] heights =
                lines.stream().mapToDouble(item -> item.box().bottom() - item.box().top()).sorted().toArray();
        return heights[heights.length / 2];
    }

    /**
     * Tests whether one ordinary line plausibly soft-wraps into the next without semantic inference.
     * @param previous last source line in the group
     * @param next following native element
     * @return whether a single-space join is allowed
     */
    private static boolean joins(final PdfLayoutItem previous, final PdfLayoutItem next) {
        if (!ordinary(previous) || !ordinary(next) || !previous.metadata().equals(next.metadata())
                || LIST_START.matcher(previous.text()).matches() || LIST_START.matcher(next.text()).matches()) {
            return false;
        }
        final var first = previous.box();
        final var second = next.box();
        final float height = first.bottom() - first.top();
        final float nextHeight = second.bottom() - second.top();
        final float gap = second.top() - first.bottom();
        return height > 0 && nextHeight > 0 && Math.abs(height - nextHeight) <= Math.max(height, nextHeight) * 0.25
                && Math.abs(first.left() - second.left()) <= 4 && gap >= -0.5
                && gap <= Math.max(4, 2 * Math.max(height, nextHeight))
                && first.right() - first.left() >= Math.max(40, (second.right() - second.left()) * 0.5);
    }

    /**
     * Retains single lines verbatim and combines multi-line groups with exact source line strings and union bounds.
     * Hyphens are retained; no dehyphenation, heading classification or punctuation normalization occurs.
     * @param output ordered destination
     * @param group adjacent source lines
     * @param work per-page traversal counter
     * @throws IOException if cancelled or the traversal budget is exhausted
     */
    private void flush(final List<PdfLayoutItem> output, final List<PdfLayoutItem> group, final Work work)
            throws IOException {
        if (group.isEmpty()) {
            return;
        }
        if (group.size() == 1) {
            output.add(group.getFirst());
            return;
        }
        float left = Float.POSITIVE_INFINITY;
        float top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float bottom = Float.NEGATIVE_INFINITY;
        final var text = new StringBuilder();
        final var omitted = wraps.boundaries(group);
        for (int index = 0; index < group.size(); index++) {
            work.step();
            final var line = group.get(index);
            if (!text.isEmpty() && !omitted.contains(index - 1)) {
                text.append(' ');
            }
            text.append(line.text());
            left = Math.min(left, line.box().left());
            top = Math.min(top, line.box().top());
            right = Math.max(right, line.box().right());
            bottom = Math.max(bottom, line.box().bottom());
        }
        final var metadata = new LinkedHashMap<>(group.getFirst().metadata());
        metadata.put("pdf_text_layout", "paragraphs_v1");
        metadata.put("pdf_line_count", group.size());
        if (!omitted.isEmpty()) {
            metadata.put("pdf_native_word_wrap_policy", "retained_hyphen_wraps_v1");
            metadata.put("pdf_native_retained_hyphen_wraps", omitted.size());
        }
        output.add(new PdfLayoutItem(ElementType.UncategorizedText, text.toString(),
                new PdfLayoutItem.Bounds(left, top, right, bottom), metadata));
    }

    /**
     * Restricts grouping to native ordinary text, without reinterpreting table/image/semantic elements.
     * @param item source item
     * @return whether native paragraph processing applies
     */
    private static boolean ordinary(final PdfLayoutItem item) {
        return item.type() == ElementType.UncategorizedText
                && !item.metadata().containsKey("pdf_source_paragraph_ordinal")
                && ((Number) item.metadata().getOrDefault("pdf_text_rotation", 0)).doubleValue() == 0;
    }

    /** Per-page independent layout budget; not a heap limit or a forced execution deadline. */
    private static final class Work {
        /** Visits consumed on this page. */
        private int used;

        /** Creates a fresh page counter. */
        Work() {
        }

        /**
         * Counts a traversal step and checks interruption.
         * @throws IOException if interrupted or the fixed 2M page budget is exhausted
         */
        void step() throws IOException {
            if ((used & 255) == 0) {
                ElementCollector.checkInterrupted();
            }
            if (++used > 2_000_000) {
                throw new PartitionException(
                        PartitionException.Code.LAYOUT_LIMIT, "PDF text layout exceeds page work limit");
            }
        }
    }
}
