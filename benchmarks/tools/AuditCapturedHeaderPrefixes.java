package dev.docweave.partition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline numeric header-prefix replay against pinned source geometry, without scoring header semantics or repairing
 * rows.
 */
public final class AuditCapturedHeaderPrefixes {
    /** Prevents utility construction. */
    private AuditCapturedHeaderPrefixes() {
    }

    /**
     * Reads every fixed source region, including failures and missing declarations.
     * @param args capture root, frozen replay manifest and NEW report; or --self-test
     * @throws Exception if identities, geometry, declaration integrity, work or fresh-output checks fail
     */
    public static void main(final String[] args) throws Exception {
        final var mapper = JsonMapper.builder().build();
        if (args.length == 1 && args[0].equals("--self-test")) {
            controls(mapper);
            return;
        }
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected capture root, replay manifest, NEW numeric report");
        }
        final var manifest = mapper.readTree(read(Path.of(args[1])));
        final var runtime = mapper.readTree(read(Path.of(args[0], "runtime.json")));
        for (final String field : List.of("detector_sha256", "structure_sha256")) {
            require(runtime.get(field).asString().equals(manifest.get(field).asString()), "Model identity mismatch");
        }
        require(runtime.get("native_first").asBoolean(), "Expected fixed native-first strategy");
        final var results = new ArrayList<Map<String, Object>>();
        final var seen = new HashSet<String>();
        final var work = new PdfTableWork();
        for (final var reference : manifest.get("references").properties()) {
            require(reference.getKey().matches("pdf-[a-z-]+"), "Invalid reference identifier");
            final byte[] bytes = read(Path.of("benchmarks/review", reference.getKey(), "manifest.json"));
            require(hash(bytes).equals(reference.getValue().asString()), "Reference manifest changed");
            final var sourceManifest = mapper.readTree(bytes);
            if (!sourceManifest.has("inputs")) {
                continue;
            }
            final var cases = new ArrayList<JsonNode>();
            if (sourceManifest.has("cases")) {
                sourceManifest.get("cases").forEach(cases::add);
            } else if (sourceManifest.has("case")) {
                cases.add(sourceManifest.get("case"));
            }
            for (final var selected : cases) {
                work.step();
                final String id = selected.get("id").asString();
                require(id.matches("[a-z-]+") && seen.add(id), "Invalid or duplicate case identifier");
                final String filename = selected.has("pdf") ? selected.get("pdf").asString()
                                                            : sourceManifest.get("source_pdf").asString();
                final int page = selected.has("source_page") ? selected.get("source_page").asInt()
                                                             : selected.get("page").asInt();
                String sourceHash = null;
                for (final var input : sourceManifest.get("inputs")) {
                    if (input.get("filename").asString().equals(filename)) {
                        sourceHash = input.get("sha256").asString();
                    }
                }
                require(sourceHash != null, "Missing frozen source identity");
                final var directory = Path.of(args[0], id);
                final var frame = mapper.readTree(read(directory.resolve("source.json")));
                require(sourceHash.equals(frame.get("pdf_sha256").asString())
                                && page == frame.get("source_page").asInt(),
                        "Captured source identity mismatch");
                final byte[] captureBytes = read(directory.resolve("native-first.json"));
                final var capture = mapper.readTree(captureBytes);
                require(capture.get("layout_strategy").asString().equals("native_first_disjoint_v1"),
                        "Unexpected extraction strategy");
                JsonNode associated = null;
                double best = 0;
                for (final var table : capture.get("tables")) {
                    work.step();
                    require(table.get("page_number").asInt() == page, "Captured page mismatch");
                    final var coordinates = table.get("coordinates");
                    require(coordinates.get("system").asString().equals("PDFUserSpace")
                                    && coordinates.get("layout_width").equals(frame.get("page_width"))
                                    && coordinates.get("layout_height").equals(frame.get("page_height")),
                            "Captured displayed frames differ");
                    final double overlap = overlap(selected.get("roi_left_top_right_bottom"), coordinates);
                    if (overlap >= 0.5 && overlap > best) {
                        associated = table;
                        best = overlap;
                    }
                }
                final var result = new LinkedHashMap<String, Object>();
                result.put("case", id);
                result.put("pdf_sha256", sourceHash);
                result.put("source_page", page);
                result.put("outcome", capture.get("outcome").asString());
                result.put("associated", associated != null);
                result.put("association_iou", best);
                result.put("tables_on_page", capture.get("tables").size());
                result.put("declared_header_rows", associated == null ? 0 : headers(associated));
                result.put("declaration_present", associated != null && associated.has("pdf_table_header_rows"));
                if (associated != null) {
                    final var cells = associated.get("pdf_table_cells");
                    result.put("rows", cells.get("row_count").asInt());
                    result.put("columns", cells.get("column_count").asInt());
                    result.put("anchors", cells.get("cells").size());
                    result.put("table_method", associated.get("pdf_table_method").asString());
                }
                result.put("capture_sha256", hash(captureBytes));
                results.add(result);
            }
        }
        Files.write(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("runtime", runtime, "cases", results)),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Validates a declaration without interpreting it as truth, merging cells or reading strings.
     * @param table complete captured table metadata
     * @return bounded positive declared prefix or zero for absent evidence
     */
    private static int headers(final JsonNode table) {
        if (!table.has("pdf_table_header_rows")) {
            require(!table.has("pdf_table_header_policy"), "Header policy without declaration");
            return 0;
        }
        final var count = table.get("pdf_table_header_rows");
        require(count.isIntegralNumber() && count.asLong() > 0
                        && count.asLong() <= table.get("pdf_table_cells").get("row_count").asInt(),
                "Invalid captured header prefix");
        require(table.has("pdf_table_header_policy")
                        && table.get("pdf_table_header_policy").asString().equals("provider_declared_prefix_v1"),
                "Unexpected captured header policy");
        return count.asInt();
    }

    /**
     * Selects by source-frame parent IoU only, with caller-managed stable tie order.
     * @param roi independently frozen source bounds; never supplied to extraction
     * @param coordinates displayed candidate rectangle
     * @return finite intersection over union
     */
    private static double overlap(final JsonNode roi, final JsonNode coordinates) {
        final var points = coordinates.get("points");
        final double l = points.get(0).get(0).asDouble();
        final double t = points.get(0).get(1).asDouble();
        final double r = points.get(2).get(0).asDouble();
        final double b = points.get(2).get(1).asDouble();
        require(Double.isFinite(l + t + r + b) && l < r && t < b, "Invalid captured parent rectangle");
        final double intersection =
                Math.max(0, Math.min(r, roi.get(2).asDouble()) - Math.max(l, roi.get(0).asDouble()))
                * Math.max(0, Math.min(b, roi.get(3).asDouble()) - Math.max(t, roi.get(1).asDouble()));
        return intersection
                / ((r - l) * (b - t)
                        + (roi.get(2).asDouble() - roi.get(0).asDouble())
                                  * (roi.get(3).asDouble() - roi.get(1).asDouble())
                        - intersection);
    }

    /**
     * Reads a bounded local JSON file without downloads or source mutation.
     * @param path explicit input
     * @return original bytes
     * @throws Exception if missing or larger than16MiB
     */
    private static byte[] read(final Path path) throws Exception {
        require(Files.size(path) <= 16 * 1024 * 1024, "Captured JSON exceeds16MiB");
        return Files.readAllBytes(path);
    }

    /**
     * Computes an original byte identity without retaining source text in output.
     * @param bytes bounded source
     * @return lowercase SHA256
     * @throws Exception if the digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Fails explicitly rather than repairing invalid replay inputs.
     * @param valid integrity condition
     * @param message content-free diagnostic
     */
    private static void require(final boolean valid, final String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Checks absent evidence, stacked declarations and invalid prefix/policy rejection offline.
     * @param mapper local diagnostic parser
     */
    private static void controls(final JsonMapper mapper) {
        require(headers(mapper.readTree("{}")) == 0, "Unknown declaration was fabricated");
        final String valid = "{\"pdf_table_header_rows\":2,\"pdf_table_header_policy\":\"provider_declared_prefix_v1\","
                             + "\"pdf_table_cells\":{\"row_count\":3}}";
        require(headers(mapper.readTree(valid)) == 2, "Stacked header count changed");
        for (final String invalid : List.of(valid.replace(":2,", ":4,"), valid.replace(":2,", ":0,"),
                     valid.replace("provider_declared_prefix_v1", "unknown"))) {
            boolean rejected = false;
            try {
                headers(mapper.readTree(invalid));
            } catch (final IllegalArgumentException expected) {
                rejected = true;
            }
            require(rejected, "Invalid header claim accepted");
        }
    }
}
