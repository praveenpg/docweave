package dev.docweave.validation;

import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

/** Owned deterministic payload amplification probe; reports actual serialized bytes without retaining all JSON. */
public final class ProbeSplitSourceProvenance {
    /** Prevents utility construction. */
    private ProbeSplitSourceProvenance() {
    }

    /**
     * Runs narrative and structured-table sources carrying the same valid incompressible PNG payload.
     * Does not assume the omission policy, allowing identical source/tool bytes on baseline and candidate runtimes.
     * Text/HTML limits, source immutability and retained provenance restoration remain mandatory.
     * @param args one fresh numeric report path; or baseline, candidate and fresh verification report
     * @throws Exception if generation, extraction invariants, serialization or output writing fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 3) {
            verify(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
            return;
        }
        require(args.length == 1, "Expected fresh report");
        final byte[] png = png();
        final String payload = Base64.getEncoder().encodeToString(png);
        require(payload.length() > 1_000_000, "Owned payload must exceed the repeated-source threshold");
        final String html = "<table>"
                            + IntStream.range(0, 12)
                                      .mapToObj(i -> "<tr><td>row " + i + "</td><td>value " + i + "</td></tr>")
                                      .collect(Collectors.joining())
                            + "</table>";
        final var inputs = List.of(new Element("narrative", ElementType.NarrativeText, "source ".repeat(120),
                                           Map.of("image_base64", payload, "image_mime_type", "image/png")),
                new Element("table", ElementType.Table, "raw table text",
                        Map.of("text_as_html", html, "image_base64", payload, "image_mime_type", "image/png")));
        final var codec = ElementJson.create();
        final var results = new ArrayList<Map<String, Object>>();
        final var options = ChunkOptions.builder().maxCharacters(120).build();
        for (final var source : inputs) {
            final var chunks = Chunker.create().basic(List.of(source), options);
            require(chunks.size() > 1, "Actual multiple fragments required");
            long provenance = 0, wire = 2;
            int omitted = 0;
            for (int index = 0; index < chunks.size(); index++) {
                final var chunk = chunks.get(index);
                require(chunk.text().codePointCount(0, chunk.text().length()) <= 120, "Text budget");
                if (chunk.metadata().get("text_as_html") instanceof final String fragment) {
                    require(fragment.codePointCount(0, fragment.length()) <= 120, "HTML budget");
                }
                if (chunk.metadata().get("orig_elements") instanceof final String encoded) {
                    provenance += encoded.length();
                    if (index == 0) {
                        require(codec.decodeOriginals(encoded).equals(List.of(source)), "Restored source");
                    }
                }
                if (chunk.metadata().containsKey("orig_elements_omitted")) {
                    omitted++;
                }
                // Serialize one-element arrays; replace their brackets with the enclosing array/comma overhead.
                wire += codec.write(List.of(chunk)).getBytes(StandardCharsets.UTF_8).length - 2L + (index == 0 ? 0 : 1);
            }
            require(source.metadata().get("image_base64").equals(payload), "Borrowed source metadata");
            final String text = chunks.stream().map(Element::text).collect(Collectors.joining("\n"));
            final String markup = chunks.stream()
                                          .map(e -> String.valueOf(e.metadata().getOrDefault("text_as_html", "")))
                                          .collect(Collectors.joining("\n"));
            results.add(Map.of("case", source.elementId(), "chunks", chunks.size(), "source_text_sha256",
                    hash(source.text().getBytes(StandardCharsets.UTF_8)), "chunk_text_sha256",
                    hash(text.getBytes(StandardCharsets.UTF_8)), "chunk_html_sha256",
                    hash(markup.getBytes(StandardCharsets.UTF_8)), "original_payload_base64_characters", provenance,
                    "explicitly_omitted_chunks", omitted, "serialized_json_utf8_bytes", wire));
        }
        Files.writeString(Path.of(args[0]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "Owned deterministic512x512RGBPNG, whole-source payload amplification; no peak heap or corpus accuracy claim",
                        "java", Runtime.version().toString(), "png_sha256", hash(png), "source_image_base64_characters",
                        payload.length(), "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Verifies complete before/after probe populations and unchanged source/content identities.
     * @param baseline original runtime observations
     * @param candidate guarded runtime observations
     * @param output fresh numeric verification report
     * @throws Exception if observations drift, omit cases or do not satisfy the safeguard
     */
    private static void verify(final Path baseline, final Path candidate, final Path output) throws Exception {
        final var mapper = JsonMapper.builder().build();
        final var before = mapper.readTree(Files.readAllBytes(baseline));
        final var after = mapper.readTree(Files.readAllBytes(candidate));
        require(before.get("png_sha256").equals(after.get("png_sha256"))
                        && before.get("source_image_base64_characters")
                                .equals(after.get("source_image_base64_characters")),
                "Source identity");
        require(before.get("results").size() == 2 && after.get("results").size() == 2, "Complete case population");
        final var deltas = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 2; index++) {
            final var a = before.get("results").get(index);
            final var b = after.get("results").get(index);
            require(a.get("case").asString().equals(index == 0 ? "narrative" : "table"), "Unique ordered cases");
            for (final String field :
                    List.of("case", "chunks", "source_text_sha256", "chunk_text_sha256", "chunk_html_sha256")) {
                require(a.get(field).equals(b.get(field)), "Unchanged source/chunk content");
            }
            require(a.get("original_payload_base64_characters").asLong() > 0
                            && b.get("original_payload_base64_characters").asLong() == 0
                            && a.get("explicitly_omitted_chunks").asInt() == 0
                            && b.get("explicitly_omitted_chunks").asInt() == b.get("chunks").asInt()
                            && b.get("serialized_json_utf8_bytes").asLong()
                                       < a.get("serialized_json_utf8_bytes").asLong(),
                    "Explicit omission and reduced serialized output");
            deltas.add(Map.of("case", a.get("case").asString(), "chunks", a.get("chunks").asInt(),
                    "serialized_json_utf8_bytes_before", a.get("serialized_json_utf8_bytes").asLong(),
                    "serialized_json_utf8_bytes_after", b.get("serialized_json_utf8_bytes").asLong(),
                    "unchanged_source_text_and_chunk_text_html", true));
        }
        Files.writeString(output,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "Owned payload amplification guard, explicit provenance omission; not corpus accuracy or peak heap",
                        "source_png_sha256", before.get("png_sha256").asString(), "results", deltas))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Generates a valid deterministic source image with high-entropy RGB values and releases its raster.
     * @return encoded PNG bytes
     * @throws Exception if the PNG encoder is unavailable or fails
     */
    private static byte[] png() throws Exception {
        final var image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        try (final var bytes = new ByteArrayOutputStream()) {
            final var random = new Random(901);
            for (int y = 0; y < 512; y++) {
                for (int x = 0; x < 512; x++) {
                    image.setRGB(x, y, random.nextInt(0x1000000));
                }
            }
            require(ImageIO.write(image, "png", bytes), "PNG encoder");
            return bytes.toByteArray();
        } finally {
            image.flush();
        }
    }

    /**
     * Computes a content identity without redistributing raw source payloads.
     * @param bytes complete owned content
     * @return lowercase SHA-256
     * @throws Exception if the required digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects changed source or fragment invariants rather than repairing the probe output.
     * @param valid required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean valid, final String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }
}
