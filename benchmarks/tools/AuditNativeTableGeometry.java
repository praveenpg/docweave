package io.docweave.partition;

import org.apache.pdfbox.Loader;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records text-free native recognition geometry on one pinned original PDF page.
 * This diagnostic requires upright text and no accepted ruled grid, so the ordinary runs are exactly
 * those available to the aligned strategies. It neither searches reference regions nor changes policies.
 */
public final class AuditNativeTableGeometry {
    /** Prevents utility construction. */
    private AuditNativeTableGeometry() {
    }

    /**
     * Extracts source metrics and invokes the existing detectors with independent default work counters.
     * @param args PDF path, one-based page, expected SHA256 and new report path
     * @throws Exception if identity, supported diagnostic scope, extraction or output checks fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 4 || !args[2].matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Expected PDF, one-based page, SHA256, new JSON report");
        }
        final var input = Path.of(args[0]);
        if (Files.size(input) > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Diagnostic PDF exceeds 16MiB");
        }
        final var bytes = Files.readAllBytes(input);
        if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(args[2])) {
            throw new IllegalArgumentException("Source identity mismatch");
        }
        final int selected = Integer.parseInt(args[1]);
        final var report = new LinkedHashMap<String, Object>();
        try (final var document = Loader.loadPDF(bytes)) {
            final var page = document.getPage(selected - 1);
            final var options = PdfOptions.defaults();
            final var geometry = new PdfPageGeometry(page);
            final var text = new PdfPageText(options);
            text.setStartPage(selected);
            text.setEndPage(selected);
            text.getText(document);
            if (page.getRotation() != 0 || text.glyphs().stream().anyMatch(glyph -> glyph.direction() != 0)) {
                throw new IllegalArgumentException("Diagnostic requires displayed upright native text");
            }
            final var graphics = new PdfPageGraphics(page, options, new PdfImageBudget(options), geometry);
            graphics.processPage(page);
            final var ruled =
                    new RuledPdfTableDetector(options.tableLimits()).detect(graphics.segments(), new PdfTableWork());
            if (!ruled.isEmpty()) {
                throw new IllegalArgumentException("Diagnostic requires no accepted ruled grid/owned glyphs");
            }
            final var runs = PdfGlyphLines.lines(text.glyphs())
                                     .stream()
                                     .map(item
                                             -> new PdfTextRun(item.text(), item.box().left(), item.box().top(),
                                                     item.box().right(), item.box().bottom()))
                                     .toList();
            report.put("pdf_sha256", args[2]);
            report.put("source_page", selected);
            report.put("source_pages", document.getNumberOfPages());
            report.put("rotation", page.getRotation());
            report.put("glyphs", text.glyphs().size());
            report.put("ordinary_runs", runs.size());
            report.put("segments", graphics.segments().size());
            report.put("images", graphics.images().size());
            report.put("ruled_tables", ruled.size());
            report.put(
                    "aligned_tables", new AlignedPdfTableDetector(options.tableLimits())
                                              .detect(runs, graphics.segments(), graphics.images(), new PdfTableWork())
                                              .size());
            report.put("multiline_tables",
                    new MultilinePdfTableDetector(options.tableLimits())
                            .detect(runs, graphics.segments(), graphics.images(), new PdfTableWork())
                            .size());
            report.put("horizontal_tables",
                    new HorizontalPdfTableDetector(options.tableLimits(), geometry)
                            .detect(text.glyphs(), graphics.segments(), graphics.images(), new PdfTableWork())
                            .size());
            report.put("aligned_blocks", blocks(runs));
            report.put("horizontal_rules", horizontalRules(graphics.segments()));
            report.put("vertical_segments", graphics.segments()
                                                    .stream()
                                                    .filter(segment
                                                            -> Math.abs(segment.x2() - segment.x1()) < 0.5
                                                                       && Math.abs(segment.y2() - segment.y1()) >= 4)
                                                    .count());
        }
        Files.writeString(Path.of(args[3]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Replays only aligned baseline grouping and block separation, retaining numeric geometry without text.
     * The first cardinality failure is an observation of an existing necessary condition, not a causal model label.
     * @param runs production line runs
     * @return full-page block summaries, including singleton and rejected groups
     */
    private static List<Map<String, Object>> blocks(final List<PdfTextRun> runs) {
        final var sorted =
                runs.stream()
                        .sorted(Comparator.comparingDouble(PdfTextRun::bottom).thenComparingDouble(PdfTextRun::left))
                        .toList();
        final var rows = new ArrayList<List<PdfTextRun>>();
        float baseline = Float.NaN;
        for (final var run : sorted) {
            if (rows.isEmpty() || Math.abs(run.bottom() - baseline) > 2) {
                rows.add(new ArrayList<>());
                baseline = run.bottom();
            }
            rows.getLast().add(run);
        }
        final var result = new ArrayList<Map<String, Object>>();
        final var block = new ArrayList<List<PdfTextRun>>();
        for (final var row : rows) {
            row.sort(Comparator.comparingDouble(PdfTextRun::left));
            if (!block.isEmpty()
                    && (block.getLast().size() < 2 || row.size() < 2
                            || row.getFirst().bottom() - block.getLast().getFirst().bottom() > 40)) {
                result.add(summarize(block));
                block.clear();
            }
            block.add(row);
        }
        if (!block.isEmpty()) {
            result.add(summarize(block));
        }
        return List.copyOf(result);
    }

    /**
     * Summarizes one complete block before any pitch/alignment/numeric/barrier checks.
     * @param rows left-sorted baselines
     * @return counts, baselines and first run-count mismatch, with no source strings
     */
    private static Map<String, Object> summarize(final List<List<PdfTextRun>> rows) {
        final var report = new LinkedHashMap<String, Object>();
        report.put("baselines", rows.stream().map(row -> row.getFirst().bottom()).toList());
        report.put("run_counts", rows.stream().map(List::size).toList());
        final int columns = rows.getFirst().size();
        int mismatch = -1;
        for (int row = 0; row < rows.size(); row++) {
            if (rows.get(row).size() != columns) {
                mismatch = row;
                break;
            }
        }
        report.put("first_cardinality_mismatch_row", mismatch);
        report.put("passes_cardinality_prerequisite", rows.size() >= 3 && columns >= 2 && mismatch == -1);
        return report;
    }

    /**
     * Groups horizontal rules with the production fixed-first-ordinate tolerance; original intervals remain distinct.
     * These are source paint intervals, not inferred cell boundaries or reference-label coordinates.
     * @param segments painted native segments
     * @return ordered rule ordinates, gaps and unjoined original intervals
     */
    private static List<Map<String, Object>> horizontalRules(final List<PdfPageGraphics.Segment> segments) {
        final var sorted = segments.stream()
                                   .filter(segment
                                           -> Math.abs(segment.y2() - segment.y1()) < 0.5
                                                      && Math.abs(segment.x2() - segment.x1()) >= 4)
                                   .sorted(Comparator.comparingDouble(PdfPageGraphics.Segment::y1))
                                   .toList();
        final var result = new ArrayList<Map<String, Object>>();
        final var spans = new ArrayList<List<Float>>();
        float baseline = Float.NaN;
        float previous = Float.NaN;
        for (final var segment : sorted) {
            if (!spans.isEmpty() && segment.y1() - baseline > 0.5) {
                result.add(rule(baseline, previous, spans));
                previous = baseline;
                spans.clear();
            }
            if (spans.isEmpty()) {
                baseline = segment.y1();
            }
            spans.add(List.of(Math.min(segment.x1(), segment.x2()), Math.max(segment.x1(), segment.x2())));
        }
        if (!spans.isEmpty()) {
            result.add(rule(baseline, previous, spans));
        }
        return List.copyOf(result);
    }

    /**
     * Freezes a source rule's intervals and optional distance from the preceding rule.
     * @param y first source ordinate
     * @param previous prior first ordinate, or NaN for the first rule
     * @param spans original paint intervals
     * @return immutable text-free rule description
     */
    private static Map<String, Object> rule(final float y, final float previous, final List<List<Float>> spans) {
        final var result = new LinkedHashMap<String, Object>();
        result.put("y", y);
        result.put("spans", spans.stream().sorted(Comparator.comparingDouble(span -> span.getFirst())).toList());
        if (!Float.isNaN(previous)) {
            result.put("gap_from_previous", y - previous);
        }
        return result;
    }
}
