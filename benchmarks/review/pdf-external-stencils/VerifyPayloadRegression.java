import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/** Complete-population original-source payload regression with independent stencil/ordinary raster inventories. */
public final class VerifyPayloadRegression {
    /** Prevents utility construction. */
    private VerifyPayloadRegression() {
    }

    /**
     * Verifies every PNG and complete output; only the two declared stencil labels may differ from baseline.
     * Independent inventory misses remain counted and do not become discarded reference entries.
     * @param args frozen manifest, original cache, baseline captures, candidate captures and fresh numeric report
     * @throws Exception if identities, fixed populations, PNG decoding or output invariants fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 5 && !Files.exists(Path.of(args[4])));
        final var mapper = JsonMapper.builder().build();
        final var manifestPath = Path.of(args[0]);
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        final var selectionPath = manifestPath.getParent().resolve("selection.json");
        require(hash(selectionPath).equals(manifest.get("selection_sha256").asString())
                && hash(manifestPath.getParent().resolve("source-inventory-java21.json"))
                        .equals(manifest.get("source_inventory_sha256").asString()));
        final var selection = mapper.readTree(Files.readAllBytes(selectionPath));
        require(manifest.get("capture_mode").asString().equals("payloads") && manifest.get("cases").size() == 3);
        require(hash(Path.of(manifest.get("capture_tool").asString()))
                        .equals(manifest.get("capture_tool_sha256").asString()));
        for (final String version : List.of("baseline", "candidate")) {
            final var runtime = mapper.readTree(
                    Files.readAllBytes(Path.of(args[version.equals("baseline") ? 2 : 3], "runtime.json")));
            require(runtime.get("java").asString().startsWith("21.") && runtime.get("include_image_bytes").asBoolean()
                    && runtime.get("manifest_sha256").asString().equals(hash(manifestPath))
                    && runtime.get("pdf_jar_sha256").equals(manifest.get(version + "_pdf_jar_sha256"))
                    && runtime.get("pdfimages_executable_sha256").equals(manifest.get("pdfimages_sha256")));
        }
        final var results = new ArrayList<Map<String, Object>>();
        final var seen = new HashSet<String>();
        boolean allEqual = true;
        int allReferences = 0;
        int allObserved = 0;
        int allPatternStencils = 0;
        int allMissing = 0;
        int allExtra = 0;
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            require(seen.add(id));
            final var source = Path.of(args[1], selected.get("pdf").asString());
            final var published =
                    selection.get("cases")
                            .valueStream()
                            .filter(item -> item.get("id").asString().toLowerCase(java.util.Locale.ROOT).equals(id))
                            .findFirst()
                            .orElseThrow();
            require(published.get("outputFileName").equals(selected.get("pdf"))
                    && HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-512").digest(Files.readAllBytes(source)))
                            .equals(published.get("sha512").asString()));
            require(Files.size(source) == selected.get("bytes").asLong()
                    && hash(source).equals(selected.get("sha256").asString()));
            final var baselinePath = Path.of(args[2], id + ".json");
            final var candidatePath = Path.of(args[3], id + ".json");
            require(Files.size(baselinePath) <= 64 * 1024 * 1024 && Files.size(candidatePath) <= 64 * 1024 * 1024);
            final var baseline = mapper.readTree(Files.readAllBytes(baselinePath));
            final var candidate = mapper.readTree(Files.readAllBytes(candidatePath));
            require(baseline.get("source_sha256").equals(selected.get("sha256"))
                    && candidate.get("source_sha256").equals(selected.get("sha256")));
            final ObjectNode comparable = (ObjectNode) candidate.deepCopy();
            final var predictions = new TreeMap<String, Integer>();
            final var payloads = new ArrayList<Map<String, Object>>();
            int patterns = 0;
            for (final var element : comparable.get("elements")) {
                if (!element.get("type").asString().equals("Image")) {
                    continue;
                }
                final ObjectNode metadata = (ObjectNode) element.get("metadata");
                final boolean stencil = metadata.has("image_stencil");
                if (stencil) {
                    require(metadata.get("image_stencil").asBoolean()
                            && metadata.get("image_pixel_policy").asString().equals("stencil_mask_samples_v1"));
                    patterns++;
                    metadata.remove("image_stencil");
                    metadata.remove("image_pixel_policy");
                } else {
                    require(!metadata.has("image_pixel_policy"));
                }
                final int width = metadata.get("image_width").asInt();
                final int height = metadata.get("image_height").asInt();
                final int page = metadata.get("page_number").asInt();
                require(width > 0 && height > 0 && (long) width * height <= 16_000_000 && page > 0
                        && page <= selected.get("pages").asInt()
                        && metadata.get("image_mime_type").asString().equals("image/png"));
                predictions.merge(
                        page + ":" + (stencil ? "stencil" : "image") + ":" + width + "x" + height, 1, Integer::sum);
                final byte[] png = Base64.getDecoder().decode(metadata.get("image_base64").asString());
                final var pixels = ImageIO.read(new ByteArrayInputStream(png));
                require(pixels != null && pixels.getWidth() == width && pixels.getHeight() == height);
                pixels.flush();
                payloads.add(Map.of("page", page, "width", width, "height", height, "pattern_stencil_mask", stencil,
                        "png_sha256", digest(png)));
            }
            final boolean equal = comparable.equals(baseline);
            allEqual &= equal;
            final var inventory = mapper.readTree(Files.readAllBytes(Path.of(args[3], id + "-images.json")));
            require(inventory.equals(mapper.readTree(Files.readAllBytes(Path.of(args[2], id + "-images.json"))))
                    && inventory.get("listing_sha256").equals(selected.get("poppler_listing_sha256")));
            final var reference = new TreeMap<String, Integer>();
            for (final var image : inventory.get("images")) {
                reference.merge(image.get("page").asInt() + ":" + image.get("kind").asString() + ":"
                                        + image.get("width").asInt() + "x" + image.get("height").asInt(),
                        1, Integer::sum);
            }
            require(mapper.valueToTree(reference).equals(selected.get("poppler_source_bag")));
            final var missing = new TreeMap<String, Integer>();
            final var extra = new TreeMap<String, Integer>();
            reference.forEach((key, count) -> {
                final int absent = count - predictions.getOrDefault(key, 0);
                if (absent > 0) {
                    missing.put(key, absent);
                }
            });
            predictions.forEach((key, count) -> {
                final int excess = count - reference.getOrDefault(key, 0);
                if (excess > 0) {
                    extra.put(key, excess);
                }
            });
            final var row = new LinkedHashMap<String, Object>();
            row.put("case", id);
            row.put("outcome", candidate.get("outcome").asString());
            row.put("source_pages", selected.get("pages").asInt());
            row.put("full_public_output_and_png_bytes_equal_except_stencil_labels", equal);
            row.put("source_reference_bag", reference);
            row.put("observed_placement_bag", predictions);
            row.put("missing_reference_bag", missing);
            row.put("extra_placement_bag", extra);
            row.put("pattern_stencil_mask_labels", patterns);
            row.put("valid_png_payloads", payloads.size());
            row.put("payloads", payloads);
            row.put("baseline_capture_sha256", hash(baselinePath));
            row.put("candidate_capture_sha256", hash(candidatePath));
            results.add(row);
            allReferences += total(reference);
            allObserved += payloads.size();
            allPatternStencils += patterns;
            allMissing += total(missing);
            allExtra += total(extra);
        }
        require(allReferences == 187);
        Files.writeString(Path.of(args[4]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("runtime", Runtime.version().toString(), "manifest_sha256", hash(manifestPath),
                                "complete_cases", results.size(), "source_reference_occurrences", allReferences,
                                "valid_png_payloads", allObserved, "pattern_stencil_mask_labels", allPatternStencils,
                                "missing_reference_occurrences", allMissing, "extra_placement_occurrences", allExtra,
                                "all_public_output_equal_except_stencil_labels", allEqual, "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
        require(allEqual);
    }

    /**
     * Sums occurrence multiplicities without deduplicating repeated source placements.
     * @param bag complete indexed occurrence counts
     * @return full population count
     */
    private static int total(final Map<String, Integer> bag) {
        return bag.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Fingerprints a numeric or source identity input without including its contents in output.
     * @param path existing local file
     * @return lowercase SHA256
     * @throws Exception if bytes or the required digest cannot be read
     */
    private static String hash(final Path path) throws Exception {
        return digest(Files.readAllBytes(path));
    }

    /**
     * Computes exact encoded-byte identity, not a perceptual image similarity score.
     * @param bytes PNG, source or numeric capture bytes
     * @return lowercase SHA256
     * @throws Exception if SHA256 is unavailable
     */
    private static String digest(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects inconsistent inputs without repairing images, output or reference populations.
     * @param valid required invariant
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Original stencil payload regression failed");
        }
    }
}
