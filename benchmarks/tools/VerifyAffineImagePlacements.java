package dev.docweave.validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Independent Cairo-matrix diagnosis of selected affine placements without replacing complete source inventories. */
public final class VerifyAffineImagePlacements {
    /** Prevents standalone numerical verifier construction. */
    private VerifyAffineImagePlacements() {
    }

    /**
     * Validates fixed numerical source transforms against all emitted placements of the declared supported profile.
     * @param args affine reference, original extraction report and fresh audit report
     * @throws Exception if identities, complete candidate population, controls or fixed transform checks fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 3 && !Files.exists(Path.of(args[2])), "Expected reference, extraction and fresh report");
        require(bounds(3, 5, new double[] {0, 2, -3, 0, 10, 20}, 0, 0).equals(List.of(-5.0, 20.0, 10.0, 26.0)),
                "Quarter-turn corner control failed");
        require(bounds(2, 3, new double[] {-1, 0, 0, 1, 20, 30}, 0, 0).equals(List.of(18.0, 30.0, 20.0, 33.0)),
                "Mirrored corner control failed");
        final var mapper = JsonMapper.builder().build();
        final byte[] referenceBytes = Files.readAllBytes(Path.of(args[0]));
        final byte[] reportBytes = Files.readAllBytes(Path.of(args[1]));
        final var reference = mapper.readTree(referenceBytes);
        final var report = mapper.readTree(reportBytes);
        require(reference.get("source_sha256").equals(report.get("source_sha256")), "Original source changed");
        require(reference.get("maximum_edge_difference_points").asDouble() == 2, "Frozen tolerance changed");
        final var profiles = new ArrayList<JsonNode>();
        report.get("results").forEach(result -> {
            if (result.get("profile").asString().equals("ORIENTED_LINES")) {
                profiles.add(result);
            }
        });
        require(profiles.size() == 1 && profiles.getFirst().get("outcome").asString().equals("SUCCESS"),
                "Expected exactly one complete supported original profile");
        final var images = profiles.getFirst().get("images");
        require(images.size() == 94 && reference.get("cases").size() == 4, "Complete fixed populations changed");
        final var results = new ArrayList<Map<String, Object>>();
        final var used = new HashSet<Integer>();
        for (final var selected : reference.get("cases")) {
            final var matrix = new double[6];
            require(selected.get("matrix").size() == 6, "Malformed affine reference");
            for (int index = 0; index < matrix.length; index++) {
                matrix[index] = selected.get("matrix").get(index).asDouble();
                require(Double.isFinite(matrix[index]), "Nonfinite affine reference");
            }
            final var expected = bounds(selected.get("width").asDouble(), selected.get("height").asDouble(), matrix,
                    selected.get("parent_translation").get(0).asDouble(),
                    selected.get("parent_translation").get(1).asDouble());
            final var candidates = new ArrayList<Integer>();
            for (int index = 0; index < images.size(); index++) {
                final var image = images.get(index);
                if (image.get("page").asInt() == selected.get("page").asInt() && near(expected, image.get("bounds"))) {
                    candidates.add(index);
                }
            }
            require(candidates.size() == 1 && used.add(candidates.getFirst()), "Missing/ambiguous affine placement");
            results.add(Map.of("id", selected.get("id").asString(), "page", selected.get("page").asInt(),
                    "expected_cairo_bounds", expected, "emitted_bounds",
                    images.get(candidates.getFirst()).get("bounds"), "candidate_count", candidates.size(), "match",
                    true));
        }
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("reference_sha256",
                        hash(referenceBytes), "extraction_sha256", hash(reportBytes), "source_sha256",
                        reference.get("source_sha256").asString(), "results", results, "full_emitted_image_population",
                        images.size(), "retained_original_reference_population", 106, "scope",
                        "Retrospective source-transform diagnosis of four placements; unchanged complete inventory and scoring, no pixel-fidelity or held-out quality claim."))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Projects all four independent raster corners, including shear, mirrors and parent-group translation.
     * @param width independent raster width
     * @param height independent raster height
     * @param matrix independent SVG affine coefficients in standard six-number order
     * @param parentX containing group horizontal translation
     * @param parentY containing group vertical translation
     * @return complete untrimmed affine envelope
     */
    private static List<Double> bounds(final double width, final double height, final double[] matrix,
            final double parentX, final double parentY) {
        require(width > 0 && height > 0 && Double.isFinite(width) && Double.isFinite(height), "Invalid raster size");
        final var transform = AffineTransform.getTranslateInstance(parentX, parentY);
        transform.concatenate(new AffineTransform(matrix));
        final var points = List.of(new Point2D.Double(0, 0), new Point2D.Double(width, 0),
                                       new Point2D.Double(0, height), new Point2D.Double(width, height))
                                   .stream()
                                   .map(point -> transform.transform(point, null))
                                   .toList();
        return List.of(points.stream().mapToDouble(Point2D::getX).min().orElseThrow(),
                points.stream().mapToDouble(Point2D::getY).min().orElseThrow(),
                points.stream().mapToDouble(Point2D::getX).max().orElseThrow(),
                points.stream().mapToDouble(Point2D::getY).max().orElseThrow());
    }

    /**
     * Applies the original two-point per-edge inventory predicate without nearest-neighbor selection.
     * @param expected independent four-corner envelope
     * @param actual one complete emitted rectangle
     * @return whether all four finite edges match
     */
    private static boolean near(final List<Double> expected, final JsonNode actual) {
        if (actual.size() != 4) {
            return false;
        }
        for (int index = 0; index < 4; index++) {
            final double value = actual.get(index).asDouble();
            if (!Double.isFinite(value) || Math.abs(expected.get(index) - value) > 2) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pins the actual reference/capture bytes used by this diagnostic.
     * @param bytes complete numeric input
     * @return lowercase SHA256
     * @throws Exception if the standard digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects invalid or ambiguous diagnostic evidence instead of changing reference membership.
     * @param condition required invariant
     * @param message bounded failure explanation
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
