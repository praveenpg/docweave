package io.docweave.validation;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exports identical-byte lossless PDF containers using existing bounded JVM image controls, without OCR. */
public final class CaptureScanComparisonSources {
    /** Prevents utility construction. */
    private CaptureScanComparisonSources() {
    }

    /**
     * Verifies every source/reference pin before writing the final descriptor used by both libraries.
     * No reference text, crop or language-model choice enters container encoding.
     * @param args frozen native scan manifest, verified original cache and NEW output directory
     * @throws Exception if identities, encoding or new-output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected manifest, original cache and NEW output directory");
        }
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var output = Files.createDirectory(Path.of(args[2]));
        final var frames = new LinkedHashMap<String, Map<String, Object>>();
        for (final var source : manifest.get("cases")) {
            final String id = source.get("id").asString();
            if (!id.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("Unsupported frozen case identity");
            }
            for (final var input : source.get("inputs")) {
                final byte[] bytes = Files.readAllBytes(Path.of(args[1], input.get("filename").asString()));
                final String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (bytes.length != input.get("bytes").asLong() || !hash.equals(input.get("sha256").asString())) {
                    throw new IllegalArgumentException("Original source identity differs");
                }
            }
            frames.put(
                    id, PdfHistoricalTextCorpusTest.exportComparisonContainer(
                                Path.of(args[1], source.get("image").asString()), source, output.resolve(id + ".pdf")));
        }
        Files.writeString(output.resolve("frames.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(frames) + "\n",
                StandardOpenOption.CREATE_NEW);
    }
}
