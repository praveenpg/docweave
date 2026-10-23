package dev.docweave.validation;

import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfWordOcrProvider;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Attributes retained-capture recognition errors to page zones and local stroke contrast; text-free diagnostic.
 *
 * <p>Uses the unchanged baseline ALIGNMENT_COLUMN_V1 captures and the same one-to-one IoU&ge;0.5 association as the
 * historical scorer. Each reference line is assigned to the page edge band (within 6% of any image edge) or the
 * interior, and to a per-page stroke-contrast tercile (background median minus the darkest decile of its box
 * luminance). Errors are code-point edit distances; missing lines count their full length. Extra predicted lines are
 * attributed to zones. No engine runs and no text is written.
 */
public final class DiagnoseScanErrors {
    /** Prevents construction. */
    private DiagnoseScanErrors() {
    }

    /**
     * Writes the attribution report.
     *
     * @param args manifest, sources, baseline captures and fresh report
     * @throws Exception if reading or scoring fails
     */
    @SuppressWarnings("unchecked")
    public static void main(final String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("manifest sources baseline report");
        }
        final var json = JsonMapper.builder().build();
        final var manifest = json.readTree(Files.readAllBytes(Path.of(args[0])));
        final var rows = new ArrayList<Map<String, Object>>();
        final var referenceMethod = PdfHistoricalTextCorpusTest.class.getDeclaredMethod("reference", byte[].class);
        referenceMethod.setAccessible(true);
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            final byte[] xml = Files.readAllBytes(Path.of(args[1], selected.get("xml").asString()));
            final var reference = referenceMethod.invoke(null, (Object) xml);
            final var linesMethod = reference.getClass().getDeclaredMethod("lines");
            linesMethod.setAccessible(true);
            final var refs = new ArrayList<double[]>();
            final var refTexts = new ArrayList<String>();
            for (final Object line : (List<Object>) linesMethod.invoke(reference)) {
                final var textMethod = line.getClass().getDeclaredMethod("text");
                final var boxMethod = line.getClass().getDeclaredMethod("box");
                textMethod.setAccessible(true);
                boxMethod.setAccessible(true);
                final Object box = boxMethod.invoke(line);
                final double[] values = new double[4];
                int index = 0;
                for (final String name : List.of("left", "top", "right", "bottom")) {
                    final var accessor = box.getClass().getDeclaredMethod(name);
                    accessor.setAccessible(true);
                    values[index++] = (double) accessor.invoke(box);
                }
                refs.add(values);
                refTexts.add((String) textMethod.invoke(line));
            }
            final BufferedImage image = ImageIO.read(Path.of(args[1], selected.get("image").asString()).toFile());
            final int[][] y = luminance(image);
            final double[] contrast = refs.stream().mapToDouble(box -> contrast(y, box)).toArray();
            final double[] sorted = Arrays.stream(contrast).sorted().toArray();
            final double low = sorted[sorted.length / 3];
            final double high = sorted[2 * sorted.length / 3];
            for (final var profile : manifest.get("profiles")) {
                final String language = profile.get("language").asString();
                final var saved = json.readTree(Files.readAllBytes(Path.of(args[2], id + "-" + language + ".json")));
                final var predictions = EvaluateOcrColumnAlignment.regroup(
                        List.of(json.treeToValue(saved.get("word_lines"), PdfWordOcrProvider.WordLine[].class)),
                        Map.of());
                final int[] match = associate(refs, predictions);
                final var buckets = new LinkedHashMap<String, long[]>();
                for (int r = 0; r < refs.size(); r++) {
                    final String zone = edge(refs.get(r), image) ? "edge" : "interior";
                    final String tercile = contrast[r] <= low ? "low" : contrast[r] <= high ? "mid" : "high";
                    final int length = refTexts.get(r).codePointCount(0, refTexts.get(r).length());
                    final long errors =
                            match[r] < 0 ? length
                                         : distance(refTexts.get(r).codePoints().toArray(),
                                                   normalize(predictions.get(match[r]).text()).codePoints().toArray());
                    for (final String key : List.of("zone:" + zone, "contrast:" + tercile)) {
                        final long[] bucket = buckets.computeIfAbsent(key, ignored -> new long[4]);
                        bucket[0]++;
                        bucket[1] += match[r] < 0 ? 1 : 0;
                        bucket[2] += errors;
                        bucket[3] += length;
                    }
                }
                final boolean[] used = new boolean[predictions.size()];
                for (final int m : match) {
                    if (m >= 0) {
                        used[m] = true;
                    }
                }
                final var extras = new LinkedHashMap<String, Integer>();
                for (int p = 0; p < predictions.size(); p++) {
                    if (!used[p]) {
                        final var line = predictions.get(p);
                        extras.merge(edge(new double[] {line.left(), line.top(), line.right(), line.bottom()}, image)
                                             ? "edge"
                                             : "interior",
                                1, Integer::sum);
                    }
                }
                final var row = new LinkedHashMap<String, Object>();
                row.put("id", id);
                row.put("language", language);
                row.put("reference_lines", refs.size());
                final var summary = new LinkedHashMap<String, Object>();
                buckets.forEach(
                        (key, value)
                                -> summary.put(key, Map.of("lines", value[0], "missing", value[1], "character_errors",
                                                            value[2], "code_points", value[3])));
                row.put("buckets", summary);
                row.put("extra_lines_by_zone", extras);
                row.put("contrast_tercile_bounds", List.of(Math.round(low), Math.round(high)));
                rows.add(row);
                System.out.println(row);
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put(
                "scope", "Zone/contrast attribution of retained baseline recognition errors; counts only, no engine.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("rows", rows);
        Files.writeString(Path.of(args[3]), json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * One-to-one association by descending IoU at 0.5, as the historical scorer.
     *
     * @param refs reference boxes
     * @param predictions predicted lines
     * @return matched prediction per reference, or -1
     */
    private static int[] associate(final List<double[]> refs, final List<PdfOcrProvider.Line> predictions) {
        record Pair(int ref, int prediction, double overlap) {}
        final var pairs = new ArrayList<Pair>();
        for (int r = 0; r < refs.size(); r++) {
            final double[] a = refs.get(r);
            for (int p = 0; p < predictions.size(); p++) {
                final var q = predictions.get(p);
                final double area = Math.max(0, Math.min(a[2], q.right()) - Math.max(a[0], q.left()))
                                    * Math.max(0, Math.min(a[3], q.bottom()) - Math.max(a[1], q.top()));
                final double iou =
                        area / ((a[2] - a[0]) * (a[3] - a[1]) + (q.right() - q.left()) * (q.bottom() - q.top()) - area);
                if (iou >= 0.5) {
                    pairs.add(new Pair(r, p, iou));
                }
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::overlap)
                        .reversed()
                        .thenComparingInt(Pair::ref)
                        .thenComparingInt(Pair::prediction));
        final int[] match = new int[refs.size()];
        Arrays.fill(match, -1);
        final boolean[] used = new boolean[predictions.size()];
        for (final var pair : pairs) {
            if (match[pair.ref()] < 0 && !used[pair.prediction()]) {
                match[pair.ref()] = pair.prediction();
                used[pair.prediction()] = true;
            }
        }
        return match;
    }

    /**
     * Tests whether a box touches the outer 6% band of the image.
     *
     * @param box left, top, right, bottom
     * @param image page raster
     * @return edge-band membership
     */
    private static boolean edge(final double[] box, final BufferedImage image) {
        final double bx = 0.06 * image.getWidth();
        final double by = 0.06 * image.getHeight();
        return box[0] < bx || box[1] < by || box[2] > image.getWidth() - bx || box[3] > image.getHeight() - by;
    }

    /**
     * Stroke contrast inside a box: background median minus the darkest-decile luminance.
     *
     * @param y luminance
     * @param box box
     * @return contrast in levels
     */
    private static double contrast(final int[][] y, final double[] box) {
        final int top = Math.max(0, (int) box[1]);
        final int bottom = Math.min(y.length, (int) Math.ceil(box[3]));
        final int left = Math.max(0, (int) box[0]);
        final int right = Math.min(y[0].length, (int) Math.ceil(box[2]));
        final int[] values = new int[Math.max(1, (bottom - top) * (right - left))];
        int count = 0;
        for (int row = top; row < bottom; row++) {
            for (int column = left; column < right; column++) {
                values[count++] = y[row][column];
            }
        }
        final int[] sorted = Arrays.copyOf(values, Math.max(1, count));
        Arrays.sort(sorted);
        return sorted[sorted.length / 2] - sorted[sorted.length / 10];
    }

    /**
     * Rec. 601 luminance.
     *
     * @param image raster
     * @return rows of luminance
     */
    private static int[][] luminance(final BufferedImage image) {
        final int[][] result = new int[image.getHeight()][image.getWidth()];
        for (int row = 0; row < image.getHeight(); row++) {
            for (int column = 0; column < image.getWidth(); column++) {
                final int rgb = image.getRGB(column, row);
                result[row][column] = (299 * ((rgb >> 16) & 255) + 587 * ((rgb >> 8) & 255) + 114 * (rgb & 255)) / 1000;
            }
        }
        return result;
    }

    /**
     * Scorer-compatible presentation: NFC and collapsed ASCII whitespace.
     *
     * @param text recognized text
     * @return normalized text
     */
    private static String normalize(final String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
                .replaceAll("[ \\t\\r\\n\\f]+", " ")
                .strip();
    }

    /**
     * Code-point Levenshtein distance.
     *
     * @param a first
     * @param b second
     * @return edits
     */
    private static int distance(final int[] a, final int[] b) {
        int[] previous = new int[b.length + 1];
        int[] current = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length; i++) {
            current[0] = i;
            for (int j = 1; j <= b.length; j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1));
            }
            final int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length];
    }
}
