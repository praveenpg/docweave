package io.docweave.validation;

import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.jsoup.Jsoup;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Replays unchanged whole-source upstream tables through the frozen NVIDIA multi-page row scorer. */
public final class ScoreNvidiaUpstreamRows {
    /** Prevents utility construction. */
    private ScoreNvidiaUpstreamRows() {
    }

    /**
     * Reads an exact existing evidence file without changing its contents.
     * @param path original evidence
     * @return lowercase SHA256
     * @throws Exception if evidence or the digest is unavailable
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects evidence drift rather than silently changing the comparison.
     * @param valid required invariant
     * @param description bounded diagnostic
     */
    private static void require(final boolean valid, final String description) {
        if (!valid) {
            throw new IllegalArgumentException(description);
        }
    }

    /**
     * Resolves a private historical evaluation helper without copying or editing its scoring rules.
     * @param name frozen method name
     * @param parameters exact historical parameter types
     * @return accessible evaluation method
     * @throws Exception if the frozen evaluator contract differs
     */
    private static Method method(final String name, final Class<?>... parameters) throws Exception {
        final var method = PdfCurrentBusinessRowsTest.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    /**
     * Scores every fixed statement and fragment, preserving full failed-document denominators.
     * Arguments are capture manifest, original source directory, raw capture directory and fresh report.
     * Run from the isolated workspace with the pinned scorer classes first on the classpath.
     * @param args four explicit local paths
     * @throws Exception if any identity, source frame, outcome or immutable scoring contract differs
     */
    @SuppressWarnings("unchecked")
    public static void main(final String[] args) throws Exception {
        require(args.length == 4, "Expected manifest, sources, capture and fresh report");
        final var mapper = JsonMapper.builder().build();
        final var manifestPath = Path.of(args[0]);
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        final String mode;
        if (manifest.has("model_profile")) {
            require(manifest.get("model_profile").asString().equals("EXPLICIT_V1_1_ALL_SOURCE_WEIGHTS"),
                    "Unsupported optional model profile");
            mode = "UNSTRUCTURED_HI_RES_EXPLICIT_V1_1_ALL";
        } else {
            mode = "UNSTRUCTURED_HI_RES_DEFAULT_TABLE";
        }
        final var sourceRoot = Path.of(args[1]);
        final var captureRoot = Path.of(args[2]);
        final var selectedSource = manifest.get("cases").get(0);
        require(manifest.get("cases").size() == 1 && selectedSource.get("source_pages").asInt() == 11
                        && selectedSource.get("reference_rows").asInt() == 110,
                "Whole NVIDIA source protocol differs");
        require(hash(Path.of("docweave-validation/src/test/java/io/docweave/validation/PdfCurrentBusinessRowsTest.java"))
                        .equals(manifest.get("scorer_sha256").asString()),
                "Historical scorer source differs");
        final var runtimePath = captureRoot.resolve("runtime.json");
        final var runtime = mapper.readTree(Files.readAllBytes(runtimePath));
        require(hash(manifestPath).equals(runtime.get("manifest_sha256").asString()), "Executed manifest differs");
        require(hash(Path.of("benchmarks/tools/capture_unstructured_tables.py"))
                        .equals(runtime.get("runner_sha256").asString()),
                "Executed capture runner differs");
        require(runtime.get("packages")
                        .get("unstructured")
                        .asString()
                        .equals(manifest.get("unstructured_version").asString()),
                "Reference runtime differs");
        final var referencePath = Path.of(selectedSource.get("reference_manifest").asString());
        require(hash(referencePath).equals(selectedSource.get("reference_manifest_sha256").asString()),
                "Independent reference differs");
        final var reference = mapper.readTree(Files.readAllBytes(referencePath));
        for (final var input : reference.get("inputs")) {
            final var path = sourceRoot.resolve(input.get("filename").asString());
            require(Files.size(path) == input.get("bytes").asLong()
                            && hash(path).equals(input.get("sha256").asString()),
                    "Original source differs");
        }
        final var rawPath = captureRoot.resolve("nvidia.json");
        final var raw = mapper.readTree(Files.readAllBytes(rawPath));
        require(raw.get("source_sha256").asString().equals(selectedSource.get("sha256").asString()),
                "Capture source differs");
        final var elements = new ArrayList<Element>();
        for (int page = 1; page <= 11; page++) {
            final var adapted = UpstreamTableCapture.page(raw, page, 595, 842, mapper);
            for (final var table : adapted.get("tables")) {
                final Map<String, Object> metadata = mapper.convertValue(table, Map.class);
                elements.add(new Element("upstream-table-" + elements.size(), ElementType.Table, "", metadata));
            }
        }
        final var html = Jsoup.parse(Files.readString(sourceRoot.resolve(reference.get("source_html").asString())));
        final var captureClass = Class.forName("io.docweave.validation.PdfCurrentBusinessRowsTest$Capture");
        final var constructor = captureClass.getDeclaredConstructor(String.class, List.class, List.class);
        constructor.setAccessible(true);
        final var capture = constructor.newInstance(raw.get("outcome").asString(), List.copyOf(elements), List.of());
        final var score = method("multiplePages", tools.jackson.databind.JsonNode.class, List.class, captureClass,
                String.class, int.class);
        final var regionClass = Class.forName("io.docweave.validation.PdfCurrentBusinessRowsTest$Region");
        final var regionConstructor = regionClass.getDeclaredConstructor(int.class, List.class);
        regionConstructor.setAccessible(true);
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : reference.get("cases")) {
            final var rows = (List<String>) method("rows", org.jsoup.nodes.Element.class)
                                     .invoke(null, html.select("table").get(selected.get("html_table_index").asInt()));
            require(rows.size() == selected.get("reference_rows").asInt(), "Complete row count differs");
            final var rowHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    String.join("\n", rows).getBytes(StandardCharsets.UTF_8)));
            require(rowHash.equals(selected.get("reference_rows_sha256").asString()), "Original row content differs");
            final var result =
                    new LinkedHashMap<>((Map<String, Object>) score.invoke(null, selected, rows, capture, mode, 11));
            final var regions = new ArrayList<Object>();
            for (final var region : selected.get("regions")) {
                final var bounds = new ArrayList<Double>();
                region.get("roi_left_top_right_bottom").forEach(edge -> bounds.add(edge.asDouble()));
                regions.add(regionConstructor.newInstance(region.get("page").asInt(), bounds));
            }
            final var fragments = method("fragments", List.class, List.class).invoke(null, elements, regions);
            final var observedMethod = fragments.getClass().getDeclaredMethod("observed");
            observedMethod.setAccessible(true);
            final var observed = (List<String>) observedMethod.invoke(fragments);
            final var remaining = new ArrayList<>(observed);
            final var presence = new ArrayList<Boolean>();
            rows.forEach(row -> presence.add(remaining.remove(row)));
            result.put("exact_reference_rows_present", presence.stream().filter(Boolean::booleanValue).count());
            result.put("reference_row_presence", presence);
            result.put("adapted_table_elements_only", true);
            result.put("raw_document_elements", raw.get("elements").size());
            results.add(result);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("java_version", System.getProperty("java.version"));
        report.put("manifest_sha256", hash(manifestPath));
        report.put("raw_capture_sha256", hash(rawPath));
        report.put("runtime_sha256", hash(runtimePath));
        report.put("results", results);
        Files.writeString(Path.of(args[3]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }
}
