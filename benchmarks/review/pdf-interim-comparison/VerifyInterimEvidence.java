import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Binds the development inventory and fresh captures to unchanged historical numeric evidence. */
public final class VerifyInterimEvidence {
    /** Prevents utility construction. */
    private VerifyInterimEvidence() {
    }

    /**
     * Checks both runtime reports, every old row, referenced source manifests and installed upstream source.
     * The separate scientific verifier checks complete profile coverage and denominators. This receipt does
     * not convert pending table/OCR/image/performance tracks into measurements.
     * @param args repository root, raw capture directory, installed upstream pdf.py and NEW receipt
     * @throws Exception if identities, prior rows, runtime pins or fresh output checks fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected repository, raw captures, installed pdf.py and NEW receipt");
        }
        final var root = Path.of(args[0]);
        final var directory = root.resolve("benchmarks/review/pdf-interim-comparison");
        final var mapper = JsonMapper.builder().build();
        final var plan = mapper.readTree(Files.readAllBytes(directory.resolve("plan.json")));
        int sources = 0;
        int inputs = 0;
        for (final var source : plan.get("cases")) {
            require(hash(root.resolve(source.get("reference_manifest").asString()))
                            .equals(source.get("reference_manifest_sha256").asString()));
            final var reference =
                    mapper.readTree(Files.readAllBytes(root.resolve(source.get("reference_manifest").asString())));
            JsonNode expectedInputs = null;
            if (source.get("domain").asString().equals("modern-business")) {
                expectedInputs = reference.get("inputs");
            } else {
                for (final var selected : reference.get("cases")) {
                    if (selected.get("id").equals(source.get("id"))) {
                        require(expectedInputs == null);
                        expectedInputs = selected.get("inputs");
                    }
                }
            }
            require(source.get("inputs").equals(expectedInputs));
            sources++;
            inputs += source.get("inputs").size();
        }
        final var current = rows(mapper.readTree(Files.readAllBytes(directory.resolve("scientific-java21.json"))));
        require(current.equals(rows(mapper.readTree(Files.readAllBytes(directory.resolve("scientific-java25.json"))))));
        final var priorAssembly = rows(mapper.readTree(
                Files.readAllBytes(root.resolve("benchmarks/review/pdf-scientific-paragraph-assembly/java21.json"))));
        final var priorPaired = rows(mapper.readTree(
                Files.readAllBytes(root.resolve("benchmarks/review/pdf-scientific-native-comparison/java21.json"))));
        for (final var previous : java.util.List.of(priorAssembly, priorPaired)) {
            for (final var row : previous.entrySet()) {
                require(row.getValue().equals(current.get(row.getKey())));
            }
        }
        final var captures = new LinkedHashMap<String, Object>();
        final var manifest = mapper.readTree(Files.readAllBytes(directory.resolve("scientific-manifest.json")));
        for (final var source : manifest.get("cases")) {
            final String id = source.get("id").asString();
            require(id.matches("[a-z]+"));
            final var path = Path.of(args[1]).resolve(id + ".json");
            final var capture = mapper.readTree(Files.readAllBytes(path));
            final var identity = new LinkedHashMap<String, Object>();
            identity.put("sha256", hash(path));
            identity.put("outcome", capture.get("outcome").asString());
            identity.put("elements", capture.get("elements").size());
            captures.put(id, identity);
        }
        final var runtimePath = Path.of(args[1]).resolve("runtime.json");
        final var runtime = mapper.readTree(Files.readAllBytes(runtimePath));
        require(runtime.get("packages")
                        .get("unstructured")
                        .asString()
                        .equals(plan.get("unstructured_version").asString()));
        require(hash(directory.resolve("upstream-runtime.json")).equals(hash(runtimePath)));
        final String sourceHash = hash(Path.of(args[2]));
        require(sourceHash.equals("0dec47c9dac28769a58f76576ccdc4795c81b4554e3d74a7329ed7e27238287e"));
        final var receipt = new LinkedHashMap<String, Object>();
        receipt.put("docweave_commit", plan.get("docweave_commit").asString());
        receipt.put("plan_sha256", hash(directory.resolve("plan.json")));
        receipt.put("scientific_manifest_sha256", hash(directory.resolve("scientific-manifest.json")));
        receipt.put("planned_source_cases", sources);
        receipt.put("pinned_original_input_files", inputs);
        receipt.put("paired_scientific_rows", current.size());
        receipt.put("prior_docweave_rows_unchanged", priorAssembly.size());
        receipt.put("prior_paired_rows_unchanged", priorPaired.size());
        receipt.put("upstream_runtime_sha256", hash(runtimePath));
        receipt.put("installed_upstream_pdf_source_sha256", sourceHash);
        receipt.put("upstream_runner_sha256", hash(root.resolve("benchmarks/tools/capture_unstructured_reading.py")));
        receipt.put("fresh_upstream_captures", captures);
        receipt.put("unexecuted_tracks_remain_pending", true);
        Files.writeString(Path.of(args[3]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(receipt) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Indexes all numeric report rows without tolerating duplicate observations.
     * @param report complete recorded scientific results
     * @return original unchanged rows by source/profile identity
     */
    private static Map<String, JsonNode> rows(final JsonNode report) {
        final var rows = new LinkedHashMap<String, JsonNode>();
        for (final var row : report.get("results")) {
            require(rows.put(row.get("id").asString() + "/" + row.get("profile").asString(), row) == null);
        }
        return rows;
    }

    /**
     * Hashes one local evidence input without changing its bytes.
     * @param path reviewed local input
     * @return lowercase SHA256
     * @throws Exception if bytes or mandatory JDK hash support cannot be read
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects changed or incomplete evidence before a usable receipt is written.
     * @param condition required identity invariant
     */
    private static void require(final boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Interim comparison identity or prior-row validation failed");
        }
    }
}
