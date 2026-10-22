package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Complete JVM image-placement and no-invented-caption controls on independently qualified original figures. */
public final class VerifySourceFigureImages {
    /** Prevents standalone utility construction. */
    private VerifySourceFigureImages() {
    }

    /**
     * Runs every qualified original under both factories and every text-rendering policy declared in the manifest.
     * Independent Poppler image inventory supplies expected placement count/dimensions, not pixel correctness.
     * Alternative/ActualText properties never supply caption strings to this verifier.
     * @param args qualified source manifest, original cache and NEW numeric report; optional --clip-bounds
     * @throws Exception if original identities, complete populations, fresh output or independent contracts fail
     */
    public static void main(final String[] args) throws Exception {
        require((args.length == 3 || args.length == 4 && args[3].equals("--clip-bounds"))
                        && !Files.exists(Path.of(args[2])),
                "Expected qualified sources, cache, NEW report and optional --clip-bounds");
        final boolean clipBounds = args.length == 4;
        final var mapper = JsonMapper.builder().build();
        final byte[] protocol = Files.readAllBytes(Path.of(args[0]));
        final var manifest = mapper.readTree(protocol);
        require(manifest.get("profiles").isArray() && !manifest.get("profiles").isEmpty(),
                "Explicit rendering profiles are required");
        final var renderings = new ArrayList<PdfOptions.TextRendering>();
        for (final var profile : manifest.get("profiles")) {
            final var rendering = PdfOptions.TextRendering.valueOf(profile.asString());
            require(!renderings.contains(rendering), "Duplicate rendering profile");
            renderings.add(rendering);
        }
        require(manifest.get("factories").size() == 2 && manifest.get("factories").get(0).asString().equals("NATIVE")
                        && manifest.get("factories").get(1).asString().equals("SOURCE_TAGGED"),
                "Expected both declared factory profiles in order");
        require(manifest.get("expected_case_profiles").asInt() == manifest.get("inputs").size() * 2 * renderings.size(),
                "Declared profile population differs from manifest schedule");
        final var rows = new ArrayList<Map<String, Object>>();
        for (final var input : manifest.get("inputs")) {
            final String filename = input.get("filename").asString();
            require(filename.matches("[0-9.a-z-]+\\.pdf"), "Unsafe original filename");
            final var path = Path.of(args[1], filename);
            require(Files.size(path) == input.get("bytes").asLong() && Files.size(path) <= 1024 * 1024,
                    "Original length changed");
            final var bytes = Files.readAllBytes(path);
            require(hash(bytes).equals(input.get("sha256").asString()), "Original source changed");
            for (final boolean tagged : List.of(false, true)) {
                for (final var rendering : renderings) {
                    final var row = new LinkedHashMap<String, Object>();
                    row.put("filename", filename);
                    row.put("source_sha256", hash(bytes));
                    row.put("factory", tagged ? "SOURCE_TAGGED" : "NATIVE");
                    row.put("text_rendering", rendering.name());
                    final var d = PdfOptions.defaults();
                    final var options = new PdfOptions(d.maxPages(), d.maxGlyphsPerPage(), d.maxOperatorsPerPage(),
                            d.maxImagePixels(), d.maxImageBytes(), true, d.tableLimits())
                                                .withTextRendering(rendering)
                                                .withImageClipBounds(clipBounds);
                    final var partitioner =
                            tagged ? PdfPartitioner.createWithTaggedTables(options) : PdfPartitioner.create(options);
                    try {
                        final var elements = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                                                     .partition(DocumentSource.bytes(bytes, filename))
                                                     .elements();
                        final var images =
                                elements.stream().filter(element -> element.type() == ElementType.Image).toList();
                        final long captions = elements.stream()
                                                      .filter(element -> element.type() == ElementType.FigureCaption)
                                                      .count();
                        boolean matches = images.size() == input.get("expected_images").asInt()
                                          && captions == input.get("expected_captions").asInt();
                        final var payloads = new ArrayList<Map<String, Object>>();
                        for (final var image : images) {
                            final var metadata = image.metadata();
                            require(metadata.containsKey("pdf_image_clip_bounds") == clipBounds,
                                    "Requested image clipping metadata selection differs");
                            final byte[] png = Base64.getDecoder().decode((String) metadata.get("image_base64"));
                            final var decoded = ImageIO.read(new ByteArrayInputStream(png));
                            require(decoded != null, "PNG payload did not decode");
                            try {
                                matches &= decoded.getWidth() == input.get("expected_width").asInt()
                                           && decoded.getHeight() == input.get("expected_height").asInt()
                                           && "image/png".equals(metadata.get("image_mime_type"))
                                           && ((Number) metadata.get("image_width")).intValue() == decoded.getWidth()
                                           && ((Number) metadata.get("image_height")).intValue() == decoded.getHeight()
                                           && image.text().isEmpty()
                                           && !metadata.containsKey("pdf_native_text_rendering_policy");
                                payloads.add(Map.of("width", decoded.getWidth(), "height", decoded.getHeight(),
                                        "png_sha256", hash(png)));
                            } finally {
                                decoded.flush();
                            }
                        }
                        row.put("outcome", "SUCCESS");
                        row.put("images", images.size());
                        row.put("captions", captions);
                        row.put("payloads", payloads);
                        row.put("matches_independent_inventory", matches);
                    } catch (final PartitionException failure) {
                        row.put("outcome", failure.code().name());
                        row.put("matches_independent_inventory", false);
                    }
                    rows.add(row);
                }
            }
        }
        require(rows.size() == manifest.get("expected_case_profiles").asInt(), "Incomplete qualified population");
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "Complete original source Figure image-count/dimension and no-invented-caption contracts; no pixel/color/semantic-caption accuracy claim",
                        "runtime", Runtime.version().toString(), "qualified_manifest_sha256", hash(protocol),
                        "image_clip_bounds_enabled", clipBounds, "results", rows))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
        require(rows.stream().allMatch(row -> Boolean.TRUE.equals(row.get("matches_independent_inventory"))),
                "Original Figure contract mismatch; complete numeric report retained");
    }

    /**
     * Hashes complete protocol/source/payload bytes without reporting content.
     * @param bytes exact evaluated evidence
     * @return lowercase SHA256
     * @throws Exception if digest initialization fails
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects altered originals or incomplete independent contracts.
     * @param condition required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
