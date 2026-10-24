package io.docweave.partition;

import io.docweave.core.ElementType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Transactional source-tag table assembly with exact retained glyph ownership and text-envelope geometry. */
final class PdfTaggedTables {
    /** Prevents construction of the page-local utility. */
    private PdfTaggedTables() {
    }

    /**
     * Prepared complete table and exact glyph identities, before any source claim is committed.
     * @param table complete staged element
     * @param glyphs exact original retained glyph identities
     * @param captionGlyphs caption identities reserved for conflict checks but left unclaimed on commit
     * @param anchors source anchor count charged on acceptance
     */
    private record Prepared(
            PdfLayoutItem table, List<PdfPageText.Glyph> glyphs, List<PdfPageText.Glyph> captionGlyphs, int anchors) {}

    /**
     * Accepted page tables and original still-unclaimed native positions.
     * @param tables immutable whole accepted tables
     * @param remaining original native glyphs without accepted claims
     */
    record Result(List<PdfLayoutItem> tables, List<PdfPageText.Glyph> remaining) {}

    /**
     * Prepares all supported source tables before committing nonoverlapping source ownership.
     * Missing/ambiguous MCIDs, unlocatable empty cells, mixed cell directions and overlapping candidate envelopes fall
     * back whole.
     * @param plans document-scoped validated source plans
     * @param page zero-based original page
     * @param text original retained glyphs and source identity map
     * @param geometry displayed original crop/rotation frame
     * @param options source/table/output policy
     * @param join explicit native text policy
     * @param work shared tagged/native/provider work counter
     * @return accepted complete tables plus exactly unclaimed original positions
     * @throws IOException if cancellation, native cell limits or shared work limits fail
     */
    static Result extract(final List<PdfTaggedStructure.Table> plans, final int page, final PdfPageText text,
            final PdfPageGeometry geometry, final PdfOptions options, final PdfNativeLayoutOptions.CellTextJoin join,
            final PdfTableWork work) throws IOException {
        return extract(plans, page, text, geometry, options, join, work, PdfTaggedTableGeometry.SUPPORTED_CELL_AXES);
    }

    /**
     * Stages source tables under an explicit cell-axis or logical-envelope geometry requirement.
     * @param plans validated document source plans
     * @param page zero-based original page
     * @param text original retained positions
     * @param geometry displayed page frame
     * @param options resource and output limits
     * @param join explicit cell text policy
     * @param work shared page work counter
     * @param tableGeometry explicit source geometry requirement
     * @return accepted tables and exactly unclaimed positions
     * @throws IOException if resource or cancellation checks fail
     */
    static Result extract(final List<PdfTaggedStructure.Table> plans, final int page, final PdfPageText text,
            final PdfPageGeometry geometry, final PdfOptions options, final PdfNativeLayoutOptions.CellTextJoin join,
            final PdfTableWork work, final PdfTaggedTableGeometry tableGeometry) throws IOException {
        final var candidates = new ArrayList<Prepared>();
        for (final var plan : plans) {
            work.step();
            if (plan.page() == page) {
                final var prepared = prepare(plan, text, geometry, options, join, work, tableGeometry);
                if (prepared != null) {
                    candidates.add(prepared);
                }
            }
        }
        if (candidates.isEmpty()) {
            return new Result(List.of(), text.glyphs());
        }
        final var blocked = new boolean[candidates.size()];
        final Map<PdfPageText.Glyph, Integer> owners = new IdentityHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            for (final var group : List.of(candidates.get(i).glyphs(), candidates.get(i).captionGlyphs())) {
                for (final var glyph : group) {
                    work.step();
                    final var prior = owners.putIfAbsent(glyph, i);
                    if (prior != null) {
                        blocked[i] = true;
                        blocked[prior] = true;
                    }
                }
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                work.step();
                if (overlaps(candidates.get(i).table().box(), candidates.get(j).table().box())) {
                    blocked[i] = true;
                    blocked[j] = true;
                }
            }
        }
        final var claims = Collections.newSetFromMap(new IdentityHashMap<PdfPageText.Glyph, Boolean>());
        final var tables = new ArrayList<PdfLayoutItem>();
        for (int i = 0; i < candidates.size(); i++) {
            if (!blocked[i]) {
                final var candidate = candidates.get(i);
                for (final var glyph : candidate.glyphs()) {
                    work.step();
                    if (!claims.add(glyph)) {
                        throw new IllegalStateException("Tagged tables overlap native ownership");
                    }
                }
                tables.add(candidate.table());
                work.retainNativeAnchors(candidate.anchors());
            }
        }
        final var remaining = new ArrayList<PdfPageText.Glyph>();
        for (final var glyph : text.glyphs()) {
            work.step();
            if (!claims.contains(glyph)) {
                remaining.add(glyph);
            }
        }
        return new Result(List.copyOf(tables), List.copyOf(remaining));
    }

    /**
     * Assembles a whole candidate from source roles/spans and actual retained native glyphs.
     * Geometry describes text envelopes, never invented rule borders or source-tag BBox precision.
     * @param plan validated source logical table
     * @param text source glyph identities
     * @param geometry displayed original frame
     * @param options cell/output limits
     * @param join explicit text joining
     * @param work shared work counter
     * @param tableGeometry explicit cell-axis or logical-envelope requirement
     * @return prepared whole candidate or null without any committed claims
     * @throws IOException if resource/cancellation bounds fail
     */
    private static Prepared prepare(final PdfTaggedStructure.Table plan, final PdfPageText text,
            final PdfPageGeometry geometry, final PdfOptions options, final PdfNativeLayoutOptions.CellTextJoin join,
            final PdfTableWork work, final PdfTaggedTableGeometry tableGeometry) throws IOException {
        for (final var reference : plan.nonText()) {
            work.step();
            if (reference.page() != plan.page() || !text.markedDeclared(reference.id())
                    || !text.marked(reference.id()).isEmpty()) {
                return null;
            }
        }
        final var values = new ArrayList<String>();
        final var glyphs = new ArrayList<PdfPageText.Glyph>();
        final var boxes = new ArrayList<PdfLayoutItem.Bounds>();
        final var descriptions = new ArrayList<Map<String, Object>>();
        final var coordinates = new PdfTableCells(geometry, 1, 1);
        final Set<PdfPageText.Glyph> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        int dates = 0;
        int multilineCells = 0;
        int softHyphens = 0;
        int empty = 0;
        int scriptGlyphs = 0;
        int retainedHyphenWraps = 0;
        for (final var cell : plan.cells()) {
            final var members = new ArrayList<PdfPageText.Glyph>();
            for (final var reference : cell.content()) {
                work.step();
                final var original = text.marked(reference.id());
                if (!text.markedDeclared(reference.id())) {
                    return null;
                }
                for (final var glyph : original) {
                    work.step();
                    if (!unique.add(glyph)) {
                        return null;
                    }
                    members.add(glyph);
                }
            }
            if (members.isEmpty() || members.stream().allMatch(glyph -> glyph.text().isBlank())) {
                empty++;
                values.add("");
                boxes.add(null);
                glyphs.addAll(members);
                continue;
            }
            final int direction = members.getFirst().direction();
            if (members.stream().anyMatch(glyph -> glyph.direction() != direction)) {
                return null;
            }
            // Logical cell text follows its native baseline frame even on a declared rotated page.
            final var lines = PdfGlyphLines.lines(members);
            final String value = String.join(" ", lines.stream().map(PdfLayoutItem::text).toList());
            if (value.codePointCount(0, value.length()) > options.tableLimits().maxCellCharacters()) {
                throw new PartitionException(
                        PartitionException.Code.TABLE_LIMIT, "Tagged table exceeds maxCellCharacters");
            }
            final boolean upright = geometry.textRotation(direction) == 0;
            final boolean sourceWords = join == PdfNativeLayoutOptions.CellTextJoin.SOURCE_WORD_WRAPS_AND_ISO_DATES;
            final var scripts = join == PdfNativeLayoutOptions.CellTextJoin.INLINE_SCRIPT_RUNS
                                        ? PdfNativeCellText.inlineScriptRuns(members, geometry, lines, work)
                                        : null;
            if (scripts != null) {
                scriptGlyphs += scripts.assignedGlyphs();
            }
            final String joinedDate =
                    (join == PdfNativeLayoutOptions.CellTextJoin.WRAPPED_ISO_DATE
                            || join == PdfNativeLayoutOptions.CellTextJoin.SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE
                            || sourceWords)
                                    && upright
                            ? PdfNativeCellText.wrappedIsoDate(lines, value)
                            : value;
            if (!joinedDate.equals(value)) {
                dates++;
            }
            final var discretionary =
                    upright
                                    && (join
                                                    == PdfNativeLayoutOptions.CellTextJoin
                                                            .SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE
                                            || sourceWords)
                            ? PdfSourceDiscretionaryText.join(cell.softHyphens(), text, lines, value, work,
                                      sourceWords ? PdfNativeCellText.compoundBoundaries(members, geometry, lines, work)
                                                  : java.util.Set.of())
                            : new PdfSourceDiscretionaryText.Result(value, 0);
            softHyphens += discretionary.joins();
            retainedHyphenWraps += discretionary.retainedHyphenWraps();
            final String joined = join == PdfNativeLayoutOptions.CellTextJoin.INLINE_SCRIPT_RUNS
                                          ? String.join(" ", scripts.lines().stream().map(PdfLayoutItem::text).toList())
                                  : join == PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS && upright
                                          ? PdfNativeCellText.preserveLineBreaks(lines, value)
                                  : discretionary.joins() > 0 || discretionary.retainedHyphenWraps() > 0
                                          ? discretionary.text()
                                          : joinedDate;
            if (scripts != null
                    && joined.codePointCount(0, joined.length()) > options.tableLimits().maxCellCharacters()) {
                throw new PartitionException(
                        PartitionException.Code.TABLE_LIMIT, "Tagged table exceeds maxCellCharacters");
            }
            if (join == PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS && !joined.equals(value)) {
                multilineCells++;
            }
            values.add(joined);
            glyphs.addAll(members);
            final var displayed = PdfGlyphLines.displayed(members, geometry);
            final var box = envelope(displayed.stream().map(PdfLayoutItem::box).toList());
            if (!finite(box) || box.left() < 0 || box.top() < 0 || box.right() > geometry.width()
                    || box.bottom() > geometry.height()) {
                return null;
            }
            boxes.add(box);
        }
        final List<PdfLayoutItem.Bounds> cellBounds;
        if (tableGeometry != PdfTaggedTableGeometry.SUPPORTED_CELL_AXES) {
            cellBounds = logicalEnvelope(boxes, text, geometry, unique, work, tableGeometry);
            if (cellBounds == null) {
                return null;
            }
        } else if (empty > 0) {
            cellBounds = inferred(plan, boxes, text, geometry, unique, work);
            if (cellBounds == null) {
                return null;
            }
        } else {
            cellBounds = boxes;
        }
        final var captionGlyphs = captionGlyphsOutsideTable(plan, text, geometry, envelope(cellBounds), unique, work);
        if (captionGlyphs == null) {
            return null;
        }
        if (options.includeTableCellCoordinates()) {
            for (int i = 0; i < plan.cells().size(); i++) {
                final var cell = plan.cells().get(i);
                final var box = cellBounds.get(i);
                descriptions.add(coordinates.cell(cell.row(), cell.column(), cell.rows(), cell.columns(), box.left(),
                        box.top(), box.right(), box.bottom(), work));
            }
        }
        final var html = new StringBuilder("<table><tbody>");
        int index = 0;
        for (int row = 0; row < plan.rows(); row++) {
            work.step();
            html.append("<tr>");
            while (index < plan.cells().size() && plan.cells().get(index).row() == row) {
                final var cell = plan.cells().get(index);
                final String tag = cell.header() ? "th" : "td";
                html.append('<').append(tag);
                if (cell.rows() > 1) {
                    html.append(" rowspan=\"").append(cell.rows()).append('"');
                }
                if (cell.columns() > 1) {
                    html.append(" colspan=\"").append(cell.columns()).append('"');
                }
                html.append('>')
                        .append(PdfNativeCellText.html(values.get(index++), join))
                        .append("</")
                        .append(tag)
                        .append('>');
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        final var metadata = new LinkedHashMap<String, Object>();
        metadata.put("text_as_html", html.toString());
        metadata.put("pdf_table_method", "tagged_structure_native_glyphs_v1");
        metadata.put("pdf_text_method", "native");
        metadata.put("pdf_table_tagged_scope", "single_page_mcid_v1");
        if (tableGeometry == PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE) {
            metadata.put("pdf_table_geometry_policy", "source_logical_text_envelope_v1");
        } else if (tableGeometry == PdfTaggedTableGeometry.LOGICAL_CELL_TEXT_ENVELOPES) {
            metadata.put("pdf_table_geometry_policy", "source_logical_cell_text_envelopes_v1");
        }
        if (!plan.nonText().isEmpty()) {
            metadata.put("pdf_table_tagged_nontext_references", plan.nonText().size());
        }
        if (!plan.captions().isEmpty()) {
            metadata.put("pdf_table_caption_references", plan.captions().size());
        }
        if (plan.headers() > 0) {
            metadata.put("pdf_table_header_rows", plan.headers());
            metadata.put("pdf_table_header_policy", "source_structure_prefix_v1");
        }
        if (options.includeTableCellCoordinates()) {
            metadata.put("pdf_table_cells",
                    PdfTableCells.describe(empty == 0 ? "tagged_text_bounds" : "tagged_inferred_cell_bounds",
                            plan.rows(), plan.columns(), descriptions));
        }
        if (empty > 0) {
            metadata.put("pdf_table_tagged_empty_cells", empty);
        }
        if (dates > 0) {
            metadata.put("pdf_table_cell_text_policy", "wrapped_iso_date_v1");
            metadata.put("pdf_table_joined_iso_dates", dates);
        }
        if (softHyphens > 0) {
            metadata.put("pdf_table_cell_text_policy", "source_soft_hyphens_and_wrapped_iso_date_v1");
            metadata.put("pdf_table_joined_soft_hyphens", softHyphens);
        }
        if (multilineCells > 0) {
            metadata.put("pdf_table_cell_text_policy", "native_line_breaks_v1");
            metadata.put("pdf_table_multiline_cells", multilineCells);
        }
        if (scriptGlyphs > 0) {
            metadata.put("pdf_table_cell_text_policy", "native_inline_script_runs_v1");
            metadata.put("pdf_table_inline_script_glyphs", scriptGlyphs);
        }
        if (join == PdfNativeLayoutOptions.CellTextJoin.SOURCE_WORD_WRAPS_AND_ISO_DATES
                && (dates > 0 || softHyphens > 0 || retainedHyphenWraps > 0)) {
            metadata.put("pdf_table_cell_text_policy", "source_word_wraps_and_iso_dates_v1");
        }
        if (retainedHyphenWraps > 0) {
            metadata.put("pdf_table_retained_hyphen_wraps", retainedHyphenWraps);
        }
        return new Prepared(new PdfLayoutItem(ElementType.Table,
                                    String.join(" ", values.stream().filter(value -> !value.isBlank()).toList()),
                                    envelope(cellBounds), metadata),
                List.copyOf(glyphs), captionGlyphs, plan.cells().size());
    }

    /**
     * Validates explicit same-page Caption references without claiming or copying their glyphs into a table.
     * Caption text remains available to normal native fallback. Missing/reused/empty caption content and
     * displayed caption runs overlapping the table envelope reject the complete source table.
     * @param plan complete original source table
     * @param text retained native source identity map
     * @param geometry displayed original source frame
     * @param bounds accepted table's real cell text envelope
     * @param claimed table-owned original glyph identities
     * @param work shared page geometry/cancellation budget
     * @return immutable source caption identities, empty when absent, or null for unsupported captions
     * @throws IOException if cancellation or shared work limits prevent validation
     */
    private static List<PdfPageText.Glyph> captionGlyphsOutsideTable(final PdfTaggedStructure.Table plan,
            final PdfPageText text, final PdfPageGeometry geometry, final PdfLayoutItem.Bounds bounds,
            final Set<PdfPageText.Glyph> claimed, final PdfTableWork work) throws IOException {
        if (plan.captions().isEmpty()) {
            return List.of();
        }
        final var unique = Collections.newSetFromMap(new IdentityHashMap<PdfPageText.Glyph, Boolean>());
        final var glyphs = new ArrayList<PdfPageText.Glyph>();
        for (final var reference : plan.captions()) {
            work.step();
            if (reference.page() != plan.page() || !text.markedDeclared(reference.id())) {
                return null;
            }
            for (final var glyph : text.marked(reference.id())) {
                work.step();
                if (claimed.contains(glyph) || !unique.add(glyph)) {
                    return null;
                }
                glyphs.add(glyph);
            }
        }
        final var lines = PdfGlyphLines.displayed(glyphs, geometry);
        if (lines.isEmpty()) {
            return null;
        }
        for (final var line : lines) {
            work.step();
            if (overlaps(bounds, line.box())) {
                return null;
            }
        }
        return List.copyOf(glyphs);
    }

    /**
     * Requires exclusive ownership inside the selected real source envelopes without locating blank slots.
     * @param boxes actual cell text envelopes, null for blank source slots
     * @param text all original retained page positions
     * @param geometry displayed source frame
     * @param claimed exact source-owned positions
     * @param work shared bounded page counter
     * @param policy whole-parent exclusion or explicit preservation of unclaimed inter-cell gap text
     * @return nonempty source boxes or null for unsupported/contaminated envelopes
     * @throws IOException if work or cancellation checks fail
     */
    private static List<PdfLayoutItem.Bounds> logicalEnvelope(final List<PdfLayoutItem.Bounds> boxes,
            final PdfPageText text, final PdfPageGeometry geometry, final Set<PdfPageText.Glyph> claimed,
            final PdfTableWork work, final PdfTaggedTableGeometry policy) throws IOException {
        final var nonempty = boxes.stream().filter(java.util.Objects::nonNull).toList();
        if (nonempty.isEmpty()) {
            return null;
        }
        final var outer = envelope(nonempty);
        for (final var glyph : text.glyphs()) {
            work.step();
            if (claimed.contains(glyph)) {
                if (geometry.textRotation(glyph.direction()) != 0) {
                    return null;
                }
            } else if (!glyph.text().isBlank()) {
                final var center = geometry.center(glyph);
                if (center.x >= outer.left() && center.x <= outer.right() && center.y >= outer.top()
                        && center.y <= outer.bottom()) {
                    if (policy == PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE) {
                        return null;
                    }
                    for (final var box : nonempty) {
                        work.step();
                        if (center.x >= box.left() && center.x <= box.right() && center.y >= box.top()
                                && center.y <= box.bottom()) {
                            return null;
                        }
                    }
                }
            }
        }
        return nonempty;
    }

    /**
     * Infers explicit logical slot rectangles only when every row/column has unspanned source text support.
     * Unknown glyphs inside an empty slot invalidate the whole candidate; no glyph is assigned by these rectangles.
     * @param plan source logical topology
     * @param boxes actual cell text envelopes, null only for empty source cells
     * @param text original source positions
     * @param geometry source/displayed frame
     * @param claimed exact candidate glyph identities
     * @param work shared work counter
     * @return inferred cell bounds or null when geometry/source ownership is ambiguous
     * @throws IOException if work/cancellation fails
     */
    private static List<PdfLayoutItem.Bounds> inferred(final PdfTaggedStructure.Table plan,
            final List<PdfLayoutItem.Bounds> boxes, final PdfPageText text, final PdfPageGeometry geometry,
            final Set<PdfPageText.Glyph> claimed, final PdfTableWork work) throws IOException {
        if (claimed.stream().anyMatch(glyph -> geometry.textRotation(glyph.direction()) != 0)) {
            return null;
        }
        final var nonempty = boxes.stream().filter(java.util.Objects::nonNull).toList();
        if (nonempty.isEmpty()) {
            return null;
        }
        final var outer = envelope(nonempty);
        final var xs = axis(plan, boxes, true, outer.left(), outer.right(), work);
        final var ys = axis(plan, boxes, false, outer.top(), outer.bottom(), work);
        if (xs == null || ys == null) {
            return null;
        }
        final var result = new ArrayList<PdfLayoutItem.Bounds>();
        for (int i = 0; i < plan.cells().size(); i++) {
            work.step();
            final var cell = plan.cells().get(i);
            final var bounds = new PdfLayoutItem.Bounds(xs[cell.column()], ys[cell.row()],
                    xs[cell.column() + cell.columns()], ys[cell.row() + cell.rows()]);
            if (boxes.get(i) == null) {
                for (final var glyph : text.glyphs()) {
                    work.step();
                    if (!glyph.text().isBlank() && !claimed.contains(glyph)) {
                        final var center = geometry.center(glyph);
                        if (center.x >= bounds.left() && center.x < bounds.right() && center.y >= bounds.top()
                                && center.y < bounds.bottom()) {
                            return null;
                        }
                    }
                }
            }
            result.add(bounds);
        }
        return result;
    }

    /**
     * Forms midpoint boundaries from independently supported single-row/single-column source text bands.
     * @param plan source logical grid
     * @param boxes source text envelopes, including null empty cells
     * @param horizontal true for column support, false for row support
     * @param minimum enclosing source text edge
     * @param maximum enclosing source text edge
     * @param work shared work counter
     * @return increasing source-supported bounds or null when an axis is missing/ambiguous
     * @throws IOException if work/cancellation fails
     */
    private static float[] axis(final PdfTaggedStructure.Table plan, final List<PdfLayoutItem.Bounds> boxes,
            final boolean horizontal, final float minimum, final float maximum, final PdfTableWork work)
            throws IOException {
        final int count = horizontal ? plan.columns() : plan.rows();
        final var low = new float[count];
        final var high = new float[count];
        java.util.Arrays.fill(low, Float.POSITIVE_INFINITY);
        java.util.Arrays.fill(high, Float.NEGATIVE_INFINITY);
        for (int i = 0; i < plan.cells().size(); i++) {
            work.step();
            final var cell = plan.cells().get(i);
            final var box = boxes.get(i);
            final int span = horizontal ? cell.columns() : cell.rows();
            final int origin = horizontal ? cell.column() : cell.row();
            if (box != null && span == 1) {
                low[origin] = Math.min(low[origin], horizontal ? box.left() : box.top());
                high[origin] = Math.max(high[origin], horizontal ? box.right() : box.bottom());
            }
        }
        final var boundaries = new float[count + 1];
        boundaries[0] = minimum;
        boundaries[count] = maximum;
        for (int i = 0; i < count; i++) {
            work.step();
            if (!Float.isFinite(low[i]) || !Float.isFinite(high[i])) {
                return null;
            }
            if (i > 0) {
                boundaries[i] = (high[i - 1] + low[i]) / 2;
            }
        }
        for (int i = 0; i < count; i++) {
            work.step();
            final float center = (low[i] + high[i]) / 2;
            if (!(boundaries[i] < center && center < boundaries[i + 1])) {
                return null;
            }
        }
        return boundaries;
    }

    /**
     * Finds the enclosing text rectangle without adjusting source cells or topology.
     * @param boxes nonempty finite source/displayed text envelopes
     * @return enclosing rectangle
     */
    private static PdfLayoutItem.Bounds envelope(final List<PdfLayoutItem.Bounds> boxes) {
        return new PdfLayoutItem.Bounds(
                (float) boxes.stream().mapToDouble(PdfLayoutItem.Bounds::left).min().orElseThrow(),
                (float) boxes.stream().mapToDouble(PdfLayoutItem.Bounds::top).min().orElseThrow(),
                (float) boxes.stream().mapToDouble(PdfLayoutItem.Bounds::right).max().orElseThrow(),
                (float) boxes.stream().mapToDouble(PdfLayoutItem.Bounds::bottom).max().orElseThrow());
    }

    /**
     * Validates finite positive text envelopes.
     * @param box actual native text envelope
     * @return whether it has usable geometry
     */
    private static boolean finite(final PdfLayoutItem.Bounds box) {
        return Float.isFinite(box.left()) && Float.isFinite(box.top()) && Float.isFinite(box.right())
                && Float.isFinite(box.bottom()) && box.left() < box.right() && box.top() < box.bottom();
    }

    /**
     * Tests strict positive interior overlap; touching edges remain disjoint.
     * @param first original displayed bounds
     * @param second original displayed bounds
     * @return positive interior overlap
     */
    static boolean overlaps(final PdfLayoutItem.Bounds first, final PdfLayoutItem.Bounds second) {
        return first.left() < second.right() && first.right() > second.left() && first.top() < second.bottom()
                && first.bottom() > second.top();
    }
}
