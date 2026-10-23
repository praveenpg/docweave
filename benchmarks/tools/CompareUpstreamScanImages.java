package dev.docweave.validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
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
import javax.imageio.ImageIO;

/**
 * Compares actual pinned Unstructured {@code ocr_only} output on original scan images with its earlier output on
 * the shared lossless PDF containers, and establishes which pixels the original-image path recognized.
 *
 * <p>Pixel provenance is checked independently on the JVM: the original TIFF is decoded twice, once as stored
 * samples without colour management and once through ImageIO's default colour-managed conversion, and each hash is
 * compared with the array hash the upstream launcher recorded before partitioning. Text uses the unchanged public
 * comparison scorer with the full PAGE denominator. Numeric-only reference lines, such as printed page numbers, are
 * reported as covered when an emitted element box holds at least half of the line box, and emitted elements that
 * overlap no reference line are listed with their category and box; element text is never returned. The
 * original-image and container captures are separate protocols and are never pooled.
 */
public final class CompareUpstreamScanImages {
    /** Prevents construction of this command-line tool. */
    private CompareUpstreamScanImages() {
    }

    /**
     * Writes one comparison row per frozen source/model case.
     *
     * @param args original-image manifest, original source cache, original-image capture directory, existing
     *         container capture directory and a fresh report
     * @throws Exception if an identity, decode or scoring requirement fails
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 5, "Expected manifest, originals, image captures, container captures and report");
        final var mapper = JsonMapper.builder().build();
        final var manifestPath = Path.of(args[0]);
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        final var originals = Path.of(args[1]);
        final var images = Path.of(args[2]);
        final var containers = Path.of(args[3]);
        final var runtime = mapper.readTree(Files.readAllBytes(images.resolve("runtime.json")));
        require(runtime.get("manifest_sha256").asString().equals(hash(Files.readAllBytes(manifestPath))),
                "Executed manifest differs");
        require(runtime.get("packages")
                        .get("unstructured")
                        .asString()
                        .equals(manifest.get("unstructured_version").asString()),
                "Executed runtime differs");
        final var containerManifest = mapper.readTree(
                Files.readAllBytes(Path.of("benchmarks/review/pdf-interim-scans/upstream-manifest.json")));
        final var nativeManifest = mapper.readTree(
                Files.readAllBytes(Path.of("benchmarks/review/pdf-interim-scans/native-manifest.json")));
        final var decodes = new LinkedHashMap<String, Map<String, Object>>();
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            final String sourceId = selected.get("source_id").asString();
            final var source = originals.resolve(selected.get("image").asString());
            final byte[] imageBytes = Files.readAllBytes(source);
            require(imageBytes.length == selected.get("bytes").asLong()
                            && hash(imageBytes).equals(selected.get("sha256").asString()),
                    "Original scan image differs");
            final byte[] xml = Files.readAllBytes(originals.resolve(reference(nativeManifest, sourceId)));
            final var capturePath = images.resolve(id + ".json");
            final var capture = mapper.readTree(Files.readAllBytes(capturePath));
            for (final String identity : List.of("model_sha256", "effective_upstream_language")) {
                require(capture.get(identity).equals(selected.get(identity)), "Capture identity differs");
            }
            require(capture.get("source_sha256").asString().equals(selected.get("sha256").asString()),
                    "Capture source differs");
            final var containerPath = containers.resolve(id + ".json");
            final var container = mapper.readTree(Files.readAllBytes(containerPath));
            require(container.get("source_sha256").equals(containerCase(containerManifest, id).get("sha256")),
                    "Container capture differs");
            final var decode = decodes.computeIfAbsent(sourceId, key -> decode(source, capture.get("decode")));
            final var lines = PdfHistoricalTextCorpusTest.referenceLineBoxes(xml);
            final var numeric = lines.stream().filter(line -> (Boolean) line.get("numeric")).toList();
            final var row = new LinkedHashMap<String, Object>();
            row.put("id", id);
            row.put("source_id", sourceId);
            row.put("language", selected.get("language").asString());
            row.put("effective_upstream_language", selected.get("effective_upstream_language").asString());
            row.put("image_capture_sha256", hash(Files.readAllBytes(capturePath)));
            row.put("container_capture_sha256", hash(Files.readAllBytes(containerPath)));
            row.put("decode", decode);
            for (final var profile : List.of(Map.entry("upstream_original_image", capture),
                         Map.entry("upstream_shared_container", container))) {
                final var observed = profile.getValue();
                final var metrics = new LinkedHashMap<String, Object>(
                        PdfHistoricalTextCorpusTest.scorePublicComparisonText(xml, publicText(observed)));
                metrics.put("outcome", observed.get("outcome").asString());
                metrics.put("elements", observed.get("elements").size());
                metrics.put("categories", categories(observed));
                metrics.put("numeric_reference_lines", coverage(numeric, observed, xmlSize(decode)));
                metrics.put("unreferenced_elements", unreferenced(lines, observed, xmlSize(decode)));
                row.put(profile.getKey(), metrics);
            }
            results.add(row);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("manifest_sha256", hash(Files.readAllBytes(manifestPath)));
        report.put("runtime_sha256", hash(Files.readAllBytes(images.resolve("runtime.json"))));
        report.put("java_version", System.getProperty("java.version"));
        report.put("results", results);
        Files.writeString(Path.of(args[4]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Decodes the original twice and compares both interpretations with the upstream array identity.
     *
     * @param source original image path
     * @param recorded upstream launcher decode record
     * @return decode provenance with both JVM hashes and their agreement with upstream
     */
    private static Map<String, Object> decode(final Path source, final JsonNode recorded) {
        try (final var input = ImageIO.createImageInputStream(source.toFile())) {
            final var readers = ImageIO.getImageReaders(input);
            require(readers.hasNext(), "No image reader");
            final var reader = readers.next();
            try {
                reader.setInput(input);
                final int width = reader.getWidth(0);
                final int height = reader.getHeight(0);
                require((long) width * height <= 16_000_000L, "Image exceeds the diagnostic pixel budget");
                // The decoded raster holds the file's stored samples; only getRGB applies the colour model.
                final BufferedImage decoded = reader.read(0);
                final String stored = storedSamples(decoded.getRaster());
                final String converted = decoded.getRaster().getNumBands() >= 3 ? rgb(decoded) : null;
                final var result = new LinkedHashMap<String, Object>();
                result.put("width", width);
                result.put("height", height);
                result.put("embedded_icc_profile", recorded.get("icc_profile_sha256").isString());
                result.put("upstream_array_sha256", recorded.get("array_sha256").asString());
                result.put("jvm_stored_samples_sha256", stored);
                result.put("jvm_colour_managed_rgb_sha256", converted);
                result.put("upstream_equals_stored_samples", stored.equals(recorded.get("array_sha256").asString()));
                result.put("upstream_equals_colour_managed", recorded.get("array_sha256").asString().equals(converted));
                return result;
            } finally {
                reader.dispose();
            }
        } catch (final Exception failure) {
            throw new IllegalStateException("Original decode failed", failure);
        }
    }

    /**
     * Hashes 8-bit stored samples in pixel-interleaved band order, without any colour conversion.
     *
     * @param raster raw raster as stored in the file
     * @return SHA-256 of the samples, one byte each
     * @throws Exception if SHA-256 is unavailable
     */
    private static String storedSamples(final Raster raster) throws Exception {
        final var digest = MessageDigest.getInstance("SHA-256");
        final int bands = raster.getNumBands();
        final var row = new int[raster.getWidth() * bands];
        final var bytes = new byte[row.length];
        for (int y = 0; y < raster.getHeight(); y++) {
            raster.getPixels(raster.getMinX(), raster.getMinY() + y, raster.getWidth(), 1, row);
            for (int index = 0; index < row.length; index++) {
                bytes[index] = (byte) row[index];
            }
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Hashes ImageIO's default colour-managed sRGB values as interleaved RGB bytes.
     *
     * @param image decoded image
     * @return SHA-256 of the RGB values
     * @throws Exception if SHA-256 is unavailable
     */
    private static String rgb(final BufferedImage image) throws Exception {
        final var digest = MessageDigest.getInstance("SHA-256");
        final var row = new int[image.getWidth()];
        final var bytes = new byte[row.length * 3];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, row.length, 1, row, 0, row.length);
            for (int x = 0; x < row.length; x++) {
                bytes[x * 3] = (byte) (row[x] >> 16);
                bytes[x * 3 + 1] = (byte) (row[x] >> 8);
                bytes[x * 3 + 2] = (byte) row[x];
            }
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Reports, for each numeric-only reference line, whether an emitted element box holds half of its area.
     *
     * @param numeric numeric-only reference lines in source pixels
     * @param capture raw upstream capture
     * @param size original image width and height in pixels
     * @return per-line coverage with the covering element's position and category
     */
    private static List<Map<String, Object>> coverage(
            final List<Map<String, Object>> numeric, final JsonNode capture, final double[] size) {
        final var result = new ArrayList<Map<String, Object>>();
        for (final var line : numeric) {
            @SuppressWarnings("unchecked") final var box = (List<Double>) line.get("box");
            final double area = (box.get(2) - box.get(0)) * (box.get(3) - box.get(1));
            final var entry = new LinkedHashMap<String, Object>();
            entry.put("reference_index", line.get("reference_index"));
            entry.put("box", box);
            entry.put("covered", false);
            int index = 0;
            for (final var element : capture.get("elements")) {
                if (overlap(bounds(element, size), box) >= area / 2) {
                    entry.put("covered", true);
                    entry.put("element_index", index);
                    entry.put("element_type", element.get("type").asString());
                    break;
                }
                index++;
            }
            result.add(entry);
        }
        return result;
    }

    /**
     * Lists emitted elements whose box overlaps no reference line box, such as stamps, rulers or colour targets.
     *
     * @param lines every reference line box
     * @param capture raw upstream capture
     * @param size original image width and height in pixels
     * @return element positions, categories and boxes in source pixels, rounded to whole pixels
     */
    private static List<Map<String, Object>> unreferenced(
            final List<Map<String, Object>> lines, final JsonNode capture, final double[] size) {
        final var result = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (final var element : capture.get("elements")) {
            final var box = bounds(element, size);
            @SuppressWarnings("unchecked")
            final boolean referenced =
                    lines.stream().anyMatch(line -> overlap(box, (List<Double>) line.get("box")) > 0);
            if (!referenced) {
                result.add(Map.of("element_index", index, "element_type", element.get("type").asString(), "box",
                        box.stream().map(Math::round).toList()));
            }
            index++;
        }
        return result;
    }

    /**
     * Converts one element's pixel-space points to an enclosing box in original source pixels.
     *
     * @param element raw upstream element
     * @param size original image width and height in pixels
     * @return left, top, right and bottom
     */
    private static List<Double> bounds(final JsonNode element, final double[] size) {
        final var coordinates = element.get("metadata").get("coordinates");
        final double scaleX = size[0] / coordinates.get("layout_width").asDouble();
        final double scaleY = size[1] / coordinates.get("layout_height").asDouble();
        double left = Double.MAX_VALUE;
        double top = Double.MAX_VALUE;
        double right = -Double.MAX_VALUE;
        double bottom = -Double.MAX_VALUE;
        for (final var point : coordinates.get("points")) {
            left = Math.min(left, point.get(0).asDouble() * scaleX);
            right = Math.max(right, point.get(0).asDouble() * scaleX);
            top = Math.min(top, point.get(1).asDouble() * scaleY);
            bottom = Math.max(bottom, point.get(1).asDouble() * scaleY);
        }
        return List.of(left, top, right, bottom);
    }

    /**
     * Returns the intersection area of two boxes given as left, top, right and bottom.
     *
     * @param first first box
     * @param second second box
     * @return intersection area, zero when disjoint
     */
    private static double overlap(final List<Double> first, final List<Double> second) {
        return Math.max(0, Math.min(first.get(2), second.get(2)) - Math.max(first.get(0), second.get(0)))
                * Math.max(0, Math.min(first.get(3), second.get(3)) - Math.max(first.get(1), second.get(1)));
    }

    /**
     * Counts emitted categories in output order of first appearance.
     *
     * @param capture raw upstream capture
     * @return category counts
     */
    private static Map<String, Long> categories(final JsonNode capture) {
        return capture.get("elements")
                .valueStream()
                .collect(Collectors.groupingBy(
                        element -> element.get("type").asString(), LinkedHashMap::new, Collectors.counting()));
    }

    /**
     * Joins all public text in original output order, as the existing paired-scan comparison does.
     *
     * @param capture raw upstream capture
     * @return public text separated by single ASCII spaces
     */
    private static String publicText(final JsonNode capture) {
        require(capture.get("outcome").asString().equals("SUCCESS") || capture.get("elements").isEmpty(),
                "Failed capture contains partial text");
        return capture.get("elements")
                .valueStream()
                .filter(element -> !element.get("type").asString().equals("Image"))
                .map(element -> element.get("text").asString())
                .collect(Collectors.joining(" "));
    }

    /**
     * Returns the original image size recorded by the JVM decode.
     *
     * @param decode decode provenance
     * @return width and height in pixels
     */
    private static double[] xmlSize(final Map<String, Object> decode) {
        return new double[] {
                ((Number) decode.get("width")).doubleValue(), ((Number) decode.get("height")).doubleValue()};
    }

    /**
     * Finds the PAGE reference file name of one source in the native manifest.
     *
     * @param nativeManifest frozen native scan manifest
     * @param sourceId source identifier
     * @return reference file name
     */
    private static String reference(final JsonNode nativeManifest, final String sourceId) {
        for (final var candidate : nativeManifest.get("cases")) {
            if (candidate.get("id").asString().equals(sourceId)) {
                return candidate.get("xml").asString();
            }
        }
        throw new IllegalStateException("Unknown source");
    }

    /**
     * Finds one case of the existing container manifest.
     *
     * @param containerManifest frozen container manifest
     * @param id case identifier
     * @return the container case
     */
    private static JsonNode containerCase(final JsonNode containerManifest, final String id) {
        for (final var candidate : containerManifest.get("cases")) {
            if (candidate.get("id").asString().equals(id)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unknown container case");
    }

    /**
     * Returns the lowercase SHA-256 of bytes.
     *
     * @param bytes bytes to hash
     * @return hexadecimal digest
     * @throws Exception if SHA-256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Fails with a descriptive message when a condition is false.
     *
     * @param condition checked condition
     * @param message   failure description
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
