package dev.docweave.validation;

import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfWordOcrProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * Independent review of the frozen ALIGNMENT_COLUMN_V1 evaluation, using only retained word captures.
 *
 * <p>It never launches an OCR engine. For every retained observation it rebuilds the WIDE_GAPS baseline with the
 * production splitter ({@code TesseractTsv.segments} and {@code line}, invoked reflectively) and compares it with the
 * evaluation harness's re-implementation; re-implements the frozen detector from the protocol text and compares every
 * eligible peak, the winner and the number of added splits with the evaluation report; recomputes the acceptance
 * verdicts from the reported metrics; and decodes each original TIFF to compare input pixel hashes with the captures
 * and with the historical studies. The report holds counts, coordinates and hashes only.
 */
public final class ReviewOcrColumnAlignment {
    /** Report serialization. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Prevents construction of this command-line tool. */
    private ReviewOcrColumnAlignment() {
    }

    /**
     * One internal word start that the frozen rule considers.
     *
     * @param line original line index
     * @param word word index within the line
     * @param x word left edge
     */
    record Start(int line, int word, double x) {}

    /**
     * One eligible cluster with both median conventions.
     *
     * @param upperMedianX element at index size/2 of the members, as the harness computes it
     * @param meanMedianX conventional median, the mean of the two middle members for even sizes
     * @param support distinct supporting lines
     * @param members cluster members in ascending x order
     */
    record Peak(double upperMedianX, double meanMedianX, int support, List<Start> members) {}

    /**
     * Writes the review report.
     *
     * @param args manifest, sources directory, private captures directory, evaluation report, historical degraded
     *         report, historical historical-text report and a fresh review report
     * @throws Exception if an identity check, reflection or a comparison fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException(
                    "manifest sources captures evaluation-report historical-degraded historical-text review-report");
        }
        final var manifest = JSON.readTree(Files.readAllBytes(Path.of(args[0])));
        final var evaluation = JSON.readTree(Files.readAllBytes(Path.of(args[3])));
        final var historical = new LinkedHashMap<String, String>();
        for (final var report : List.of(args[4], args[5])) {
            for (final var row : JSON.readTree(Files.readAllBytes(Path.of(report))).get("results")) {
                if (row.has("source_rgb_sha256")) {
                    historical.put(row.get("id").asString(), row.get("source_rgb_sha256").asString());
                }
            }
        }
        final var production = Production.load();
        final var rows = new ArrayList<Map<String, Object>>();
        final var pixels = new TreeMap<String, Object>();
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            final String decoded = rgb(Path.of(args[1], selected.get("image").asString()));
            for (final var profile : manifest.get("profiles")) {
                final String language = profile.get("language").asString();
                final var capture = JSON.readTree(Files.readAllBytes(Path.of(args[2], id + "-" + language + ".json")));
                final var lines =
                        List.of(JSON.treeToValue(capture.get("word_lines"), PdfWordOcrProvider.WordLine[].class));
                final var report = find(evaluation, id, language);
                final var row = new LinkedHashMap<String, Object>();
                row.put("id", id);
                row.put("language", language);
                row.put("capture_outcome", capture.get("outcome").asString());
                row.put("raw_lines", lines.size());
                row.put("words", lines.stream().mapToInt(line -> line.words().size()).sum());
                final var productionWide = production.wideGaps(lines);
                final var harnessWide = harnessRegroup(lines, Map.of());
                row.put("production_wide_gap_lines", productionWide.size());
                row.put("harness_wide_gaps_equal_production", productionWide.equals(harnessWide));
                final var detection = detect(lines);
                final var winner = detection.isEmpty() ? null : detection.getFirst();
                final var conventional = new ArrayList<>(detection);
                conventional.sort((a, b)
                                          -> a.support() != b.support()
                                                     ? Integer.compare(b.support(), a.support())
                                                     : Double.compare(a.meanMedianX(), b.meanMedianX()));
                row.put("independent_peaks", detection.stream().map(ReviewOcrColumnAlignment::peakEntry).toList());
                final var reported = new ArrayList<List<Object>>();
                report.get("peaks").forEach(
                        peak -> reported.add(List.of(peak.get("x").asDouble(), peak.get("support").asInt())));
                row.put("peaks_equal_report",
                        reported.equals(detection.stream()
                                        .map(peak -> List.<Object>of(peak.upperMedianX(), peak.support()))
                                        .toList()));
                row.put("winner_same_under_conventional_median", winner == null || conventional.getFirst() == winner);
                final var boundaries = new LinkedHashMap<Integer, Integer>();
                if (winner != null) {
                    winner.members().forEach(start -> boundaries.merge(start.line(), start.word(), Math::min));
                }
                final var candidate = harnessRegroup(lines, boundaries);
                final int additions = candidate.size() - harnessWide.size();
                row.put("independent_additional_splits", additions);
                row.put("additions_equal_report", additions == report.get("additional_splits").asInt());
                row.put("candidate_equals_wide_gaps", candidate.equals(harnessWide));
                row.put("token_sequence_preserved",
                        flat(candidate).equals(flat(harnessWide))
                                && flat(harnessWide)
                                        .equals(flat(lines.stream().map(PdfWordOcrProvider.WordLine::line).toList())));
                final var wide = report.get("wide_gaps");
                final var aligned = report.get("alignment");
                final boolean positive = id.equals("buerger_gedichte_1778_0039");
                final boolean recomputed =
                        capture.get("outcome").asString().equals("SUCCESS")
                        && (positive ? additions > 0 && more(aligned, wide, "associated_reference_lines")
                                                && notMore(aligned, wide, "missing_reference_lines")
                                                && notMore(aligned, wide, "predicted_lines_without_reference")
                                                && notMore(aligned, wide, "joint_reference_line_character_errors")
                                     : candidate.equals(harnessWide));
                row.put("recomputed_primary_acceptance", recomputed);
                row.put("acceptance_equals_report", recomputed == report.get("primary_acceptance").asBoolean());
                final var metrics = new LinkedHashMap<String, Object>();
                for (final var key : List.of("reference_lines", "recognized_lines", "associated_reference_lines",
                             "missing_reference_lines", "predicted_lines_without_reference",
                             "joint_reference_line_character_errors")) {
                    metrics.put(key, List.of(report.get("raw").get(key).asInt(), wide.get(key).asInt(),
                                             aligned.get(key).asInt()));
                }
                metrics.put("xml_order_correct_reversed_unmatched",
                        List.of(order(report.get("raw")), order(wide), order(aligned)));
                row.put("raw_wide_alignment_metrics", metrics);
                row.put("prediction_text_sha256_equal",
                        wide.get("prediction_text_sha256")
                                .asString()
                                .equals(aligned.get("prediction_text_sha256").asString()));
                rows.add(row);
                final var pixel = new LinkedHashMap<String, Object>();
                pixel.put("decoded_rgb_sha256", decoded);
                pixel.put("capture_rgb_sha256_" + language, capture.get("input_rgb_sha256").asString());
                pixel.put("historical_rgb_sha256", historical.get(id));
                @SuppressWarnings("unchecked")
                final var previous = (Map<String, Object>) pixels.computeIfAbsent(id, key -> new LinkedHashMap<>());
                previous.putAll(pixel);
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("java_version", System.getProperty("java.version"));
        report.put("engine_executions", 0);
        report.put("observations", rows);
        report.put("input_pixels", pixels);
        Files.writeString(Path.of(args[6]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Applies the frozen protocol text independently: page median height, page extent, candidate starts, fixed-anchor
     * clusters with at least six distinct lines, ranked by support and then the harness's median convention.
     *
     * @param lines retained word lines
     * @return eligible peaks in rank order
     */
    static List<Peak> detect(final List<PdfWordOcrProvider.WordLine> lines) {
        final double[] heights = lines.stream()
                                         .flatMap(line -> line.words().stream())
                                         .mapToDouble(word -> word.bottom() - word.top())
                                         .sorted()
                                         .toArray();
        if (heights.length == 0) {
            return List.of();
        }
        final double h = heights[heights.length / 2];
        final double pageLeft = lines.stream()
                                        .flatMap(line -> line.words().stream())
                                        .mapToDouble(PdfWordOcrProvider.Word::left)
                                        .min()
                                        .orElseThrow();
        final double pageRight = lines.stream()
                                         .flatMap(line -> line.words().stream())
                                         .mapToDouble(PdfWordOcrProvider.Word::right)
                                         .max()
                                         .orElseThrow();
        final var starts = new ArrayList<Start>();
        for (int index = 0; index < lines.size(); index++) {
            final var words = lines.get(index).words();
            final double lineRight = words.stream().mapToDouble(PdfWordOcrProvider.Word::right).max().orElse(0);
            for (int word = 1; word < words.size(); word++) {
                final double x = words.get(word).left();
                final boolean inBand =
                        x >= pageLeft + 0.25 * (pageRight - pageLeft) && x <= pageLeft + 0.80 * (pageRight - pageLeft);
                if (inBand && x >= words.get(word - 1).right() && x - words.getFirst().left() >= 3 * h
                        && lineRight - x >= 3 * h) {
                    starts.add(new Start(index, word, x));
                }
            }
        }
        starts.sort((a, b)
                            -> a.x() != b.x()         ? Double.compare(a.x(), b.x())
                               : a.line() != b.line() ? Integer.compare(a.line(), b.line())
                                                      : Integer.compare(a.word(), b.word()));
        final var peaks = new ArrayList<Peak>();
        int at = 0;
        while (at < starts.size()) {
            final double anchor = starts.get(at).x();
            final var members = new ArrayList<Start>();
            while (at < starts.size() && starts.get(at).x() - anchor <= h) {
                members.add(starts.get(at++));
            }
            final long support = members.stream().mapToInt(Start::line).distinct().count();
            if (support >= 6) {
                final int middle = members.size() / 2;
                final double mean = members.size() % 2 == 1
                                            ? members.get(middle).x()
                                            : (members.get(middle - 1).x() + members.get(middle).x()) / 2;
                peaks.add(new Peak(members.get(middle).x(), mean, (int) support, List.copyOf(members)));
            }
        }
        peaks.sort((a, b)
                           -> a.support() != b.support() ? Integer.compare(b.support(), a.support())
                                                         : Double.compare(a.upperMedianX(), b.upperMedianX()));
        return List.copyOf(peaks);
    }

    /**
     * Regroups as the evaluation harness does: wide-gap splits plus at most one added boundary per line.
     *
     * @param lines retained word lines
     * @param additions added boundary word index per line
     * @return regrouped lines
     */
    static List<PdfOcrProvider.Line> harnessRegroup(
            final List<PdfWordOcrProvider.WordLine> lines, final Map<Integer, Integer> additions) {
        final var output = new ArrayList<PdfOcrProvider.Line>();
        for (int index = 0; index < lines.size(); index++) {
            final var words = lines.get(index).words();
            if (words.isEmpty()) {
                output.add(lines.get(index).line());
                continue;
            }
            final double h = words.stream().mapToDouble(word -> word.bottom() - word.top()).max().orElseThrow();
            int from = 0;
            for (int word = 1; word <= words.size(); word++) {
                if (word == words.size() || additions.getOrDefault(index, -1) == word
                        || words.get(word).left() - words.get(word - 1).right() > 3 * h) {
                    output.add(join(words.subList(from, word)));
                    from = word;
                }
            }
        }
        return List.copyOf(output);
    }

    /**
     * Describes one peak with a stable key order.
     *
     * @param peak eligible peak
     * @return report entry
     */
    private static Map<String, Object> peakEntry(final Peak peak) {
        final var entry = new LinkedHashMap<String, Object>();
        entry.put("x", peak.upperMedianX());
        entry.put("support", peak.support());
        entry.put("mean_median_x", peak.meanMedianX());
        return entry;
    }

    /**
     * Reflective access to the production wide-gap splitter and line builder.
     *
     * @param segments production {@code TesseractTsv.segments} method
     * @param line production {@code TesseractTsv.line} method
     * @param deadline production {@code OcrDeadline(Duration)} constructor
     */
    private record Production(Method segments, Method line, Constructor<?> deadline) {
        /**
         * Resolves the package-private production methods.
         *
         * @return accessible handles
         * @throws Exception if the production classes changed shape
         */
        static Production load() throws Exception {
            final var tsv = Class.forName("dev.docweave.ocr.TesseractTsv");
            final var deadlineClass = Class.forName("dev.docweave.ocr.OcrDeadline");
            final var segments = tsv.getDeclaredMethod("segments", List.class, int.class, deadlineClass);
            final var line = tsv.getDeclaredMethod("line", List.class, deadlineClass);
            final var deadline = deadlineClass.getDeclaredConstructor(Duration.class);
            segments.setAccessible(true);
            line.setAccessible(true);
            deadline.setAccessible(true);
            return new Production(segments, line, deadline);
        }

        /**
         * Splits every retained line with the production WIDE_GAPS rule and builds lines with production code.
         *
         * @param lines retained word lines
         * @return production-equivalent lines
         * @throws Exception if invocation fails
         */
        @SuppressWarnings("unchecked")
        List<PdfOcrProvider.Line> wideGaps(final List<PdfWordOcrProvider.WordLine> lines) throws Exception {
            final var output = new ArrayList<PdfOcrProvider.Line>();
            final Object budget = deadline.newInstance(Duration.ofMinutes(5));
            for (final var original : lines) {
                if (original.words().isEmpty()) {
                    output.add(original.line());
                    continue;
                }
                for (final var segment : (List<List<PdfWordOcrProvider.Word>>) segments.invoke(
                             null, original.words(), Integer.MAX_VALUE, budget)) {
                    output.add((PdfOcrProvider.Line) line.invoke(null, segment, budget));
                }
            }
            return List.copyOf(output);
        }
    }

    /**
     * Joins one segment with single spaces and its enclosing box, as the harness does.
     *
     * @param words nonempty segment
     * @return line
     */
    private static PdfOcrProvider.Line join(final List<PdfWordOcrProvider.Word> words) {
        return new PdfOcrProvider.Line(String.join(" ", words.stream().map(PdfWordOcrProvider.Word::text).toList()),
                words.stream().mapToDouble(PdfWordOcrProvider.Word::left).min().orElseThrow(),
                words.stream().mapToDouble(PdfWordOcrProvider.Word::top).min().orElseThrow(),
                words.stream().mapToDouble(PdfWordOcrProvider.Word::right).max().orElseThrow(),
                words.stream().mapToDouble(PdfWordOcrProvider.Word::bottom).max().orElseThrow());
    }

    /**
     * Returns the joined token sequence of lines.
     *
     * @param lines lines
     * @return text
     */
    private static String flat(final List<PdfOcrProvider.Line> lines) {
        return String.join(" ", lines.stream().map(PdfOcrProvider.Line::text).toList());
    }

    /**
     * Hashes an original image as the harness does: ImageIO decode, colour-managed RGB bytes.
     *
     * @param path original TIFF
     * @return SHA-256 of interleaved RGB bytes
     * @throws Exception if decoding fails
     */
    private static String rgb(final Path path) throws Exception {
        final BufferedImage image = ImageIO.read(path.toFile());
        final var digest = MessageDigest.getInstance("SHA-256");
        final var row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, row.length, 1, row, 0, row.length);
            for (final int pixel : row) {
                digest.update((byte) (pixel >> 16));
                digest.update((byte) (pixel >> 8));
                digest.update((byte) pixel);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Finds one observation in the evaluation report.
     *
     * @param evaluation report
     * @param id case
     * @param language model
     * @return report row
     */
    private static JsonNode find(final JsonNode evaluation, final String id, final String language) {
        for (final var row : evaluation.get("results")) {
            if (row.get("id").asString().equals(id) && row.get("language").asString().equals(language)) {
                return row;
            }
        }
        throw new IllegalStateException("Observation missing from report");
    }

    /**
     * Returns XML-order counts as a list.
     *
     * @param metrics scorer metrics
     * @return correct, reversed, unmatched
     */
    private static List<Integer> order(final JsonNode metrics) {
        final var order = metrics.get("xml_order");
        return List.of(order.get("correct").asInt(), order.get("reversed").asInt(), order.get("unmatched").asInt());
    }

    /**
     * Tests a strict increase.
     *
     * @param after candidate metrics
     * @param before baseline metrics
     * @param key metric
     * @return whether after exceeds before
     */
    private static boolean more(final JsonNode after, final JsonNode before, final String key) {
        return after.get(key).asInt() > before.get(key).asInt();
    }

    /**
     * Tests a non-increase.
     *
     * @param after candidate metrics
     * @param before baseline metrics
     * @param key metric
     * @return whether after does not exceed before
     */
    private static boolean notMore(final JsonNode after, final JsonNode before, final String key) {
        return after.get(key).asInt() <= before.get(key).asInt();
    }
}
