import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Exercises rejection of incomplete, failed and inconsistent numeric performance captures. */
public final class VerifyPerformanceControls {
    /** Prevents utility construction. */
    private VerifyPerformanceControls() {
    }

    /**
     * Mutates copies only; the recorded performance evidence and its denominators stay intact.
     * @param args repository root, complete results and NEW numeric control receipt
     * @throws Exception if any invalid capture produces an accepted summary
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected repository, results and NEW control receipt");
        }
        final var mapper = JsonMapper.builder().build();
        final var original = mapper.readTree(Files.readAllBytes(Path.of(args[1])));
        final Map<String, Consumer<JsonNode>> controls = new LinkedHashMap<>();
        controls.put("missing_fork", tree -> ((ArrayNode) tree.get("forks")).remove(0));
        controls.put("duplicate_sample", tree -> {
            final var samples = (ArrayNode) tree.get("forks").get(0).get("worker").get("samples");
            samples.set(1, samples.get(0).deepCopy());
        });
        controls.put("failed_fork_with_output", tree -> fork(tree).put("outcome", "TIMEOUT"));
        controls.put("wrong_output_hash",
                tree
                -> ((ObjectNode) fork(tree).get("worker").get("samples").get(0)).put("text_sha256", "0".repeat(64)));
        controls.put("wrong_manifest_hash", tree -> ((ObjectNode) tree).put("manifest_sha256", "0".repeat(64)));
        controls.put("wrong_heap", tree -> ((ObjectNode) fork(tree).get("worker")).put("maximum_heap_bytes", 1L));
        controls.put("wrong_affinity", tree -> ((ObjectNode) fork(tree).get("worker")).put("cpu_affinity", "0-7"));
        controls.put("impossible_process_wall", tree -> fork(tree).put("whole_process_wall_ns", 1L));
        final Path temporary = Files.createTempDirectory("docweave-performance-controls-");
        for (final var control : controls.entrySet()) {
            final var altered = original.deepCopy();
            control.getValue().accept(altered);
            final Path input = temporary.resolve(control.getKey() + ".json");
            final Path output = temporary.resolve(control.getKey() + "-summary.json");
            Files.writeString(input, mapper.writeValueAsString(altered), StandardOpenOption.CREATE_NEW);
            boolean rejected = false;
            try {
                VerifyPerformance.main(new String[] {args[0], input.toString(), output.toString()});
            } catch (final IllegalArgumentException expected) {
                rejected = true;
            }
            if (!rejected || Files.exists(output)) {
                throw new IllegalStateException("Invalid performance capture accepted: " + control.getKey());
            }
        }
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("invalid_capture_controls_rejected",
                        controls.keySet(), "controls", controls.size(), "original_evidence_unchanged", true))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Selects the known first fork in a private altered capture.
     * @param tree complete capture copy
     * @return mutable first fork
     */
    private static ObjectNode fork(final JsonNode tree) {
        return (ObjectNode) tree.get("forks").get(0);
    }
}
