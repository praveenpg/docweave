import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.layout.OnnxTableLayout;
import io.docweave.layout.OnnxTableOptions;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfNativeLayoutOptions;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Packaged-JAR-only local provider validation with a predeclared process protocol. Fixed owned model
 * controls are distinct from real YOLOX/financial-structure original-document observations. No test
 * classes, model downloads, OCR process, JSON dependency or remote service is used.
 */
public final class ValidatePackagedProviders {
    /** Prevents launcher construction. */
    private ValidatePackagedProviders() {
    }

    /**
     * Dispatches fixture authoring, supervision or one isolated worker.
     * @param args author PDF; supervise protocol classpath fresh-directory; or worker protocol profile report
     * @throws Exception if a declared gate, subprocess, input or output fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("author")) {
            author(Path.of(args[1]));
        } else if (args.length == 4 && args[0].equals("supervise")) {
            supervise(Path.of(args[1]), args[2], Path.of(args[3]));
        } else if (args.length == 4 && args[0].equals("worker")) {
            worker(Path.of(args[1]), args[2], Path.of(args[3]));
        } else {
            throw new IllegalArgumentException("Expected author, supervise or worker arguments");
        }
    }

    /**
     * Authors a native-text page matching the owned graph's known 300-by-180 input frame.
     * @param output new private PDF
     * @throws Exception if authoring fails or the output exists
     */
    private static void author(final Path output) throws Exception {
        if (Files.exists(output)) {
            throw new IllegalArgumentException("Author output must be fresh");
        }
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(300, 180));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
                content.newLineAtOffset(30, 125);
                content.showText("Owned provider control");
                content.endText();
            }
            document.save(output.toFile());
        }
    }

    /**
     * Executes all declared process slots and retains failing slots rather than stopping early.
     * @param protocol frozen properties
     * @param classpath this tool and the eight packaged runtime JARs
     * @param output fresh private report directory
     * @throws Exception if process orchestration or final gate evaluation fails
     */
    private static void supervise(final Path protocol, final String classpath, final Path output) throws Exception {
        final var policy = read(protocol);
        Files.createDirectory(output);
        final var rows = new ArrayList<String>();
        rows.add("profile\tprocess\texited\texit_code\twall_ms\tworker_pass");
        boolean passed = true;
        for (final String profile : policy.getProperty("profiles").split(",")) {
            for (int slot = 1; slot <= Integer.parseInt(policy.getProperty("processes")); slot++) {
                final String id = profile + "-" + slot;
                final var report = output.resolve(id + ".properties");
                final var command = new ArrayList<String>();
                command.add(ProcessHandle.current().info().command().orElseThrow());
                command.addAll(List.of(policy.getProperty("jvm_options").split(" ")));
                command.addAll(List.of("-cp", classpath, ValidatePackagedProviders.class.getName(), "worker",
                        protocol.toString(), profile, report.toString()));
                final long started = System.nanoTime();
                final var child = new ProcessBuilder(command)
                                          .redirectErrorStream(true)
                                          .redirectOutput(output.resolve(id + ".log").toFile())
                                          .start();
                final boolean exited;
                try {
                    exited = child.waitFor(Long.parseLong(policy.getProperty("deadline_seconds")), TimeUnit.SECONDS);
                } catch (final InterruptedException failure) {
                    child.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw failure;
                }
                if (!exited) {
                    child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                }
                final boolean workerPass =
                        Files.exists(report) && Boolean.parseBoolean(read(report).getProperty("passed"));
                final int exit = exited ? child.exitValue() : -1;
                rows.add(profile + "\t" + slot + "\t" + exited + "\t" + exit + "\t"
                         + (System.nanoTime() - started) / 1_000_000 + "\t" + workerPass);
                passed &= exited && exit == 0 && workerPass;
            }
        }
        Files.write(output.resolve("processes.tsv"), rows, StandardOpenOption.CREATE_NEW);
        // Child checks cover within-process determinism; this distinct gate covers independent JVMs.
        for (final String profile : policy.getProperty("profiles").split(",")) {
            final var hashes = new TreeSet<String>();
            for (int slot = 1; slot <= Integer.parseInt(policy.getProperty("processes")); slot++) {
                final var report = output.resolve(profile + "-" + slot + ".properties");
                if (Files.exists(report)) {
                    hashes.add(read(report).getProperty("output_hashes", "missing"));
                }
            }
            passed &= hashes.size() == 1 && !hashes.contains("missing");
        }
        Files.writeString(output.resolve("checks.txt"), "all_declared_gates_pass=" + passed + "\n",
                StandardOpenOption.CREATE_NEW);
        require(passed, "Packaged study gate failed; all process records are retained");
    }

    /**
     * Exercises one shared managed provider through cold, sequential and two concurrent calls.
     * @param protocol predeclared identities and limits
     * @param profile fixed, limit or real workload identifier
     * @param report fresh result path
     * @throws Exception if initialization, execution, cleanup or a gate fails
     */
    private static void worker(final Path protocol, final String profile, final Path report) throws Exception {
        final var policy = read(protocol);
        final boolean real = profile.equals("real-yolox-fin");
        final boolean limit = profile.equals("fixed-anchor-limit");
        require(real || limit || profile.equals("fixed-structure"), "Undeclared profile");
        final byte[] source = pinned(policy, real ? "real" : "owned");
        final var fixed =
                OnnxTableOptions.defaults(Path.of(policy.getProperty("fixed.path")), policy.getProperty("fixed.sha256"))
                        .withConcurrency(2);
        final OnnxTableLayout provider;
        if (real) {
            provider =
                    OnnxTableLayout.createWithYoloX(OnnxTableOptions
                                                            .yoloXDefaults(Path.of(policy.getProperty("detector.path")),
                                                                    policy.getProperty("detector.sha256"))
                                                            .withConcurrency(2),
                            OnnxTableOptions
                                    .defaults(Path.of(policy.getProperty("structure.path")),
                                            policy.getProperty("structure.sha256"))
                                    .withConcurrency(2)
                                    .withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE));
        } else {
            provider = OnnxTableLayout.create(fixed);
        }
        final var calls = new AtomicInteger();
        final var geometry = new AtomicInteger();
        final Callable<Observation> attempt = () -> {
            try {
                final var service = PdfPartitioner.createWithNativeLayout(PdfOptions.defaults(),
                        new PdfNativeLayoutOptions(real ? 150 : 72, 16_000_000, limit ? 1 : 1024), image -> {
                            calls.incrementAndGet();
                            final var tables = provider.locate(image);
                            geometry.addAndGet(tables.size());
                            return tables;
                        });
                final var elements = DocWeave.create(PartitionerRegistry.of(List.of(service)))
                                             .partition(DocumentSource.bytes(source, "source.pdf"))
                                             .elements();
                return new Observation(
                        hash(canonical(elements.stream()
                                               .map(e
                                                       -> Map.<String, Object>of("type", e.type().name(), "text",
                                                               e.text(), "metadata", e.metadata()))
                                               .toList())
                                        .getBytes(StandardCharsets.UTF_8)),
                        "", (int) elements.stream().filter(e -> e.type() == ElementType.Table).count());
            } catch (final PartitionException failure) {
                return new Observation("", failure.code().name(), 0);
            }
        };
        final var observations = new ArrayList<Observation>();
        try (provider) {
            observations.add(attempt.call());
            observations.add(attempt.call());
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var one = executor.submit(attempt);
                final var two = executor.submit(attempt);
                observations.add(one.get());
                observations.add(two.get());
            }
        }
        boolean closedRejected = false;
        final var after = new BufferedImage(300, 180, BufferedImage.TYPE_INT_RGB);
        try {
            provider.locate(after);
        } catch (final PartitionException expected) {
            closedRejected = true;
        } finally {
            after.flush();
        }
        final var hashes = new TreeSet<String>();
        observations.stream().filter(o -> o.failure().isEmpty()).forEach(o -> hashes.add(o.sha256()));
        final var failures = new TreeMap<String, Integer>();
        observations.stream()
                .filter(o -> !o.failure().isEmpty())
                .forEach(o -> failures.merge(o.failure(), 1, Integer::sum));
        final long hwm = highWater();
        final boolean outputsPass =
                limit ? failures.equals(Map.of("TABLE_LIMIT", 4)) && hashes.isEmpty()
                      : failures.isEmpty() && hashes.size() == 1 && observations.stream().allMatch(o -> o.tables() > 0);
        final boolean passed = outputsPass && calls.get() > 0 && geometry.get() > 0 && closedRejected && hwm > 0
                               && hwm < Long.parseLong(policy.getProperty("rss_ceiling_kib"));
        final var result = new Properties();
        result.setProperty("passed", Boolean.toString(passed));
        result.setProperty("attempts", "4");
        result.setProperty("failures", failures.toString());
        result.setProperty("output_hashes", hashes.toString());
        result.setProperty("table_counts", observations.stream().map(Observation::tables).toList().toString());
        result.setProperty("provider_calls", Integer.toString(calls.get()));
        result.setProperty("provider_tables", Integer.toString(geometry.get()));
        result.setProperty("closed_rejected", Boolean.toString(closedRejected));
        result.setProperty("vm_hwm_kib", Long.toString(hwm));
        result.setProperty("protocol_sha256", hash(Files.readAllBytes(protocol)));
        result.setProperty("java", Runtime.version().toString());
        try (final var output = Files.newOutputStream(report, StandardOpenOption.CREATE_NEW)) {
            result.store(output, "packaged provider observation");
        }
        require(passed, "Worker gate failed: " + result);
    }

    /**
     * One content-free attempt outcome.
     * @param sha256 logical output identity, empty on failure
     * @param failure typed error name, empty on success
     * @param tables extracted table count
     */
    private record Observation(String sha256, String failure, int tables) {}

    /**
     * Canonicalizes typed values with sorted map keys and length-prefixed scalar values.
     * @param value supported metadata/list/scalar tree
     * @return unambiguous deterministic encoding
     */
    private static String canonical(final Object value) {
        if (value instanceof Map<?, ?> map) {
            final var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put((String) key, item));
            final var result = new StringBuilder("M" + map.size() + ":");
            sorted.forEach((key, item) -> result.append(canonical(key)).append(canonical(item)));
            return result.toString();
        }
        if (value instanceof List<?> list) {
            final var result = new StringBuilder("L" + list.size() + ":");
            list.forEach(item -> result.append(canonical(item)));
            return result.toString();
        }
        if (value == null) {
            return "Z";
        }
        final String type = value instanceof String    ? "S"
                            : value instanceof Number  ? "N"
                            : value instanceof Boolean ? "B"
                                                       : "";
        require(!type.isEmpty(), "Unsupported fingerprint type");
        final String text = value.toString();
        return type + text.length() + ":" + text;
    }

    /**
     * Reads a complete small trusted properties file.
     * @param path protocol or worker report
     * @return parsed properties
     * @throws Exception if the file cannot be read
     */
    private static Properties read(final Path path) throws Exception {
        final var result = new Properties();
        try (final var input = Files.newInputStream(path)) {
            result.load(input);
        }
        return result;
    }

    /**
     * Checks original source identity before extraction.
     * @param policy frozen protocol
     * @param prefix source key prefix
     * @return complete verified bytes
     * @throws Exception if source identity or reading fails
     */
    private static byte[] pinned(final Properties policy, final String prefix) throws Exception {
        final var bytes = Files.readAllBytes(Path.of(policy.getProperty(prefix + ".path")));
        require(hash(bytes).equals(policy.getProperty(prefix + ".sha256")), "Source identity changed");
        return bytes;
    }

    /**
     * Computes SHA-256 for a source or logical output.
     * @param bytes complete input
     * @return lowercase hexadecimal identity
     * @throws Exception if the mandatory digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Observes Linux process high-water memory, including native ONNX memory in this process.
     * @return KiB, or minus one when unavailable
     * @throws Exception if process status cannot be read
     */
    private static long highWater() throws Exception {
        for (final String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmHWM:")) {
                return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        }
        return -1;
    }

    /**
     * Enforces an unchanged predeclared gate.
     * @param condition gate result
     * @param message failure explanation
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
