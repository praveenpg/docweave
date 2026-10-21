package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
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
import java.util.stream.Collectors;

/**
 * One isolated sequential native-text worker; source reading, output hashing and serialization are outside API
 * timings.
 */
public final class BenchmarkPdfNative {
    /** Prevents utility construction. */
    private BenchmarkPdfNative() {
    }

    /**
     * Prepared immutable input, independent of reference labels or extraction output.
     * @param id fixed source identity
     * @param source reusable original byte source
     * @param expectedText exact separately captured public text identity
     */
    private record Input(String id, DocumentSource source, String expectedText) {}

    /**
     * Records setup, first-pass, warmup and every measured API sample without selecting the fastest observations.
     * The parent measures whole-process wall/RSS through process exit; this worker never invents a per-call RSS.
     * @param args frozen performance manifest, verified original source cache and NEW worker output
     * @throws Exception if input/output identities or new-report requirements fail
     */
    public static void main(final String[] args) throws Exception {
        final long started = System.nanoTime();
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected manifest, source cache and NEW worker output");
        }
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var inputs = new ArrayList<Input>();
        for (final var selected : manifest.get("cases")) {
            final byte[] bytes = Files.readAllBytes(Path.of(args[1], selected.get("pdf").asString()));
            if (bytes.length != selected.get("bytes").asLong()
                    || !hash(bytes).equals(selected.get("sha256").asString())) {
                throw new IllegalArgumentException("Frozen performance source differs");
            }
            inputs.add(new Input(selected.get("id").asString(),
                    DocumentSource.bytes(bytes, selected.get("pdf").asString()),
                    selected.get("native_public_text_sha256").asString()));
        }
        final var processor =
                DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults()))));
        final long setup = System.nanoTime() - started;
        final var samples = new ArrayList<Map<String, Object>>();
        final int warmups = manifest.get("warmup_passes").asInt();
        final int measured = manifest.get("measured_passes").asInt();
        for (int pass = 0; pass < 1 + warmups + measured; pass++) {
            final String phase = pass == 0 ? "FIRST_PASS" : pass <= warmups ? "WARMUP" : "MEASURED";
            for (final var input : inputs) {
                final long begin = System.nanoTime();
                final var result = processor.partition(input.source());
                final long elapsed = System.nanoTime() - begin;
                final String text = result.elements()
                                            .stream()
                                            .filter(element -> element.type() != ElementType.Image)
                                            .map(element -> element.text())
                                            .collect(Collectors.joining("\n"));
                final String identity = hash(text.getBytes(StandardCharsets.UTF_8));
                if (!identity.equals(input.expectedText())) {
                    throw new IllegalArgumentException(
                            "Measured native public output changed from its frozen baseline");
                }
                samples.add(Map.of("id", input.id(), "phase", phase, "pass", pass, "elapsed_ns", elapsed, "elements",
                        result.elements().size(), "text_sha256", identity));
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("java", Runtime.version().toString());
        report.put("setup_ns", setup);
        report.put("available_processors", Runtime.getRuntime().availableProcessors());
        report.put("maximum_heap_bytes", Runtime.getRuntime().maxMemory());
        report.put("cpu_affinity", Files.readAllLines(Path.of("/proc/self/status"))
                                           .stream()
                                           .filter(line -> line.startsWith("Cpus_allowed_list:"))
                                           .findFirst()
                                           .orElse("UNAVAILABLE"));
        report.put("samples", samples);
        Files.writeString(Path.of(args[2]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Hashes exact bytes without candidate repair or source-text emission.
     * @param bytes original source or complete public text bytes
     * @return lowercase SHA256
     * @throws Exception if mandatory JDK hash support is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
