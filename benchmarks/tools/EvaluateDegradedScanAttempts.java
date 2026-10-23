package dev.docweave.validation;

import dev.docweave.ocr.TesseractOcr;
import dev.docweave.ocr.TesseractOptions;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfWordOcrProvider;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Evaluates frozen degraded-scan attempts against retained ALIGNMENT_COLUMN_V1 baseline word captures.
 *
 * <p>Every variant uses the same engine protocol as those captures: colour-managed RGB, AUTO segmentation, default
 * thresholding, no engine DPI override and one native thread. Variants are {@code DESKEW} (existing public deskew
 * decorator), {@code RULE} (ridge-based printed-rule split of the retained words; no OCR), {@code BORDER} (edge-band
 * masking), {@code COMPONENTS} (large dark component suppression) and {@code DUAL_UNION} (non-overlapping lines from a
 * stored-sample recognition). Labels are read only by the unchanged scorer after the output is fixed. Reports contain
 * metrics and hashes; new word captures are written privately.
 */
public final class EvaluateDegradedScanAttempts {
    /** Stable serialization. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Metrics where a larger value is better. */
    private static final List<String> HIGHER = List.of("associated_reference_lines");

    /** Metrics where a smaller value is better. */
    private static final List<String> LOWER = List.of("missing_reference_lines", "predicted_lines_without_reference",
            "joint_reference_line_character_errors", "global_character_edits", "xml_sequence_character_edits");

    /** Prevents construction. */
    private EvaluateDegradedScanAttempts() {
    }

    /**
     * Runs one variant over all four scans and both models.
     *
     * @param args variant, manifest, executable, tessdata, sources, baseline captures, fresh private capture directory
     *         and fresh report
     * @throws Exception if an identity, budget or scoring step fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 8) {
            throw new IllegalArgumentException(
                    "variant manifest executable tessdata sources baseline new-captures report");
        }
        final String variant = args[0];
        require(List.of("CAPTURE", "DESKEW", "RULE", "RUNTIME_RULE", "BORDER", "COMPONENTS", "DUAL_UNION")
                        .contains(variant),
                "Unknown variant");
        final Path manifestPath = Path.of(args[1]);
        final var manifest = JSON.readTree(Files.readAllBytes(manifestPath));
        final String manifestHash = hash(Files.readAllBytes(manifestPath));
        final Path executable = Path.of(args[2]);
        require(hash(Files.readAllBytes(executable)).equals(manifest.get("engine_sha256").asString()),
                "Engine changed");
        final Path captures = Path.of(args[6]);
        Files.createDirectories(captures);
        final var results = new ArrayList<Map<String, Object>>();
        boolean accepted = true;
        boolean targetGain = false;
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            for (final var pin : selected.get("inputs")) {
                final var bytes = Files.readAllBytes(Path.of(args[4], pin.get("filename").asString()));
                require(bytes.length == pin.get("bytes").asLong() && hash(bytes).equals(pin.get("sha256").asString()),
                        "Source pin changed");
            }
            final byte[] xml = Files.readAllBytes(Path.of(args[4], selected.get("xml").asString()));
            for (final var profile : manifest.get("profiles")) {
                final String language = profile.get("language").asString();
                require(hash(Files.readAllBytes(Path.of(args[3]).resolve(profile.get("filename").asString())))
                                .equals(profile.get("sha256").asString()),
                        "Model changed");
                final Path baselinePath = Path.of(args[5], id + "-" + language + ".json");
                if (variant.equals("CAPTURE")) {
                    capture(selected, language, executable, Path.of(args[3]), Path.of(args[4]), manifestHash,
                            profile.get("sha256").asString(), baselinePath);
                    continue;
                }
                final var saved = JSON.readTree(Files.readAllBytes(baselinePath));
                require(saved.get("manifest_sha256").asString().equals(manifestHash)
                                && saved.get("outcome").asString().equals("SUCCESS"),
                        "Baseline capture differs");
                final var baseline =
                        List.of(JSON.treeToValue(saved.get("word_lines"), PdfWordOcrProvider.WordLine[].class));
                final var row = new LinkedHashMap<String, Object>();
                row.put("id", id);
                row.put("language", language);
                row.put("baseline_capture_sha256", hash(Files.readAllBytes(baselinePath)));
                final List<PdfWordOcrProvider.WordLine> words;
                final var details = new LinkedHashMap<String, Object>();
                if (variant.equals("RULE")) {
                    words = baseline;
                } else if (variant.equals("RUNTIME_RULE")) {
                    // The public opt-in policy must reproduce the evaluated rule exactly through the real engine.
                    final var rgb = rgb(ImageIO.read(Path.of(args[4], selected.get("image").asString()).toFile()));
                    final var options =
                            new TesseractOptions(executable, Path.of(args[3]), List.of(language),
                                    Duration.ofSeconds(60), 1, 20_000_000, 32L * 1024 * 1024, 4 * 1024 * 1024, 10_000,
                                    1_000_000)
                                    .withLineSplitting(TesseractOptions.LineSplitting.WIDE_GAPS_AND_PRINTED_RULE);
                    final var runtime = TesseractOcr.create(options).recognize(rgb);
                    final var evaluated =
                            ruleSplit(baseline, Path.of(args[4], selected.get("image").asString()), details);
                    details.put("runtime_equals_evaluated_rule", runtime.equals(evaluated));
                    require(runtime.equals(evaluated), "Runtime policy differs from the evaluated rule");
                    words = baseline;
                } else {
                    final var decoded = ImageIO.read(Path.of(args[4], selected.get("image").asString()).toFile());
                    final var rgb = rgb(decoded);
                    require(hash(rgbBytes(rgb)).equals(saved.get("input_rgb_sha256").asString()),
                            "Managed RGB differs from baseline capture");
                    final var options = new TesseractOptions(executable, Path.of(args[3]), List.of(language),
                            Duration.ofSeconds(60), 1, 20_000_000, 32L * 1024 * 1024, 4 * 1024 * 1024, 10_000,
                            1_000_000);
                    final var engine = TesseractOcr.createWithWords(options);
                    words = switch (variant) {
                        case "DESKEW" ->
                            PdfWordOcrProvider.deskewed(engine, new PdfOcrOptions(300, 20_000_000, 10_000, 1_000_000))
                                    .recognizeWords(rgb, PdfOcrProvider.InputKind.PAGE);
                        case "BORDER" -> engine.recognizeWords(masked(rgb, details), PdfOcrProvider.InputKind.PAGE);
                        case "COMPONENTS" ->
                            engine.recognizeWords(components(rgb, details), PdfOcrProvider.InputKind.PAGE);
                        default -> {
                            final var stored = PdfHistoricalTextCorpusTest.storedSamples(decoded);
                            details.put("secondary", stored == null ? "NONE" : "STORED_SAMPLES");
                            yield stored == null
                                    ? baseline
                                    : union(baseline, engine.recognizeWords(stored, PdfOcrProvider.InputKind.PAGE));
                        }
                    };
                    final var capture = new LinkedHashMap<String, Object>();
                    capture.put("id", id);
                    capture.put("language", language);
                    capture.put("variant", variant);
                    capture.put("word_lines", words);
                    Files.writeString(captures.resolve(id + "-" + language + "-" + variant + ".json"),
                            JSON.writeValueAsString(capture), StandardOpenOption.CREATE_NEW);
                }
                final boolean regroup =
                        variant.equals("DESKEW") || variant.equals("RULE") || variant.equals("RUNTIME_RULE");
                final var before = regroup ? EvaluateOcrColumnAlignment.regroup(baseline, Map.of())
                                           : baseline.stream().map(PdfWordOcrProvider.WordLine::line).toList();
                final List<PdfOcrProvider.Line> after;
                if (variant.equals("RULE") || variant.equals("RUNTIME_RULE")) {
                    after = ruleSplit(baseline, Path.of(args[4], selected.get("image").asString()), details);
                } else if (regroup) {
                    after = EvaluateOcrColumnAlignment.regroup(words, Map.of());
                } else {
                    after = words.stream().map(PdfWordOcrProvider.WordLine::line).toList();
                }
                final var baseScore = EvaluateOcrColumnAlignment.score(xml, before);
                final var newScore = EvaluateOcrColumnAlignment.score(xml, after);
                final boolean worse = worse(baseScore, newScore);
                final boolean better = better(baseScore, newScore);
                final boolean identical = before.equals(after);
                row.put("output_lines", List.of(before.size(), after.size()));
                row.put("identical_output", identical);
                row.put("baseline", baseScore);
                row.put("candidate", newScore);
                row.put("any_metric_worse", worse);
                row.put("any_metric_better", better);
                row.put("details", details);
                final boolean target = id.equals("buerger_gedichte_1778_0039") || id.equals("nn_besuch_1780_00001");
                if (variant.equals("RULE") || variant.equals("RUNTIME_RULE")) {
                    final boolean positive = id.equals("buerger_gedichte_1778_0039");
                    final boolean pass = positive ? number(newScore, "associated_reference_lines")
                                                                    > number(baseScore, "associated_reference_lines")
                                                            && !worseExcept(baseScore, newScore)
                                                  : identical;
                    accepted &= pass;
                    targetGain |= positive && pass;
                } else if (variant.equals("DESKEW")) {
                    final boolean positive = id.equals("buerger_gedichte_1778_0039");
                    accepted &= positive ? number(newScore, "associated_reference_lines")
                                                           > number(baseScore, "associated_reference_lines")
                                                   && !worseExcept(baseScore, newScore)
                                         : !worse;
                    targetGain |= positive;
                } else {
                    accepted &= !worse;
                    targetGain |= target && better;
                }
                results.add(row);
                System.out.println(
                        "Complete " + variant + " " + id + " " + language + " worse=" + worse + " better=" + better);
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("variant", variant);
        report.put("manifest_sha256", manifestHash);
        report.put("java_version", System.getProperty("java.version"));
        report.put("no_metric_worse_where_required", accepted);
        report.put("target_gain", targetGain);
        report.put("results", results);
        Files.writeString(Path.of(args[7]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Writes one baseline word capture in the retained ALIGNMENT_COLUMN_V1 format under the same engine protocol.
     *
     * @param selected manifest case
     * @param language model language
     * @param executable pinned engine
     * @param data model directory
     * @param sources source directory
     * @param manifestHash manifest identity
     * @param model model identity
     * @param path fresh capture path
     * @throws Exception if decoding, recognition or writing fails
     */
    private static void capture(final tools.jackson.databind.JsonNode selected, final String language,
            final Path executable, final Path data, final Path sources, final String manifestHash, final String model,
            final Path path) throws Exception {
        final var rgb = rgb(ImageIO.read(sources.resolve(selected.get("image").asString()).toFile()));
        require(rgb.getWidth() == selected.get("width").asInt() && rgb.getHeight() == selected.get("height").asInt(),
                "Raster differs");
        final var options = new TesseractOptions(executable, data, List.of(language), Duration.ofSeconds(60), 1,
                20_000_000, 32L * 1024 * 1024, 4 * 1024 * 1024, 10_000, 1_000_000);
        final var capture = new LinkedHashMap<String, Object>();
        capture.put("id", selected.get("id").asString());
        capture.put("language", language);
        capture.put("model_sha256", model);
        capture.put("input_rgb_sha256", hash(rgbBytes(rgb)));
        capture.put("manifest_sha256", manifestHash);
        capture.put("outcome", "SUCCESS");
        capture.put(
                "word_lines", TesseractOcr.createWithWords(options).recognizeWords(rgb, PdfOcrProvider.InputKind.PAGE));
        Files.createDirectories(path.getParent());
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(capture) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("Captured " + path.getFileName());
    }

    /**
     * Tests whether any tracked metric present in both scores worsened.
     *
     * @param before baseline score
     * @param after candidate score
     * @return whether any metric is worse
     */
    private static boolean worse(final Map<String, Object> before, final Map<String, Object> after) {
        return HIGHER.stream().anyMatch(k -> number(after, k) < number(before, k)) || worseExcept(before, after);
    }

    /**
     * Tests whether any lower-is-better metric present in both scores worsened.
     *
     * @param before baseline score
     * @param after candidate score
     * @return whether any such metric is worse
     */
    private static boolean worseExcept(final Map<String, Object> before, final Map<String, Object> after) {
        return LOWER.stream().filter(before::containsKey).anyMatch(k -> number(after, k) > number(before, k));
    }

    /**
     * Tests whether any tracked metric improved.
     *
     * @param before baseline score
     * @param after candidate score
     * @return whether any metric is better
     */
    private static boolean better(final Map<String, Object> before, final Map<String, Object> after) {
        return HIGHER.stream().anyMatch(k -> number(after, k) > number(before, k))
                || LOWER.stream().filter(before::containsKey).anyMatch(k -> number(after, k) < number(before, k));
    }

    /**
     * Applies the frozen ridge-based printed-rule split to retained words.
     *
     * @param lines retained baseline words
     * @param image source image path
     * @param details numeric detection diagnostics
     * @return WIDE_GAPS regrouping plus rule boundaries
     * @throws Exception if decoding fails
     */
    private static List<PdfOcrProvider.Line> ruleSplit(final List<PdfWordOcrProvider.WordLine> lines, final Path image,
            final Map<String, Object> details) throws Exception {
        final var rgb = rgb(ImageIO.read(image.toFile()));
        final int width = rgb.getWidth();
        final int height = rgb.getHeight();
        final int[][] y = luminance(rgb);
        final double[] heights = lines.stream()
                                         .flatMap(l -> l.words().stream())
                                         .mapToDouble(w -> w.bottom() - w.top())
                                         .sorted()
                                         .toArray();
        final double h = heights[heights.length / 2];
        final double left = lines.stream().flatMap(l -> l.words().stream()).mapToDouble(w -> w.left()).min().orElse(0);
        final double right =
                lines.stream().flatMap(l -> l.words().stream()).mapToDouble(w -> w.right()).max().orElse(0);
        int bestX = -1, bestRun = 0, bestTop = 0, bestBottom = 0;
        for (int x = Math.max(2, (int) Math.ceil(left + 0.1 * (right - left)));
                x <= Math.min(width - 3, (int) Math.floor(left + 0.9 * (right - left))); x++) {
            int start = -1, gap = 0;
            for (int row = 0; row < height; row++) {
                final boolean evidence = (y[row][x - 2] + y[row][x + 2]) / 2.0 - y[row][x] >= 8;
                if (evidence) {
                    if (start < 0) {
                        start = row;
                    }
                    gap = 0;
                    final int length = row - start + 1;
                    if (length > bestRun) {
                        bestRun = length;
                        bestX = x;
                        bestTop = start;
                        bestBottom = row;
                    }
                } else if (start >= 0 && ++gap > 2) {
                    start = -1;
                    gap = 0;
                }
            }
        }
        details.put("median_word_height", h);
        details.put("longest_run", bestRun);
        details.put("run_x", bestX);
        details.put("run_top", bestTop);
        details.put("run_bottom", bestBottom);
        final boolean accepted = bestX >= 0 && bestRun >= 8 * h;
        details.put("rule_accepted", accepted);
        if (!accepted) {
            return EvaluateOcrColumnAlignment.regroup(lines, Map.of());
        }
        final var additions = new LinkedHashMap<Integer, Integer>();
        for (int index = 0; index < lines.size(); index++) {
            final var words = lines.get(index).words();
            final var box = lines.get(index).line();
            if (words.size() < 2 || box.bottom() < bestTop || box.top() > bestBottom) {
                continue;
            }
            final double x = bestX;
            if (words.stream().anyMatch(w -> w.left() < x && w.right() > x)) {
                continue;
            }
            for (int word = 1; word < words.size(); word++) {
                if (words.get(word - 1).right() <= x && words.get(word).left() >= x) {
                    additions.put(index, word);
                    break;
                }
            }
        }
        details.put("rule_splits", additions.size());
        return EvaluateOcrColumnAlignment.regroup(lines, additions);
    }

    /**
     * Applies frozen T6 edge-band masking.
     *
     * @param rgb source raster, not modified
     * @param details numeric diagnostics
     * @return masked copy
     */
    private static BufferedImage masked(final BufferedImage rgb, final Map<String, Object> details) {
        final int width = rgb.getWidth();
        final int height = rgb.getHeight();
        final int[][] y = luminance(rgb);
        final int threshold = otsu(y);
        final double[] columns = new double[width];
        final double[] rows = new double[height];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                columns[column] += y[row][column];
                rows[row] += y[row][column];
            }
        }
        int left = 0;
        while (left < width && columns[left] / height < threshold) {
            left++;
        }
        int right = width;
        while (right > left && columns[right - 1] / height < threshold) {
            right--;
        }
        int top = 0;
        while (top < height && rows[top] / width < threshold) {
            top++;
        }
        int bottom = height;
        while (bottom > top && rows[bottom - 1] / width < threshold) {
            bottom--;
        }
        final var copy = copy(rgb);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                if (column < left || column >= right || row < top || row >= bottom) {
                    copy.setRGB(column, row, 0xffffff);
                }
            }
        }
        details.put("otsu", threshold);
        details.put("kept_box", List.of(left, top, right, bottom));
        return copy;
    }

    /**
     * Applies frozen large-component suppression.
     *
     * @param rgb source raster, not modified
     * @param details numeric diagnostics
     * @return copy with oversized dark components whitened
     */
    private static BufferedImage components(final BufferedImage rgb, final Map<String, Object> details) {
        final int width = rgb.getWidth();
        final int height = rgb.getHeight();
        final int[][] y = luminance(rgb);
        final int threshold = otsu(y);
        final int[] label = new int[width * height];
        final var boxes = new ArrayList<int[]>();
        final var queue = new ArrayDeque<Integer>();
        for (int start = 0; start < label.length; start++) {
            if (label[start] != 0 || y[start / width][start % width] >= threshold) {
                continue;
            }
            final int id = boxes.size() + 1;
            final int[] box = {start % width, start / width, start % width, start / width};
            label[start] = id;
            queue.add(start);
            while (!queue.isEmpty()) {
                final int at = queue.poll();
                final int ax = at % width;
                final int ay = at / width;
                box[0] = Math.min(box[0], ax);
                box[1] = Math.min(box[1], ay);
                box[2] = Math.max(box[2], ax);
                box[3] = Math.max(box[3], ay);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        final int nx = ax + dx;
                        final int ny = ay + dy;
                        if (nx >= 0 && ny >= 0 && nx < width && ny < height && label[ny * width + nx] == 0
                                && y[ny][nx] < threshold) {
                            label[ny * width + nx] = id;
                            queue.add(ny * width + nx);
                        }
                    }
                }
            }
            boxes.add(box);
        }
        final double[] heights =
                boxes.stream().mapToDouble(b -> b[3] - b[1] + 1).filter(value -> value >= 4).sorted().toArray();
        final double h = heights.length == 0 ? 0 : heights[heights.length / 2];
        final boolean[] remove = new boolean[boxes.size() + 1];
        int removed = 0;
        for (int index = 0; index < boxes.size(); index++) {
            final int[] b = boxes.get(index);
            if (h > 0 && (b[3] - b[1] + 1 > 6 * h || b[2] - b[0] + 1 > 30 * h)) {
                remove[index + 1] = true;
                removed++;
            }
        }
        final var copy = copy(rgb);
        long pixels = 0;
        for (int at = 0; at < label.length; at++) {
            if (label[at] != 0 && remove[label[at]]) {
                copy.setRGB(at % width, at / width, 0xffffff);
                pixels++;
            }
        }
        details.put("otsu", threshold);
        details.put("components", boxes.size());
        details.put("median_component_height", h);
        details.put("removed_components", removed);
        details.put("removed_pixels", pixels);
        return copy;
    }

    /**
     * Adds non-overlapping secondary word lines at their top position, keeping every primary line.
     *
     * @param primary primary word lines
     * @param secondary secondary word lines
     * @return union
     */
    static List<PdfWordOcrProvider.WordLine> union(
            final List<PdfWordOcrProvider.WordLine> primary, final List<PdfWordOcrProvider.WordLine> secondary) {
        final var lines =
                PdfHistoricalTextCorpusTest.union(primary.stream().map(PdfWordOcrProvider.WordLine::line).toList(),
                        secondary.stream().map(PdfWordOcrProvider.WordLine::line).toList());
        final var byLine = new LinkedHashMap<PdfOcrProvider.Line, PdfWordOcrProvider.WordLine>();
        secondary.forEach(line -> byLine.putIfAbsent(line.line(), line));
        primary.forEach(line -> byLine.put(line.line(), line));
        return lines.stream().map(byLine::get).toList();
    }

    /**
     * Copies a decoded image to TYPE_INT_RGB exactly as the baseline capture protocol does.
     *
     * @param decoded decoded image
     * @return RGB copy
     */
    private static BufferedImage rgb(final BufferedImage decoded) {
        final var rgb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < rgb.getHeight(); row++) {
            final int[] pixels = decoded.getRGB(0, row, rgb.getWidth(), 1, null, 0, rgb.getWidth());
            rgb.setRGB(0, row, rgb.getWidth(), 1, pixels, 0, rgb.getWidth());
        }
        return rgb;
    }

    /**
     * Copies a raster.
     *
     * @param rgb source raster
     * @return independent copy
     */
    private static BufferedImage copy(final BufferedImage rgb) {
        final var copy = new BufferedImage(rgb.getWidth(), rgb.getHeight(), BufferedImage.TYPE_INT_RGB);
        copy.setData(rgb.getRaster());
        return copy;
    }

    /**
     * Serializes RGB bytes in the baseline hash order.
     *
     * @param rgb raster
     * @return interleaved RGB bytes
     */
    private static byte[] rgbBytes(final BufferedImage rgb) {
        final byte[] bytes = new byte[rgb.getWidth() * rgb.getHeight() * 3];
        int at = 0;
        for (int row = 0; row < rgb.getHeight(); row++) {
            for (final int pixel : rgb.getRGB(0, row, rgb.getWidth(), 1, null, 0, rgb.getWidth())) {
                bytes[at++] = (byte) (pixel >> 16);
                bytes[at++] = (byte) (pixel >> 8);
                bytes[at++] = (byte) pixel;
            }
        }
        return bytes;
    }

    /**
     * Computes integer luminance.
     *
     * @param rgb raster
     * @return luminance rows
     */
    private static int[][] luminance(final BufferedImage rgb) {
        final int[][] y = new int[rgb.getHeight()][rgb.getWidth()];
        for (int row = 0; row < rgb.getHeight(); row++) {
            for (int column = 0; column < rgb.getWidth(); column++) {
                final int v = rgb.getRGB(column, row);
                y[row][column] = (299 * ((v >> 16) & 255) + 587 * ((v >> 8) & 255) + 114 * (v & 255)) / 1000;
            }
        }
        return y;
    }

    /**
     * Computes the global Otsu threshold.
     *
     * @param y luminance rows
     * @return threshold
     */
    private static int otsu(final int[][] y) {
        final long[] histogram = new long[256];
        long total = 0;
        for (final int[] row : y) {
            for (final int value : row) {
                histogram[value]++;
                total++;
            }
        }
        double sum = 0;
        for (int index = 0; index < 256; index++) {
            sum += index * (double) histogram[index];
        }
        double background = 0, max = -1;
        long weight = 0;
        int best = 0;
        for (int index = 0; index < 256; index++) {
            weight += histogram[index];
            if (weight == 0) {
                continue;
            }
            final long foreground = total - weight;
            if (foreground == 0) {
                break;
            }
            background += index * (double) histogram[index];
            final double mb = background / weight;
            final double mf = (sum - background) / foreground;
            final double variance = (double) weight * foreground * (mb - mf) * (mb - mf);
            if (variance > max) {
                max = variance;
                best = index;
            }
        }
        return best;
    }

    /**
     * Reads one integer metric.
     *
     * @param map score
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
     * @throws Exception if unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Fails an invariant.
     *
     * @param value condition
     * @param message explanation
     */
    private static void require(final boolean value, final String message) {
        if (!value) {
            throw new IllegalStateException(message);
        }
    }
}
