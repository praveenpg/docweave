package io.docweave.layout;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One-to-one geometry-only proposal comparison; never supplies annotations to inference. */
public final class CompareDetectorProposals {
    /**
     * Finite positive-area displayed/source rectangle.
     * @param left left extent
     * @param top top extent
     * @param right right extent
     * @param bottom bottom extent
     */
    private record Box(double left, double top, double right, double bottom) {
        /** Rejects nonfinite or inverted geometry instead of repairing predictions. */
        Box {
            require(Double.isFinite(left) && Double.isFinite(top) && Double.isFinite(right) && Double.isFinite(bottom)
                    && right > left && bottom > top);
        }
    }

    /**
     * One independently frozen original-page region.
     * @param id stable case/page key, without publisher text
     * @param box fixed source-frame region
     */
    private record Reference(String id, Box box) {}

    /** Prevents utility construction. */
    private CompareDetectorProposals() {
    }

    /**
     * Compares every fixed source region with one-to-one maximum-cardinality IoU>=.5 matching.
     * Unmatched predictions are not labelled false positives because the selected references are incomplete.
     * @param args frozen alternative manifest, TATR stage report, JVM YOLOX report, fresh output; or --self-test
     * @throws Exception if identities, frames, coverage or fresh output validation fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            selfTest();
            System.out.println("Detector comparison matching controls passed");
            return;
        }
        require(args.length == 4);
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var referencePath = Path.of(manifest.get("reference_manifest").asString());
        require(hash(referencePath).equals(manifest.get("reference_manifest_sha256").asString()));
        require(hash(Path.of(manifest.get("tool").asString())).equals(manifest.get("tool_sha256").asString()));
        final var reference = mapper.readTree(Files.readAllBytes(referencePath));
        final var tatr = mapper.readTree(Files.readAllBytes(Path.of(args[1])));
        final var yolox = mapper.readTree(Files.readAllBytes(Path.of(args[2])));
        require(yolox.get("runtime").asString().startsWith("21.")
                && yolox.get("model_sha256").equals(manifest.get("model_sha256")));
        require(tatr.get("pdf_sha256").equals(yolox.get("pdf_sha256"))
                && yolox.get("pdf_sha256").equals(reference.get("inputs").get(0).get("sha256")));
        final int count = manifest.get("original_pages").asInt();
        require(tatr.get("pages").size() == count && yolox.get("pages").size() == count);
        for (int index = 0; index < count; index++) {
            final var a = tatr.get("pages").get(index);
            final var b = yolox.get("pages").get(index);
            require(a.get("source_page").asInt() == index + 1 && b.get("source_page").asInt() == index + 1);
            for (final String key : List.of("raster_width", "raster_height", "source_crop_width", "source_crop_height",
                         "source_rotation")) {
                require(a.get(key).equals(b.get(key)));
            }
        }
        final var regions = new TreeMap<Integer, List<Reference>>();
        int population = 0;
        for (final var selected : reference.get("cases")) {
            for (final var region : selected.get("regions")) {
                final int page = region.get("page").asInt();
                require(page > 0 && page <= count);
                regions.computeIfAbsent(page, unused -> new ArrayList<>())
                        .add(new Reference(selected.get("id").asString() + "/" + page,
                                box(region.get("roi_left_top_right_bottom"), 1, 1)));
                population++;
            }
        }
        require(population == manifest.get("reference_regions").asInt());
        final var results = new LinkedHashMap<String, Object>();
        results.put("tatr_thresholded_proposals", evaluate(regions, tatr, false));
        results.put("yolox_jvm_post_nms_proposals", evaluate(regions, yolox, true));
        Files.writeString(Path.of(args[3]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "One-to-one IoU>=.5 proposal associations on all frozen source regions. Native table/text scores remain unchanged; different fixed detector preprocessing/threshold configurations are explicit. Not an upstream library ranking or text-accuracy claim.",
                        "manifest_sha256", hash(Path.of(args[0])), "tatr_report_sha256", hash(Path.of(args[1])),
                        "yolox_report_sha256", hash(Path.of(args[2])), "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Scores complete per-page proposal pools without allowing one candidate to receive duplicate source credit.
     * @param regions all fixed references grouped by original page
     * @param report complete original-page detector report
     * @param yolox whether the report contains post-NMS YOLOX proposals rather than thresholded TATR objects
     * @return complete metric population and matched reference identities
     */
    private static Map<String, Object> evaluate(
            final Map<Integer, List<Reference>> regions, final JsonNode report, final boolean yolox) {
        final var matches = new ArrayList<Map<String, Object>>();
        int total = 0;
        int proposals = 0;
        for (final var entry : regions.entrySet()) {
            final var page = report.get("pages").get(entry.getKey() - 1);
            final double sx = page.get("source_crop_width").asDouble() / page.get("raster_width").asDouble();
            final double sy = page.get("source_crop_height").asDouble() / page.get("raster_height").asDouble();
            final var boxes = new ArrayList<Box>();
            for (final var candidate : page.get(yolox ? "table_proposals" : "detector_objects")) {
                if (yolox || candidate.get("label").asInt() == 0) {
                    boxes.add(box(candidate.get("bounds"), sx, sy));
                }
            }
            final var edges = new ArrayList<List<Integer>>();
            for (final var selected : entry.getValue()) {
                final var eligible = new ArrayList<Integer>();
                for (int index = 0; index < boxes.size(); index++) {
                    if (iou(selected.box(), boxes.get(index)) >= .5) {
                        eligible.add(index);
                    }
                }
                eligible.sort(Comparator.<Integer>comparingDouble(index -> iou(selected.box(), boxes.get(index)))
                                .reversed()
                                .thenComparingInt(Integer::intValue));
                edges.add(eligible);
            }
            final var owners = maximum(edges, boxes.size());
            for (int index = 0; index < owners.length; index++) {
                if (owners[index] >= 0) {
                    final var selected = entry.getValue().get(owners[index]);
                    matches.add(Map.of("reference", selected.id(), "proposal_index", index, "iou",
                            iou(selected.box(), boxes.get(index))));
                }
            }
            total += entry.getValue().size();
            proposals += boxes.size();
        }
        return Map.of("reference_regions", total, "matched_reference_regions", matches.size(),
                "selected_page_proposals", proposals, "unmatched_proposals", proposals - matches.size(), "matches",
                matches);
    }

    /**
     * Finds maximum-cardinality ownership using augmenting paths in fixed reference order.
     * @param edges candidate indices eligible for each reference
     * @param candidates original candidate population
     * @return candidate owners, with -1 for unmatched proposals
     */
    private static int[] maximum(final List<List<Integer>> edges, final int candidates) {
        final var owners = new int[candidates];
        Arrays.fill(owners, -1);
        for (int reference = 0; reference < edges.size(); reference++) {
            assign(reference, edges, owners, new boolean[candidates]);
        }
        return owners;
    }

    /**
     * Reassigns earlier ownership only when an augmenting path increases complete matching cardinality.
     * @param reference current reference index
     * @param edges fixed eligibility graph
     * @param owners mutable candidate ownership
     * @param seen candidates visited during this augmentation
     * @return whether one reference obtains ownership
     */
    private static boolean assign(
            final int reference, final List<List<Integer>> edges, final int[] owners, final boolean[] seen) {
        for (final int candidate : edges.get(reference)) {
            if (!seen[candidate]) {
                seen[candidate] = true;
                if (owners[candidate] < 0 || assign(owners[candidate], edges, owners, seen)) {
                    owners[candidate] = reference;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reads a numeric rectangle with only the declared frame scaling.
     * @param values left/top/right/bottom
     * @param sx horizontal scale
     * @param sy vertical scale
     * @return immutable source-frame box
     */
    private static Box box(final JsonNode values, final double sx, final double sy) {
        require(values.isArray() && values.size() == 4);
        return new Box(values.get(0).asDouble() * sx, values.get(1).asDouble() * sy, values.get(2).asDouble() * sx,
                values.get(3).asDouble() * sy);
    }

    /**
     * Computes continuous source-envelope IoU, separate from detector-specific inclusive NMS.
     * @param first fixed source region
     * @param second emitted proposal
     * @return zero through one
     */
    private static double iou(final Box first, final Box second) {
        final double intersection =
                Math.max(0, Math.min(first.right(), second.right()) - Math.max(first.left(), second.left()))
                * Math.max(0, Math.min(first.bottom(), second.bottom()) - Math.max(first.top(), second.top()));
        final double total = (first.right() - first.left()) * (first.bottom() - first.top())
                             + (second.right() - second.left()) * (second.bottom() - second.top()) - intersection;
        return intersection / total;
    }

    /** Checks duplicate-credit prevention, augmenting reassignment and fixed IoU arithmetic. */
    private static void selfTest() {
        require(Arrays.stream(maximum(List.of(List.of(0), List.of(0)), 1)).filter(owner -> owner >= 0).count() == 1);
        require(Arrays.stream(maximum(List.of(List.of(0, 1), List.of(0)), 2)).filter(owner -> owner >= 0).count() == 2);
        require(maximum(List.of(List.of(), List.of()), 0).length == 0);
        require(iou(new Box(0, 0, 50, 100), new Box(0, 0, 100, 100)) == .5);
    }

    /**
     * Binds complete local evidence bytes.
     * @param path fixed evidence file
     * @return SHA256
     * @throws Exception if reading or hashing fails
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects inconsistent evidence without changing inputs or printing source text.
     * @param valid required invariant
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Detector proposal comparison failed");
        }
    }
}
