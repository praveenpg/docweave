import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOcrOptions;
import io.docweave.partition.PdfOcrProvider;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Predeclared packaged success-path study. The supervisor launches one child JVM per declared process slot with the
 * caller-supplied packaged runtime classpath, enforces the process deadline and retains every exit, timeout and typed
 * failure. Each child verifies the source identity, then measures one cold extraction, untimed warmups, timed
 * sequential extractions and virtual-thread concurrent rounds, recording logical-output hashes and Linux VmHWM.
 * Observations are single-host measurements, not capacity, throughput or memory-minimum claims.
 */
public final class MeasurePackagedPdfStudy {
    /** Prevents construction of this command-line study. */
    private MeasurePackagedPdfStudy() {
    }

    /**
     * Runs {@code supervise PROTOCOL RUNTIME_CLASSPATH FRESH_REPORT}, internal workers or {@code --self-test}.
     * @param args mode and its arguments
     * @throws Exception if the protocol, a source identity or report creation fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            controls();
        } else if (args.length == 4 && args[0].equals("supervise")) {
            supervise(Path.of(args[1]), args[2], Path.of(args[3]));
        } else if (args.length == 9 && args[0].equals("worker")) {
            worker(args);
        } else {
            throw new IllegalArgumentException("Expected supervise PROTOCOL CLASSPATH FRESH_REPORT");
        }
    }

    /**
     * Launches every declared child process sequentially and writes one complete JSON report.
     * @param protocol predeclared protocol properties
     * @param classpath packaged runtime classpath plus this tool's directory
     * @param report fresh report path
     * @throws Exception if the protocol is incomplete or the report exists
     */
    private static void supervise(final Path protocol, final String classpath, final Path report) throws Exception {
        if (Files.exists(report)) {
            throw new IllegalArgumentException("Report must be fresh");
        }
        final var declared = new Properties();
        try (final var input = Files.newInputStream(protocol)) {
            declared.load(input);
        }
        final String java = ProcessHandle.current().info().command().orElseThrow();
        final int processes = Integer.parseInt(declared.getProperty("processes"));
        final long deadline = Long.parseLong(declared.getProperty("process_deadline_seconds"));
        final var json = new StringBuilder("{\n  \"protocol_sha256\": \"" + hash(Files.readAllBytes(protocol))
                                           + "\",\n  \"runtime\": \"" + Runtime.version() + "\",\n  \"java\": \""
                                           + escape(java) + "\",\n  \"jvm_options\": \""
                                           + escape(declared.getProperty("jvm_options")) + "\",\n  \"runs\": [");
        boolean firstRun = true;
        final Path temporary = Files.createTempDirectory("docweave-study");
        for (final String profile : declared.getProperty("profiles").split(",")) {
            for (int slot = 1; slot <= processes; slot++) {
                final Path out = temporary.resolve(profile + "-" + slot + ".properties");
                final var command = new ArrayList<String>();
                command.add(java);
                command.addAll(List.of(declared.getProperty("jvm_options").split(" ")));
                command.addAll(List.of("-cp", classpath, MeasurePackagedPdfStudy.class.getName(), "worker",
                        declared.getProperty(profile + ".source"), declared.getProperty(profile + ".sha256"),
                        declared.getProperty(profile + ".policy"), declared.getProperty("warmups"),
                        declared.getProperty("timed"), declared.getProperty("concurrency"),
                        declared.getProperty("rounds"), out.toString()));
                final long started = System.nanoTime();
                final Path log = temporary.resolve(profile + "-" + slot + ".log");
                final var process =
                        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                final boolean exited;
                try {
                    exited = process.waitFor(deadline, TimeUnit.SECONDS);
                } catch (final InterruptedException failure) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw failure;
                }
                if (!exited) {
                    process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                }
                final double processMillis = (System.nanoTime() - started) / 1e6;
                final String tail = tail(log);
                final var observed = new Properties();
                if (Files.exists(out)) {
                    try (final var input = Files.newInputStream(out)) {
                        observed.load(input);
                    }
                }
                json.append(firstRun ? "\n" : ",\n")
                        .append("    {\"profile\": \"")
                        .append(escape(profile))
                        .append("\", \"process\": ")
                        .append(slot)
                        .append(", \"exited_before_deadline\": ")
                        .append(exited)
                        .append(", \"exit_code\": ")
                        .append(exited ? Integer.toString(process.exitValue()) : "null")
                        .append(", \"process_wall_ms\": ")
                        .append(format(processMillis));
                if (!exited || process.exitValue() != 0) {
                    json.append(", \"child_output_tail\": \"").append(escape(tail)).append('"');
                }
                observed.stringPropertyNames().stream().sorted().forEach(key
                        -> json.append(", \"")
                                .append(key)
                                .append("\": \"")
                                .append(escape(observed.getProperty(key)))
                                .append('"'));
                json.append('}');
                firstRun = false;
            }
        }
        json.append("\n  ]\n}\n");
        Files.writeString(report, json, StandardOpenOption.CREATE_NEW);
    }

    /**
     * Measures one declared profile inside an isolated child JVM.
     * @param args worker arguments: source, SHA-256, policy, warmups, timed, concurrency, rounds, output
     * @throws Exception if identity verification or output writing fails
     */
    private static void worker(final String[] args) throws Exception {
        final byte[] bytes = Files.readAllBytes(Path.of(args[1]));
        if (!hash(bytes).equals(args[2])) {
            throw new IllegalArgumentException("Source identity mismatch");
        }
        final Partitioners options = args[3].startsWith("TESSERACT:") || args[3].startsWith("TESSERACT_LIMIT:")
                                             ? tesseract(args[3])
                                             : nativeText(args[3]);
        final int warmups = Integer.parseInt(args[4]);
        final int timed = Integer.parseInt(args[5]);
        final int concurrency = Integer.parseInt(args[6]);
        final int rounds = Integer.parseInt(args[7]);
        final var result = new Properties();
        final var hashes = new java.util.TreeSet<String>();
        final var failures = new java.util.TreeMap<String, Integer>();
        final Observation cold = extract(bytes, options);
        record
        (cold, hashes, failures);
        result.setProperty("cold_ms", format(cold.millis()));
        result.setProperty("elements", Integer.toString(cold.elements()));
        result.setProperty("tables", Integer.toString(cold.tables()));
        result.setProperty("images", Integer.toString(cold.images()));
        for (int index = 0; index < warmups; index++) {
            record
            (extract(bytes, options), hashes, failures);
        }
        final var sequential = new ArrayList<Double>();
        for (int index = 0; index < timed; index++) {
            final Observation observation = extract(bytes, options);
            record
            (observation, hashes, failures);
            sequential.add(observation.millis());
        }
        result.setProperty(
                "warm_sequential_ms", sequential.stream().map(MeasurePackagedPdfStudy::format).toList().toString());
        final var roundsMillis = new ArrayList<Double>();
        for (int round = 0; round < rounds; round++) {
            final long started = System.nanoTime();
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var futures = new ArrayList<Future<Observation>>();
                for (int task = 0; task < concurrency; task++) {
                    futures.add(executor.submit(() -> extract(bytes, options)));
                }
                for (final var future : futures) {
                    record
                    (future.get(), hashes, failures);
                }
            }
            roundsMillis.add((System.nanoTime() - started) / 1e6);
        }
        result.setProperty(
                "concurrent_round_ms", roundsMillis.stream().map(MeasurePackagedPdfStudy::format).toList().toString());
        result.setProperty("attempts", Integer.toString(1 + warmups + timed + concurrency * rounds));
        result.setProperty("output_sha256_distinct", hashes.toString());
        result.setProperty("output_sha256_encoding", "typed-length-prefixed-v1");
        result.setProperty("failures", failures.toString());
        result.setProperty("vm_hwm_kib", highWater());
        result.setProperty("max_heap_bytes", Long.toString(Runtime.getRuntime().maxMemory()));
        try (final var output = Files.newOutputStream(Path.of(args[8]), StandardOpenOption.CREATE_NEW)) {
            result.store(output, "worker");
        }
    }

    /**
     * One extraction outcome.
     * @param millis wall time
     * @param outputSha256 logical output hash, or null on typed failure
     * @param failure typed failure code, or null on success
     * @param elements element count
     * @param tables Table element count
     * @param images Image element count
     */
    private record
            Observation(double millis, String outputSha256, String failure, int elements, int tables, int images) {}

    /**
     * Creates a fresh public PDF partitioner for one extraction attempt.
     */
    @FunctionalInterface
    private interface Partitioners {
        /**
         * Builds an independent service; no source is read or engine run here.
         * @return fresh partitioner
         * @throws Exception if an explicitly selected optional module cannot be loaded
         */
        PdfPartitioner create() throws Exception;
    }

    /**
     * Maps a declared native-text policy to fresh default-provider partitioners.
     * @param policy declared native policy name
     * @return partitioner factory
     */
    private static Partitioners nativeText(final String policy) {
        final var options = switch (policy) {
            case "DEFAULT" -> PdfOptions.defaults();
            case "ORIENTED_LINES" -> PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.ORIENTED_LINES);
            case "RULED_AND_PARTIAL_TEXT" ->
                PdfOptions.defaults().withTableDetection(PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT);
            default -> throw new IllegalArgumentException("Undeclared policy");
        };
        return () -> PdfPartitioner.create(options);
    }

    /**
     * Maps {@code TESSERACT:LANGUAGE:DPI} or {@code TESSERACT_LIMIT:LANGUAGE:DPI} to explicit packaged OCR.
     * The optional OCR module is loaded reflectively so native-only protocols still compile against the core and PDF
     * JARs alone. The trusted executable and model directory come from the declared JVM options
     * {@code docweave.study.tesseract} and {@code docweave.study.tessdata}. Settings mirror the frozen historical
     * replay: one engine thread per process, automatic page segmentation, engine-default thresholding, the declared
     * source DPI and a 16M-pixel OCR raster budget. The LIMIT form declares a one-pixel-short raster budget so the
     * PDF layer must reject the page with a typed failure before any engine process starts.
     * @param policy declared OCR policy
     * @return partitioner factory creating a fresh engine wrapper per attempt
     */
    private static Partitioners tesseract(final String policy) {
        final var parts = policy.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Undeclared policy");
        }
        final String language = parts[1];
        final int dpi = Integer.parseInt(parts[2]);
        final boolean limit = parts[0].equals("TESSERACT_LIMIT");
        final String executable = System.getProperty("docweave.study.tesseract");
        final String data = System.getProperty("docweave.study.tessdata");
        if (executable == null || data == null) {
            throw new IllegalArgumentException("Declared OCR executable and model directory are required");
        }
        return () -> {
            final var optionsType = Class.forName("io.docweave.ocr.TesseractOptions");
            final var options = optionsType
                                        .getConstructor(Path.class, Path.class, List.class, java.time.Duration.class,
                                                int.class, long.class, long.class, int.class, int.class, int.class)
                                        .newInstance(Path.of(executable), Path.of(data), List.of(language),
                                                java.time.Duration.ofSeconds(60), 1, 16_000_000L, 32L * 1024 * 1024,
                                                4 * 1024 * 1024, 10_000, 1_000_000);
            final var thresholding = Class.forName("io.docweave.ocr.TesseractThresholding");
            @SuppressWarnings({"unchecked", "rawtypes"})
            final Object engineDefault = Enum.valueOf((Class) thresholding, "ENGINE_DEFAULT");
            final var engine = (PdfOcrProvider) Class.forName("io.docweave.ocr.TesseractOcr")
                                       .getMethod("create", optionsType, thresholding, int.class)
                                       .invoke(null, options, engineDefault, dpi);
            // The historical source page is 1,675 x 2,286 pixels at 300 DPI; LIMIT allows one pixel fewer.
            final long pixels = limit ? 1_675L * 2_286 - 1 : 16_000_000L;
            return PdfPartitioner.create(
                    PdfOptions.defaults(), new PdfOcrOptions(dpi, pixels, 10_000, 1_000_000), engine);
        };
    }

    /**
     * Runs one public extraction with a fresh service and hashes its logical output.
     * @param bytes source bytes
     * @param options declared partitioner factory
     * @return timed observation; typed failures are observations, not exceptions
     * @throws Exception if hashing fails
     */
    private static Observation extract(final byte[] bytes, final Partitioners options) throws Exception {
        final long started = System.nanoTime();
        try {
            final List<Element> elements = DocWeave.create(PartitionerRegistry.of(List.of(options.create())))
                                                   .partition(DocumentSource.bytes(bytes, "study.pdf"))
                                                   .elements();
            final double millis = (System.nanoTime() - started) / 1e6;
            final var digest = MessageDigest.getInstance("SHA-256");
            for (final var element : elements) {
                digest.update(canonical(List.of(element.type().name(), element.text(), element.metadata()))
                                .getBytes(StandardCharsets.UTF_8));
            }
            return new Observation(millis, HexFormat.of().formatHex(digest.digest()), null, elements.size(),
                    (int) elements.stream().filter(e -> e.type().name().equals("Table")).count(),
                    (int) elements.stream().filter(e -> e.type().name().equals("Image")).count());
        } catch (final PartitionException failure) {
            return new Observation((System.nanoTime() - started) / 1e6, null, failure.code().name(), 0, 0, 0);
        }
    }

    /**
     * Fingerprints metadata with sorted map keys, type tags and length-delimited strings.
     * Delimiters in source strings cannot masquerade as structure; list order remains significant.
     * @param value metadata value
     * @return order-independent text
     */
    private static String canonical(final Object value) {
        if (value instanceof final java.util.Map<?, ?> map) {
            final var sorted = new java.util.TreeMap<String, String>();
            map.forEach((key, item) -> {
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException("Metadata keys must be strings");
                }
                sorted.put(name, canonical(item));
            });
            final var text = new StringBuilder("M" + sorted.size() + ":");
            sorted.forEach((key, item) -> text.append(canonical(key)).append(item));
            return text.toString();
        }
        if (value instanceof final List<?> list) {
            final var text = new StringBuilder("L" + list.size() + ":");
            list.forEach(item -> text.append(canonical(item)));
            return text.toString();
        }
        if (value == null) {
            return "Z";
        }
        if (value instanceof Boolean flag) {
            return flag ? "T" : "F";
        }
        if (value instanceof Number) {
            final String number = value.toString();
            return "N" + number.length() + ":" + number;
        }
        if (value instanceof String string) {
            final String escaped = escape(string);
            return "S" + escaped.length() + ":" + escaped;
        }
        throw new IllegalArgumentException("Unsupported metadata value");
    }

    /**
     * Accumulates distinct output hashes and typed failure counts.
     * @param observation one extraction
     * @param hashes distinct successful output hashes
     * @param failures failure-code counts
     */
    private static void record(final Observation observation, final java.util.Set<String> hashes,
            final java.util.Map<String, Integer> failures) {
        if (observation.failure() == null) {
            hashes.add(observation.outputSha256());
        } else {
            failures.merge(observation.failure(), 1, Integer::sum);
        }
    }

    /**
     * Reads Linux VmHWM for this process when available.
     * @return kibibytes, or "unavailable"
     */
    private static String highWater() {
        try {
            for (final String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmHWM:")) {
                    return line.replaceAll("[^0-9]", "");
                }
            }
        } catch (final java.io.IOException ignored) {
            return "unavailable";
        }
        return "unavailable";
    }

    /**
     * Formats milliseconds with one decimal.
     * @param millis value
     * @return text
     */
    private static String format(final double millis) {
        return String.format(Locale.ROOT, "%.1f", millis);
    }

    /**
     * Hashes bytes.
     * @param bytes input
     * @return lowercase SHA-256
     * @throws Exception if the digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Escapes a value for a JSON string.
     * @param value raw text
     * @return escaped text
     */
    private static String escape(final String value) {
        final var text = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character == '\\' || character == '"') {
                text.append('\\').append(character);
            } else if (character < 0x20 || Character.isSurrogate(character)) {
                text.append('\\').append('u').append(String.format(Locale.ROOT, "%04x", (int) character));
            } else {
                text.append(character);
            }
        }
        return text.toString();
    }

    /**
     * Reads only a bounded diagnostic suffix rather than materializing the entire child log.
     * @param path child output file
     * @return at most 4096 decoded bytes, with replacement for a partial leading UTF-8 sequence
     * @throws Exception if bounded diagnostic reading fails
     */
    private static String tail(final Path path) throws Exception {
        if (!Files.exists(path)) {
            return "";
        }
        try (final var input = Files.newByteChannel(path)) {
            final var bytes = java.nio.ByteBuffer.allocate((int) Math.min(input.size(), 4096));
            input.position(Math.max(0, input.size() - bytes.capacity()));
            while (bytes.hasRemaining() && input.read(bytes) > 0) {
                // A single bounded buffer also handles partial channel reads.
            }
            return new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
        }
    }

    /** Checks fingerprint structure and non-English numeric formatting without extracting a document. */
    private static void controls() {
        final Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            if (!format(1.5).equals("1.5") || canonical(List.of("a, b")).equals(canonical(List.of("a", "b")))
                    || canonical(java.util.Map.of("a", "1")).equals(canonical(java.util.Map.of("a", 1)))
                    || canonical("\uD800").equals(canonical("\uD801"))
                    || !escape("\n\t\r").equals("\\u000a\\u0009\\u000d")) {
                throw new AssertionError("Packaged study fingerprint or formatting controls failed");
            }
            final var reordered = new java.util.LinkedHashMap<String, Object>();
            reordered.put("b", 2);
            reordered.put("a", List.of(1, true));
            if (!canonical(reordered).equals(canonical(java.util.Map.of("a", List.of(1, true), "b", 2)))) {
                throw new AssertionError("Map order affected fingerprint");
            }
        } finally {
            Locale.setDefault(previous);
        }
        System.out.println("Packaged study controls passed");
    }
}
