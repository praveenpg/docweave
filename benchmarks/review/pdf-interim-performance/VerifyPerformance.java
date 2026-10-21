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

/** Verifies every frozen performance observation and summarizes samples without dropping outliers. */
public final class VerifyPerformance {
    /** Prevents utility construction. */
    private VerifyPerformance() {
    }

    /**
     * Checks capture identities, resource policies, complete pass coverage and text stability.
     * @param args repository root, complete results and NEW summary path
     * @throws Exception if any observation, source identity or policy differs
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 3, "Expected repository, results and NEW summary");
        final Path root = Path.of(args[0]);
        final var mapper = JsonMapper.builder().build();
        final Path manifestPath = root.resolve("benchmarks/review/pdf-interim-performance/manifest.json");
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        final var results = mapper.readTree(Files.readAllBytes(Path.of(args[1])));
        require(hash(manifestPath).equals(results.get("manifest_sha256").asString()), "Manifest hash");
        final Map<String, String> tools =
                Map.of("native_worker_sha256", "BenchmarkPdfNative.java", "upstream_worker_sha256",
                        "benchmark_unstructured_native.py", "orchestrator_sha256", "RunPdfPerformance.java");
        for (final var entry : tools.entrySet()) {
            require(hash(root.resolve("benchmarks/tools/" + entry.getValue()))
                            .equals(results.get(entry.getKey()).asString()),
                    "Tool hash " + entry.getValue());
        }
        final List<String> runtimes = new ArrayList<>();
        manifest.get("runtimes").forEach(value -> runtimes.add(value.asString()));
        final List<JsonNode> cases = new ArrayList<>();
        manifest.get("cases").forEach(cases::add);
        final int forks = manifest.get("forks_per_runtime").asInt();
        final int warmups = manifest.get("warmup_passes").asInt();
        final int measured = manifest.get("measured_passes").asInt();
        final Map<String, List<Long>> elapsed = new LinkedHashMap<>();
        final Map<String, List<Long>> first = new LinkedHashMap<>();
        final Map<String, List<Long>> wall = new LinkedHashMap<>();
        final Map<String, List<Long>> rss = new LinkedHashMap<>();
        final Map<String, List<Long>> setup = new LinkedHashMap<>();
        require(results.get("forks").size() == forks * runtimes.size(), "Fork coverage");
        for (int index = 0; index < forks * runtimes.size(); index++) {
            final var fork = results.get("forks").get(index);
            final int round = index / runtimes.size();
            final String runtime = runtimes.get((index % runtimes.size() + round) % runtimes.size());
            require(fork.get("runtime").asString().equals(runtime) && fork.get("round").asInt() == round
                            && fork.get("id").asString().equals(runtime + "-" + round),
                    "Rotating fork identity");
            require(fork.get("outcome").asString().equals("SUCCESS"), "Failed fork retained; no success-only summary");
            final var worker = fork.get("worker");
            final boolean nativeRuntime = runtime.startsWith("java");
            if (nativeRuntime) {
                require(worker.get("java").asString().startsWith(runtime.substring(4) + "."), "Actual JVM");
                require(worker.get("available_processors").asInt() == manifest.get("active_processors").asInt(),
                        "CPU count");
                require(worker.get("maximum_heap_bytes").asLong()
                                == manifest.get("native_max_heap_mib").asLong() * 1048576L,
                        "Heap policy");
                require(worker.get("cpu_affinity")
                                .asString()
                                .strip()
                                .equals("Cpus_allowed_list:\t" + manifest.get("cpu_set").asString()),
                        "Native affinity");
            } else {
                require(worker.get("torch_threads").asInt() == 4 && worker.get("cpu_affinity").size() == 4,
                        "Upstream CPU policy");
                for (int cpu = 0; cpu < 4; cpu++) {
                    require(worker.get("cpu_affinity").get(cpu).asInt() == cpu, "Upstream affinity");
                }
            }
            require(worker.get("samples").size() == cases.size() * (1 + warmups + measured), "Sample coverage");
            long total = worker.get("setup_ns").asLong();
            require(total > 0, "Positive setup");
            add(setup, runtime, total);
            for (int sampleIndex = 0; sampleIndex < worker.get("samples").size(); sampleIndex++) {
                final var sample = worker.get("samples").get(sampleIndex);
                final var source = cases.get(sampleIndex % cases.size());
                final int pass = sampleIndex / cases.size();
                final String phase = pass == 0 ? "FIRST_PASS" : pass <= warmups ? "WARMUP" : "MEASURED";
                require(sample.get("id").equals(source.get("id")) && sample.get("pass").asInt() == pass
                                && sample.get("phase").asString().equals(phase),
                        "Ordered unique sample coverage");
                require(sample.get("text_sha256")
                                .equals(source.get(
                                        nativeRuntime ? "native_public_text_sha256" : "upstream_public_text_sha256")),
                        "Complete output identity");
                final long duration = sample.get("elapsed_ns").asLong();
                require(duration > 0 && sample.get("elements").asInt() > 0, "Positive API observation");
                total = Math.addExact(total, duration);
                final String key = runtime + "/" + source.get("id").asString();
                if (phase.equals("MEASURED")) {
                    add(elapsed, key, duration);
                } else if (phase.equals("FIRST_PASS")) {
                    add(first, key, duration);
                }
            }
            final long processWall = fork.get("whole_process_wall_ns").asLong();
            final long peakRss = fork.get("full_process_peak_rss_kib").asLong();
            require(processWall >= total && peakRss > 0, "Whole-process observations");
            add(wall, runtime, processWall);
            add(rss, runtime, peakRss);
        }
        final List<Map<String, Object>> documents = new ArrayList<>();
        final List<Map<String, Object>> processes = new ArrayList<>();
        for (final String runtime : runtimes) {
            for (final var source : cases) {
                final String id = source.get("id").asString();
                final String key = runtime + "/" + id;
                require(elapsed.get(key).size() == forks * measured && first.get(key).size() == forks,
                        "Summary denominators");
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("runtime", runtime);
                row.put("source", id);
                row.put("measured_api_ms", summary(elapsed.get(key), 1000000.0));
                row.put("first_pass_api_ms", summary(first.get(key), 1000000.0));
                documents.add(row);
            }
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("runtime", runtime);
            row.put("whole_process_wall_seconds", summary(wall.get(runtime), 1000000000.0));
            row.put("full_process_peak_rss_mib", summary(rss.get(runtime), 1024.0));
            row.put("worker_setup_seconds", summary(setup.get(runtime), 1000000000.0));
            processes.add(row);
        }
        final Map<String, Object> report = new LinkedHashMap<>();
        report.put("results_sha256", hash(Path.of(args[1])));
        report.put("all_nine_forks_and_360_api_samples_verified", true);
        report.put("all_output_hashes_match_independent_defaults", true);
        report.put("documents", documents);
        report.put("processes", processes);
        Files.writeString(Path.of(args[2]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Retains each observation in insertion order.
     * @param values sample groups
     * @param key group identity
     * @param value complete observation
     */
    private static void add(final Map<String, List<Long>> values, final String key, final long value) {
        values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
    }

    /**
     * Summarizes all values, including extremes, using an explicitly declared unit conversion.
     * @param values unfiltered observations
     * @param divisor input units per output unit
     * @return sample count and minimum, median and maximum
     */
    private static Map<String, Object> summary(final List<Long> values, final double divisor) {
        final var sorted = values.stream().sorted().toList();
        require(!sorted.isEmpty() && sorted.size() % 2 == 1, "Odd nonempty sample group");
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("samples", sorted.size());
        result.put("minimum", sorted.getFirst() / divisor);
        result.put("median", sorted.get(sorted.size() / 2) / divisor);
        result.put("maximum", sorted.getLast() / divisor);
        return result;
    }

    /**
     * Binds a complete file without retaining its contents in the summary.
     * @param path exact input file
     * @return SHA-256 hexadecimal digest
     * @throws Exception if the file cannot be read or hashed
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects incomplete or inconsistent captures before producing a numeric summary.
     * @param condition invariant to enforce
     * @param message non-publisher diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
