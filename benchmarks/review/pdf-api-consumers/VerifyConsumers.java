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
import java.util.zip.ZipFile;

/** Binds independently compiled public API consumers to exact current snapshot JARs and Java21 bytecode. */
public final class VerifyConsumers {
    /** Prevents construction of the executable verifier. */
    private VerifyConsumers() {
    }

    /**
     * Requires fresh-source equivalence, exact runtime dependency sets and identical packaged library bytes.
     * @param args project root, installed cache, consumer21 root, consumer25 root and NEW receipt
     * @throws Exception if source/runtime identity, bytecode, dependency or success checks fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 5);
        final Path root = Path.of(args[0]);
        final Path cache = Path.of(args[1]);
        final var artifacts = new LinkedHashMap<String, String>();
        final var results = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 2; index++) {
            final int java = index == 0 ? 21 : 25;
            final Path run = Path.of(args[index + 2]);
            for (final String kind : List.of("pdf", "tesseract", "onnx")) {
                final Path work = kind.equals("pdf") ? run.resolve("pdf") : run.resolve("adapters/" + kind);
                final String main = kind.equals("pdf")         ? "PdfConsumer"
                                    : kind.equals("tesseract") ? "TesseractConsumer"
                                                               : "OnnxConsumer";
                final Path source = kind.equals("pdf")
                                            ? root.resolve("benchmarks/review/release-artifacts/consumer/src/main/java/"
                                                           + main + ".java")
                                            : root.resolve("benchmarks/review/adapter-consumers/" + kind
                                                           + "/src/main/java/" + main + ".java");
                final Path copiedSource = work.resolve("project/src/main/java/" + main + ".java");
                require(hash(source).equals(hash(copiedSource)));
                final String jarName = kind.equals("pdf") ? "pdf-consumer-1.0.jar" : kind + "-consumer-1.0.jar";
                final Path jar = work.resolve("project/target/" + jarName);
                try (final var zip = new ZipFile(jar.toFile())) {
                    final var entry = zip.getEntry(main + ".class");
                    require(entry != null);
                    final byte[] bytes;
                    try (final var stream = zip.getInputStream(entry)) {
                        bytes = stream.readNBytes(8);
                    }
                    require(bytes.length == 8 && ((bytes[6] & 0xff) << 8 | bytes[7] & 0xff) == 65);
                }
                final var dependencies = Files.readAllLines(work.resolve("runtime-dependencies.txt"));
                require(dependencies.size() == (kind.equals("pdf") ? 6 : kind.equals("tesseract") ? 7 : 8));
                require(dependencies.stream().distinct().count() == dependencies.size());
                final var dependencyHashes = new LinkedHashMap<String, String>();
                for (final String name : dependencies) {
                    final String copied = hash(unique(work.resolve("repository"), name));
                    require(copied.equals(hash(unique(cache, name))));
                    if (artifacts.containsKey(name)) {
                        require(artifacts.get(name).equals(copied));
                    }
                    artifacts.put(name, copied);
                    dependencyHashes.put(name, copied);
                }
                final String output = Files.readString(work.resolve("run.log"));
                require(output.contains("passed on " + java + "."));
                if (kind.equals("pdf")) {
                    require(output.contains("All four presets, full-page ownership and paragraph API linkage passed"));
                } else if (kind.equals("tesseract")) {
                    require(output.contains("Public OCR interface and missing-engine diagnostic passed")
                            && !output.contains("Explicit installed-engine recognition passed"));
                } else {
                    require(output.contains("ONNX native geometry, identity and managed lifecycle passed"));
                }
                final var row = new LinkedHashMap<String, Object>();
                row.put("java", java);
                row.put("consumer", kind);
                row.put("source_sha256", hash(source));
                row.put("consumer_jar_sha256", hash(jar));
                row.put("class_major", 65);
                row.put("runtime_dependencies", dependencyHashes);
                row.put("build_log_sha256", hash(work.resolve("build.log")));
                row.put("run_log_sha256", hash(work.resolve("run.log")));
                row.put("outcome", "SUCCESS");
                results.add(row);
            }
        }
        final var receipt = new LinkedHashMap<String, Object>();
        receipt.put("source_library_commit", sourceCommit(root));
        receipt.put("scope",
                "Snapshot packaged public API/isolated classpath checks; no release, signing, upload or corpus-quality claim");
        receipt.put("all_six_consumers_use_identical_current_installed_libraries", true);
        receipt.put("system_installed_ocr_invoked", false);
        receipt.put("results", results);
        Files.writeString(Path.of(args[4]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(receipt) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Finds exactly one named artifact in an isolated repository or the explicitly supplied cache.
     * @param directory bounded local Maven tree
     * @param filename exact manifest filename
     * @return unique runtime artifact
     * @throws Exception if missing, duplicated or unreadable
     */
    private static Path unique(final Path directory, final String filename) throws Exception {
        try (final var paths = Files.walk(directory)) {
            final var matches =
                    paths.filter(path -> path.getFileName().toString().equals(filename) && Files.isRegularFile(path))
                            .toList();
            require(matches.size() == 1);
            return matches.getFirst();
        }
    }

    /**
     * Records the actual checkout identity rather than a historical hard-coded commit.
     * Artifact byte identities remain the authoritative binding to the installed runtime; a checkout
     * commit alone does not prove how a JAR was built.
     * @param root current project checkout
     * @return complete current commit identifier
     * @throws Exception if Git fails, times out or returns an invalid identity
     */
    private static String sourceCommit(final Path root) throws Exception {
        final var process =
                new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "HEAD").redirectErrorStream(true).start();
        final boolean exited;
        try {
            exited = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final InterruptedException failure) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw failure;
        }
        if (!exited) {
            process.destroyForcibly();
            throw new java.io.IOException("Timed out reading consumer source identity");
        }
        final String commit =
                new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        require(process.exitValue() == 0 && commit.matches("[a-f0-9]{40}"));
        return commit;
    }

    /**
     * Binds complete local source/artifact/log bytes without copying operational paths or recognized text.
     * @param path complete original file
     * @return lowercase SHA-256 identity
     * @throws Exception if the file or required digest is unavailable
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects inconsistent consumer evidence before writing a receipt.
     * @param condition required invariant
     */
    private static void require(final boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Independent consumer identity or execution check failed");
        }
    }
}
