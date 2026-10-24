package io.docweave.layout;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline, detector-only evaluation of frozen independently supplied table rectangles. */
public final class EvaluateDocLayNetProposals {
    /** Prevents construction. */
    private EvaluateDocLayNetProposals() {
    }

    /**
     * Runs one detector over the frozen selection; no structure recognition or OCR occurs.
     * @param args selection JSON, private PDF directory, detector name, graph, hash and new report
     * @throws Exception if identity, loading or report persistence fails
     */
    public static void main(final String[] args) throws Exception {
        controls();
        if (args.length == 1 && args[0].equals("controls")) {
            System.out.println("scoring controls pass");
            return;
        }
        if (args.length != 6) {
            throw new IllegalArgumentException("selection source detector graph sha report");
        }
        final var mapper = JsonMapper.builder().build();
        final var selection = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final boolean yolo = args[2].equals("yolox");
        if (!yolo && !args[2].equals("tatr")) {
            throw new IllegalArgumentException("Unknown detector");
        }
        final var options = yolo ? OnnxTableOptions.yoloXDefaults(Path.of(args[3]), args[4])
                                 : OnnxTableOptions.defaults(Path.of(args[3]), args[4]);
        final var reports = new ArrayList<Map<String, Object>>();
        try (final var session = yolo ? new ManagedTableSession<>(options, new YoloXTableModel())
                                      : new ManagedTableSession<>(options, new TatrTableModel(3))) {
            for (final var page : selection.get("pages")) {
                final var report = new LinkedHashMap<String, Object>();
                report.put("id", page.get("id").asString());
                report.put("category", page.get("metadata").get("doc_category").asString());
                report.put("group", page.get("group").asString());
                final var truth = boxes(page.get("tables"));
                report.put("references", truth.size());
                final long start = System.nanoTime();
                try {
                    final byte[] bytes =
                            Files.readAllBytes(Path.of(args[1]).resolve(page.get("id").asString() + ".pdf"));
                    final var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    if (!hash.equals(page.get("pdf_sha256").asString())) {
                        throw new IllegalStateException("Source changed");
                    }
                    try (final var pdf = Loader.loadPDF(bytes)) {
                        final var raster = new PDFRenderer(pdf).renderImageWithDPI(0, 300);
                        try {
                            final var objects =
                                    session.infer(raster, new LayoutDeadline(options.timeout()), List::copyOf);
                            final var predictions = new ArrayList<double[]>();
                            final var scores = new ArrayList<Double>();
                            int rotated = 0;
                            for (final var object : objects) {
                                if (object.label() != 0) {
                                    rotated++;
                                    continue;
                                }
                                final var b = object.box();
                                predictions.add(new double[] {b.left() * 1025 / raster.getWidth(),
                                        b.top() * 1025 / raster.getHeight(), b.right() * 1025 / raster.getWidth(),
                                        b.bottom() * 1025 / raster.getHeight()});
                                scores.add(object.score());
                            }
                            final int tp = matches(predictions, truth);
                            report.put("predictions", predictions);
                            report.put("scores", scores);
                            report.put("unsupported_objects", rotated);
                            report.put("tp", tp);
                            report.put("fp", predictions.size() - tp);
                            report.put("fn", truth.size() - tp);
                            report.put("status", "ok");
                        } finally {
                            raster.flush();
                        }
                    }
                } catch (final Exception failure) {
                    report.put("status", "error");
                    report.put("error", failure.getClass().getName() + ": " + failure.getMessage());
                    report.put("tp", 0);
                    report.put("fp", null);
                    report.put("fn", truth.size());
                }
                report.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000);
                reports.add(report);
                Files.writeString(Path.of(args[5] + ".progress"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(reports));
            }
        }
        Files.writeString(Path.of(args[5]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("detector", args[2], "graph_sha256", args[4], "pages", reports))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Copies a JSON rectangle array.
     * @param array left/top/right/bottom rectangles
     * @return independent rectangles
     */
    private static List<double[]> boxes(final JsonNode array) {
        final var result = new ArrayList<double[]>();
        for (final var b : array) {
            result.add(
                    new double[] {b.get(0).asDouble(), b.get(1).asDouble(), b.get(2).asDouble(), b.get(3).asDouble()});
        }
        return result;
    }

    /**
     * Computes maximum-cardinality matching, with overlap-descending then index adjacency order.
     * @param predictions detector rectangles
     * @param truth reference rectangles
     * @return matched pairs at IoU at least one half
     */
    static int matches(final List<double[]> predictions, final List<double[]> truth) {
        final var edges = new ArrayList<List<Integer>>();
        for (final var prediction : predictions) {
            final var adjacent = new ArrayList<Integer>();
            for (int j = 0; j < truth.size(); j++) {
                if (iou(prediction, truth.get(j)) >= .5)
                    adjacent.add(j);
            }
            adjacent.sort(java.util.Comparator.<Integer>comparingDouble(j -> iou(prediction, truth.get(j)))
                            .reversed()
                            .thenComparingInt(Integer::intValue));
            edges.add(adjacent);
        }
        final int[] owners = new int[truth.size()];
        Arrays.fill(owners, -1);
        int count = 0;
        for (int i = 0; i < predictions.size(); i++) {
            if (augment(i, edges, owners, new boolean[truth.size()]))
                count++;
        }
        return count;
    }

    /**
     * Finds an augmenting path without counting any reference twice.
     * @param prediction current prediction
     * @param edges eligible references
     * @param owners current reference owners
     * @param seen visited references in this search
     * @return whether cardinality increased
     */
    private static boolean augment(
            final int prediction, final List<List<Integer>> edges, final int[] owners, final boolean[] seen) {
        for (final int ref : edges.get(prediction)) {
            if (!seen[ref]) {
                seen[ref] = true;
                if (owners[ref] < 0 || augment(owners[ref], edges, owners, seen)) {
                    owners[ref] = prediction;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Computes rectangle intersection over union.
     * @param a first rectangle
     * @param b second rectangle
     * @return overlap ratio
     */
    private static double iou(final double[] a, final double[] b) {
        final double intersection = Math.max(0, Math.min(a[2], b[2]) - Math.max(a[0], b[0]))
                                    * Math.max(0, Math.min(a[3], b[3]) - Math.max(a[1], b[1]));
        return intersection / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - intersection);
    }

    /** Checks duplicate, negative, boundary and non-greedy matching cases without models. */
    private static void controls() {
        final double[] a = {0, 0, 10, 10}, b = {4, 0, 14, 10}, broad = {0, 0, 14, 10};
        if (matches(List.of(a, a), List.of(a)) != 1 || matches(List.of(a), List.of()) != 0
                || matches(List.of(), List.of(a)) != 0 || matches(List.of(broad, a), List.of(a, b)) != 2
                || iou(a, new double[] {0, 0, 5, 10}) != .5
                || matches(List.of(new double[] {0, 0, 5, 10}), List.of(a)) != 1)
            throw new AssertionError("Scoring control failed");
    }
}
