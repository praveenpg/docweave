package io.docweave.validation;

import io.docweave.ocr.TesseractOcr;
import io.docweave.ocr.TesseractOptions;
import io.docweave.partition.PdfOcrProvider;
import io.docweave.partition.PdfWordOcrProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Evaluates the frozen P6 scan-preprocessing candidates ({@code benchmarks/review/pdf-scan-preprocessing-p6/PROTOCOL.md})
 * with the ALIGNMENT_COLUMN_V1 inputs, the pinned Tesseract, the unchanged {@code WIDE_GAPS} regrouping and the
 * unchanged historical scorer of {@link EvaluateOcrColumnAlignment}. Baselines are the retained word captures,
 * replayed with identity checks. Candidates read only baseline OCR geometry and pixels, never reference labels. The
 * report holds metrics, counts and hashes only.
 */
public final class EvaluateScanPreprocessing {
    /** JSON codec. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Prevents construction. */
    private EvaluateScanPreprocessing() {
    }

    /**
     * Runs one candidate on all four scans and both models.
     * @param args candidate ({@code P6_2} or {@code P6_4}), manifest, Tesseract executable, tessdata directory,
     *        sources, retained baseline captures and a NEW report
     * @throws Exception if an identity, budget or write fails
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 7 && (args[0].equals("P6_2") || args[0].equals("P6_4")),
                "Expected P6_2|P6_4 manifest executable tessdata sources baseline-captures NEW-report");
        final String candidate = args[0];
        final Path manifestPath = Path.of(args[1]);
        final var manifest = JSON.readTree(Files.readAllBytes(manifestPath));
        final String manifestHash = hash(Files.readAllBytes(manifestPath));
        final Path executable = Path.of(args[2]);
        final Path data = Path.of(args[3]);
        require(hash(Files.readAllBytes(executable)).equals(manifest.get("engine_sha256").asString()), "Engine changed");
        final var results = new ArrayList<Map<String, Object>>();
        boolean accepted = true;
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
                require(hash(Files.readAllBytes(data.resolve(profile.get("filename").asString())))
                                .equals(profile.get("sha256").asString()), "Model changed");
                final var saved = JSON.readTree(Files.readAllBytes(Path.of(args[5], id + "-" + language + ".json")));
                require(saved.get("manifest_sha256").asString().equals(manifestHash)
                                && saved.get("model_sha256").asString().equals(profile.get("sha256").asString())
                                && "SUCCESS".equals(saved.get("outcome").asString()), "Baseline capture differs");
                final var baselineWords =
                        List.of(JSON.treeToValue(saved.get("word_lines"), PdfWordOcrProvider.WordLine[].class));
                final BufferedImage rgb = rgb(Path.of(args[4], selected.get("image").asString()), selected);
                require(rgbHash(rgb).equals(saved.get("input_rgb_sha256").asString()), "Input pixels differ");
                final var row = new LinkedHashMap<String, Object>();
                row.put("id", id);
                row.put("language", language);
                row.put("candidate", candidate);
                final int changed = candidate.equals("P6_2") ? restoreStrokes(rgb, baselineWords)
                                                             : removeArtifacts(rgb, baselineWords);
                row.put(candidate.equals("P6_2") ? "lines_filtered" : "components_removed", changed);
                row.put("preprocessed_rgb_sha256", rgbHash(rgb));
                final var options = new TesseractOptions(executable, data, List.of(language), Duration.ofSeconds(60), 1,
                        20_000_000, 32L * 1024 * 1024, 4 * 1024 * 1024, 10_000, 1_000_000);
                List<PdfWordOcrProvider.WordLine> words = List.of();
                String outcome = "SUCCESS";
                final long started = System.nanoTime();
                try {
                    words = TesseractOcr.createWithWords(options).recognizeWords(rgb, PdfOcrProvider.InputKind.PAGE);
                } catch (final Exception failure) {
                    outcome = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                } finally {
                    rgb.flush();
                }
                row.put("outcome", outcome);
                row.put("ocr_millis", (System.nanoTime() - started) / 1_000_000);
                final var baseline = EvaluateOcrColumnAlignment.score(xml,
                        EvaluateOcrColumnAlignment.regroup(baselineWords, Map.of()));
                final var measured = EvaluateOcrColumnAlignment.score(xml,
                        EvaluateOcrColumnAlignment.regroup(words, Map.of()));
                row.put("baseline", pick(baseline));
                row.put("candidate_metrics", pick(measured));
                final boolean target = candidate.equals("P6_2") ? id.equals("buerger_gedichte_1778_0039")
                                                                : id.equals("nn_besuch_1780_00001");
                final boolean pass = outcome.equals("SUCCESS") && acceptable(candidate, target, baseline, measured);
                row.put("role", target ? "target" : "control");
                row.put("acceptance", pass);
                accepted &= pass;
                results.add(row);
                System.out.println(id + " " + language + " " + row.get("role") + " " + pick(baseline) + " -> "
                                   + pick(measured) + " changed=" + changed + " pass=" + pass);
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("protocol", "benchmarks/review/pdf-scan-preprocessing-p6/PROTOCOL.md");
        report.put("manifest_sha256", manifestHash);
        report.put("java_version", System.getProperty("java.version"));
        report.put("candidate", candidate);
        report.put("acceptance", accepted);
        report.put("results", results);
        Files.write(Path.of(args[6]), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
                StandardOpenOption.CREATE_NEW);
        System.out.println("candidate " + candidate + " acceptance=" + accepted);
    }

    /**
     * Applies the frozen acceptance rule to one observation.
     * @param candidate candidate
     * @param target whether this observation is the candidate's target
     * @param baseline baseline metrics
     * @param measured candidate metrics
     * @return whether it passes
     */
    private static boolean acceptable(final String candidate, final boolean target, final Map<String, Object> baseline,
            final Map<String, Object> measured) {
        final boolean noFewerAssociated = n(measured, "associated_reference_lines") >= n(baseline,
                "associated_reference_lines");
        final boolean noMoreMissing = n(measured, "missing_reference_lines") <= n(baseline, "missing_reference_lines");
        final boolean noMoreExtras = n(measured, "predicted_lines_without_reference") <= n(baseline,
                "predicted_lines_without_reference");
        final boolean noMoreErrors = n(measured, "joint_reference_line_character_errors") <= n(baseline,
                "joint_reference_line_character_errors");
        if (!target) {
            return noFewerAssociated && noMoreMissing && noMoreExtras && noMoreErrors;
        }
        if (candidate.equals("P6_2")) {
            return n(measured, "joint_reference_line_character_errors") < n(baseline,
                    "joint_reference_line_character_errors") && noFewerAssociated && noMoreMissing && noMoreExtras;
        }
        return n(measured, "predicted_lines_without_reference") < n(baseline, "predicted_lines_without_reference")
               && noFewerAssociated && noMoreMissing && noMoreErrors;
    }

    /**
     * P6-2: 3x3 per-channel minimum inside the low- and mid-contrast tercile baseline line boxes.
     * @param rgb page pixels, changed in place
     * @param lines baseline word lines
     * @return number of filtered line boxes
     */
    private static int restoreStrokes(final BufferedImage rgb, final List<PdfWordOcrProvider.WordLine> lines) {
        final int width = rgb.getWidth();
        final int height = rgb.getHeight();
        final int[] source = rgb.getRGB(0, 0, width, height, null, 0, width);
        final var boxes = new ArrayList<int[]>();
        final var contrasts = new ArrayList<Double>();
        for (final var line : lines) {
            final var box = clamp(line.line().left(), line.line().top(), line.line().right(), line.line().bottom(),
                    width, height);
            if (box != null) {
                boxes.add(box);
                contrasts.add(contrast(source, width, box));
            }
        }
        if (boxes.isEmpty()) {
            return 0;
        }
        final double[] sorted = contrasts.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        final double high = sorted[(int) Math.floor((sorted.length - 1) * 2.0 / 3.0)];
        final int[] output = source.clone();
        int filtered = 0;
        for (int index = 0; index < boxes.size(); index++) {
            if (contrasts.get(index) > high) {
                continue;
            }
            filtered++;
            final int[] box = boxes.get(index);
            for (int y = box[1]; y < box[3]; y++) {
                for (int x = box[0]; x < box[2]; x++) {
                    int r = 255;
                    int g = 255;
                    int b = 255;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            final int px = Math.min(width - 1, Math.max(0, x + dx));
                            final int py = Math.min(height - 1, Math.max(0, y + dy));
                            final int pixel = source[py * width + px];
                            r = Math.min(r, (pixel >> 16) & 0xFF);
                            g = Math.min(g, (pixel >> 8) & 0xFF);
                            b = Math.min(b, pixel & 0xFF);
                        }
                    }
                    output[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        }
        rgb.setRGB(0, 0, width, height, output, 0, width);
        return filtered;
    }

    /**
     * P6-4: removes specks and thin tall components wholly outside every baseline word box.
     * @param rgb page pixels, changed in place
     * @param lines baseline word lines
     * @return number of removed components
     */
    private static int removeArtifacts(final BufferedImage rgb, final List<PdfWordOcrProvider.WordLine> lines) {
        final int width = rgb.getWidth();
        final int height = rgb.getHeight();
        final int[] pixels = rgb.getRGB(0, 0, width, height, null, 0, width);
        final int[] luminance = new int[pixels.length];
        final int[] histogram = new int[256];
        for (int i = 0; i < pixels.length; i++) {
            luminance[i] = luma(pixels[i]);
            histogram[luminance[i]]++;
        }
        final int threshold = otsu(histogram, pixels.length);
        final var words = new ArrayList<int[]>();
        final var heights = new ArrayList<Double>();
        for (final var line : lines) {
            for (final var word : line.words()) {
                final var box = clamp(word.left(), word.top(), word.right(), word.bottom(), width, height);
                if (box != null) {
                    words.add(box);
                    heights.add(word.bottom() - word.top());
                }
            }
        }
        if (heights.isEmpty()) {
            return 0;
        }
        final double h = heights.stream().mapToDouble(Double::doubleValue).sorted().toArray()[heights.size() / 2];
        final boolean[] inWord = new boolean[pixels.length];
        for (final int[] box : words) {
            for (int y = box[1]; y < box[3]; y++) {
                Arrays.fill(inWord, y * width + box[0], y * width + box[2], true);
            }
        }
        // Background colour: per-channel median of non-ink pixels.
        final int[][] channel = new int[3][256];
        for (int i = 0; i < pixels.length; i++) {
            if (luminance[i] > threshold) {
                channel[0][(pixels[i] >> 16) & 0xFF]++;
                channel[1][(pixels[i] >> 8) & 0xFF]++;
                channel[2][pixels[i] & 0xFF]++;
            }
        }
        final int background = 0xFF000000 | (median(channel[0]) << 16) | (median(channel[1]) << 8) | median(channel[2]);
        final boolean[] seen = new boolean[pixels.length];
        final var queue = new ArrayDeque<Integer>();
        final var members = new ArrayList<Integer>();
        int removed = 0;
        for (int start = 0; start < pixels.length; start++) {
            if (seen[start] || luminance[start] > threshold) {
                continue;
            }
            members.clear();
            queue.add(start);
            seen[start] = true;
            int left = width;
            int top = height;
            int right = -1;
            int bottom = -1;
            boolean touchesWord = false;
            while (!queue.isEmpty()) {
                final int at = queue.poll();
                members.add(at);
                final int x = at % width;
                final int y = at / width;
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
                touchesWord |= inWord[at];
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        final int nx = x + dx;
                        final int ny = y + dy;
                        if (nx >= 0 && ny >= 0 && nx < width && ny < height) {
                            final int next = ny * width + nx;
                            if (!seen[next] && luminance[next] <= threshold) {
                                seen[next] = true;
                                queue.add(next);
                            }
                        }
                    }
                }
            }
            final int w = right - left + 1;
            final int ht = bottom - top + 1;
            final boolean speck = Math.max(w, ht) < 0.75 * h;
            final boolean thin = w < 0.15 * h && ht > 5 * h;
            if (!touchesWord && (speck || thin)) {
                removed++;
                for (final int at : members) {
                    pixels[at] = background;
                }
            }
        }
        rgb.setRGB(0, 0, width, height, pixels, 0, width);
        return removed;
    }

    /**
     * Stroke contrast of a box: luminance background median minus the darkest-decile value.
     * @param pixels page pixels
     * @param width page width
     * @param box left, top, right, bottom (exclusive)
     * @return contrast in levels
     */
    private static double contrast(final int[] pixels, final int width, final int[] box) {
        final int[] histogram = new int[256];
        int count = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                histogram[luma(pixels[y * width + x])]++;
                count++;
            }
        }
        return quantile(histogram, count, 0.5) - quantile(histogram, count, 0.1);
    }

    /**
     * Returns a histogram quantile.
     * @param histogram histogram
     * @param count total
     * @param q quantile
     * @return value
     */
    private static int quantile(final int[] histogram, final int count, final double q) {
        final long rank = (long) Math.floor(q * (count - 1));
        long seen = 0;
        for (int v = 0; v < 256; v++) {
            seen += histogram[v];
            if (seen > rank) {
                return v;
            }
        }
        return 255;
    }

    /**
     * Returns a histogram median.
     * @param histogram histogram
     * @return median value
     */
    private static int median(final int[] histogram) {
        final int count = Arrays.stream(histogram).sum();
        return count == 0 ? 255 : quantile(histogram, count, 0.5);
    }

    /**
     * Returns the global Otsu threshold.
     * @param histogram luminance histogram
     * @param total pixel count
     * @return threshold (ink is at or below it)
     */
    private static int otsu(final int[] histogram, final int total) {
        double sum = 0;
        for (int v = 0; v < 256; v++) {
            sum += (double) v * histogram[v];
        }
        double backgroundSum = 0;
        long background = 0;
        double best = -1;
        int threshold = 0;
        for (int v = 0; v < 256; v++) {
            background += histogram[v];
            if (background == 0) {
                continue;
            }
            final long foreground = total - background;
            if (foreground == 0) {
                break;
            }
            backgroundSum += (double) v * histogram[v];
            final double meanB = backgroundSum / background;
            final double meanF = (sum - backgroundSum) / foreground;
            final double between = (double) background * foreground * (meanB - meanF) * (meanB - meanF);
            if (between > best) {
                best = between;
                threshold = v;
            }
        }
        return threshold;
    }

    /**
     * Returns Rec. 601 luminance.
     * @param pixel RGB
     * @return luminance
     */
    private static int luma(final int pixel) {
        return (int) Math.round(0.299 * ((pixel >> 16) & 0xFF) + 0.587 * ((pixel >> 8) & 0xFF) + 0.114 * (pixel & 0xFF));
    }

    /**
     * Clamps a box to the page.
     * @param left left
     * @param top top
     * @param right right
     * @param bottom bottom
     * @param width page width
     * @param height page height
     * @return integer box, or null when empty
     */
    private static int[] clamp(final double left, final double top, final double right, final double bottom,
            final int width, final int height) {
        final int x0 = Math.max(0, (int) Math.floor(left));
        final int y0 = Math.max(0, (int) Math.floor(top));
        final int x1 = Math.min(width, (int) Math.ceil(right));
        final int y1 = Math.min(height, (int) Math.ceil(bottom));
        return x1 > x0 && y1 > y0 ? new int[] {x0, y0, x1, y1} : null;
    }

    /**
     * Loads the scan as colour-managed TYPE_INT_RGB exactly as the alignment harness does.
     * @param path image
     * @param selected manifest case
     * @return pixels
     * @throws Exception on read failure
     */
    private static BufferedImage rgb(final Path path, final JsonNode selected) throws Exception {
        final var decoded = ImageIO.read(path.toFile());
        require(decoded != null && decoded.getWidth() == selected.get("width").asInt()
                        && decoded.getHeight() == selected.get("height").asInt(), "Raster differs");
        final var rgb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < rgb.getHeight(); y++) {
            rgb.setRGB(0, y, rgb.getWidth(), 1, decoded.getRGB(0, y, rgb.getWidth(), 1, null, 0, rgb.getWidth()), 0,
                    rgb.getWidth());
        }
        decoded.flush();
        return rgb;
    }

    /**
     * Hashes pixels as the alignment harness does (R, G, B bytes in row order).
     * @param rgb pixels
     * @return SHA-256
     * @throws Exception if unavailable
     */
    private static String rgbHash(final BufferedImage rgb) throws Exception {
        final var digest = MessageDigest.getInstance("SHA-256");
        for (int y = 0; y < rgb.getHeight(); y++) {
            for (final int pixel : rgb.getRGB(0, y, rgb.getWidth(), 1, null, 0, rgb.getWidth())) {
                digest.update((byte) (pixel >> 16));
                digest.update((byte) (pixel >> 8));
                digest.update((byte) pixel);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Selects the protocol metrics.
     * @param metrics scorer metrics
     * @return associated, missing, extras and character errors
     */
    private static Map<String, Object> pick(final Map<String, Object> metrics) {
        final var picked = new LinkedHashMap<String, Object>();
        for (final String key : List.of("associated_reference_lines", "missing_reference_lines",
                "predicted_lines_without_reference", "joint_reference_line_character_errors")) {
            picked.put(key, metrics.get(key));
        }
        return picked;
    }

    /**
     * Reads an integer metric.
     * @param metrics metrics
     * @param key key
     * @return value
     */
    private static int n(final Map<String, Object> metrics, final String key) {
        return ((Number) metrics.get(key)).intValue();
    }

    /**
     * Computes a SHA-256 digest.
     * @param bytes bytes
     * @return hex
     * @throws Exception if unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Fails on a violated requirement.
     * @param valid requirement
     * @param message message
     */
    private static void require(final boolean valid, final String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }
}
