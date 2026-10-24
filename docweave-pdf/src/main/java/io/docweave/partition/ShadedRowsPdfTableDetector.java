package io.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Explicit native financial-style row inference from pale paint and repeated numeric subtotal rules. */
final class ShadedRowsPdfTableDetector implements PdfNativeTableDetector {
    /** Source-coordinate tolerance; does not join separate painted row intervals. */
    private static final float SNAP = 0.75f;
    /** Narrow numeric syntax, including original currency, balanced parentheses and missing-value dashes. */
    private static final Pattern NUMBER =
            Pattern.compile("(?:[$€£¥]\\s*)?(?:[+\\-−]?[0-9]+(?:[.,][0-9]+)*%?|\\([0-9]+(?:[.,][0-9]+)*%?\\)|[—–-])");
    /** Immutable structured-output bounds. */
    private final TableLimits limits;
    /** Original displayed geometry. */
    private final PdfPageGeometry geometry;
    /** Original opaque pale rectangles, never rendered or converted into borders. */
    private final List<FilledPdfRowBand> bands;
    /** Explicit numeric-cell projection; false preserves historical source padding. */
    private final boolean compactPadding;
    /** Explicit whole-run script grouping; false retains earlier per-glyph interpretation. */
    private final boolean scriptRuns;

    /**
     * Captures caller limits and original source evidence without reference labels.
     * @param limits table output limits
     * @param geometry displayed page geometry
     * @param bands explicitly retained source paint
     */
    ShadedRowsPdfTableDetector(
            final TableLimits limits, final PdfPageGeometry geometry, final List<FilledPdfRowBand> bands) {
        this(limits, geometry, bands, false);
    }

    /**
     * Captures an explicit digit-padding interpretation independently of row recognition.
     * @param limits structured-output bounds
     * @param geometry original displayed frame
     * @param bands original row paint
     * @param compactPadding whether to omit unique shifted overlaid ASCII padding in numeric cells
     */
    ShadedRowsPdfTableDetector(final TableLimits limits, final PdfPageGeometry geometry,
            final List<FilledPdfRowBand> bands, final boolean compactPadding) {
        this(limits, geometry, bands, compactPadding, false);
    }

    /**
     * Captures independently explicit padding and whole-script grouping interpretations.
     * @param limits structured-output bounds
     * @param geometry original displayed frame
     * @param bands original row paint
     * @param compactPadding whether numeric cells may project unique overlaid padding
     * @param scriptRuns whether complete small glyph runs may share a unique body baseline
     */
    ShadedRowsPdfTableDetector(final TableLimits limits, final PdfPageGeometry geometry,
            final List<FilledPdfRowBand> bands, final boolean compactPadding, final boolean scriptRuns) {
        this.limits = limits;
        this.geometry = geometry;
        this.bands = List.copyOf(bands);
        this.compactPadding = compactPadding;
        this.scriptRuns = scriptRuns;
    }

    /**
     * One source ordinate with unjoined horizontal painted intervals.
     * @param y original rule ordinate
     * @param spans sorted original intervals
     */
    private record Rule(float y, List<PdfPageGraphics.Segment> spans) {}

    /**
     * One immutable text projection without altering original membership or inferred cell areas.
     * @param cells source-coordinate cell values
     * @param omittedPaddingGlyphs positions omitted only from explicitly selected text projection
     */
    private record Row(List<PdfInferredTable.Cell> cells, int omittedPaddingGlyphs) {}

    /**
     * Detects disjoint complete candidates without consuming rejected native source positions.
     * @param glyphs currently unowned original native positions
     * @param segments source subtotal support and vertical barriers
     * @param images original raster barriers
     * @param work shared page work and cancellation counter
     * @return immutable accepted tables with original glyph indices
     * @throws IOException if cancellation, work or structured-output bounds fail
     */
    @Override
    public List<PdfInferredTable> detect(final List<PdfPageText.Glyph> glyphs,
            final List<PdfPageGraphics.Segment> segments, final List<PdfPageGraphics.Image> images,
            final PdfTableWork work) throws IOException {
        if (geometry.textRotation(0) != 0 || glyphs.stream().anyMatch(g -> g.direction() != 0)) {
            return List.of();
        }
        final var rules = rules(segments, work);
        final var groups = new ArrayList<List<FilledPdfRowBand>>();
        for (final var band : rowPaint(bands, work)) {
            List<FilledPdfRowBand> selected = null;
            for (final var group : groups) {
                work.step();
                final var first = group.getFirst();
                final var last = group.getLast();
                if (first.rgb() == band.rgb() && Math.abs(first.bounds().left() - band.bounds().left()) <= SNAP
                        && band.bounds().top() - last.bounds().bottom() <= 64) {
                    selected = group;
                    break;
                }
            }
            if (selected == null) {
                selected = new ArrayList<>();
                groups.add(selected);
            }
            selected.add(band);
        }
        final var candidates = new ArrayList<PdfInferredTable>();
        for (final var group : groups) {
            work.step();
            if (group.size() >= 4) {
                final var table = accept(group, rules, glyphs, segments, images, work);
                if (table != null) {
                    candidates.add(table);
                }
            }
        }
        final var result = new ArrayList<PdfInferredTable>();
        for (int first = 0; first < candidates.size(); first++) {
            boolean overlaps = false;
            for (int second = 0; second < candidates.size(); second++) {
                work.step();
                if (first != second && intersects(candidates.get(first).frame(), candidates.get(second).frame())) {
                    overlaps = true;
                }
            }
            if (!overlaps) {
                result.add(candidates.get(first));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Coalesces same-color, same-height source cell paint within one fixed row ordinate.
     * Contained duplicate paints do not create extra rows. Horizontal gaps are at most four
     * source units and half the row height; vertical gaps are never bridged. Resulting envelopes
     * must be at least four times as wide as tall. This check applies after cell coalescing,
     * allowing tall individual cell fills while rejecting isolated block-shaped envelopes.
     * Resulting envelopes are explicit row inferences, not painted borders or semantic merge evidence.
     * @param bands original rectangles
     * @param work page work/cancellation counter
     * @return top-sorted row-paint envelopes without joining distinct colors or ordinates
     * @throws IOException if interrupted or over budget
     */
    private static List<FilledPdfRowBand> rowPaint(final List<FilledPdfRowBand> bands, final PdfTableWork work)
            throws IOException {
        final var ordinates = new ArrayList<List<FilledPdfRowBand>>();
        for (final var band : bands.stream().sorted(Comparator.comparingDouble(b -> b.bounds().top())).toList()) {
            work.step();
            List<FilledPdfRowBand> selected = null;
            for (final var ordinate : ordinates) {
                work.step();
                final var first = ordinate.getFirst();
                if (first.rgb() == band.rgb() && Math.abs(first.bounds().top() - band.bounds().top()) <= 0.5
                        && Math.abs(first.bounds().bottom() - band.bounds().bottom()) <= 0.5) {
                    selected = ordinate;
                    break;
                }
            }
            if (selected == null) {
                selected = new ArrayList<>();
                ordinates.add(selected);
            }
            selected.add(band);
        }
        final var result = new ArrayList<FilledPdfRowBand>();
        for (final var ordinate : ordinates) {
            ordinate.sort(Comparator.comparingDouble((FilledPdfRowBand b) -> b.bounds().left())
                            .thenComparing(
                                    Comparator.comparingDouble((FilledPdfRowBand b) -> b.bounds().right()).reversed()));
            final var merged = new ArrayList<FilledPdfRowBand>();
            for (final var band : ordinate) {
                work.step();
                if (!merged.isEmpty()) {
                    final var previous = merged.getLast();
                    final var first = previous.bounds();
                    final var second = band.bounds();
                    if (previous.rgb() == band.rgb() && Math.abs(first.top() - second.top()) <= 0.5
                            && Math.abs(first.bottom() - second.bottom()) <= 0.5
                            && second.left() - first.right() <= Math.min(4, (first.bottom() - first.top()) / 2)) {
                        merged.set(merged.size() - 1,
                                new FilledPdfRowBand(
                                        new PdfLayoutItem.Bounds(first.left(), Math.min(first.top(), second.top()),
                                                Math.max(first.right(), second.right()),
                                                Math.max(first.bottom(), second.bottom())),
                                        previous.rgb()));
                        continue;
                    }
                }
                merged.add(band);
            }
            for (final var band : merged) {
                work.step();
                final var bounds = band.bounds();
                if (bounds.right() - bounds.left() >= (bounds.bottom() - bounds.top()) * 4) {
                    result.add(band);
                }
            }
        }
        final var retained = new ArrayList<FilledPdfRowBand>();
        for (int index = 0; index < result.size(); index++) {
            final var inner = result.get(index);
            boolean contained = false;
            for (int other = 0; other < result.size(); other++) {
                work.step();
                final var outer = result.get(other);
                if (index == other || inner.rgb() != outer.rgb()) {
                    continue;
                }
                final var a = inner.bounds();
                final var b = outer.bounds();
                if (b.left() <= a.left() && b.right() >= a.right() && b.top() <= a.top() && b.bottom() >= a.bottom()
                        && (other < index || !a.equals(b))) {
                    contained = true;
                }
            }
            if (!contained) {
                retained.add(inner);
            }
        }
        retained.sort(Comparator.comparingDouble(b -> b.bounds().top()));
        return List.copyOf(retained);
    }

    /**
     * Groups horizontal rules without bridging paint gaps or collecting vertical borders.
     * @param segments original painted segments
     * @param work shared page counter
     * @return ordinate-sorted source groups with left-sorted intervals
     * @throws IOException if interrupted or over budget
     */
    private static List<Rule> rules(final List<PdfPageGraphics.Segment> segments, final PdfTableWork work)
            throws IOException {
        final var selected = new ArrayList<PdfPageGraphics.Segment>();
        for (final var segment : segments) {
            work.step();
            if (Math.abs(segment.y2() - segment.y1()) <= 0.5 && Math.abs(segment.x2() - segment.x1()) >= 12) {
                selected.add(new PdfPageGraphics.Segment(Math.min(segment.x1(), segment.x2()), segment.y1(),
                        Math.max(segment.x1(), segment.x2()), segment.y2()));
            }
        }
        selected.sort(Comparator.comparingDouble(PdfPageGraphics.Segment::y1));
        final var result = new ArrayList<Rule>();
        for (final var segment : selected) {
            work.step();
            if (result.isEmpty() || segment.y1() - result.getLast().y() > 0.5) {
                result.add(new Rule(segment.y1(), new ArrayList<>()));
            }
            result.getLast().spans().add(segment);
        }
        result.forEach(rule -> rule.spans().sort(Comparator.comparingDouble(PdfPageGraphics.Segment::x1)));
        return result;
    }

    /**
     * Requires repeated disjoint numeric rule intervals and rejects competing equally supported patterns.
     * Double underlines less than four units apart count as one supporting ordinate.
     * @param group repeated same-color row paint
     * @param rules complete horizontal source groups
     * @param work page work counter
     * @return uniquely supported numeric intervals, or empty on ambiguity
     * @throws IOException if interrupted or over budget
     */
    private static List<PdfPageGraphics.Segment> columns(
            final List<FilledPdfRowBand> group, final List<Rule> rules, final PdfTableWork work) throws IOException {
        List<PdfPageGraphics.Segment> best = List.of();
        int support = 2;
        boolean tie = false;
        final double paintRight = group.stream().mapToDouble(b -> b.bounds().right()).max().orElseThrow();
        for (final var candidate : rules) {
            work.step();
            final var spans = candidate.spans();
            if (candidate.y() < group.getFirst().bounds().top()
                    || candidate.y() > group.getLast().bounds().bottom() + 24 || spans.size() < 2 || spans.size() > 8
                    || spans.getFirst().x1() <= group.getFirst().bounds().left() + 36
                    || Math.abs(spans.getLast().x2() - paintRight) > SNAP) {
                continue;
            }
            boolean disjoint = true;
            for (int index = 1; index < spans.size(); index++) {
                work.step();
                disjoint &= spans.get(index).x1() - spans.get(index - 1).x2() >= 1;
            }
            if (!disjoint) {
                continue;
            }
            int count = 0;
            float last = Float.NEGATIVE_INFINITY;
            for (final var rule : rules) {
                work.step();
                if (rule.y() >= group.getFirst().bounds().top() && rule.y() <= group.getLast().bounds().bottom() + 24
                        && rule.y() - last >= 4 && sameColumns(spans, rule.spans(), work)) {
                    count++;
                    last = rule.y();
                }
            }
            if (count > support) {
                support = count;
                best = spans;
                tie = false;
            } else if (count == support && !best.isEmpty() && !sameColumns(best, spans, work)) {
                tie = true;
            }
        }
        return tie ? List.of() : best;
    }

    /**
     * Compares original numeric interval endpoints with fixed tolerance, without transitive snapping.
     * @param first original pattern
     * @param second other original pattern
     * @param work shared counter
     * @return whether all endpoints agree
     * @throws IOException if interrupted or over budget
     */
    private static boolean sameColumns(final List<PdfPageGraphics.Segment> first,
            final List<PdfPageGraphics.Segment> second, final PdfTableWork work) throws IOException {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            work.step();
            if (Math.abs(first.get(index).x1() - second.get(index).x1()) > SNAP
                    || Math.abs(first.get(index).x2() - second.get(index).x2()) > SNAP) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds one complete source-owned table from paint-delimited rows and repeated numeric columns.
     * @param group same-color source row bands
     * @param rules original horizontal paint
     * @param glyphs currently unowned original native positions
     * @param segments all painted barriers
     * @param images original raster barriers
     * @param work shared page work/cancellation counter
     * @return complete candidate, or null without consuming any glyph
     * @throws IOException if output/work limits or cancellation fail
     */
    private PdfInferredTable accept(final List<FilledPdfRowBand> group, final List<Rule> rules,
            final List<PdfPageText.Glyph> glyphs, final List<PdfPageGraphics.Segment> segments,
            final List<PdfPageGraphics.Image> images, final PdfTableWork work) throws IOException {
        final var pattern = columns(group, rules, work);
        if (pattern.isEmpty()) {
            return null;
        }
        final float left = group.getFirst().bounds().left();
        final float right = (float) group.stream().mapToDouble(b -> b.bounds().right()).max().orElseThrow();
        if (group.stream().filter(b -> Math.abs(b.bounds().right() - right) <= SNAP).count() < 4) {
            return null;
        }
        for (final var band : group) {
            work.step();
            if (Math.abs(band.bounds().right() - right) > SNAP
                    && Math.abs(band.bounds().right() - pattern.getFirst().x1()) > SNAP) {
                return null;
            }
        }
        final float firstTop = group.getFirst().bounds().top();
        if (left < 0 || right > geometry.width() || firstTop < 0
                || group.getLast().bounds().bottom() > geometry.height()) {
            return null;
        }
        final var headers = new ArrayList<Float>();
        for (final var rule : rules) {
            work.step();
            if (rule.y() >= firstTop - 64 && rule.y() <= firstTop - 2
                    && Math.abs(rule.spans().getFirst().x1() - pattern.getFirst().x1()) <= SNAP
                    && Math.abs(rule.spans().getLast().x2() - right) <= SNAP
                    && (headers.isEmpty() || rule.y() - headers.getLast() >= 4)) {
                headers.add(rule.y());
            }
        }
        if (headers.isEmpty() || headers.size() > 2 || headers.getFirst() <= 0) {
            return null;
        }
        final var xs = new ArrayList<Float>();
        xs.add(left);
        xs.add(pattern.getFirst().x1());
        for (int index = 1; index < pattern.size(); index++) {
            xs.add((pattern.get(index - 1).x2() + pattern.get(index).x1()) / 2);
        }
        xs.add(right);
        final var ys = new ArrayList<Float>();
        float headerTop = Math.max(0, headers.getFirst() - (headers.size() == 1 ? 24 : 14));
        if (headers.size() == 2) {
            for (final var rule : rules) {
                work.step();
                if (rule.y() == headers.getFirst()) {
                    headerTop = PdfShadedHeaderBounds.top(headerTop,
                            Math.min(12, (headers.getLast() - headers.getFirst()) / 2), left, right, rule.spans(),
                            glyphs, work);
                    break;
                }
            }
        }
        ys.add(headerTop);
        ys.addAll(headers);
        for (final var band : group) {
            work.step();
            if (band.bounds().top() < ys.getLast() - SNAP) {
                return null;
            }
            if (band.bounds().top() > ys.getLast() + SNAP) {
                ys.add(band.bounds().top());
            }
            ys.add(band.bounds().bottom());
        }
        final float lastBottom = ys.getLast();
        final float extension = Math.min(24, group.getLast().bounds().bottom() - group.getLast().bounds().top());
        float bottom = lastBottom;
        for (final var glyph : glyphs) {
            work.step();
            if (glyph.x() >= left && glyph.x() + glyph.width() <= right + SNAP && glyph.y() > lastBottom
                    && glyph.y() <= lastBottom + extension) {
                bottom = Math.max(bottom, glyph.y() + 1);
            }
        }
        if (bottom > lastBottom) {
            if (bottom > geometry.height()) {
                return null;
            }
            ys.add(bottom);
        }
        final var frame = new PdfLayoutItem.Bounds(left, ys.getFirst(), right, ys.getLast());
        for (final var image : images) {
            work.step();
            if (intersects(frame, new PdfLayoutItem.Bounds(image.x(), image.y(), image.x() + image.width(),
                                          image.y() + image.height()))) {
                return null;
            }
        }
        for (final var segment : segments) {
            work.step();
            if (Math.abs(segment.x1() - segment.x2()) <= 0.5 && Math.abs(segment.y1() - segment.y2()) >= 4
                    && intersects(
                            frame, new PdfLayoutItem.Bounds(Math.min(segment.x1(), segment.x2()),
                                           Math.min(segment.y1(), segment.y2()), Math.max(segment.x1(), segment.x2()),
                                           Math.max(segment.y1(), segment.y2())))) {
                return null;
            }
        }
        final int rowCount = ys.size() - 1;
        final int columnCount = xs.size() - 1;
        if (rowCount > limits.maxRows() || columnCount > limits.maxColumns()
                || (long) rowCount * columnCount > limits.maxCells()) {
            throw new PartitionException(PartitionException.Code.TABLE_LIMIT, "PDF shaded table exceeds table limits");
        }
        final var rows = new ArrayList<List<PdfPageText.Glyph>>();
        for (int row = 0; row < rowCount; row++) {
            rows.add(new ArrayList<>());
        }
        final var members = new ArrayList<Integer>();
        for (int index = 0; index < glyphs.size(); index++) {
            work.step();
            final var glyph = glyphs.get(index);
            final float centerX = glyph.x() + glyph.width() / 2;
            final int row = interval(glyph.y() - glyph.height() / 2, ys, work);
            if (row >= 0 && centerX >= left && centerX < right) {
                final int column = interval(centerX, xs, work);
                if (glyph.text().isBlank()
                        && (glyph.x() < left - SNAP || glyph.x() + glyph.width() > right + SNAP
                                || glyph.y() - glyph.height() < frame.top() - SNAP || glyph.y() > frame.bottom() + SNAP
                                || row >= headers.size() && column >= 0
                                           && (glyph.x() < xs.get(column) - SNAP
                                                   || glyph.x() + glyph.width() > xs.get(column + 1) + SNAP))) {
                    // Padding crossing an inferred boundary stays unowned; it cannot justify moving that boundary.
                    continue;
                }
                if (glyph.x() < left - SNAP || glyph.x() + glyph.width() > right + SNAP
                        || glyph.y() - glyph.height() < frame.top() - SNAP || glyph.y() > frame.bottom() + SNAP) {
                    return null;
                }
                rows.get(row).add(glyph);
                members.add(index);
            }
        }
        final var result = new ArrayList<List<PdfInferredTable.Cell>>();
        int numericRows = 0;
        int omittedPaddingGlyphs = 0;
        for (int row = 0; row < rowCount; row++) {
            final var projected = cells(rows.get(row), xs, ys.get(row), ys.get(row + 1), row < headers.size(), work);
            if (projected == null) {
                return null;
            }
            final var cells = projected.cells();
            omittedPaddingGlyphs += projected.omittedPaddingGlyphs();
            if (row >= headers.size()) {
                final var values =
                        cells.stream().filter(cell -> cell.column() > 0).map(PdfInferredTable.Cell::text).toList();
                if (values.stream().anyMatch(value -> !value.isBlank())) {
                    if (values.size() != columnCount - 1
                            || values.stream().anyMatch(value -> !NUMBER.matcher(value).matches())) {
                        return null;
                    }
                    numericRows++;
                }
            }
            for (final var cell : cells) {
                work.step();
                if (cell.text().codePointCount(0, cell.text().length()) > limits.maxCellCharacters()) {
                    throw new PartitionException(
                            PartitionException.Code.TABLE_LIMIT, "PDF shaded cell exceeds maxCellCharacters");
                }
            }
            result.add(cells);
        }
        return numericRows >= 3
                ? new PdfInferredTable(List.copyOf(result), columnCount, headers.size(), List.copyOf(members),
                          IntStream.range(1, columnCount).boxed().toList(), frame, omittedPaddingGlyphs)
                : null;
    }

    /**
     * Assigns complete body glyphs to fixed columns, or infers header spans from whole source run extents.
     * @param glyphs complete source row positions
     * @param xs supported column boundaries
     * @param top original paint-derived row top
     * @param bottom original row bottom
     * @param header whether source runs may span adjacent header columns
     * @param work shared counter
     * @return immutable cells and projection counts, or null on ambiguous ownership
     * @throws IOException if cancelled or work limits fail
     */
    private Row cells(final List<PdfPageText.Glyph> glyphs, final List<Float> xs, final float top, final float bottom,
            final boolean header, final PdfTableWork work) throws IOException {
        final int columns = xs.size() - 1;
        final var text = new StringBuilder[columns];
        final int[] spans = new int[columns];
        int omittedPaddingGlyphs = 0;
        for (int column = 0; column < columns; column++) {
            text[column] = new StringBuilder();
            spans[column] = 1;
        }
        if (header) {
            for (final var line : PdfGlyphLines.lines(glyphs)) {
                work.step();
                float inkLeft = Float.POSITIVE_INFINITY;
                float inkRight = Float.NEGATIVE_INFINITY;
                for (final var glyph : glyphs) {
                    work.step();
                    if (!glyph.text().isBlank() && glyph.width() > 0 && Math.abs(glyph.y() - line.box().bottom()) <= 2
                            && glyph.x() >= line.box().left() - SNAP
                            && glyph.x() + glyph.width() <= line.box().right() + SNAP) {
                        inkLeft = Math.min(inkLeft, glyph.x());
                        inkRight = Math.max(inkRight, glyph.x() + glyph.width());
                    }
                }
                // Source padding may cross an inferred column; only nonblank positions justify a header span.
                final int first = interval(Math.max(xs.getFirst(), inkLeft + SNAP), xs, work);
                final int last = interval(Math.min(xs.getLast() - 0.01f, inkRight - SNAP), xs, work);
                if (first < 0 || last < first) {
                    return null;
                }
                final boolean continuation = !text[first].isEmpty();
                if (spans[first] != (continuation ? last - first + 1 : 1)) {
                    return null;
                }
                for (int column = first + 1; column <= last; column++) {
                    if (!text[column].isEmpty() || spans[column] != (continuation ? 0 : 1)) {
                        return null;
                    }
                }
                if (!text[first].isEmpty()) {
                    text[first].append(' ');
                }
                text[first].append(line.text());
                spans[first] = last - first + 1;
                for (int column = first + 1; column <= last; column++) {
                    spans[column] = 0;
                }
            }
        } else {
            final var positions = new ArrayList<List<PdfPageText.Glyph>>();
            for (int column = 0; column < columns; column++) {
                positions.add(new ArrayList<>());
            }
            for (final var glyph : glyphs) {
                work.step();
                final int column = interval(glyph.x() + glyph.width() / 2, xs, work);
                if (column < 0 || glyph.x() < xs.get(column) - SNAP
                        || glyph.x() + glyph.width() > xs.get(column + 1) + SNAP) {
                    return null;
                }
                positions.get(column).add(glyph);
            }
            for (int column = 0; column < columns; column++) {
                final var lines = PdfParagraphInlineScripts.lines(positions.get(column), work).lines();
                final String original = String.join(" ", lines.stream().map(PdfLayoutItem::text).toList());
                if (scriptRuns && original.codePointCount(0, original.length()) > limits.maxCellCharacters()) {
                    throw new PartitionException(PartitionException.Code.TABLE_LIMIT,
                            "PDF shaded cell exceeds maxCellCharacters before script grouping");
                }
                if (compactPadding && column > 0) {
                    if (original.codePointCount(0, original.length()) > limits.maxCellCharacters()) {
                        throw new PartitionException(PartitionException.Code.TABLE_LIMIT,
                                "PDF shaded cell exceeds maxCellCharacters before padding projection");
                    }
                    final var projection = PdfOverlaidDigitPadding.project(positions.get(column), work);
                    omittedPaddingGlyphs += projection.omittedPaddingGlyphs();
                    if (projection.omittedPaddingGlyphs() > 0) {
                        text[column].append(String.join(" ", bodyLines(projection.glyphs(), work)
                                                                     .lines()
                                                                     .stream()
                                                                     .map(PdfLayoutItem::text)
                                                                     .toList()));
                    } else {
                        text[column].append(scriptRuns ? String.join(" ", bodyLines(positions.get(column), work)
                                                                                  .lines()
                                                                                  .stream()
                                                                                  .map(PdfLayoutItem::text)
                                                                                  .toList())
                                                       : original);
                    }
                } else {
                    text[column].append(scriptRuns ? String.join(" ", bodyLines(positions.get(column), work)
                                                                              .lines()
                                                                              .stream()
                                                                              .map(PdfLayoutItem::text)
                                                                              .toList())
                                                   : original);
                }
            }
        }
        final var cells = new ArrayList<PdfInferredTable.Cell>();
        for (int column = 0; column < columns; column++) {
            work.step();
            if (spans[column] > 0) {
                cells.add(new PdfInferredTable.Cell(column, spans[column], text[column].toString(),
                        new PdfLayoutItem.Bounds(xs.get(column), top, xs.get(column + spans[column]), bottom)));
            }
        }
        return new Row(List.copyOf(cells), omittedPaddingGlyphs);
    }

    /**
     * Selects the caller's source-preserving body baseline interpretation.
     * @param positions complete original cell positions or explicit numeric-padding projection
     * @param work shared page counter
     * @return original-coordinate runs and assignment count
     * @throws IOException if shared work or cancellation limits fail
     */
    private PdfParagraphInlineScripts.Result bodyLines(final List<PdfPageText.Glyph> positions, final PdfTableWork work)
            throws IOException {
        return scriptRuns ? PdfSmallGlyphRuns.lines(positions, work) : PdfParagraphInlineScripts.lines(positions, work);
    }

    /**
     * Finds one half-open source interval without transitive coordinate changes.
     * @param value original source coordinate
     * @param boundaries ascending source-supported boundaries
     * @param work shared counter
     * @return interval index or minus one
     * @throws IOException if interrupted or over budget
     */
    private static int interval(final float value, final List<Float> boundaries, final PdfTableWork work)
            throws IOException {
        for (int index = 0; index + 1 < boundaries.size(); index++) {
            work.step();
            if (value >= boundaries.get(index) && value < boundaries.get(index + 1)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Tests inclusive source frame overlap for raster, border and competing-candidate barriers.
     * @param first source frame
     * @param second barrier frame
     * @return whether original extents touch or overlap
     */
    private static boolean intersects(final PdfLayoutItem.Bounds first, final PdfLayoutItem.Bounds second) {
        return first.left() <= second.right() && second.left() <= first.right() && first.top() <= second.bottom()
                && second.top() <= first.bottom();
    }
}
