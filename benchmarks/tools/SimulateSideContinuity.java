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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulates the frozen, threshold-free side-column continuity move on existing row-tie output; evaluation only.
 *
 * <p>Starting from {@code SMALLER_TYPE_BLOCKS_ROW_TIES} output in a column layout, one in-order pass moves each side
 * element directly after the nearest preceding side element when every element between them is a non-side element
 * whose box does not horizontally overlap the union extent of the two side elements (the existing side-join barrier
 * test). Elements without displayed bounds act as barriers. Elements are never split, joined or rewritten. Pair
 * outcomes use the unchanged {@link CompareUpstreamLineOrder} population and outcome function; the report has counts
 * and hashes only.
 */
public final class SimulateSideContinuity {
    /** Side-stream provenance value. */
    private static final String SIDE = "side_stream_blocks_v1";

    /** Prevents construction of this command-line tool. */
    private SimulateSideContinuity() {
    }

    /**
     * Writes simulated pair transitions for every paper and column profile.
     *
     * @param args original source directory, pinned capture directory and a fresh report
     * @throws Exception if an identity, invariant or extraction check fails
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
                    final var moved = move(elements);
                    requirePreserved(elements, moved);
                    final var before = CompareUpstreamLineOrder.outcomes(atoms, texts(elements));
                    final var after = CompareUpstreamLineOrder.outcomes(atoms, texts(moved));
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
                    row.put("moved_elements", movedCount(elements, moved));
                    row.put("pairs", before.size());
                    row.put("reversed_before", count(before, CompareUpstreamLineOrder.Outcome.REVERSED));
                    row.put("reversed_after", count(after, CompareUpstreamLineOrder.Outcome.REVERSED));
                    row.put("changed_pairs", counts);
                    reports.add(row);
                }
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Post-hoc, threshold-free side-column continuity simulation on page 1; evaluation only, "
                                    + "no runtime change. Whole elements preserved by construction and checked.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("reports", reports);
        Files.writeString(Path.of(args[2]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Applies the frozen continuity move.
     *
     * @param elements row-tie output for one page, images included
     * @return the same elements, possibly reordered
     */
    static List<Element> move(final List<Element> elements) {
        final var result = new ArrayList<>(elements);
        for (int index = 1; index < result.size(); index++) {
            final var side = result.get(index);
            if (!SIDE.equals(side.metadata().get("pdf_reading_order"))) {
                continue;
            }
            int previous = index - 1;
            while (previous >= 0 && !SIDE.equals(result.get(previous).metadata().get("pdf_reading_order"))) {
                previous--;
            }
            if (previous < 0 || previous == index - 1) {
                continue;
            }
            final double[] a = bounds(result.get(previous));
            final double[] b = bounds(side);
            if (a == null || b == null) {
                continue;
            }
            final double left = Math.min(a[0], b[0]);
            final double right = Math.max(a[2], b[2]);
            boolean barrier = false;
            for (int between = previous + 1; between < index && !barrier; between++) {
                final double[] box = bounds(result.get(between));
                barrier = box == null || (box[0] < right && box[2] > left);
            }
            if (!barrier) {
                result.remove(index);
                result.add(previous + 1, side);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Fails unless the multiset of elements and the order of non-side elements are unchanged.
     *
     * @param before original order
     * @param after simulated order
     */
    private static void requirePreserved(final List<Element> before, final List<Element> after) {
        final var counts = new HashMap<Element, Integer>();
        before.forEach(element -> counts.merge(element, 1, Integer::sum));
        after.forEach(element -> counts.merge(element, -1, Integer::sum));
        final var main = before.stream().filter(e -> !SIDE.equals(e.metadata().get("pdf_reading_order"))).toList();
        final var mainAfter = after.stream().filter(e -> !SIDE.equals(e.metadata().get("pdf_reading_order"))).toList();
        if (before.size() != after.size() || counts.values().stream().anyMatch(value -> value != 0)
                || !main.equals(mainAfter)) {
            throw new AssertionError("Whole-element invariant failed");
        }
    }

    /**
     * Counts positions whose element changed.
     *
     * @param before original order
     * @param after simulated order
     * @return number of differing positions
     */
    private static long movedCount(final List<Element> before, final List<Element> after) {
        long moved = 0;
        for (int index = 0; index < before.size(); index++) {
            if (!before.get(index).equals(after.get(index))) {
                moved++;
            }
        }
        return moved;
    }

    /**
     * Counts one outcome.
     *
     * @param outcomes pair outcomes
     * @param outcome counted outcome
     * @return occurrences
     */
    private static long count(
            final List<CompareUpstreamLineOrder.Outcome> outcomes, final CompareUpstreamLineOrder.Outcome outcome) {
        return outcomes.stream().filter(outcome::equals).count();
    }

    /**
     * Reads displayed left, top, right and bottom from PDF user-space coordinates.
     *
     * @param element an extracted element
     * @return bounds, or null when absent
     */
    private static double[] bounds(final Element element) {
        if (!(element.metadata().get("coordinates") instanceof Map<?, ?> coordinates)
                || !"PDFUserSpace".equals(coordinates.get("system"))
                || !(coordinates.get("points") instanceof List<?> points) || points.size() != 4
                || !(points.get(0) instanceof List<?> first) || !(points.get(2) instanceof List<?> last)) {
            return null;
        }
        return new double[] {((Number) first.get(0)).doubleValue(), ((Number) first.get(1)).doubleValue(),
                ((Number) last.get(0)).doubleValue(), ((Number) last.get(1)).doubleValue()};
    }

    /**
     * Extracts page-one elements, images included, with explicit native options.
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
