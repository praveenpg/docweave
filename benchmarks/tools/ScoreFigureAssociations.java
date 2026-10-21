package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Complete original-document caption/image association scoring against independent numeric source labels. */
public final class ScoreFigureAssociations {
    /** Original caption ordinal only; this does not classify body completeness or rewrite any source text. */
    private static final Pattern ORDINAL = Pattern.compile(
            "^(?:Fig(?:ure)?\\.?|FIG(?:URE)?\\.?)[\\p{Zs}\\t]+([1-9][0-9]{0,2}[A-Za-z]?)(?=[\\p{Zs}\\t.:])");

    /** Prevents standalone evaluator construction. */
    private ScoreFigureAssociations() {
    }

    /**
     * Runs all frozen profiles and preserves complete image/prediction populations, including extraction failures.
     * @param args reference JSON, exact original PDF and fresh numeric output JSON
     * @throws Exception if source/reference identity, owned metric controls or fresh output checks fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 3 && !Files.exists(Path.of(args[2])), "Expected reference, original and fresh report");
        verifyControls();
        final var mapper = JsonMapper.builder().build();
        final var referenceBytes = Files.readAllBytes(Path.of(args[0]));
        final var reference = mapper.readTree(referenceBytes);
        require(Files.size(Path.of(args[1])) <= 16 * 1024 * 1024, "Source exceeds evaluator cap");
        final var bytes = Files.readAllBytes(Path.of(args[1]));
        require(bytes.length == reference.get("source").get("bytes").asLong()
                        && hash(bytes).equals(reference.get("source").get("sha256").asString()),
                "Original source identity changed");
        final double tolerance = reference.get("maximum_edge_difference_points").asDouble();
        require(tolerance == 2, "Frozen integer-bound edge tolerance changed");
        final var labels = new ArrayList<Label>();
        final var identities = new HashSet<String>();
        for (final var selected : reference.get("labels")) {
            final var bounds = new ArrayList<Double>();
            selected.get("bounds").forEach(number -> bounds.add(number.asDouble()));
            final var label = new Label(selected.get("id").asString(), selected.get("page").asInt(),
                    selected.get("figure").asBoolean(), List.copyOf(bounds));
            require(identities.add(label.id()) && label.page() > 0 && label.bounds().size() == 4
                            && label.bounds().stream().allMatch(Double::isFinite)
                            && label.bounds().get(2) > label.bounds().get(0)
                            && label.bounds().get(3) > label.bounds().get(1),
                    "Invalid or duplicate numeric source label");
            labels.add(label);
        }
        final int expectedImages =
                reference.get("expected_images") == null ? 7 : reference.get("expected_images").asInt();
        final int expectedFigures =
                reference.get("expected_figures") == null ? 5 : reference.get("expected_figures").asInt();
        require(expectedImages > 0 && expectedImages <= 128 && expectedFigures >= 0 && expectedFigures <= expectedImages
                        && labels.size() == expectedImages
                        && labels.stream().filter(Label::figure).count() == expectedFigures,
                "Complete frozen image/figure population changed");
        final var results = new ArrayList<Map<String, Object>>();
        final var profiles = new ArrayList<>(List.of("NATIVE_LINES", "NATIVE_COLUMNS", "SOURCE_TAGGED_LINES"));
        if (reference.get("include_oriented_profile") != null
                && reference.get("include_oriented_profile").asBoolean()) {
            profiles.add("ORIENTED_LINES");
        }
        final boolean orientedColumns = reference.get("include_oriented_columns_profile") != null
                                        && reference.get("include_oriented_columns_profile").asBoolean();
        if (orientedColumns) {
            require(profiles.contains("ORIENTED_LINES"), "Combined-layout comparison requires its oriented baseline");
            profiles.add("ORIENTED_COLUMNS");
        }
        Map<Map<String, Object>, Long> projectedBaseline = null;
        for (final String profile : profiles) {
            final var result = new LinkedHashMap<String, Object>();
            result.put("profile", profile);
            final var predictions = new ArrayList<Observation>();
            final var images = new ArrayList<Observation>();
            try {
                final var options =
                        PdfOptions.defaults()
                                .withTextLayout(layout(profile))
                                .withFigureCaptions(PdfOptions.FigureCaptions.IMAGE_EDGE_ALIGNED_NUMBERED_TITLES);
                final var partitioner = profile.equals("SOURCE_TAGGED_LINES")
                                                ? PdfPartitioner.createWithTaggedTables(options)
                                                : PdfPartitioner.create(options);
                final var extraction = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                                               .partition(DocumentSource.bytes(bytes, "source.pdf"));
                for (final var element : extraction.elements()) {
                    if (element.type() == ElementType.Image) {
                        images.add(new Observation("", page(element), bounds(element)));
                    } else if (element.type() == ElementType.FigureCaption) {
                        final var ordinal = ORDINAL.matcher(element.text());
                        predictions.add(new Observation(ordinal.find() ? ordinal.group(1) : "UNKNOWN", page(element),
                                numbers(element.metadata().get("pdf_figure_caption_image_bounds"))));
                    }
                }
                result.put("outcome", "SUCCESS");
                result.put("warnings", extraction.warnings());
                if (orientedColumns) {
                    final var projected =
                            extraction.elements()
                                    .stream()
                                    .filter(element
                                            -> "projected_baseline_v1".equals(
                                                    element.metadata().get("pdf_text_geometry")))
                                    .collect(java.util.stream.Collectors.groupingBy(element
                                            -> Map.<String, Object>of("type", element.type().name(), "text",
                                                    element.text(), "metadata", element.metadata()),
                                            java.util.stream.Collectors.counting()));
                    result.put("native_layout_counts",
                            Map.of("projected_lines", projected.values().stream().mapToLong(Long::longValue).sum(),
                                    "paragraphs",
                                    extraction.elements()
                                            .stream()
                                            .filter(element -> element.metadata().containsKey("pdf_line_count"))
                                            .count(),
                                    "column_elements",
                                    extraction.elements()
                                            .stream()
                                            .filter(element -> element.metadata().containsKey("pdf_column"))
                                            .count(),
                                    "compound_paragraphs",
                                    extraction.elements()
                                            .stream()
                                            .filter(element
                                                    -> element.metadata().containsKey(
                                                            "pdf_native_retained_hyphen_wraps"))
                                            .count()));
                    if (profile.equals("ORIENTED_LINES")) {
                        projectedBaseline = projected;
                    } else if (profile.equals("ORIENTED_COLUMNS")) {
                        result.put("projected_source_elements_exact_vs_oriented_lines",
                                projectedBaseline != null && projectedBaseline.equals(projected));
                    }
                }
            } catch (final Exception failure) {
                result.put("outcome", "FAILURE");
                result.put("failure_class", failure.getClass().getName());
                if (failure instanceof PartitionException partitionFailure) {
                    result.put("failure_code", partitionFailure.code().name());
                }
            }
            result.put("association_counts", score(labels, predictions, tolerance));
            result.put("predictions", predictions);
            result.put("images", images);
            final var inventory = inventory(labels, images, tolerance);
            result.put("image_inventory", inventory);
            results.add(result);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("java_runtime", System.getProperty("java.runtime.version"));
        report.put("reference_sha256", hash(referenceBytes));
        report.put("source_sha256", hash(bytes));
        report.put("results", results);
        report.put("limits",
                "Development source; rounded placement association, not body completeness, pixel fidelity or semantic figure detection");
        Files.writeString(Path.of(args[2]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Maps explicit benchmark profiles to unchanged public layout strategies.
     * @param profile previously validated profile identity
     * @return exact public selection, never a source-specific winner
     */
    private static PdfOptions.TextLayout layout(final String profile) {
        return switch (profile) {
            case "NATIVE_COLUMNS" -> PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS;
            case "ORIENTED_LINES" -> PdfOptions.TextLayout.ORIENTED_LINES;
            case "ORIENTED_COLUMNS" -> PdfOptions.TextLayout.ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS;
            default -> PdfOptions.TextLayout.LINES;
        };
    }

    /**
     * Scores one-to-one same-ordinal/page/image relations; every duplicate/unknown/wrong relation is a false positive.
     * @param labels complete independent figure and nonfigure source population
     * @param predictions all predicted captions, without reference repair
     * @param tolerance frozen maximum difference for each integer-rounded edge
     * @return raw true-positive, false-positive and missed-reference counts with unchanged denominators
     */
    private static Map<String, Integer> score(
            final List<Label> labels, final List<Observation> predictions, final double tolerance) {
        final var used = new HashSet<String>();
        int correct = 0;
        for (final var prediction : predictions) {
            for (final var label : labels) {
                if (label.figure() && label.id().equals(prediction.id()) && label.page() == prediction.page()
                        && near(label.bounds(), prediction.bounds(), tolerance) && used.add(label.id())) {
                    correct++;
                    break;
                }
            }
        }
        final int references = (int) labels.stream().filter(Label::figure).count();
        return Map.of("references", references, "predictions", predictions.size(), "correct", correct, "false_positive",
                predictions.size() - correct, "missed_reference", references - correct);
    }

    /**
     * Retains every source image occurrence, including nonfigures, with one-to-one geometric accounting.
     * @param labels independent complete placement population
     * @param images every emitted raster occurrence
     * @param tolerance frozen per-edge difference
     * @return matched, missing and unmatched counts; duplicates receive no extra credit
     */
    private static Map<String, Integer> inventory(
            final List<Label> labels, final List<Observation> images, final double tolerance) {
        final var used = new HashSet<String>();
        for (final var image : images) {
            for (final var label : labels) {
                if (label.page() == image.page() && near(label.bounds(), image.bounds(), tolerance)
                        && used.add(label.id())) {
                    break;
                }
            }
        }
        return Map.of("references", labels.size(), "emitted", images.size(), "matched", used.size(), "missing",
                labels.size() - used.size(), "unmatched", images.size() - used.size());
    }

    /**
     * Checks independent scorer counterexamples before any real-source evaluation.
     * @throws IllegalStateException if swapped identities, duplicate predictions or nonfigure links receive credit
     */
    private static void verifyControls() {
        final var first = List.of(40.0, 40.0, 140.0, 90.0);
        final var second = List.of(40.0, 200.0, 140.0, 250.0);
        final var icon = List.of(300.0, 40.0, 320.0, 60.0);
        final var labels = List.of(
                new Label("1", 1, true, first), new Label("2", 1, true, second), new Label("icon", 1, false, icon));
        require(score(labels, List.of(new Observation("1", 1, first), new Observation("2", 1, second)), 2)
                        .equals(Map.of("references", 2, "predictions", 2, "correct", 2, "false_positive", 0,
                                "missed_reference", 0)),
                "Exact source associations lost credit");
        require(score(labels, List.of(new Observation("1", 1, second), new Observation("2", 1, first)), 2)
                                .get("correct")
                        == 0,
                "Swapped images received credit");
        require(score(labels, List.of(new Observation("1", 1, first), new Observation("1", 1, first)), 2)
                                .get("false_positive")
                        == 1,
                "Duplicate source association received credit");
        for (final var invalid : List.of(new Observation("1", 2, first), new Observation("UNKNOWN", 1, first),
                     new Observation("1", 1, icon), new Observation("1", 1, List.of()))) {
            require(score(labels, List.of(invalid), 2).get("correct") == 0,
                    "Wrong-page/unknown/nonfigure/missing association received credit");
        }
        require(inventory(labels, List.of(new Observation("", 1, first), new Observation("", 1, first)), 2)
                                .get("unmatched")
                        == 1,
                "Duplicate emitted image hid a missing source occurrence");
        final var negatives =
                List.of(new Label("portrait", 1, false, first), new Label("advertisement", 1, false, second));
        require(score(negatives, List.of(), 2)
                        .equals(Map.of("references", 0, "predictions", 0, "correct", 0, "false_positive", 0,
                                "missed_reference", 0)),
                "A complete negative-only source fabricated positive references");
        require(score(negatives, List.of(new Observation("1", 1, first), new Observation("2", 1, second)), 2)
                                .get("false_positive")
                        == 2,
                "Negative-only source captions escaped false-positive accounting");
    }

    /**
     * Compares complete finite rectangles without selecting a nearest reference or moving boundaries.
     * @param expected independently rounded source rectangle
     * @param actual original emitted placement rectangle
     * @param tolerance fixed per-edge difference in PDF points
     * @return whether every original edge is within the predeclared tolerance
     */
    private static boolean near(final List<Double> expected, final List<Double> actual, final double tolerance) {
        if (expected.size() != 4 || actual.size() != 4) {
            return false;
        }
        for (int index = 0; index < 4; index++) {
            if (!Double.isFinite(actual.get(index)) || Math.abs(expected.get(index) - actual.get(index)) > tolerance) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads the original emitted page number without fallback inference.
     * @param element emitted source unit
     * @return one-based physical page
     */
    private static int page(final Element element) {
        return ((Number) element.metadata().get("page_number")).intValue();
    }

    /**
     * Converts an original four-corner coordinate envelope to its top-left/bottom-right edges.
     * @param element emitted raster occurrence
     * @return original source-point bounds, or empty for unavailable geometry
     */
    private static List<Double> bounds(final Element element) {
        if (!(element.metadata().get("coordinates") instanceof Map<?, ?> coordinates)
                || !(coordinates.get("points") instanceof List<?> points) || points.size() != 4) {
            return List.of();
        }
        final var first = numbers(points.getFirst());
        final var last = numbers(points.get(2));
        return first.size() == 2 && last.size() == 2 ? List.of(first.get(0), first.get(1), last.get(0), last.get(1))
                                                     : List.of();
    }

    /**
     * Copies numeric metadata without coercing strings or repairing missing components.
     * @param value original metadata array
     * @return immutable numeric values or an empty unsupported marker
     */
    private static List<Double> numbers(final Object value) {
        if (!(value instanceof List<?> array) || array.stream().anyMatch(item -> !(item instanceof Number))) {
            return List.of();
        }
        return array.stream().map(item -> ((Number) item).doubleValue()).toList();
    }

    /**
     * Computes the complete raw input identity.
     * @param bytes exact input bytes
     * @return lowercase SHA256
     * @throws Exception if SHA256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Stops invalid evaluations rather than silently changing populations or references.
     * @param condition required evidence invariant
     * @param message explanation without private input text
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * One independently reviewed source placement.
     * @param id figure ordinal or nonfigure label
     * @param page one-based physical page
     * @param figure whether this is a main-body numbered figure
     * @param bounds independently rounded source rectangle
     */
    private record Label(String id, int page, boolean figure, List<Double> bounds) {}

    /**
     * One original emitted caption relation or raster occurrence.
     * @param id unmodified recognized caption ordinal or empty raster marker
     * @param page original one-based source page
     * @param bounds original emitted image rectangle
     */
    private record Observation(String id, int page, List<Double> bounds) {}
}
