package dev.docweave.json;

import com.sun.management.ThreadMXBean;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.InflaterInputStream;

/** Warmed same-JVM Java allocation comparison, preserving exact decompressed provenance for all owned fixtures. */
public final class ProbeOriginalsAllocations {
    /** Observable encoded-output use prevents eliminating the measured result. */
    private static volatile int sink;

    /** Prevents standalone benchmark construction. */
    private ProbeOriginalsAllocations() {
    }

    /**
     * Captures one call's Java-thread allocation and elapsed time, excluding fixture construction and validation.
     * @param allocated bytes reported by the main thread's allocation counter
     * @param nanos elapsed call time, not a production latency guarantee
     */
    private record Sample(long allocated, long nanos) {}

    /**
     * Compares a separately compiled5cebb58 codec with the current public codec interface.
     * @param args transformed legacy source file and fresh numeric report
     * @throws Exception if identities, JVM counters, deterministic output or exact decoded bytes fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 2 && !Files.exists(Path.of(args[1])), "Expected legacy source and fresh report");
        final var constructor = Class.forName("dev.docweave.json.LegacyElementJson").getDeclaredConstructor();
        constructor.setAccessible(true);
        final var legacy = (ElementJson) constructor.newInstance();
        final var current = ElementJson.create();
        require(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean, "Java allocation counter unavailable");
        final var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        require(bean.isThreadAllocatedMemorySupported(), "Java thread allocation unsupported");
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        final var fixtures = new LinkedHashMap<String, List<Element>>();
        fixtures.put("two_mib_ascii_text",
                List.of(new Element("large", ElementType.NarrativeText, "x".repeat(2 * 1024 * 1024), Map.of())));
        final var random = new Random(4517);
        final var bytes = new byte[1536 * 1024];
        random.nextBytes(bytes);
        fixtures.put("two_mib_owned_image_payload",
                List.of(new Element("image", ElementType.Image, "",
                        Map.of("image_base64", Base64.getEncoder().encodeToString(bytes), "page_number", 1))));
        final var small = new ArrayList<Element>();
        for (int index = 0; index < 1024; index++) {
            small.add(new Element("small-" + index, ElementType.NarrativeText,
                    "Small source text with stable content ".repeat(2),
                    Map.of("page_number", index / 50, "filename", "owned.pdf")));
        }
        fixtures.put("one_thousand_short_originals", List.copyOf(small));
        final var results = new ArrayList<Map<String, Object>>();
        for (final var fixture : fixtures.entrySet()) {
            final var source = fixture.getValue();
            final String oldPayload = legacy.encodeOriginals(source), newPayload = current.encodeOriginals(source);
            final byte[] oldJson = inflated(oldPayload), newJson = inflated(newPayload);
            require(Arrays.equals(oldJson, newJson), "Decoded wire changed for " + fixture.getKey());
            for (int warmup = 0; warmup < 8; warmup++) {
                require(legacy.encodeOriginals(source).equals(oldPayload), "Legacy output changed");
                require(current.encodeOriginals(source).equals(newPayload), "Current output changed");
            }
            final var before = new ArrayList<Sample>();
            final var after = new ArrayList<Sample>();
            for (int iteration = 0; iteration < 5; iteration++) {
                if (iteration % 2 == 0) {
                    before.add(sample(bean, legacy, source, oldPayload));
                    after.add(sample(bean, current, source, newPayload));
                } else {
                    after.add(sample(bean, current, source, newPayload));
                    before.add(sample(bean, legacy, source, oldPayload));
                }
            }
            final var row = new LinkedHashMap<String, Object>(Map.of("fixture", fixture.getKey(), "elements",
                    source.size(), "json_utf8_bytes", oldJson.length, "json_sha256", hash(oldJson),
                    "decoded_wire_exact", true, "compressed_payload_equal", oldPayload.equals(newPayload),
                    "legacy_allocated_bytes", before.stream().map(Sample::allocated).toList(),
                    "current_allocated_bytes", after.stream().map(Sample::allocated).toList(), "legacy_elapsed_nanos",
                    before.stream().map(Sample::nanos).toList(), "current_elapsed_nanos",
                    after.stream().map(Sample::nanos).toList()));
            final long previousBytes = median(before.stream().map(Sample::allocated).toList());
            final long currentBytes = median(after.stream().map(Sample::allocated).toList());
            row.put("legacy_median_allocated_bytes", previousBytes);
            row.put("current_median_allocated_bytes", currentBytes);
            row.put("allocation_reduction_percent", (previousBytes - currentBytes) * 100.0 / previousBytes);
            row.put("legacy_median_elapsed_nanos", median(before.stream().map(Sample::nanos).toList()));
            row.put("current_median_elapsed_nanos", median(after.stream().map(Sample::nanos).toList()));
            results.add(row);
        }
        Files.writeString(Path.of(args[1]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("java_runtime",
                        System.getProperty("java.runtime.version"), "legacy_commit", "5cebb58", "legacy_source_sha256",
                        hash(Files.readAllBytes(Path.of(args[0]))), "legacy_class_sha256", classHash(legacy.getClass()),
                        "current_class_sha256", classHash(current.getClass()), "probe_source_sha256",
                        hash(Files.readAllBytes(Path.of("benchmarks/tools/ProbeOriginalsAllocations.java"))), "scope",
                        "Eight warmups then five alternating-order samples; main-thread Java allocated bytes only, excluding input construction/validation; no retained/native/peak memory or production latency claim",
                        "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Summarizes the fixed five samples in Java without selecting or dropping an observation.
     * @param values fixed odd sample population
     * @return middle sorted sample
     */
    private static long median(final List<Long> values) {
        return values.stream().sorted().toList().get(values.size() / 2);
    }

    /**
     * Measures one complete public-interface call, validating output outside the measured interval.
     * @param bean explicit main-thread allocation counter
     * @param codec old or current codec service
     * @param source borrowed fixed owned originals
     * @param expected deterministic payload for this implementation
     * @return elapsed and allocated values without retaining the encoded payload
     */
    private static Sample sample(
            final ThreadMXBean bean, final ElementJson codec, final List<Element> source, final String expected) {
        final long thread = Thread.currentThread().threadId();
        final long startBytes = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
        final String output = codec.encodeOriginals(source);
        final long elapsed = System.nanoTime() - start, allocated = bean.getThreadAllocatedBytes(thread) - startBytes;
        require(allocated >= 0 && output.equals(expected), "Counter or measured output changed");
        sink = output.hashCode();
        return new Sample(allocated, elapsed);
    }

    /**
     * Decodes only bounded owned fixture output for exact independent wire validation.
     * @param payload encoded controlled output
     * @return decompressed UTF8 JSON
     * @throws Exception if controlled decompression fails
     */
    private static byte[] inflated(final String payload) throws Exception {
        try (final var stream =
                        new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(payload)))) {
            return stream.readAllBytes();
        }
    }

    /**
     * Pins the actual loaded implementation rather than assuming a matching file name implies matching code.
     * @param type loaded old or current codec class
     * @return exact loaded class SHA256
     * @throws Exception if the class resource cannot be read
     */
    private static String classHash(final Class<?> type) throws Exception {
        try (final var stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            require(stream != null, "Loaded class bytes unavailable");
            return hash(stream.readAllBytes());
        }
    }

    /**
     * Computes an exact source, bytecode or owned JSON identity.
     * @param bytes complete fixed byte sequence
     * @return lowercase SHA256
     * @throws Exception if SHA256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects invalid counter/identity/semantic evidence instead of dropping a fixture.
     * @param condition expected invariant
     * @param message source-safe failure description
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
