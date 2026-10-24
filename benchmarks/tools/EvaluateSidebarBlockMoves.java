package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Diagnostic upper bound for moving one intact sidebar element at a time; not a production ordering rule. */
public final class EvaluateSidebarBlockMoves {
    /** Prevents construction. */
    private EvaluateSidebarBlockMoves() {
    }

    /**
     * Exhaustively scores single intact-sidebar moves against saved upstream order, without altering extraction.
     * @param args original PDF directory, pinned upstream capture directory and new numeric report
     * @throws Exception if extraction or identities fail
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
            final var capture = mapper.readTree(captureBytes);
            if (!capture.get("outcome").asString().equals("SUCCESS")) {
                throw new IllegalStateException("Bad capture");
            }
            final var upstream = new ArrayList<String>();
            for (final var element : capture.get("elements")) {
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
                    final var elements = extract(pdf,
                            options.withTextLayout(layout).withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS));
                    final var baseline = CompareUpstreamLineOrder.outcomes(atoms, texts(elements));
                    final long reversed =
                            baseline.stream().filter(x -> x == CompareUpstreamLineOrder.Outcome.REVERSED).count();
                    int moves = 0, safeImproving = 0, bestSafeGain = 0, bestAnyGain = 0;
                    final var safe = new ArrayList<Map<String, Object>>();
                    for (int from = 0; from < elements.size(); from++) {
                        if (!"side_stream_blocks_v1".equals(elements.get(from).metadata().get("pdf_reading_order"))) {
                            continue;
                        }
                        for (int to = 0; to < elements.size(); to++) {
                            if (from == to) {
                                continue;
                            }
                            final var moved = new ArrayList<>(elements);
                            final var side = moved.remove(from);
                            moved.add(to, side);
                            final var measured = CompareUpstreamLineOrder.outcomes(atoms, texts(moved));
                            int fixed = 0, broken = 0, newlyUnmatched = 0;
                            for (int pair = 0; pair < baseline.size(); pair++) {
                                final var before = baseline.get(pair);
                                final var after = measured.get(pair);
                                if (before == CompareUpstreamLineOrder.Outcome.REVERSED
                                        && after == CompareUpstreamLineOrder.Outcome.CORRECT) {
                                    fixed++;
                                }
                                if (before == CompareUpstreamLineOrder.Outcome.CORRECT && after != before) {
                                    broken++;
                                }
                                if (before != CompareUpstreamLineOrder.Outcome.UNMATCHED
                                        && after == CompareUpstreamLineOrder.Outcome.UNMATCHED) {
                                    newlyUnmatched++;
                                }
                            }
                            moves++;
                            bestAnyGain = Math.max(bestAnyGain, fixed - broken);
                            if (broken == 0 && newlyUnmatched == 0 && fixed > 0) {
                                safeImproving++;
                                bestSafeGain = Math.max(bestSafeGain, fixed);
                                safe.add(Map.of("from", from, "to", to, "fixed_pairs", fixed));
                            }
                        }
                    }
                    reports.add(Map.of("case", id, "layout", layout.name(), "inline", inline, "source_sha256",
                            hash(pdf), "capture_sha256", hash(captureBytes), "baseline_reversed", reversed,
                            "simulation", simulation(elements, atoms, baseline), "diagnostic",
                            Map.of("moves_tested", moves, "safe_improving_moves", safeImproving,
                                    "best_single_safe_gain", bestSafeGain, "best_single_net_gain_ignoring_safety",
                                    bestAnyGain, "safe_moves", safe)));
                }
            }
        }
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "Post-hoc exhaustive single-block placement diagnostic; preserves every element and main-element sequence. Ignores spatial barriers. Not a production candidate or simultaneous-move bound.",
                        "reports", reports))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Measures the fixed, post-hoc geometry rule without changing any extracted element.
     * @param elements existing output
     * @param atoms fixed line population
     * @param baseline existing pair outcomes
     * @return all changed-pair counts and whole-element invariant checks
     */
    private static Map<String, Object> simulation(final List<Element> elements,
            final List<CompareUpstreamLineOrder.Atom> atoms, final List<CompareUpstreamLineOrder.Outcome> baseline) {
        final var moved = move(elements);
        int swaps = 0;
        for (int i = 0; i < elements.size(); i++) {
            if ("side_stream_blocks_v1".equals(elements.get(i).metadata().get("pdf_reading_order"))
                    && !elements.get(i).equals(moved.get(i))) {
                swaps++;
            }
        }
        final var after = CompareUpstreamLineOrder.outcomes(atoms, texts(moved));
        int fixed = 0, broken = 0, newlyUnmatched = 0, reversed = 0;
        for (int i = 0; i < baseline.size(); i++) {
            final var before = baseline.get(i);
            final var now = after.get(i);
            if (before == CompareUpstreamLineOrder.Outcome.REVERSED
                    && now == CompareUpstreamLineOrder.Outcome.CORRECT) {
                fixed++;
            }
            if (before == CompareUpstreamLineOrder.Outcome.CORRECT && now != before) {
                broken++;
            }
            if (before != CompareUpstreamLineOrder.Outcome.UNMATCHED
                    && now == CompareUpstreamLineOrder.Outcome.UNMATCHED) {
                newlyUnmatched++;
            }
            if (now == CompareUpstreamLineOrder.Outcome.REVERSED) {
                reversed++;
            }
        }
        final var beforeMain =
                elements.stream()
                        .filter(e -> !"side_stream_blocks_v1".equals(e.metadata().get("pdf_reading_order")))
                        .toList();
        final var afterMain =
                moved.stream()
                        .filter(e -> !"side_stream_blocks_v1".equals(e.metadata().get("pdf_reading_order")))
                        .toList();
        final boolean mainEqual = beforeMain.equals(afterMain);
        final boolean elementsEqual = new java.util.HashSet<>(elements).equals(new java.util.HashSet<>(moved))
                                      && elements.size() == moved.size();
        if (!mainEqual || !elementsEqual) {
            throw new AssertionError("Element invariant failed");
        }
        return Map.of("swaps", swaps, "fixed_pairs", fixed, "broken_pairs", broken, "newly_unmatched", newlyUnmatched,
                "reversed", reversed, "main_elements_equal", mainEqual, "whole_elements_preserved", elementsEqual);
    }

    /**
     * Simulates the frozen adjacent whole-element rule within one page.
     * @param elements existing page elements
     * @return reordered whole elements
     */
    static List<Element> move(final List<Element> elements) {
        final var moved = new ArrayList<>(elements);
        for (int i = 1; i < moved.size(); i++) {
            final var side = moved.get(i);
            final var previous = moved.get(i - 1);
            final var a = bounds(side);
            final var b = bounds(previous);
            if ("side_stream_blocks_v1".equals(side.metadata().get("pdf_reading_order"))
                    && previous.type() == ElementType.UncategorizedText
                    && !"side_stream_blocks_v1".equals(previous.metadata().get("pdf_reading_order"))
                    && (!(previous.metadata().get("pdf_line_count") instanceof Number count) || count.intValue() == 1)
                    && a != null && b != null && a[2] <= b[0] && b[1] <= a[1] && a[1] < b[3]) {
                moved.set(i - 1, side);
                moved.set(i, previous);
            }
        }
        return List.copyOf(moved);
    }

    /**
     * Reads native displayed bounding rectangle endpoints.
     * @param element an extracted element
     * @return left/top/right/bottom, or null for unsupported coordinates
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
     * Extracts first-page items with explicit native options.
     * @param bytes source PDF
     * @param options native policy
     * @return complete first-page elements including images
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
     * @param elements whole elements in measured order
     * @return collapsed non-image text
     */
    private static List<String> texts(final List<Element> elements) {
        return elements.stream()
                .filter(e -> e.type() != ElementType.Image)
                .map(e -> CompareUpstreamLineOrder.collapse(e.text()))
                .toList();
    }

    /**
     * Hashes complete source bytes.
     * @param bytes exact bytes
     * @return SHA-256
     * @throws Exception if SHA-256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
