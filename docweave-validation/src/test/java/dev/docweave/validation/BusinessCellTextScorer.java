package dev.docweave.validation;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compares captured table output with pinned Tabula CSV expectations without saving text.
 *
 * <p>Replaces {@code compare_business_cell_text.py}. CSV labels are not an independently reviewed span
 * oracle. Geometry, never text, selects at most one reference table. Merged anchors are projected only at
 * their origin for evaluation, uncovered or missing slots never earn credit for expected blanks, and only
 * ASCII whitespace is collapsed. Every reference position stays in the denominator.
 */
final class BusinessCellTextScorer implements BenchmarkScorer {
    /** ASCII whitespace runs collapsed by {@link #normalize}. */
    private static final Pattern ASCII_SPACE = Pattern.compile("[ \\t\\r\\n\\f\\u000B]+");
    /** Allowed profile names; they become capture file names. */
    private static final Pattern PROFILE = Pattern.compile("[a-z][a-z-]*");
    /** CPython {@code html._charref}. */
    private static final Pattern CHARREF =
            Pattern.compile("&(#[0-9]+;?|#[xX][0-9a-fA-F]+;?|[^\\t\\n\\f <&#;]{1,32};?)");
    /** Named references DocWeave's table escaper emits, with their exact semicolon-terminated forms. */
    private static final Map<String, String> NAMED =
            Map.of("amp;", "&", "lt;", "<", "gt;", ">", "quot;", "\"", "apos;", "'", "nbsp;", " ");
    /** CPython {@code html._invalid_charrefs}: numeric references remapped before any other rule. */
    private static final Map<Integer, String> INVALID_CHARREFS = invalidCharrefs();
    /** CPython {@code html._invalid_codepoints}: numeric references removed entirely. */
    private static final Set<Integer> INVALID_CODEPOINTS = invalidCodepoints();
    /** Elements whose content CPython's parser treats as raw text; rejected rather than reinterpreted. */
    private static final Set<String> RAW_TEXT =
            Set.of("script", "style", "xmp", "iframe", "noembed", "noframes", "textarea", "title", "plaintext");

    /** Creates the scorer. */
    BusinessCellTextScorer() {
    }

    @Override
    public String command() {
        return "business-cell-text";
    }

    /**
     * Builds the Windows-1252 remapping table of {@code html._invalid_charrefs}.
     *
     * @return code point to replacement text
     */
    private static Map<Integer, String> invalidCharrefs() {
        final var result = new HashMap<Integer, String>();
        result.put(0x00, "�");
        result.put(0x0d, "\r");
        final String high = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008d"
                            + "Ž\u008f\u0090‘’“”•–—˜™š›œ"
                            + "\u009džŸ";
        for (int index = 0; index < high.length(); index++) {
            result.put(0x80 + index, String.valueOf(high.charAt(index)));
        }
        return Map.copyOf(result);
    }

    /**
     * Builds {@code html._invalid_codepoints}.
     *
     * @return removed code points
     */
    private static Set<Integer> invalidCodepoints() {
        final var result = new HashSet<Integer>();
        for (int point = 0x1; point <= 0x8; point++) {
            result.add(point);
        }
        for (int point = 0xe; point <= 0x1f; point++) {
            result.add(point);
        }
        for (int point = 0x7f; point <= 0x9f; point++) {
            result.add(point);
        }
        for (int point = 0xfdd0; point <= 0xfdef; point++) {
            result.add(point);
        }
        result.add(0xb);
        for (int plane = 0; plane <= 0x10; plane++) {
            result.add((plane << 16) | 0xfffe);
            result.add((plane << 16) | 0xffff);
        }
        return Set.copyOf(result);
    }

    /**
     * Collapses ASCII whitespace only; case, punctuation and Unicode spacing are preserved.
     *
     * @param value cell text
     * @return normalized text
     */
    static String normalize(final String value) {
        final String collapsed = ASCII_SPACE.matcher(value).replaceAll(" ");
        int start = 0;
        int end = collapsed.length();
        while (start < end && isAsciiSpace(collapsed.charAt(start))) {
            start++;
        }
        while (end > start && isAsciiSpace(collapsed.charAt(end - 1))) {
            end--;
        }
        return collapsed.substring(start, end);
    }

    /**
     * Tests the ASCII whitespace set {@code ' \t\r\n\f\v'}.
     *
     * @param c character
     * @return whether it is ASCII whitespace
     */
    private static boolean isAsciiSpace(final char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == '\u000B';
    }

    /**
     * Decodes character references like CPython {@code html.unescape} for DocWeave's escaped table text.
     *
     * <p>Numeric references follow CPython exactly, including its remapping and removal tables. Named
     * references other than DocWeave's own semicolon-terminated escapes are rejected, because CPython's
     * HTML5 longest-prefix rules would otherwise need to be reproduced.
     *
     * @param text escaped character data
     * @return decoded text
     * @throws IllegalArgumentException for an unsupported named reference
     */
    static String unescape(final String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        final var matcher = CHARREF.matcher(text);
        final var result = new StringBuilder();
        while (matcher.find()) {
            final String reference = matcher.group(1);
            final String replacement;
            if (reference.charAt(0) == '#') {
                final boolean hex = reference.charAt(1) == 'x' || reference.charAt(1) == 'X';
                final String digits = reference.substring(hex ? 2 : 1).replaceAll(";+$", "");
                final var number = new java.math.BigInteger(digits, hex ? 16 : 10);
                replacement = numeric(number);
            } else {
                replacement = NAMED.get(reference);
                if (replacement == null) {
                    throw new IllegalArgumentException("Unsupported character reference");
                }
            }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Resolves one numeric reference.
     *
     * @param number referenced code point, possibly out of range
     * @return replacement text
     */
    private static String numeric(final java.math.BigInteger number) {
        if (number.bitLength() > 31) {
            return "�";
        }
        final int point = number.intValue();
        if (INVALID_CHARREFS.containsKey(point)) {
            return INVALID_CHARREFS.get(point);
        }
        if (point >= 0xd800 && point <= 0xdfff || point > 0x10ffff) {
            return "�";
        }
        if (INVALID_CODEPOINTS.contains(point)) {
            return "";
        }
        return Character.toString(point);
    }

    /**
     * Reads escaped generated cell text without executing or interpreting markup ({@code Cells}).
     *
     * @param html generated {@code text_as_html}
     * @return decoded cell text in row-major document order; {@code <br>} contributes a newline
     * @throws IllegalArgumentException for nested or unmatched cells, unterminated markup or raw-text elements
     */
    static List<String> cells(final String html) {
        final var values = new ArrayList<String>();
        StringBuilder current = null;
        int index = 0;
        while (index < html.length()) {
            final int open = html.indexOf('<', index);
            final int end = open < 0 ? html.length() : open;
            if (current != null && end > index) {
                current.append(unescape(html.substring(index, end)));
            }
            if (open < 0) {
                break;
            }
            if (html.startsWith("<!--", open)) {
                final int close = html.indexOf("-->", open + 4);
                if (close < 0) {
                    throw new IllegalArgumentException("Unterminated comment");
                }
                index = close + 3;
                continue;
            }
            if (html.startsWith("<![", open)) {
                throw new IllegalArgumentException("Unsupported marked section");
            }
            if (html.startsWith("<!", open) || html.startsWith("<?", open)) {
                final int close = html.indexOf('>', open);
                if (close < 0) {
                    throw new IllegalArgumentException("Unterminated declaration");
                }
                index = close + 1;
                continue;
            }
            final boolean endTag = html.startsWith("</", open);
            final int nameStart = open + (endTag ? 2 : 1);
            if (nameStart >= html.length() || !isAsciiLetter(html.charAt(nameStart))) {
                throw new IllegalArgumentException("Unsupported bare '<' in table markup");
            }
            int cursor = nameStart;
            while (cursor < html.length() && " \t\n\r\f/>".indexOf(html.charAt(cursor)) < 0) {
                cursor++;
            }
            final String name = html.substring(nameStart, cursor).toLowerCase(Locale.ROOT);
            final int close = tagEnd(html, cursor);
            final boolean selfClosing = !endTag && html.charAt(close - 1) == '/';
            if (RAW_TEXT.contains(name)) {
                throw new IllegalArgumentException("Unsupported raw-text element: " + name);
            }
            final boolean cell = name.equals("td") || name.equals("th");
            if (!endTag) {
                if (cell) {
                    if (current != null) {
                        throw new IllegalArgumentException("Nested cell");
                    }
                    current = new StringBuilder();
                } else if (name.equals("br") && current != null) {
                    current.append('\n');
                }
            }
            if ((endTag || selfClosing) && cell) {
                if (current == null) {
                    throw new IllegalArgumentException("Unmatched cell");
                }
                values.add(current.toString());
                current = null;
            }
            index = close + 1;
        }
        if (current != null) {
            throw new IllegalArgumentException("HTML/coordinate anchor mismatch");
        }
        return values;
    }

    /**
     * Finds the closing {@code >} of a tag, skipping quoted attribute values.
     *
     * @param html markup
     * @param from offset after the tag name
     * @return offset of {@code >}
     */
    private static int tagEnd(final String html, final int from) {
        char quote = 0;
        for (int index = from; index < html.length(); index++) {
            final char c = html.charAt(index);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return index;
            }
        }
        throw new IllegalArgumentException("Unterminated tag");
    }

    /**
     * Tests an ASCII letter.
     *
     * @param c character
     * @return whether it starts a tag name
     */
    private static boolean isAsciiLetter(final char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    /**
     * Returns emitted displayed PDF user-space geometry without text-based adjustment ({@code rectangle}).
     *
     * @param coordinates emitted coordinate metadata
     * @return enclosing envelope
     */
    static TableGeometry.Box rectangle(final Map<String, Object> coordinates) {
        if (!"PDFUserSpace".equals(PythonJson.member(coordinates, "system"))) {
            throw new IllegalArgumentException("Unexpected coordinate system");
        }
        final var points = PythonJson.array(PythonJson.member(coordinates, "points"), "points");
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        if (points.isEmpty()) {
            throw new IllegalArgumentException("Empty coordinate points");
        }
        for (final Object item : points) {
            final var point = PythonJson.array(item, "point");
            final double x = ((Number) point.get(0)).doubleValue();
            final double y = ((Number) point.get(1)).doubleValue();
            left = Math.min(left, x);
            top = Math.min(top, y);
            right = Math.max(right, x);
            bottom = Math.max(bottom, y);
        }
        return new TableGeometry.Box(left, top, right, bottom);
    }

    /**
     * Rectangular intersection over union in one displayed page frame ({@code iou}).
     *
     * @param first first envelope
     * @param second second envelope
     * @return float ratio, or integer {@code 0} when the union is not positive
     */
    static Number iou(final TableGeometry.Box first, final TableGeometry.Box second) {
        final double intersection =
                Math.max(0, Math.min(first.right(), second.right()) - Math.max(first.left(), second.left()))
                * Math.max(0, Math.min(first.bottom(), second.bottom()) - Math.max(first.top(), second.top()));
        final double union = (first.right() - first.left()) * (first.bottom() - first.top())
                             + (second.right() - second.left()) * (second.bottom() - second.top()) - intersection;
        return union > 0 ? (Number) (intersection / union) : (Number) 0L;
    }

    /**
     * Geometry-only table selection.
     *
     * @param table selected table, or {@code null}
     * @param overlap selected or best IoU, or integer {@code 0} without tables
     */
    record Selection(Map<String, Object> table, Number overlap) {}

    /**
     * Selects at most one table by maximum ROI IoU at 0.5, with stable index tie-breaking ({@code select}).
     *
     * @param tables emitted tables
     * @param roi reference rectangle
     * @return selection
     */
    static Selection select(final List<Map<String, Object>> tables, final TableGeometry.Box roi) {
        if (tables.isEmpty()) {
            return new Selection(null, 0L);
        }
        int best = 0;
        Number bestScore = null;
        for (int index = 0; index < tables.size(); index++) {
            final Number score = iou(
                    rectangle(PythonJson.object(PythonJson.member(tables.get(index), "coordinates"), "coordinates")),
                    roi);
            if (bestScore == null || PythonJson.numericCompare(score, bestScore) > 0) {
                best = index;
                bestScore = score;
            }
        }
        if (bestScore.doubleValue() < 0.5) {
            return new Selection(null, bestScore);
        }
        return new Selection(tables.get(best), bestScore);
    }

    /**
     * Evaluation projection of emitted anchors.
     *
     * @param grid emitted {@code pdf_table_cells} metadata
     * @param slots normalized origin text; covered non-origin slots are empty strings
     */
    record Projection(Map<String, Object> grid, Map<List<Integer>, String> slots) {}

    /**
     * Projects emitted anchors into evaluation slots; never invents uncovered cells ({@code project}).
     *
     * @param table emitted table
     * @return grid metadata and slots
     */
    static Projection project(final Map<String, Object> table) {
        final var grid = PythonJson.object(PythonJson.member(table, "pdf_table_cells"), "pdf_table_cells");
        final var values = cells((String) PythonJson.member(table, "text_as_html"));
        final var anchors = PythonJson.array(PythonJson.member(grid, "cells"), "cells");
        if (values.size() != anchors.size()) {
            throw new IllegalArgumentException("HTML/coordinate anchor mismatch");
        }
        final var slots = new LinkedHashMap<List<Integer>, String>();
        for (int index = 0; index < anchors.size(); index++) {
            final var anchor = PythonJson.object(anchors.get(index), "anchor");
            final int row = PythonJson.integerValue(PythonJson.member(anchor, "row_index"), "row_index");
            final int column = PythonJson.integerValue(PythonJson.member(anchor, "column_index"), "column_index");
            final int rowSpan = PythonJson.integerValue(PythonJson.member(anchor, "row_span"), "row_span");
            final int columnSpan = PythonJson.integerValue(PythonJson.member(anchor, "column_span"), "column_span");
            for (int r = row; r < row + rowSpan; r++) {
                for (int c = column; c < column + columnSpan; c++) {
                    if (!(PythonJson.numericCompare(0L, (long) r) <= 0
                                && PythonJson.numericCompare((long) r, (Number) PythonJson.member(grid, "row_count"))
                                           < 0
                                && 0 <= c
                                && PythonJson.numericCompare((long) c, (Number) PythonJson.member(grid, "column_count"))
                                           < 0)) {
                        throw new IllegalArgumentException("Out-of-grid anchor");
                    }
                    final var key = List.of(r, c);
                    if (slots.containsKey(key)) {
                        throw new IllegalArgumentException("Overlapping anchors");
                    }
                    slots.put(key, r == row && c == column ? normalize(values.get(index)) : "");
                }
            }
        }
        return new Projection(grid, slots);
    }

    /**
     * Counts every reference position, including failures, without missing-empty credit ({@code compare}).
     *
     * @param reference rectangular CSV expectation
     * @param table selected emitted table, or {@code null}
     * @return text-free position counts and mismatch-index hash
     */
    static Map<String, Object> compare(final List<List<String>> reference, final Map<String, Object> table) {
        if (reference.isEmpty()) {
            throw new IllegalArgumentException("Empty CSV expectation");
        }
        final int expectedRows = reference.size();
        final int expectedColumns = reference.getFirst().size();
        Map<List<Integer>, String> slots = Map.of();
        Map<String, Object> grid = null;
        if (table != null) {
            final var projection = project(table);
            grid = projection.grid();
            slots = projection.slots();
        }
        long matched = 0;
        long nonemptyMatched = 0;
        long emptyMatched = 0;
        long nonempty = 0;
        final var mismatches = new ArrayList<Object>();
        for (int r = 0; r < reference.size(); r++) {
            final var row = reference.get(r);
            if (row.size() != expectedColumns) {
                throw new IllegalArgumentException("Nonrectangular CSV expectation");
            }
            for (int c = 0; c < row.size(); c++) {
                final String expected = normalize(row.get(c));
                nonempty += expected.isEmpty() ? 0 : 1;
                final String actual = slots.get(List.of(r, c));
                final boolean exact = actual != null && actual.equals(expected);
                matched += exact ? 1 : 0;
                nonemptyMatched += exact && !expected.isEmpty() ? 1 : 0;
                emptyMatched += exact && expected.isEmpty() ? 1 : 0;
                if (!exact) {
                    mismatches.add(List.of((long) r, (long) c));
                }
            }
        }
        final boolean dimensions =
                grid != null && !grid.isEmpty()
                && PythonJson.pyEquals(PythonJson.member(grid, "row_count"), (long) expectedRows)
                && PythonJson.pyEquals(PythonJson.member(grid, "column_count"), (long) expectedColumns);
        final long total = (long) expectedRows * expectedColumns;
        final var result = new LinkedHashMap<String, Object>();
        result.put("reference_positions", total);
        result.put("nonempty_positions", nonempty);
        result.put("empty_positions", total - nonempty);
        result.put("matched_positions", matched);
        result.put("matched_nonempty", nonemptyMatched);
        result.put("matched_empty", emptyMatched);
        result.put("dimensions_match", dimensions);
        result.put("full_grid_text_match", dimensions && matched == total);
        result.put("emitted_rows", grid == null ? null : PythonJson.member(grid, "row_count"));
        result.put("emitted_columns", grid == null ? null : PythonJson.member(grid, "column_count"));
        result.put("mismatch_count", (long) mismatches.size());
        result.put("mismatch_positions_sha256",
                ScoringFiles.sha256(PythonJson.compact(mismatches).getBytes(StandardCharsets.UTF_8)));
        return result;
    }

    /**
     * Verifies pinned source/CSV identities before computing text-free diagnostics ({@code run}).
     *
     * @param manifest parsed manifest
     * @param cache pinned input cache
     * @param captures capture root
     * @param profiles unique simple profile names
     * @return report with runtime identity and one record per case and profile
     * @throws IOException if an input cannot be read
     */
    static Map<String, Object> run(final Map<String, Object> manifest, final Path cache, final Path captures,
            final List<String> profiles) throws IOException {
        if (profiles.isEmpty() || new HashSet<>(profiles).size() != profiles.size()
                || profiles.stream().anyMatch(name -> !PROFILE.matcher(name).matches())) {
            throw new IllegalArgumentException("Expected unique simple profile names");
        }
        for (final Object item : PythonJson.array(PythonJson.member(manifest, "inputs"), "inputs")) {
            final var entry = PythonJson.object(item, "input");
            final String filename = (String) PythonJson.member(entry, "filename");
            final byte[] data = Files.readAllBytes(cache.resolve(filename));
            if (!PythonJson.pyEquals((long) data.length, PythonJson.member(entry, "bytes"))
                    || !ScoringFiles.sha256(data).equals(PythonJson.member(entry, "sha256"))
                    || !ScoringFiles.gitBlobSha1(data).equals(PythonJson.member(entry, "git_blob_sha1"))) {
                throw new IllegalArgumentException("Pinned input mismatch: " + filename);
            }
        }
        final var cases = new ArrayList<Object>();
        for (final Object item : PythonJson.array(PythonJson.member(manifest, "cases"), "cases")) {
            final var entry = PythonJson.object(item, "case");
            final var reference = ScoringFiles.csv(cache.resolve((String) PythonJson.member(entry, "csv")));
            if (reference.isEmpty()
                    || !PythonJson.pyEquals((long) reference.size(), PythonJson.member(entry, "expected_rows"))
                    || !PythonJson.pyEquals(
                            (long) reference.getFirst().size(), PythonJson.member(entry, "expected_columns"))) {
                throw new IllegalArgumentException("Reference dimensions changed");
            }
            final String id = (String) PythonJson.member(entry, "id");
            final Object sourceFrame =
                    PythonJson.parse(ScoringFiles.readText(captures.resolve(id).resolve("source.json")));
            final var roi = PythonJson.array(PythonJson.member(entry, "roi_left_top_right_bottom"), "roi");
            final var box =
                    new TableGeometry.Box(((Number) roi.get(0)).doubleValue(), ((Number) roi.get(1)).doubleValue(),
                            ((Number) roi.get(2)).doubleValue(), ((Number) roi.get(3)).doubleValue());
            for (final String profile : profiles) {
                final Path path = captures.resolve(id).resolve(profile + ".json");
                final var result = PythonJson.object(PythonJson.parse(ScoringFiles.readText(path)), "capture");
                final var tables = PythonJson.array(PythonJson.member(result, "tables"), "tables")
                                           .stream()
                                           .map(table -> PythonJson.object(table, "table"))
                                           .toList();
                final var selection = select(tables, box);
                final var record = new LinkedHashMap<String, Object>();
                record.put("case", id)
                ;
                record.put("domain", PythonJson.member(entry, "domain"))
                ;
                record.put("profile", profile)
                ;
                record.put("source_frame", sourceFrame)
                ;
                record.put("outcome", PythonJson.member(result, "outcome"))
                ;
                record.put("emitted_tables", (long) tables.size())
                ;
                record.put("associated_tables", selection.table() == null ? 0L : 1L)
                ;
                record.put("association_iou", PythonJson.round(selection.overlap(), 6))
                ;
                record.put("unmatched_tables", (long) tables.size() - (selection.table() == null ? 0 : 1))
                ;
                record.put("page_calls", PythonJson.member(result, "page_calls"))
                ;
                record.put("page_raster_sha256", PythonJson.member(result, "page_raster_sha256"))
                ;
                record.put("cell_calls", PythonJson.member(result, "cell_calls"))
                ;
                record.put("layout_calls", PythonJson.member(result, "layout_calls"))
                ;
                record.put("layout_tables", PythonJson.member(result, "layout_tables"))
                ;
                record.put("local_capture_sha256", ScoringFiles.sha256(path))
                ;
                record.putAll(compare(reference, selection.table()))
                ;
                cases.add(record);
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("runtime", PythonJson.parse(ScoringFiles.readText(captures.resolve("runtime.json"))));
        report.put("cases", cases);
        return report;
    }

    @Override
    public void run(final List<String> arguments, final PrintStream console) throws IOException {
        final var options = ScoringArguments.parse(arguments, Set.of(), Set.of(), Set.of("profiles"), 4);
        final var positionals = options.positionals();
        final var manifest =
                PythonJson.object(PythonJson.parse(ScoringFiles.readText(Path.of(positionals.get(0)))), "manifest");
        final var report = run(manifest, Path.of(positionals.get(1)), Path.of(positionals.get(2)),
                options.lists().getOrDefault("profiles", List.of("page", "cells")));
        Files.writeString(Path.of(positionals.get(3)), PythonJson.dumps(report, false) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        for (final Object item : PythonJson.array(report.get("cases"), "cases")) {
            final var record = PythonJson.object(item, "case");
            console.println(record.get("case") + " " + record.get("profile") + " " + record.get("outcome") + " "
                            + record.get("matched_positions") + "/" + record.get("reference_positions")
                            + " dimensions_match=" + ((Boolean) record.get("dimensions_match") ? "True" : "False"));
        }
    }
}
