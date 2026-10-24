package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Captures unchanged default PDF behavior and independent Poppler source-raster metadata, without OCR providers. */
public final class CapturePdfDefaults {
    /** Prevents utility construction. */
    private CapturePdfDefaults() {
    }

    /**
     * Processes every frozen whole document with the default native partitioner and retains typed failures.
     * Raw captures contain publisher text and stay local; public evidence is prepared separately on the JVM.
     * Optional payload capture requires a packaged PDF service JAR so the runtime receipt binds its exact bytes.
     * @param args frozen manifest, verified PDF cache, NEW raw output and installed pdfimages executable;
     *             optional literal payloads enables normalized PNGs with otherwise identical native defaults
     * @throws Exception if source identities, bounded metadata collection or new-output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 4 && args.length != 5 || args.length == 5 && !args[4].equals("payloads")) {
            throw new IllegalArgumentException(
                    "Expected manifest, sources, NEW output, installed pdfimages and optional payloads");
        }
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var output = Files.createDirectory(Path.of(args[2]));
        final var defaults = PdfOptions.defaults();
        final var options =
                args.length == 5
                        ? new PdfOptions(defaults.maxPages(), defaults.maxGlyphsPerPage(),
                                  defaults.maxOperatorsPerPage(), defaults.maxImagePixels(), defaults.maxImageBytes(),
                                  true, defaults.tableLimits(), defaults.tableDetection(), defaults.textLayout(),
                                  defaults.includeTableCellCoordinates(), defaults.tableContinuation(),
                                  defaults.unicodeNormalization(), defaults.textRendering())
                        : defaults;
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
        final var runtime = new LinkedHashMap<String, Object>();
        runtime.put("java", Runtime.version().toString());
        if (options.includeImageBytes()) {
            runtime.put("include_image_bytes", true);
            runtime.put("pdf_jar_sha256",
                    hash(Files.readAllBytes(Path.of(
                            PdfPartitioner.class.getProtectionDomain().getCodeSource().getLocation().toURI()))));
        }
        runtime.put("manifest_sha256", hash(Files.readAllBytes(Path.of(args[0]))));
        runtime.put("pdfimages_executable_sha256", hash(Files.readAllBytes(Path.of(args[3]))));
        write(output.resolve("runtime.json"), runtime, mapper);
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            final var input = Path.of(args[1], selected.get("pdf").asString());
            final byte[] bytes = Files.readAllBytes(input);
            if (!id.matches("[a-z0-9_-]+") || bytes.length != selected.get("bytes").asLong()
                    || !hash(bytes).equals(selected.get("sha256").asString())) {
                throw new IllegalArgumentException("Frozen source differs");
            }
            final var capture = new LinkedHashMap<String, Object>();
            capture.put("source_sha256", hash(bytes));
            try {
                final var result = processor.partition(DocumentSource.bytes(bytes, selected.get("pdf").asString()));
                capture.put("outcome", "SUCCESS");
                capture.put("elements", result.elements());
                capture.put("warnings", result.warnings().stream().map(warning -> warning.code().name()).toList());
            } catch (final PartitionException failure) {
                capture.put("outcome", failure.code().name());
                capture.put("elements", List.of());
                capture.put("warnings", List.of());
            }
            write(output.resolve(id + ".json"), capture, mapper);
            final String listing = listing(Path.of(args[3]), input);
            Files.writeString(output.resolve(id + "-pdfimages.txt"), listing, StandardOpenOption.CREATE_NEW);
            final var images = new ArrayList<Map<String, Object>>();
            for (final String line : listing.lines().toList()) {
                final String[] fields = line.strip().split("\\s+");
                if (fields.length >= 5 && fields[0].matches("[0-9]+") && fields[1].matches("[0-9]+")) {
                    images.add(Map.of("page", Integer.parseInt(fields[0]), "ordinal", Integer.parseInt(fields[1]),
                            "kind", fields[2], "width", Integer.parseInt(fields[3]), "height",
                            Integer.parseInt(fields[4])));
                }
            }
            write(output.resolve(id + "-images.json"),
                    Map.of("images", images, "listing_sha256",
                            hash(listing.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    mapper);
            System.out.printf("Default PDF complete: %s outcome=%s source_raster_entries=%d%n", id,
                    capture.get("outcome"), images.size());
        }
    }

    /**
     * Drains bounded external metadata using a virtual thread and bounds the process independently.
     * Native image listing is independent of PDFBox extraction; no document text is interpreted here.
     * @param executable explicitly supplied installed Poppler tool
     * @param input original whole PDF
     * @return complete metadata listing
     * @throws Exception if process, output or cancellation bounds fail
     */
    private static String listing(final Path executable, final Path input) throws Exception {
        final var process =
                new ProcessBuilder(executable.toString(), "-list", input.toString()).redirectErrorStream(true).start();
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var read = executor.submit(() -> {
                try (final var stream = process.getInputStream()) {
                    final byte[] bytes = stream.readNBytes(1_000_001);
                    if (bytes.length > 1_000_000) {
                        throw new IllegalArgumentException("Image reference metadata exceeds limit");
                    }
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            });
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    throw new IllegalArgumentException("Image reference process failed or exceeded timeout");
                }
                return read.get(5, TimeUnit.SECONDS);
            } finally {
                process.destroyForcibly();
                process.getInputStream().close();
                read.cancel(true);
            }
        }
    }

    /**
     * Writes one local capture without replacing any earlier execution.
     * @param path NEW output path
     * @param value capture/identity data
     * @param mapper local JSON codec
     * @throws Exception if serialization or fresh writing fails
     */
    private static void write(final Path path, final Object value, final JsonMapper mapper) throws Exception {
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Computes the byte identity of supplied source/tool data.
     * @param bytes original bytes
     * @return lowercase SHA256
     * @throws Exception if mandatory JDK hash support is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
