package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
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
 * Simulates the pinned Unstructured {@code fast} page ordering on whole DocWeave elements; evaluation only.
 *
 * <p>The order follows the installed 0.27.28 source: a stable basic sort by the first point's top then left, then
 * {@code recursive_xy_cut_swapped} on boxes shrunk to 0.9 of their width and height (left and top kept, integer
 * truncation), with projection splits at zero-coverage gaps wider than one unit. NumPy's default argsort is not
 * stable; this simulation uses stable sorts. Elements an XY-cut pass would drop (zero-extent boxes) are appended in
 * basic order and counted. Pair outcomes use the unchanged {@link CompareUpstreamLineOrder} functions.
 */
public final class SimulateUpstreamXyCut {
    /** Prevents construction of this command-line tool. */
    private SimulateUpstreamXyCut() {
    }

    /**
     * Writes simulated pair transitions for every paper and column profile.
     *
     * @param args original source directory, pinned capture directory and a fresh report
     * @throws Exception if an identity or extraction check fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("sources captures report");
        }
        final var mapper = JsonMapper.builder().build();
        final var runtime = mapper.readTree(Files.readAllBytes(Path.of(args[1], "runtime.json")));
        if (!runtime.get("packages").get("unstructured").asString().equals("0.27.28")) {
            throw new IllegalArgumentException("Reference runtime differs");
        }
        final var reports = new ArrayList<Map<String, Object>>();
        for (final var id : List.of("recent", "next", "holdout", "elife")) {
            final byte[] pdf = Files.readAllBytes(Path.of(args[0], id + ".pdf"));
            final byte[] captureBytes = Files.readAllBytes(Path.of(args[1], id + ".json"));
            final var upstream = new ArrayList<String>();
            for (final var element : mapper.readTree(captureBytes).get("elements")) {
                if (element.get("metadata").get("page_number").asInt() == 1
                        && !element.get("type").asString().equals("Image")) {
                    upstream.add(CompareUpstreamLineOrder.collapse(element.get("text").asString()));
                }
            }
            for (final boolean inline : List.of(false, true)) {
                var options = PdfOptions.defaults();
                if (inline) {
                    options = options.withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
                }
                final var atoms = CompareUpstreamLineOrder.atoms(
                        texts(extract(pdf, options.withTextLayout(PdfOptions.TextLayout.LINES))), upstream);
                for (final var layout : List.of(PdfOptions.TextLayout.COLUMN_PARAGRAPHS,
                             PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS)) {
                    final var elements = extract(pdf, options.withTextLayout(layout).withSideStreams(
                                                              PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES));
                    final int[] dropped = new int[1];
                    final var sorted = order(elements, dropped);
                    if (sorted.size() != elements.size() || !new java.util.HashSet<>(sorted).containsAll(elements)) {
                        throw new AssertionError("Whole-element invariant failed");
                    }
                    final var before = CompareUpstreamLineOrder.outcomes(atoms, texts(elements));
                    final var after = CompareUpstreamLineOrder.outcomes(atoms, texts(sorted));
                    final var counts = new LinkedHashMap<String, Long>();
                    for (int pair = 0; pair < before.size(); pair++) {
                        if (before.get(pair) != after.get(pair)) {
                            counts.merge(before.get(pair).name().toLowerCase() + "_to_"
                                                 + after.get(pair).name().toLowerCase(),
                                    1L, Long::sum);
                        }
                    }
                    final var row = new LinkedHashMap<String, Object>();
                    row.put("case", id);
                    row.put("layout", layout.name());
                    row.put("inline", inline);
                    row.put("source_sha256", hash(pdf));
                    row.put("capture_sha256", hash(captureBytes));
                    row.put("elements", elements.size());
                    row.put("dropped_by_xy_cut_appended", dropped[0]);
                    row.put("pairs", before.size());
                    row.put("reversed_before",
                            before.stream().filter(CompareUpstreamLineOrder.Outcome.REVERSED::equals).count());
                    row.put("reversed_after",
                            after.stream().filter(CompareUpstreamLineOrder.Outcome.REVERSED::equals).count());
                    row.put("changed_pairs", counts);
                    reports.add(row);
                }
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Upstream fast basic + XY-cut ordering applied to whole row-tie elements on page 1; "
                                    + "evaluation only, stable sorts, no runtime change.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("reports", reports);
        Files.writeString(Path.of(args[2]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Orders one page's elements as upstream would.
     *
     * @param elements page elements
     * @param dropped receives the number of elements the XY-cut pass did not emit
     * @return all elements in simulated order
     */
    static List<Element> order(final List<Element> elements, final int[] dropped) {
        final var basic = new ArrayList<>(elements);
        basic.sort(Comparator.comparingDouble((Element e) -> bounds(e)[1]).thenComparingDouble(e -> bounds(e)[0]));
        final var boxes = new int[basic.size()][];
        for (int index = 0; index < basic.size(); index++) {
            final double[] b = bounds(basic.get(index));
            final int left = (int) b[0];
            final int top = (int) b[1];
            final int right = (int) b[2];
            final int bottom = (int) b[3];
            final double width = right - left;
            final double height = bottom - top;
            boxes[index] = new int[] {
                    left, top, (int) (right - (width - width * 0.9)), (int) (bottom - (height - height * 0.9))};
        }
        final var indices = new ArrayList<Integer>();
        for (int index = 0; index < basic.size(); index++) {
            indices.add(index);
        }
        final var result = new ArrayList<Integer>();
        swapped(boxes, indices, result);
        final var emitted = new java.util.LinkedHashSet<>(result);
        final var ordered = new ArrayList<Element>();
        emitted.forEach(index -> ordered.add(basic.get(index)));
        for (int index = 0; index < basic.size(); index++) {
            if (!emitted.contains(index)) {
                ordered.add(basic.get(index));
                dropped[0]++;
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * Upstream {@code recursive_xy_cut_swapped}, with stable sorts.
     *
     * @param boxes all boxes
     * @param indices current subset in current order
     * @param result output order
     */
    private static void swapped(final int[][] boxes, final List<Integer> indices, final List<Integer> result) {
        final var xSorted = new ArrayList<>(indices);
        xSorted.sort(Comparator.comparingInt(index -> boxes[index][0]));
        final var xSplit = split(projection(boxes, xSorted, 0));
        if (xSplit == null) {
            return;
        }
        for (final int[] segment : xSplit) {
            final var chunk = xSorted.stream()
                                      .filter(index -> segment[0] <= boxes[index][0] && boxes[index][0] < segment[1])
                                      .sorted(Comparator.comparingInt(index -> boxes[index][1]))
                                      .toList();
            final var ySplit = split(projection(boxes, chunk, 1));
            if (ySplit == null) {
                continue;
            }
            if (ySplit.size() == 1) {
                result.addAll(chunk);
                continue;
            }
            for (final int[] rows : ySplit) {
                swapped(boxes,
                        chunk.stream()
                                .filter(index -> rows[0] <= boxes[index][1] && boxes[index][1] < rows[1])
                                .toList(),
                        result);
            }
        }
    }

    /**
     * Upstream {@code projection_by_bboxes}.
     *
     * @param boxes all boxes
     * @param subset boxes to project
     * @param axis 0 for x, 1 for y
     * @return coverage histogram
     */
    private static int[] projection(final int[][] boxes, final List<Integer> subset, final int axis) {
        int length = 0;
        for (final int index : subset) {
            length = Math.max(length, Math.max(boxes[index][axis], boxes[index][axis + 2]));
        }
        final int[] histogram = new int[Math.max(0, length)];
        for (final int index : subset) {
            for (int at = Math.max(0, boxes[index][axis]); at < boxes[index][axis + 2]; at++) {
                histogram[at]++;
            }
        }
        return histogram;
    }

    /**
     * Upstream {@code split_projection_profile} with min_value 0 and min_gap 1.
     *
     * @param values histogram
     * @return [start, end) segments, or null when nothing is covered
     */
    private static List<int[]> split(final int[] values) {
        final var covered = new ArrayList<Integer>();
        for (int index = 0; index < values.length; index++) {
            if (values[index] > 0) {
                covered.add(index);
            }
        }
        if (covered.isEmpty()) {
            return null;
        }
        final var segments = new ArrayList<int[]>();
        int start = covered.getFirst();
        for (int index = 1; index < covered.size(); index++) {
            if (covered.get(index) - covered.get(index - 1) > 1) {
                segments.add(new int[] {start, covered.get(index - 1) + 1});
                start = covered.get(index);
            }
        }
        segments.add(new int[] {start, covered.getLast() + 1});
        return segments;
    }

    /**
     * Reads displayed left, top, right and bottom.
     *
     * @param element an extracted element
     * @return bounds; elements without coordinates sort last at the origin-free maximum
     */
    private static double[] bounds(final Element element) {
        if (!(element.metadata().get("coordinates") instanceof Map<?, ?> coordinates)
                || !(coordinates.get("points") instanceof List<?> points) || points.size() != 4
                || !(points.get(0) instanceof List<?> first) || !(points.get(2) instanceof List<?> last)) {
            return new double[] {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        }
        return new double[] {((Number) first.get(0)).doubleValue(), ((Number) first.get(1)).doubleValue(),
                ((Number) last.get(0)).doubleValue(), ((Number) last.get(1)).doubleValue()};
    }

    /**
     * Extracts page-one elements, images included.
     *
     * @param bytes source PDF
     * @param options native policy
     * @return page-one elements in output order
     * @throws Exception if extraction fails
     */
    private static List<Element> extract(final byte[] bytes, final PdfOptions options) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(bytes, "paper.pdf"))
                .elements()
                .stream()
                .filter(e -> e.metadata().get("page_number") instanceof Number n && n.intValue() == 1)
                .toList();
    }

    /**
     * Projects text exactly as the existing pair evaluator does.
     *
     * @param elements elements in measured order
     * @return collapsed non-image texts
     */
    private static List<String> texts(final List<Element> elements) {
        return elements.stream()
                .filter(e -> e.type() != ElementType.Image)
                .map(e -> CompareUpstreamLineOrder.collapse(e.text()))
                .toList();
    }

    /**
     * Returns the lowercase SHA-256 of bytes.
     *
     * @param bytes bytes to hash
     * @return hexadecimal digest
     * @throws Exception if SHA-256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
