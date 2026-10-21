package dev.docweave.partition;

import dev.docweave.core.ElementType;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit word-box adaptation of the conservative uniform aligned-numeric table strategy. */
final class PdfScannedWordTables {
    /** Actual rendered-to-displayed horizontal ratio. */
    private final double xScale;
    /** Actual rendered-to-displayed vertical ratio. */
    private final double yScale;
    /** Shared bounded grouping, recognition and ownership work. */
    private final PdfTableWork work = new PdfTableWork();
    /** Optional normalized cell-coordinate serializer. */
    private final PdfTableCells cells;

    /**
     * Captures the already displayed page frame; crop/rotation are not applied a second time.
     * @param geometry displayed page frame
     * @param image rendered page raster
     * @param includeCells whether approximate text-cell envelopes are requested
     */
    PdfScannedWordTables(final PdfPageGeometry geometry, final BufferedImage image, final boolean includeCells) {
        xScale = geometry.width() / (double) image.getWidth();
        yScale = geometry.height() / (double) image.getHeight();
        cells = includeCells ? new PdfTableCells(geometry, 1, 1) : null;
    }

    /**
     * Builds complete aligned numeric blocks and preserves every unowned token in source order.
     * No cell subprocesses, guessed token locations, empty cells or merges are introduced.
     * @param image borrowed raster, never modified
     * @param detailed validated source lines/words from one PAGE call
     * @param limits structured table budgets
     * @param options explicit OCR resource policy
     * @param budget shared raw text/output budget
     * @param regions optional mixed-page native exclusion policy
     * @return pending tables followed by original-order unowned text
     * @throws IOException if cancellation, geometry or output limits fail
     */
    List<PdfOcrElement> detect(final BufferedImage image, final List<PdfWordOcrProvider.WordLine> detailed,
            final TableLimits limits, final PdfOcrOptions options, final PdfOcrBudget budget,
            final PdfOcrSelection regions) throws IOException {
        final var tokens = new ArrayList<Token>();
        final var owned = new ArrayList<boolean[]>();
        for (int line = 0; line < detailed.size(); line++) {
            work.step();
            final var words = detailed.get(line).words();
            owned.add(new boolean[words.size()]);
            for (int word = 0; word < words.size(); word++) {
                work.step();
                final var value = words.get(word);
                if (regions == null || regions.owns(PdfOcrTokens.bounds(List.of(value)))) {
                    tokens.add(new Token(line, word, value));
                }
            }
        }
        final var groups = groups(tokens);
        final var runs = groups.stream().map(Group::run).toList();
        final var segments = new ArrayList<PdfPageGraphics.Segment>();
        for (final var segment : RasterPdfTableDetector.segments(image, work)) {
            work.step();
            segments.add(new PdfPageGraphics.Segment((float) (segment.x1() * xScale), (float) (segment.y1() * yScale),
                    (float) (segment.x2() * xScale), (float) (segment.y2() * yScale)));
        }
        final var output = new ArrayList<PdfOcrElement>();
        int cellCount = 0;
        for (final var table : new AlignedPdfTableDetector(limits).detect(runs, segments, List.of(), work)) {
            final var members = new ArrayList<PdfWordOcrProvider.Word>();
            for (final var row : table.rows()) {
                for (final int index : row) {
                    for (final var token : groups.get(index).tokens()) {
                        work.step();
                        members.add(token.value());
                    }
                }
            }
            final var envelope = PdfOcrTokens.bounds(members);
            if (regions != null && !regions.owns(envelope)) {
                continue;
            }
            final int count = table.rows().size() * table.rows().getFirst().size();
            if (count > options.maxTableCells() - cellCount) {
                throw new PartitionException(
                        PartitionException.Code.TABLE_LIMIT, "PDF word tables exceed maxTableCells");
            }
            cellCount += count;
            final var html = new StringBuilder("<table><tbody>");
            final var values = new ArrayList<String>();
            final var descriptions = new ArrayList<Map<String, Object>>();
            for (int row = 0; row < table.rows().size(); row++) {
                html.append("<tr>");
                final var indices = table.rows().get(row);
                for (int column = 0; column < indices.size(); column++) {
                    work.step();
                    final var group = groups.get(indices.get(column));
                    final var run = group.run();
                    values.add(run.text());
                    html.append("<td>").append(HtmlTextEscaper.escape(run.text())).append("</td>");
                    if (cells != null) {
                        descriptions.add(
                                cells.cell(row, column, 1, 1, run.left(), run.top(), run.right(), run.bottom(), work));
                    }
                    for (final var token : group.tokens()) {
                        work.step();
                        owned.get(token.line())[token.word()] = true;
                    }
                }
                html.append("</tr>");
            }
            // Charge generated separators conservatively in addition to the original source-line text.
            budget.reserveCharacters(Math.max(0, members.size() - 1));
            final var metadata = new LinkedHashMap<String, Object>();
            metadata.put("text_as_html", html.append("</tbody></table>").toString());
            metadata.put("pdf_table_method", "scanned_aligned_words_heuristic_v1");
            metadata.put("pdf_table_evidence",
                    Map.of("rows", table.rows().size(), "columns", table.rows().getFirst().size(), "numeric_columns",
                            table.numericColumns(), "policy", "aligned_numeric_v1"));
            if (cells != null) {
                metadata.put("pdf_table_cells", PdfTableCells.describe("text_bounds", table.rows().size(),
                                                        table.rows().getFirst().size(), descriptions));
            }
            output.add(new PdfOcrElement(ElementType.Table, String.join(" ", values), envelope, metadata));
        }
        output.addAll(PdfOcrTokens.remainders(detailed, owned, regions, work));
        return List.copyOf(output);
    }

    /**
     * Groups nearby actual tokens on a common displayed bottom ordinate, splitting gaps of at least12 units.
     * @param tokens source-addressed actual words
     * @return aligned detector runs with exact reverse ownership links
     * @throws IOException if work or cancellation fails
     */
    private List<Group> groups(final List<Token> tokens) throws IOException {
        tokens.sort(Comparator.comparingDouble((Token token) -> token.value().bottom())
                        .thenComparingDouble(token -> token.value().left()));
        final var rows = new ArrayList<List<Token>>();
        double baseline = Double.NaN;
        for (final var token : tokens) {
            work.step();
            if (rows.isEmpty() || (token.value().bottom() - baseline) * yScale > 2) {
                rows.add(new ArrayList<>());
                baseline = token.value().bottom();
            }
            rows.getLast().add(token);
        }
        final var result = new ArrayList<Group>();
        for (final var row : rows) {
            row.sort(Comparator.comparingDouble(token -> token.value().left()));
            final var group = new ArrayList<Token>();
            double right = Double.NaN;
            for (final var token : row) {
                work.step();
                final double gap = (token.value().left() - right) * xScale;
                if (!group.isEmpty() && (gap < 0 || gap >= 12)) {
                    addGroup(result, group);
                    group.clear();
                }
                group.add(token);
                right = token.value().right();
            }
            addGroup(result, group);
        }
        return List.copyOf(result);
    }

    /**
     * Projects a complete token run into the existing detector's displayed-unit frame.
     * @param output destination runs
     * @param tokens nonempty current run
     */
    private void addGroup(final List<Group> output, final List<Token> tokens) {
        if (tokens.isEmpty()) {
            return;
        }
        final var words = tokens.stream().map(Token::value).toList();
        final var bounds = PdfOcrTokens.bounds(words);
        final String text = String.join(" ", words.stream().map(PdfWordOcrProvider.Word::text).toList());
        output.add(new Group(new PdfTextRun(text, (float) (bounds.left() * xScale), (float) (bounds.top() * yScale),
                                     (float) (bounds.right() * xScale), (float) (bounds.bottom() * yScale)),
                List.copyOf(tokens)));
    }

    /**
     * One source-addressed actual word.
     * @param line original detailed line index
     * @param word original token index within that line
     * @param value immutable actual geometry/text
     */
    private record Token(int line, int word, PdfWordOcrProvider.Word value) {}

    /**
     * Complete spatial run plus reverse token ownership, confined to this page.
     * @param run normalized displayed-unit detector input
     * @param tokens original provider words contributing exactly once
     */
    private record Group(PdfTextRun run, List<Token> tokens) {}
}
