package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates page-one output against the reading-order contract ({@code docs/pdf-reading-order-contract.md} v1).
 *
 * <ul>
 *   <li><b>R4 contiguity:</b> the number of runs of left-margin elements (right edge left of the main column, top at
 *       or below a fixed band start, nonblank). The contract wants one run per side stream; barrier-divided streams
 *       and unrecognized members can add runs.</li>
 *   <li><b>Pair adjudication:</b> each reversed element pair of a {@link ClassifyResidualLineOrder} report (reversed
 *       against pinned upstream order) is judged by R3/R5 from element boxes only. It is either
 *       {@code OUTPUT_VIOLATES_CONTRACT} (the upstream direction is the contract's) or {@code REFERENCE_DIFFERS} (the
 *       output follows the contract and upstream differs).</li>
 * </ul>
 *
 * <p>The main-column left edge and band start are declared per case, from page geometry, before measurement. The
 * report holds counts and indices only, never document text.</p>
 */
public final class EvaluateReadingOrderContract {
    /** JSON codec. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Prevents construction. */
    private EvaluateReadingOrderContract() {
    }

    /**
     * Runs the evaluation.
     * @param args source directory, residual report, comma-separated {@code case=mainLeft:bandTop} specs,
     *        comma-separated profiles ({@code LAYOUT+SIDE_STREAMS_POLICY}) and a NEW report path
     * @throws Exception on identity or I/O failure
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected sources, residual report, case specs, profiles, NEW report");
        }
        final var residual = JSON.readTree(Files.readAllBytes(Path.of(args[1])));
        final var report = new LinkedHashMap<String, Object>();
        report.put("contract", "docs/pdf-reading-order-contract.md v1");
        final var cases = new ArrayList<Map<String, Object>>();
        for (final String spec : args[2].split(",")) {
            final String id = spec.substring(0, spec.indexOf('='));
            final String[] geometry = spec.substring(spec.indexOf('=') + 1).split(":");
            final double mainLeft = Double.parseDouble(geometry[0]);
            final double bandTop = Double.parseDouble(geometry[1]);
            final byte[] bytes = Files.readAllBytes(Path.of(args[0], id + ".pdf"));
            final var result = new LinkedHashMap<String, Object>();
            result.put("case", id);
            result.put("main_left", mainLeft);
            result.put("band_top", bandTop);
            final var runs = new LinkedHashMap<String, Integer>();
            for (final String profile : args[3].split(",")) {
                runs.put(profile, runs(page(bytes, profile), mainLeft, bandTop));
            }
            result.put("r4_margin_runs", runs);
            for (final var residualCase : residual.get("cases")) {
                if (residualCase.get("id").asString().equals(id)) {
                    for (final var profile : residualCase.get("profiles")) {
                        result.put("adjudicated_profile", profile.get("profile").asString());
                        result.put("reversed_line_pairs_by_verdict",
                                adjudicate(page(bytes, profile.get("profile").asString()), profile, mainLeft, bandTop));
                    }
                }
            }
            cases.add(result);
            System.out.println(result);
        }
        report.put("cases", cases);
        Files.write(Path.of(args[4]), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Extracts page one with a profile.
     * @param bytes source
     * @param profile {@code LAYOUT[+SIDE_STREAMS_x][+INLINE_SCRIPTS]}
     * @return page-one elements in output order
     * @throws Exception on extraction failure
     */
    private static List<Element> page(final byte[] bytes, final String profile) throws Exception {
        final var parts = profile.split("\\+");
        PdfOptions options = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.valueOf(parts[0]));
        for (int index = 1; index < parts.length; index++) {
            options = switch (parts[index]) {
                case "INLINE_SCRIPTS" -> options.withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
                case "SIDE_STREAMS" -> options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS);
                case "SIDE_STREAMS_ROW_TIES" ->
                    options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES);
                case "SIDE_STREAMS_ROW_FRAGMENTS" ->
                    options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS);
                case "SIDE_STREAMS_CONTIGUOUS" ->
                    options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_CONTIGUOUS);
                default -> throw new IllegalArgumentException("Unknown profile modifier");
            };
        }
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                       .partition(DocumentSource.bytes(bytes, "source.pdf")).elements().stream()
                       .filter(element -> element.metadata().get("page_number") instanceof Number number
                                          && number.intValue() == 1)
                       .toList();
    }

    /**
     * Returns an element's box as left, top, right, bottom, or null without coordinates.
     * @param element element
     * @return box
     */
    private static double[] box(final Element element) {
        if (!(element.metadata().get("coordinates") instanceof Map<?, ?> coordinates)
                || !(coordinates.get("points") instanceof List<?> points) || points.size() != 4) {
            return null;
        }
        final var first = (List<?>) points.get(0);
        final var third = (List<?>) points.get(2);
        return new double[] {((Number) first.get(0)).doubleValue(), ((Number) first.get(1)).doubleValue(),
                ((Number) third.get(0)).doubleValue(), ((Number) third.get(1)).doubleValue()};
    }

    /**
     * Counts runs of left-margin elements in output order; blank elements neither start nor end a run.
     * @param elements page-one elements
     * @param mainLeft main column left edge
     * @param bandTop band start
     * @return runs
     */
    private static int runs(final List<Element> elements, final double mainLeft, final double bandTop) {
        int runs = 0;
        boolean inRun = false;
        for (final Element element : elements) {
            final double[] box = box(element);
            if (box == null || element.text().isBlank()) {
                continue;
            }
            final boolean margin = box[2] < mainLeft && box[1] >= bandTop;
            if (margin && !inRun) {
                runs++;
            }
            inRun = margin;
        }
        return runs;
    }

    /**
     * Judges each reversed element pair by contract rules R3 and R5.
     * @param elements page-one elements (indices as in the residual report)
     * @param profile residual profile entry
     * @param mainLeft main column left edge
     * @param bandTop band start
     * @return reversed line pairs per verdict
     */
    private static Map<String, Integer> adjudicate(final List<Element> elements, final JsonNode profile,
            final double mainLeft, final double bandTop) {
        double sideTop = Double.MAX_VALUE;
        for (final Element element : elements) {
            final double[] box = box(element);
            if (box != null && !element.text().isBlank() && box[2] < mainLeft && box[1] >= bandTop) {
                sideTop = Math.min(sideTop, box[1]);
            }
        }
        final var verdicts = new HashMap<String, Integer>();
        for (final var pair : profile.get("reversed_element_pairs")) {
            final int first = pair.get("elements").get(0).get("index").asInt();
            final int second = pair.get("elements").get(1).get("index").asInt();
            final double[] a = box(elements.get(first));
            final double[] b = box(elements.get(second));
            final boolean sideA = a[2] < mainLeft;
            final boolean sideB = b[2] < mainLeft;
            final int contractFirst;
            if (sideA == sideB) {
                contractFirst = a[1] <= b[1] ? first : second;
            } else {
                final double[] main = sideB ? a : b;
                contractFirst = main[3] < sideTop ? (sideB ? first : second) : (sideB ? second : first);
            }
            final int upstreamFirst = pair.get("expect_second_before_first").asInt() > 0 ? second : first;
            final String verdict = contractFirst == upstreamFirst ? "OUTPUT_VIOLATES_CONTRACT" : "REFERENCE_DIFFERS";
            verdicts.merge(verdict, pair.get("reversed").asInt(), Integer::sum);
        }
        return verdicts;
    }
}
