package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Independent Cairo corner correspondence for every previously identified affine magazine placement. */
public final class VerifySourceImageCornerMaps {
    /** Prevents standalone utility construction. */
    private VerifySourceImageCornerMaps() {
    }

    /**
     * Hashes exact source or numerical reference bytes without modifying them.
     * @param path pinned existing input
     * @return lowercase SHA256
     * @throws Exception if input or the standard digest is unavailable
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Preserves failed evidence instead of silently selecting a different population or tolerance.
     * @param valid required invariant
     * @param reason content-free failure description
     */
    private static void require(final boolean valid, final String reason) {
        if (!valid) {
            throw new IllegalArgumentException(reason);
        }
    }

    /**
     * Processes the complete original28-page magazine, matching all four frozen independent affine references.
     * Cairo's raster top-left corresponds to PDF source unit corner01; order is retained, never fitted.
     * @param args original PDF, original Cairo numeric reference and fresh numeric report
     * @throws Exception if input identity, complete population or ordered corner correspondence differs
     */
    @SuppressWarnings("unchecked")
    public static void main(final String[] args) throws Exception {
        require(args.length == 3, "Expected original PDF, numeric reference and fresh output");
        final var mapper = JsonMapper.builder().build();
        final var source = Path.of(args[0]);
        final var referencePath = Path.of(args[1]);
        final var reference = mapper.readTree(Files.readAllBytes(referencePath));
        require(hash(source).equals(reference.get("source_sha256").asString()), "Original source differs");
        require(reference.get("maximum_edge_difference_points").asDouble() == 2, "Frozen tolerance differs");
        require(reference.get("cases").size() == 4, "Complete affine population differs");
        final var result =
                DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                                        PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.ORIENTED_LINES)))))
                        .partition(DocumentSource.bytes(Files.readAllBytes(source), source.getFileName().toString()));
        final var images = result.elements().stream().filter(e -> e.type() == ElementType.Image).toList();
        require(images.size() == 94, "Complete source-image occurrence population differs");
        require(images.stream().allMatch(e -> e.metadata().containsKey("pdf_image_coordinates")),
                "Missing source-corner map");
        final var observations = new ArrayList<Map<String, Object>>();
        final var used = new HashSet<Integer>();
        for (final var selected : reference.get("cases")) {
            final var matrix = new double[6];
            require(selected.get("matrix").size() == 6, "Malformed reference transform");
            for (int index = 0; index < matrix.length; index++) {
                matrix[index] = selected.get("matrix").get(index).asDouble();
                require(Double.isFinite(matrix[index]), "Nonfinite reference transform");
            }
            final double width = selected.get("width").asDouble();
            final double height = selected.get("height").asDouble();
            final double tx = selected.get("parent_translation").get(0).asDouble();
            final double ty = selected.get("parent_translation").get(1).asDouble();
            final var expected =
                    List.of(List.of(0.0, height), List.of(width, height), List.of(width, 0.0), List.of(0.0, 0.0))
                            .stream()
                            .map(p
                                    -> List.of(matrix[0] * p.get(0) + matrix[2] * p.get(1) + matrix[4] + tx,
                                            matrix[1] * p.get(0) + matrix[3] * p.get(1) + matrix[5] + ty))
                            .toList();
            final var matches = new ArrayList<Integer>();
            final var errors = new ArrayList<Double>();
            for (int index = 0; index < images.size(); index++) {
                final var image = images.get(index);
                if (((Number) image.metadata().get("page_number")).intValue() != selected.get("page").asInt()) {
                    continue;
                }
                final var coordinates = (Map<?, ?>) image.metadata().get("pdf_image_coordinates");
                require(coordinates.get("system").equals("PDFUserSpace")
                                && coordinates.get("point_order").equals("source_unit_square_00_10_11_01"),
                        "Unsupported source-corner representation");
                final var actual = (List<List<Number>>) coordinates.get("points");
                require(actual.size() == 4, "Incomplete source corners");
                double error = 0;
                for (int corner = 0; corner < 4; corner++) {
                    require(actual.get(corner).size() == 2, "Malformed source corner");
                    for (int axis = 0; axis < 2; axis++) {
                        final double coordinate = actual.get(corner).get(axis).doubleValue();
                        require(Double.isFinite(coordinate), "Nonfinite source corner");
                        error = Math.max(error, Math.abs(expected.get(corner).get(axis) - coordinate));
                    }
                }
                if (error <= 2) {
                    matches.add(index);
                    errors.add(error);
                }
            }
            require(matches.size() == 1 && used.add(matches.getFirst()), "Missing or ambiguous ordered correspondence");
            observations.add(Map.of("id", selected.get("id").asString(), "page", selected.get("page").asInt(),
                    "expected_cairo_source_ordered_corners", expected, "emitted_source_ordered_corners",
                    ((Map<?, ?>) images.get(matches.getFirst()).metadata().get("pdf_image_coordinates")).get("points"),
                    "maximum_coordinate_difference", errors.getFirst(), "matching_occurrences", matches.size()));
        }
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("source_sha256", hash(source), "reference_sha256", hash(referencePath),
                                "source_image_occurrences", images.size(), "source_corner_maps", images.size(),
                                "maximum_coordinate_difference_points", 2, "results", observations))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }
}
