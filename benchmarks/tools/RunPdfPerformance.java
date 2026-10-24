package io.docweave.validation;

import tools.jackson.databind.json.JsonMapper;

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
import java.util.concurrent.TimeUnit;

/** Bounded JVM orchestration of CPU-pinned independent runtime forks, including whole-process wall and RSS. */
public final class RunPdfPerformance {
    /** Unique zsh wait4/rusage marker, outside every SDK's report format. */
    private static final String RSS = "DW_BENCH_MAX_RSS_KIB=";

    /** Prevents utility construction. */
    private RunPdfPerformance() {
    }

    /**
     * Runs predeclared rotating fork order without discarding failures or selecting best samples.
     * A fixed quoted positional-argument shell wrapper reads zsh's child rusage after teardown;
     * document names/classpaths are arguments, never interpolated into executable shell text.
     * @param args manifest, source cache, NEW output directory, JDK21 root, JDK25 root and pinned Python executable
     * @throws Exception if preparation, process ownership, complete coverage or fresh output checks fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("Expected manifest, sources, NEW output, JDK21, JDK25 and Python");
        }
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var output = Files.createDirectory(Path.of(args[2]));
        final var runtimes = List.of("java21", "java25", "unstructured028");
        final var forks = new ArrayList<Map<String, Object>>();
        for (int round = 0; round < manifest.get("forks_per_runtime").asInt(); round++) {
            for (int offset = 0; offset < runtimes.size(); offset++) {
                final String runtime = runtimes.get((round + offset) % runtimes.size());
                final String id = runtime + "-" + round;
                final var worker = output.resolve(id + ".json").toAbsolutePath();
                final var log = output.resolve(id + ".log").toAbsolutePath();
                final var command =
                        new ArrayList<String>(List.of("/bin/zsh", "-c", "TIMEFMT='" + RSS + "%M'; time \"$@\"",
                                "docweave-benchmark", "/usr/bin/taskset", "-c", manifest.get("cpu_set").asString()));
                final String executable;
                if (runtime.startsWith("java")) {
                    executable = Path.of(runtime.equals("java21") ? args[3] : args[4], "bin/java").toString();
                    command.addAll(
                            List.of(executable, "-XX:ActiveProcessorCount=" + manifest.get("active_processors").asInt(),
                                    "-Xmx" + manifest.get("native_max_heap_mib").asInt() + "m", "-cp",
                                    System.getProperty("java.class.path"), "io.docweave.validation.BenchmarkPdfNative",
                                    args[0], args[1], worker.toString()));
                } else {
                    executable = args[5];
                    command.addAll(List.of(executable, "benchmarks/tools/benchmark_unstructured_native.py", args[0],
                            args[1], worker.toString()));
                }
                final var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
                builder.environment().put("OMP_NUM_THREADS", "4");
                builder.environment().put("OMP_THREAD_LIMIT", "4");
                final long began = System.nanoTime();
                final var process = builder.start();
                final boolean complete =
                        process.waitFor(manifest.get("fork_timeout_seconds").asInt(), TimeUnit.SECONDS);
                if (!complete) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
                final long wall = System.nanoTime() - began;
                final var result = new LinkedHashMap<String, Object>();
                result.put("id", id);
                result.put("runtime", runtime);
                result.put("round", round);
                result.put("whole_process_wall_ns", wall);
                result.put("outcome", complete && process.exitValue() == 0 ? "SUCCESS"
                                      : complete                           ? "PROCESS_FAILURE"
                                                                           : "TIMEOUT");
                result.put("executable_sha256", hash(Path.of(executable)));
                if (complete && process.exitValue() == 0) {
                    final var markers = Files.readAllLines(log).stream().filter(line -> line.startsWith(RSS)).toList();
                    if (markers.size() != 1 || !markers.getFirst().substring(RSS.length()).matches("[0-9]+")) {
                        throw new IllegalArgumentException("Missing or ambiguous full-process peak RSS");
                    }
                    result.put("full_process_peak_rss_kib", Long.parseLong(markers.getFirst().substring(RSS.length())));
                    result.put("worker", mapper.readTree(Files.readAllBytes(worker)));
                }
                forks.add(result);
                System.out.printf("Performance fork complete: %s %s%n", id, result.get("outcome"));
            }
        }
        final var receipt = new LinkedHashMap<String, Object>();
        receipt.put("manifest_sha256", hash(Path.of(args[0])));
        receipt.put("native_worker_sha256", hash(Path.of("benchmarks/tools/BenchmarkPdfNative.java")));
        receipt.put("upstream_worker_sha256", hash(Path.of("benchmarks/tools/benchmark_unstructured_native.py")));
        receipt.put("orchestrator_sha256", hash(Path.of("benchmarks/tools/RunPdfPerformance.java")));
        receipt.put("taskset_executable_sha256", hash(Path.of("/usr/bin/taskset")));
        receipt.put("zsh_executable_sha256", hash(Path.of("/bin/zsh")));
        receipt.put("cpu_model", Files.readAllLines(Path.of("/proc/cpuinfo"))
                                         .stream()
                                         .filter(line -> line.startsWith("model name"))
                                         .findFirst()
                                         .orElse("UNAVAILABLE"));
        receipt.put("forks", forks);
        Files.writeString(output.resolve("results.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(receipt) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Binds exact worker/orchestrator/executable bytes, independently of timing samples.
     * @param path original local code or executable
     * @return lowercase SHA256
     * @throws Exception if bytes or mandatory JDK hash support are unavailable
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
