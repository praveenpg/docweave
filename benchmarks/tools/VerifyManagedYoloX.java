package io.docweave.layout;

import io.docweave.partition.PartitionException;
import io.docweave.partition.PdfTableLayoutProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Exact numeric regression of managed detectors and complete public pipelines against all frozen original pages. */
public final class VerifyManagedYoloX {
    /** Prevents utility construction. */
    private VerifyManagedYoloX() {
    }

    /**
     * Validates identities before running every raw YOLOX page and both public detector/structure profiles.
     * Legacy captures omit header evidence and provenance; only that declared geometry projection is compared.
     * No references, source text, selected ROIs, threshold tuning or per-page configuration enter inference.
     * @param args frozen replay manifest, original page captures, frozen proposal root, frozen structure root,
     *             TATR detector, YOLOX detector, TATR structure and NEW numeric report
     * @throws Exception if pins, complete populations or numeric regression requirements fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 8 && !Files.exists(Path.of(args[7])), "Expected seven explicit inputs and NEW report");
        final var mapper = JsonMapper.builder().build();
        final var manifestPath = Path.of(args[0]);
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        final var proposalManifestPath = manifestPath.getParent().resolve("manifest.json");
        require(hash(proposalManifestPath).equals(manifest.get("proposal_manifest_sha256").asString()),
                "Proposal protocol changed");
        require(hash(Path.of(manifest.get("reference_manifest").asString()))
                        .equals(manifest.get("reference_manifest_sha256").asString()),
                "Source protocol changed");
        final var proposalManifest = mapper.readTree(Files.readAllBytes(proposalManifestPath));
        require(manifest.get("pages").size() == proposalManifest.get("pages").size(), "Page population changed");
        final var captures = Path.of(args[1]);
        final var proposals = Path.of(args[2]);
        final var structures = Path.of(args[3]);
        for (int index = 0; index < manifest.get("pages").size(); index++) {
            final var page = manifest.get("pages").get(index);
            final String id = page.get("capture_id").asString();
            require(id.matches("original-page-[0-9]{4}"), "Unexpected capture identifier");
            require(page.get("source_page").asInt() == index + 1, "Page sequence changed");
            require(hash(captures.resolve(id).resolve("render.png")).equals(page.get("render_png_sha256").asString()),
                    "Original raster changed");
            final var proposalPin = proposalManifest.get("pages").get(index);
            require(proposalPin.get("source_page").asInt() == index + 1, "Proposal page sequence changed");
            for (final String profile : List.of("tatr", "yolox")) {
                require(hash(proposals.resolve(id).resolve(profile + ".tsv"))
                                .equals(proposalPin.get(profile + "_proposals_sha256").asString()),
                        "Frozen proposals changed");
                for (final String file : List.of("tables.tsv", "anchors.tsv", "outcome.txt")) {
                    require(hash(structures.resolve(id).resolve(profile).resolve(file))
                                    .equals(page.get(profile).get(file).asString()),
                            "Frozen structure changed");
                }
            }
        }
        final var tatr = options(Path.of(args[4]), manifest.get("detector_sha256").get("tatr").asString(), false);
        final var yoloX = options(Path.of(args[5]), manifest.get("detector_sha256").get("yolox").asString(), true);
        final var child = options(Path.of(args[6]), manifest.get("structure_sha256").asString(), false)
                                  .withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
        final var results = new ArrayList<Map<String, Object>>();
        try (final var detector = new ManagedTableSession<>(yoloX, new YoloXTableModel())) {
            for (final var page : manifest.get("pages")) {
                final String id = page.get("capture_id").asString();
                final var image = ImageIO.read(captures.resolve(id).resolve("render.png").toFile());
                require(image != null, "Unreadable original raster");
                final var report = entry(page, "managed_yolox_proposals");
                try {
                    final var actual = detector.infer(image, new LayoutDeadline(yoloX.timeout()), objects -> objects);
                    final var expected = proposals(proposals.resolve(id).resolve("yolox.tsv"));
                    report.put("outcome", "SUCCESS");
                    report.put("expected_proposals", expected.size());
                    report.put("actual_proposals", actual.size());
                    report.put("exact_numeric_match", expected.equals(actual));
                } catch (final PartitionException failure) {
                    report.put("outcome", failure.code().name());
                    report.put("exact_numeric_match", false);
                } finally {
                    image.flush();
                }
                results.add(report);
            }
        }
        for (final String profile : List.of("tatr", "yolox")) {
            try (final var provider = profile.equals("tatr") ? OnnxTableLayout.create(tatr, child)
                                                             : OnnxTableLayout.createWithYoloX(yoloX, child)) {
                for (final var page : manifest.get("pages")) {
                    final String id = page.get("capture_id").asString();
                    final var image = ImageIO.read(captures.resolve(id).resolve("render.png").toFile());
                    require(image != null, "Unreadable original raster");
                    final var report = entry(page, "public_" + profile + "_structures");
                    try {
                        final var actual = provider.locate(image);
                        final var expected = tables(structures.resolve(id).resolve(profile));
                        report.put("outcome", "SUCCESS");
                        report.put("expected_tables", expected.size());
                        report.put("actual_tables", actual.size());
                        report.put("actual_anchors", actual.stream().mapToInt(table -> table.anchors().size()).sum());
                        report.put("declared_header_tables",
                                actual.stream().filter(table -> table.headerRows() > 0).count());
                        report.put("exact_numeric_match",
                                expected.equals(actual.stream().map(VerifyManagedYoloX::geometry).toList()));
                    } catch (final PartitionException failure) {
                        report.put("outcome", failure.code().name());
                        report.put("exact_numeric_match", false);
                    } finally {
                        image.flush();
                    }
                    results.add(report);
                }
            }
        }
        Files.writeString(Path.of(args[7]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime",
                        Runtime.version().toString(), "manifest_sha256", hash(manifestPath), "scope",
                        "All original-page rasters; exact raw YOLOX proposals and declared legacy geometry projection; no row-text or header accuracy claim",
                        "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
        require(results.stream().allMatch(result -> Boolean.TRUE.equals(result.get("exact_numeric_match"))),
                "Managed numeric regression; inspect the complete report");
    }

    /**
     * Creates fixed four-thread policies matching the earlier captures.
     * @param model trusted pinned local graph
     * @param hash expected graph identity
     * @param yoloX whether to select the fixed YOLOX query/score profile
     * @return immutable bounded graph policy
     */
    private static OnnxTableOptions options(final Path model, final String hash, final boolean yoloX) {
        final var base = yoloX ? OnnxTableOptions.yoloXDefaults(model, hash) : OnnxTableOptions.defaults(model, hash);
        return new OnnxTableOptions(model, hash, base.timeout(), base.maxConcurrentCalls(), base.maxModelBytes(),
                base.maxInputPixels(), base.maxQueries(), base.tableLimits(), base.minimumScore(), 4,
                base.mergeConflicts());
    }

    /**
     * Initializes one content-free page/profile result before execution.
     * @param page frozen source page identity
     * @param profile fixed model stage
     * @return mutable numeric report row
     */
    private static Map<String, Object> entry(final JsonNode page, final String profile) {
        final var result = new LinkedHashMap<String, Object>();
        result.put("source_page", page.get("source_page").asInt());
        result.put("render_png_sha256", page.get("render_png_sha256").asString());
        result.put("profile", profile);
        return result;
    }

    /**
     * Reads exactly the frozen ordinary-table proposals in preserved encounter order.
     * @param file pinned numeric TSV
     * @return immutable source-frame objects
     * @throws Exception if the pinned schema is invalid
     */
    private static List<LayoutObject> proposals(final Path file) throws Exception {
        final var lines = Files.readAllLines(file);
        require(lines.getFirst().equals("score\tleft\ttop\tright\tbottom"), "Invalid proposal schema");
        final var output = new ArrayList<LayoutObject>();
        for (final String line : lines.subList(1, lines.size())) {
            final var fields = line.split("\t", -1);
            require(fields.length == 5, "Invalid proposal row");
            output.add(new LayoutObject(0, Double.parseDouble(fields[0]), box(fields, 1)));
        }
        return List.copyOf(output);
    }

    /**
     * Reads complete frozen geometry without guessing omitted headers or provenance.
     * @param directory pinned legacy page/profile capture
     * @return immutable geometry projection in original capture order
     * @throws Exception if legacy schema/outcome is unsupported
     */
    private static List<PdfTableLayoutProvider.Table> tables(final Path directory) throws Exception {
        require(Files.readString(directory.resolve("outcome.txt")).strip().equals("OK"), "Non-success baseline");
        final var lines = Files.readAllLines(directory.resolve("anchors.tsv"));
        final var anchors = new LinkedHashMap<String, List<PdfTableLayoutProvider.Anchor>>();
        for (final String line : lines.subList(1, lines.size())) {
            final var fields = line.split("\t", -1);
            require(fields.length == 10, "Invalid legacy anchor row");
            anchors.computeIfAbsent(fields[0] + "/" + fields[1], unused -> new ArrayList<>())
                    .add(new PdfTableLayoutProvider.Anchor(Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                            Integer.parseInt(fields[4]), Integer.parseInt(fields[5]), box(fields, 6)));
        }
        final var tables = Files.readAllLines(directory.resolve("tables.tsv"));
        final var result = new ArrayList<PdfTableLayoutProvider.Table>();
        for (final String line : tables.subList(1, tables.size())) {
            final var fields = line.split("\t", -1);
            require(fields.length == 8, "Invalid legacy table row");
            result.add(new PdfTableLayoutProvider.Table(Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                    box(fields, 4), anchors.get(fields[0] + "/" + fields[1]), "legacy_geometry", 0));
        }
        return List.copyOf(result);
    }

    /**
     * Compares all numeric topology fields while declaring the two unavailable legacy fields explicitly.
     * @param table complete current managed layout
     * @return exact geometry without new header/provenance evidence
     */
    private static PdfTableLayoutProvider.Table geometry(final PdfTableLayoutProvider.Table table) {
        return new PdfTableLayoutProvider.Table(
                table.rows(), table.columns(), table.bounds(), table.anchors(), "legacy_geometry", 0);
    }

    /**
     * Parses one original-frame rectangle from fixed numeric columns.
     * @param fields validated numeric row
     * @param offset first coordinate index
     * @return original finite rectangle
     */
    private static PdfTableLayoutProvider.Box box(final String[] fields, final int offset) {
        return new PdfTableLayoutProvider.Box(Double.parseDouble(fields[offset]),
                Double.parseDouble(fields[offset + 1]), Double.parseDouble(fields[offset + 2]),
                Double.parseDouble(fields[offset + 3]));
    }

    /**
     * Hashes complete protocol/input bytes before model execution.
     * @param path pinned local input
     * @return lowercase SHA256
     * @throws Exception if reading or digest creation fails
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects malformed protocols without source text in diagnostics.
     * @param condition required invariant
     * @param message bounded content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
