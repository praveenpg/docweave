package io.docweave.validation;

import io.docweave.partition.PdfOcrProvider;
import io.docweave.partition.PdfWordOcrProvider;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * Evaluates frozen post-recognition variants on retained word captures only; no OCR engine or model runs.
 *
 * <p>Inputs are the retained ALIGNMENT_COLUMN_V1 baseline captures and, for union variants, the retained
 * {@code DUAL_UNION} captures whose additional lines came from the earlier stored-sample recognition. Variants:
 * {@code UNION_MARGIN} keeps only additions lying wholly above or below the baseline text block (lines with at least
 * two words); {@code RULE_COLUMN_ORDER} applies the accepted printed-rule split and then emits segments inside the
 * rule's vertical band left column first. Every variant is scored by the unchanged scorer against the same baseline
 * presentation, on all four scans and both models; labels are read only after outputs are fixed.
 */
public final class EvaluateCachedScanVariants {
    /** Stable serialization. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Metrics where a larger value is better. */
    private static final List<String> HIGHER = List.of("associated_reference_lines");

    /** Metrics where a smaller value is better. */
    private static final List<String> LOWER = List.of("missing_reference_lines", "predicted_lines_without_reference",
            "joint_reference_line_character_errors", "global_character_edits", "xml_sequence_character_edits");

    /** Prevents construction. */
    private EvaluateCachedScanVariants() {
    }

    /**
     * Runs one variant over all four scans and both models.
     *
     * @param args variant, manifest, sources, baseline captures, variant captures (or {@code -}) and fresh report
     * @throws Exception if an identity or scoring step fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 6
                || !List.of("UNION_MARGIN", "UNION_CONSENSUS", "RULE_COLUMN_ORDER", "RULE_GUARDED_COLUMN_ORDER",
                                "RUNTIME_GUARDED_COLUMN_ORDER", "UNION_PAGE_NUMBER", "DIAGNOSE_GUARDED_MISSING",
                                "DIAGNOSE_EXTRAS", "RULE_GUARDED_JOIN")
                        .contains(args[0])) {
            throw new IllegalArgumentException(
                    "UNION_MARGIN|UNION_CONSENSUS|RULE_COLUMN_ORDER|RULE_GUARDED_COLUMN_ORDER "
                    + "manifest sources baseline variants report");
        }
        final String variant = args[0];
        final var manifestBytes = Files.readAllBytes(Path.of(args[1]));
        final var manifest = JSON.readTree(manifestBytes);
        final String manifestHash = hash(manifestBytes);
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            for (final var pin : selected.get("inputs")) {
                final var bytes = Files.readAllBytes(Path.of(args[2], pin.get("filename").asString()));
                require(bytes.length == pin.get("bytes").asLong() && hash(bytes).equals(pin.get("sha256").asString()),
                        "Source pin changed");
            }
            final byte[] xml = Files.readAllBytes(Path.of(args[2], selected.get("xml").asString()));
            final Path image = Path.of(args[2], selected.get("image").asString());
            for (final var profile : manifest.get("profiles")) {
                final String language = profile.get("language").asString();
                final Path baselinePath = Path.of(args[3], id + "-" + language + ".json");
                final var saved = JSON.readTree(Files.readAllBytes(baselinePath));
                require(saved.get("manifest_sha256").asString().equals(manifestHash)
                                && saved.get("outcome").asString().equals("SUCCESS"),
                        "Baseline capture differs");
                final var baseline =
                        List.of(JSON.treeToValue(saved.get("word_lines"), PdfWordOcrProvider.WordLine[].class));
                final var details = new LinkedHashMap<String, Object>();
                final List<PdfOcrProvider.Line> before;
                final List<PdfOcrProvider.Line> after;
                if (variant.startsWith("UNION_")) {
                    final Path unionPath = Path.of(args[4], id + "-" + language + "-DUAL_UNION.json");
                    final var union =
                            List.of(JSON.treeToValue(JSON.readTree(Files.readAllBytes(unionPath)).get("word_lines"),
                                    PdfWordOcrProvider.WordLine[].class));
                    details.put("union_capture_sha256", hash(Files.readAllBytes(unionPath)));
                    before = baseline.stream().map(PdfWordOcrProvider.WordLine::line).toList();
                    if (variant.equals("UNION_MARGIN")) {
                        after = margin(baseline, union, details);
                    } else if (variant.equals("UNION_PAGE_NUMBER")) {
                        after = pageNumber(baseline, union, details);
                    } else {
                        final String other = StreamSupport.stream(manifest.get("profiles").spliterator(), false)
                                                     .map(p -> p.get("language").asString())
                                                     .filter(l -> !l.equals(language))
                                                     .findFirst()
                                                     .orElseThrow();
                        final var otherBaseline = List.of(JSON.treeToValue(
                                JSON.readTree(Files.readAllBytes(Path.of(args[3], id + "-" + other + ".json")))
                                        .get("word_lines"),
                                PdfWordOcrProvider.WordLine[].class));
                        final var otherUnion = List.of(JSON.treeToValue(
                                JSON.readTree(
                                            Files.readAllBytes(Path.of(args[4], id + "-" + other + "-DUAL_UNION.json")))
                                        .get("word_lines"),
                                PdfWordOcrProvider.WordLine[].class));
                        after = consensus(baseline, union, otherBaseline, otherUnion, details);
                    }
                } else {
                    before = EvaluateOcrColumnAlignment.regroup(baseline, Map.of());
                    final var split = EvaluateDegradedScanAttempts.ruleSplit(baseline, image, details);
                    final boolean guarded = variant.contains("GUARDED");
                    final boolean accepted =
                            (boolean) details.get("rule_accepted") && (!guarded || twoSided(baseline, details));
                    final var ordered = accepted ? columnMajor(split, details) : guarded ? before : split;
                    after = variant.equals("RULE_GUARDED_JOIN") && accepted ? join(ordered, details) : ordered;
                    if (variant.equals("RULE_GUARDED_JOIN")) {
                        details.put("guarded_score", EvaluateOcrColumnAlignment.score(xml, ordered));
                    }
                    details.put("rule_only_score", EvaluateOcrColumnAlignment.score(xml, split));
                    if (variant.equals("DIAGNOSE_EXTRAS")) {
                        details.put("extra_lines", extras(xml, before, baseline, image));
                    }
                    if (variant.equals("DIAGNOSE_GUARDED_MISSING")) {
                        details.put("missing_reference_lines", missing(xml, after, baseline));
                    }
                    if (variant.equals("RUNTIME_GUARDED_COLUMN_ORDER")) {
                        // The public opt-in parser must reproduce the evaluated output from the same recognized words.
                        final var runtime = runtime(baseline, image, language);
                        details.put("runtime_equals_evaluated", runtime.equals(after));
                        require(runtime.equals(after), "Runtime policy differs from the evaluated guarded order");
                    }
                }
                final var baseScore = EvaluateOcrColumnAlignment.score(xml, before);
                final var newScore = EvaluateOcrColumnAlignment.score(xml, after);
                final var row = new LinkedHashMap<String, Object>();
                row.put("id", id);
                row.put("language", language);
                row.put("baseline_capture_sha256", hash(Files.readAllBytes(baselinePath)));
                row.put("output_lines", List.of(before.size(), after.size()));
                row.put("identical_output", before.equals(after));
                row.put("baseline", baseScore);
                row.put("candidate", newScore);
                row.put("any_metric_worse", HIGHER.stream().anyMatch(k -> number(newScore, k) < number(baseScore, k))
                                                    || LOWER.stream()
                                                            .filter(baseScore::containsKey)
                                                            .anyMatch(k -> number(newScore, k) > number(baseScore, k)));
                row.put("any_metric_better",
                        HIGHER.stream().anyMatch(k -> number(newScore, k) > number(baseScore, k))
                                || LOWER.stream()
                                        .filter(baseScore::containsKey)
                                        .anyMatch(k -> number(newScore, k) < number(baseScore, k)));
                row.put("details", details);
                results.add(row);
                System.out.println(variant + " " + id + " " + language + " worse=" + row.get("any_metric_worse")
                                   + " better=" + row.get("any_metric_better")
                                   + " identical=" + row.get("identical_output"));
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("variant", variant);
        report.put("manifest_sha256", manifestHash);
        report.put("java_version", System.getProperty("java.version"));
        report.put("results", results);
        Files.writeString(Path.of(args[5]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Keeps baseline lines and only those union additions lying wholly outside the baseline text block.
     *
     * @param baseline primary recognized lines
     * @param union retained dual-decode union in its emitted order
     * @param details receives counts
     * @return gated union in emitted order
     */
    static List<PdfOcrProvider.Line> margin(final List<PdfWordOcrProvider.WordLine> baseline,
            final List<PdfWordOcrProvider.WordLine> union, final Map<String, Object> details) {
        final var primary = new HashSet<>(baseline.stream().map(PdfWordOcrProvider.WordLine::line).toList());
        final double top = baseline.stream()
                                   .filter(line -> line.words().size() >= 2)
                                   .mapToDouble(line -> line.line().top())
                                   .min()
                                   .orElse(Double.POSITIVE_INFINITY);
        final double bottom = baseline.stream()
                                      .filter(line -> line.words().size() >= 2)
                                      .mapToDouble(line -> line.line().bottom())
                                      .max()
                                      .orElse(Double.NEGATIVE_INFINITY);
        final var result = new ArrayList<PdfOcrProvider.Line>();
        int additions = 0;
        int kept = 0;
        for (final var word : union) {
            final var line = word.line();
            if (primary.contains(line)) {
                result.add(line);
                continue;
            }
            additions++;
            if (line.bottom() <= top || line.top() >= bottom) {
                kept++;
                result.add(line);
            }
        }
        details.put("text_block_top", top);
        details.put("text_block_bottom", bottom);
        details.put("union_additions", additions);
        details.put("kept_margin_additions", kept);
        return List.copyOf(result);
    }

    /**
     * Emits lines inside the accepted rule band column by column; other lines keep their positions.
     *
     * @param lines rule-split output in emitted order
     * @param details rule diagnostics ({@code run_x}, {@code run_top}, {@code run_bottom}); receives counts
     * @return reordered lines
     */
    static List<PdfOcrProvider.Line> columnMajor(
            final List<PdfOcrProvider.Line> lines, final Map<String, Object> details) {
        final double x = ((Number) details.get("run_x")).doubleValue();
        final double top = ((Number) details.get("run_top")).doubleValue();
        final double bottom = ((Number) details.get("run_bottom")).doubleValue();
        final int[] group = new int[lines.size()];
        int first = -1;
        for (int index = 0; index < lines.size(); index++) {
            final var line = lines.get(index);
            final double centreY = (line.top() + line.bottom()) / 2;
            if (centreY >= top && centreY <= bottom) {
                group[index] = (line.left() + line.right()) / 2 < x ? 1 : 2;
                first = first < 0 ? index : first;
            }
        }
        final var order = new ArrayList<Integer>();
        for (int index = 0; index < lines.size(); index++) {
            order.add(index);
        }
        final int start = first;
        // Lines before the first band line keep their place; band lines regroup left then right; later non-band
        // lines keep their relative order after the band.
        order.sort(Comparator
                        .comparingInt((Integer index)
                                              -> start < 0 || index < start ? 0
                                                 : group[index] == 0        ? 3
                                                                            : group[index])
                        .thenComparingInt(index -> index));
        final var result = order.stream().map(lines::get).toList();
        details.put("band_left_lines", java.util.Arrays.stream(group).filter(g -> g == 1).count());
        details.put("band_right_lines", java.util.Arrays.stream(group).filter(g -> g == 2).count());
        details.put("order_changed", !result.equals(lines));
        return result;
    }

    /**
     * Keeps only union additions that look like a page number and lie outside the baseline text block.
     *
     * @param baseline primary lines
     * @param union retained union
     * @param details receives counts
     * @return gated union in emitted order
     */
    static List<PdfOcrProvider.Line> pageNumber(final List<PdfWordOcrProvider.WordLine> baseline,
            final List<PdfWordOcrProvider.WordLine> union, final Map<String, Object> details) {
        final var marginal = margin(baseline, union, new LinkedHashMap<>());
        final var primary = new HashSet<>(baseline.stream().map(PdfWordOcrProvider.WordLine::line).toList());
        final var result = new ArrayList<PdfOcrProvider.Line>();
        int kept = 0;
        for (final var line : marginal) {
            if (primary.contains(line)) {
                result.add(line);
            } else if (line.text().strip().matches("[0-9]{1,4}|\\[[0-9]{1,4}\\]|-\\s*[0-9]{1,4}\\s*-")) {
                kept++;
                result.add(line);
            }
        }
        details.put("kept_page_number_additions", kept);
        return List.copyOf(result);
    }

    /**
     * Keeps a model's union addition only when the other model's union adds an overlapping line (IoU at least 0.5).
     *
     * @param baseline this model's primary lines
     * @param union this model's retained union
     * @param otherBaseline the other model's primary lines
     * @param otherUnion the other model's retained union
     * @param details receives counts
     * @return gated union in emitted order
     */
    static List<PdfOcrProvider.Line> consensus(final List<PdfWordOcrProvider.WordLine> baseline,
            final List<PdfWordOcrProvider.WordLine> union, final List<PdfWordOcrProvider.WordLine> otherBaseline,
            final List<PdfWordOcrProvider.WordLine> otherUnion, final Map<String, Object> details) {
        final var primary = new HashSet<>(baseline.stream().map(PdfWordOcrProvider.WordLine::line).toList());
        final var otherPrimary = new HashSet<>(otherBaseline.stream().map(PdfWordOcrProvider.WordLine::line).toList());
        final var otherAdditions = otherUnion.stream()
                                           .map(PdfWordOcrProvider.WordLine::line)
                                           .filter(line -> !otherPrimary.contains(line))
                                           .toList();
        final var result = new ArrayList<PdfOcrProvider.Line>();
        int additions = 0;
        int kept = 0;
        for (final var word : union) {
            final var line = word.line();
            if (primary.contains(line)) {
                result.add(line);
                continue;
            }
            additions++;
            if (otherAdditions.stream().anyMatch(other -> iou(line, other) >= 0.5)) {
                kept++;
                result.add(line);
            }
        }
        details.put("union_additions", additions);
        details.put("other_model_additions", otherAdditions.size());
        details.put("kept_consensus_additions", kept);
        return List.copyOf(result);
    }

    /**
     * Requires recognized words on both sides of the found rule along its vertical run.
     *
     * @param baseline primary word lines
     * @param details rule diagnostics; receives side counts
     * @return whether at least three overlapping lines end left of and three start right of the rule
     */
    static boolean twoSided(final List<PdfWordOcrProvider.WordLine> baseline, final Map<String, Object> details) {
        final double x = ((Number) details.get("run_x")).doubleValue();
        final double top = ((Number) details.get("run_top")).doubleValue();
        final double bottom = ((Number) details.get("run_bottom")).doubleValue();
        int left = 0;
        int right = 0;
        for (final var line : baseline) {
            if (line.line().bottom() < top || line.line().top() > bottom) {
                continue;
            }
            left += line.words().stream().anyMatch(word -> word.right() <= x) ? 1 : 0;
            right += line.words().stream().anyMatch(word -> word.left() >= x) ? 1 : 0;
        }
        details.put("guard_left_lines", left);
        details.put("guard_right_lines", right);
        final boolean accepted = left >= 3 && right >= 3;
        details.put("guard_accepted", accepted);
        return accepted;
    }

    /**
     * Rectangle intersection over union.
     *
     * @param a first line
     * @param b second line
     * @return IoU, or 0 for an empty union
     */
    private static double iou(final PdfOcrProvider.Line a, final PdfOcrProvider.Line b) {
        final double area = Math.max(0, Math.min(a.right(), b.right()) - Math.max(a.left(), b.left()))
                            * Math.max(0, Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top()));
        final double union = (a.right() - a.left()) * (a.bottom() - a.top())
                             + (b.right() - b.left()) * (b.bottom() - b.top()) - area;
        return union > 0 ? area / union : 0;
    }

    /**
     * Rebuilds Tesseract TSV from retained word lines and parses it with the public guarded policy.
     *
     * @param lines retained Tesseract lines in recognition order
     * @param image page raster path
     * @param language model language
     * @return runtime parser output
     * @throws Exception if parsing fails
     */
    static List<PdfOcrProvider.Line> runtime(
            final List<PdfWordOcrProvider.WordLine> lines, final Path image, final String language) throws Exception {
        final var page = javax.imageio.ImageIO.read(image.toFile());
        final var tsv = new StringBuilder(
                "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n");
        for (int line = 0; line < lines.size(); line++) {
            final var words = lines.get(line).words();
            for (int word = 0; word < words.size(); word++) {
                final var w = words.get(word);
                tsv.append("5\t1\t1\t1\t")
                        .append(line + 1)
                        .append('\t')
                        .append(word + 1)
                        .append('\t')
                        .append((int) w.left())
                        .append('\t')
                        .append((int) w.top())
                        .append('\t')
                        .append((int) (w.right() - w.left()))
                        .append('\t')
                        .append((int) (w.bottom() - w.top()))
                        .append("\t90\t")
                        .append(w.text())
                        .append('\n');
            }
        }
        final var options =
                new io.docweave.ocr
                        .TesseractOptions(Path.of("tesseract"), null, List.of(language),
                                java.time.Duration.ofSeconds(60), 1, 20_000_000, 32L * 1024 * 1024, 4 * 1024 * 1024,
                                10_000, 1_000_000)
                        .withLineSplitting(
                                io.docweave.ocr.TesseractOptions.LineSplitting.WIDE_GAPS_AND_GUARDED_RULE_COLUMNS);
        final Class<?> parser = Class.forName("io.docweave.ocr.TesseractTsv");
        final Class<?> deadline = Class.forName("io.docweave.ocr.OcrDeadline");
        final var constructor = deadline.getDeclaredConstructor(java.time.Duration.class);
        constructor.setAccessible(true);
        final var parse = parser.getDeclaredMethod("parse", byte[].class, int.class, int.class,
                io.docweave.ocr.TesseractOptions.class, deadline, java.awt.image.BufferedImage.class);
        parse.setAccessible(true);
        @SuppressWarnings("unchecked")
        final var result = (List<PdfOcrProvider.Line>) parse.invoke(null,
                tsv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), page.getWidth(), page.getHeight(),
                options, constructor.newInstance(java.time.Duration.ofSeconds(60)), page);
        return result;
    }

    /**
     * Classifies reference lines left unassociated by an output: recognition (no overlapping recognized word) or
     * segmentation (words exist; their union box would or would not associate at IoU 0.5).
     *
     * @param xml reference transcription
     * @param output scored output lines
     * @param words recognized words
     * @return text-free records per missing reference line
     * @throws Exception if reference parsing fails
     */
    static List<Map<String, Object>> missing(final byte[] xml, final List<PdfOcrProvider.Line> output,
            final List<PdfWordOcrProvider.WordLine> words) throws Exception {
        final var references = PdfHistoricalTextCorpusTest.referenceLineBoxes(xml);
        record Pair(int ref, int prediction, double overlap) {}
        final var pairs = new ArrayList<Pair>();
        final var boxes = new ArrayList<double[]>();
        for (final var reference : references) {
            final var box = (List<?>) reference.get("box");
            boxes.add(new double[] {((Number) box.get(0)).doubleValue(), ((Number) box.get(1)).doubleValue(),
                    ((Number) box.get(2)).doubleValue(), ((Number) box.get(3)).doubleValue()});
        }
        for (int r = 0; r < boxes.size(); r++) {
            for (int p = 0; p < output.size(); p++) {
                final double overlap = iou(boxes.get(r), output.get(p));
                if (overlap >= 0.5) {
                    pairs.add(new Pair(r, p, overlap));
                }
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::overlap)
                        .reversed()
                        .thenComparingInt(Pair::ref)
                        .thenComparingInt(Pair::prediction));
        final boolean[] matched = new boolean[boxes.size()];
        final boolean[] used = new boolean[output.size()];
        for (final var pair : pairs) {
            if (!matched[pair.ref()] && !used[pair.prediction()]) {
                matched[pair.ref()] = true;
                used[pair.prediction()] = true;
            }
        }
        final var result = new ArrayList<Map<String, Object>>();
        for (int r = 0; r < boxes.size(); r++) {
            if (matched[r]) {
                continue;
            }
            final double[] box = boxes.get(r);
            final var overlapping =
                    words.stream()
                            .flatMap(line -> line.words().stream())
                            .filter(word
                                    -> Math.min(box[2], word.right()) > Math.max(box[0], word.left())
                                               && Math.min(box[3], word.bottom()) > Math.max(box[1], word.top()))
                            .toList();
            final var row = new LinkedHashMap<String, Object>();
            row.put("reference_index", r);
            row.put("overlapping_words", overlapping.size());
            if (!overlapping.isEmpty()) {
                final var union =
                        new PdfOcrProvider.Line("", overlapping.stream().mapToDouble(w -> w.left()).min().orElseThrow(),
                                overlapping.stream().mapToDouble(w -> w.top()).min().orElseThrow(),
                                overlapping.stream().mapToDouble(w -> w.right()).max().orElseThrow(),
                                overlapping.stream().mapToDouble(w -> w.bottom()).max().orElseThrow());
                row.put("word_union_iou", Math.round(iou(box, union) * 1000) / 1000.0);
                row.put("covering_output_lines", output.stream().filter(line -> iou(box, line) > 0).count());
            }
            row.put("class", overlapping.isEmpty() ? "RECOGNITION"
                             : iou(box, new PdfOcrProvider.Line("",
                                                overlapping.stream().mapToDouble(w -> w.left()).min().orElseThrow(),
                                                overlapping.stream().mapToDouble(w -> w.top()).min().orElseThrow(),
                                                overlapping.stream().mapToDouble(w -> w.right()).max().orElseThrow(),
                                                overlapping.stream().mapToDouble(w -> w.bottom()).max().orElseThrow()))
                                             >= 0.5
                                     ? "SEGMENTATION_RECOVERABLE"
                                     : "SEGMENTATION_GEOMETRY");
            result.add(row);
        }
        return result;
    }

    /**
     * Describes unassociated output lines by geometry, word count and stroke contrast; no text is reported.
     *
     * @param xml reference transcription
     * @param output scored output lines
     * @param words recognized words
     * @param image page raster
     * @return text-free records per extra line
     * @throws Exception if parsing or decoding fails
     */
    static List<Map<String, Object>> extras(final byte[] xml, final List<PdfOcrProvider.Line> output,
            final List<PdfWordOcrProvider.WordLine> words, final Path image) throws Exception {
        final var references = PdfHistoricalTextCorpusTest.referenceLineBoxes(xml);
        final double median = words.stream()
                                      .flatMap(line -> line.words().stream())
                                      .mapToDouble(word -> word.bottom() - word.top())
                                      .sorted()
                                      .skip(words.stream().mapToLong(line -> line.words().size()).sum() / 2)
                                      .findFirst()
                                      .orElse(1);
        final var raster = javax.imageio.ImageIO.read(image.toFile());
        final boolean[] used = new boolean[output.size()];
        record Pair(int ref, int prediction, double overlap) {}
        final var pairs = new ArrayList<Pair>();
        for (int r = 0; r < references.size(); r++) {
            final var box = (List<?>) references.get(r).get("box");
            final double[] b = {((Number) box.get(0)).doubleValue(), ((Number) box.get(1)).doubleValue(),
                    ((Number) box.get(2)).doubleValue(), ((Number) box.get(3)).doubleValue()};
            for (int p = 0; p < output.size(); p++) {
                final double overlap = iou(b, output.get(p));
                if (overlap >= 0.5) {
                    pairs.add(new Pair(r, p, overlap));
                }
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::overlap)
                        .reversed()
                        .thenComparingInt(Pair::ref)
                        .thenComparingInt(Pair::prediction));
        final boolean[] matched = new boolean[references.size()];
        for (final var pair : pairs) {
            if (!matched[pair.ref()] && !used[pair.prediction()]) {
                matched[pair.ref()] = true;
                used[pair.prediction()] = true;
            }
        }
        final var result = new ArrayList<Map<String, Object>>();
        for (int p = 0; p < output.size(); p++) {
            if (used[p]) {
                continue;
            }
            final var line = output.get(p);
            int dark = 0;
            int count = 0;
            for (int y = Math.max(0, (int) line.top()); y < Math.min(raster.getHeight(), (int) line.bottom()); y++) {
                for (int x = Math.max(0, (int) line.left()); x < Math.min(raster.getWidth(), (int) line.right()); x++) {
                    final int rgb = raster.getRGB(x, y);
                    final int luminance =
                            (299 * ((rgb >> 16) & 255) + 587 * ((rgb >> 8) & 255) + 114 * (rgb & 255)) / 1000;
                    dark += luminance < 128 ? 1 : 0;
                    count++;
                }
            }
            final var row = new LinkedHashMap<String, Object>();
            row.put("height_in_median_word_heights", Math.round((line.bottom() - line.top()) / median * 100) / 100.0);
            row.put("width_in_median_word_heights", Math.round((line.right() - line.left()) / median * 100) / 100.0);
            row.put("words", line.text().isBlank() ? 0 : line.text().strip().split("\\s+").length);
            row.put("characters", line.text().codePointCount(0, line.text().length()));
            row.put("dark_pixel_fraction", count == 0 ? 0 : Math.round(dark * 1000.0 / count) / 1000.0);
            row.put("overlaps_reference_line", references.stream().anyMatch(reference -> {
                final var box = (List<?>) reference.get("box");
                return iou(new double[] {((Number) box.get(0)).doubleValue(), ((Number) box.get(1)).doubleValue(),
                                   ((Number) box.get(2)).doubleValue(), ((Number) box.get(3)).doubleValue()},
                               line)
                        > 0;
            }));
            result.add(row);
        }
        return result;
    }

    /**
     * IoU between a reference box and a line.
     *
     * @param box reference left, top, right, bottom
     * @param line output line
     * @return IoU
     */
    private static double iou(final double[] box, final PdfOcrProvider.Line line) {
        final double area = Math.max(0, Math.min(box[2], line.right()) - Math.max(box[0], line.left()))
                            * Math.max(0, Math.min(box[3], line.bottom()) - Math.max(box[1], line.top()));
        final double union = (box[2] - box[0]) * (box[3] - box[1])
                             + (line.right() - line.left()) * (line.bottom() - line.top()) - area;
        return union > 0 ? area / union : 0;
    }

    /**
     * Joins consecutive same-side lines inside the accepted rule band that overlap vertically by at least half the
     * smaller line height; text is space-joined in output order and boxes are unioned, so no word is dropped.
     *
     * @param lines guarded column-ordered output
     * @param details rule diagnostics; receives the join count
     * @return joined lines
     */
    static List<PdfOcrProvider.Line> join(final List<PdfOcrProvider.Line> lines, final Map<String, Object> details) {
        final double x = ((Number) details.get("run_x")).doubleValue();
        final double top = ((Number) details.get("run_top")).doubleValue();
        final double bottom = ((Number) details.get("run_bottom")).doubleValue();
        final var result = new ArrayList<PdfOcrProvider.Line>();
        int joins = 0;
        for (final var line : lines) {
            if (!result.isEmpty()) {
                final var previous = result.getLast();
                final boolean band = inBand(previous, top, bottom) && inBand(line, top, bottom);
                final boolean sameSide =
                        ((previous.left() + previous.right()) / 2 < x) == ((line.left() + line.right()) / 2 < x);
                final double overlap =
                        Math.min(previous.bottom(), line.bottom()) - Math.max(previous.top(), line.top());
                final double smaller = Math.min(previous.bottom() - previous.top(), line.bottom() - line.top());
                if (band && sameSide && overlap >= 0.5 * smaller) {
                    result.set(result.size() - 1,
                            new PdfOcrProvider.Line(previous.text() + " " + line.text(),
                                    Math.min(previous.left(), line.left()), Math.min(previous.top(), line.top()),
                                    Math.max(previous.right(), line.right()),
                                    Math.max(previous.bottom(), line.bottom())));
                    joins++;
                    continue;
                }
            }
            result.add(line);
        }
        details.put("joins", joins);
        return List.copyOf(result);
    }

    /**
     * Tests whether a line's vertical centre lies inside the rule run.
     *
     * @param line output line
     * @param top run top
     * @param bottom run bottom
     * @return band membership
     */
    private static boolean inBand(final PdfOcrProvider.Line line, final double top, final double bottom) {
        final double centre = (line.top() + line.bottom()) / 2;
        return centre >= top && centre <= bottom;
    }

    /**
     * Reads an integer metric.
     *
     * @param map metrics
     * @param key metric
     * @return value
     */
    private static int number(final Map<String, Object> map, final String key) {
        return ((Number) map.get(key)).intValue();
    }

    /**
     * Returns a lowercase SHA-256.
     *
     * @param bytes bytes
     * @return digest
     * @throws Exception if SHA-256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Fails on a broken invariant.
     *
     * @param value invariant
     * @param message failure message
     */
    private static void require(final boolean value, final String message) {
        if (!value) {
            throw new IllegalStateException(message);
        }
    }
}
