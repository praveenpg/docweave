import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict report-tree comparisons; no output or reference text normalization. */
final class CompareCaptionReports {
    /** Prevents utility construction. */
    private CompareCaptionReports() {
    }

    /**
     * Checks every fixed original report and emits measured counts only after all invariants hold.
     * @param args repository root, fresh experiment directory and NEW comparison output
     * @throws Exception if a fixed population, exact invariant or fresh-output requirement fails
     */
    public static void main(final String[] args) throws Exception {
        final var mapper = JsonMapper.builder().build();
        final var root = Path.of(args[0]);
        final var experiment = Path.of(args[1]);
        final var rows = new ArrayList<Map<String, Object>>();
        int compared = 0;
        for (final String population : List.of("old", "peerj")) {
            final var old =
                    mapper
                            .readTree(Files.readAllBytes(root.resolve(
                                    population.equals("old")
                                            ? "benchmarks/review/pdf-horizontal-figure-captions/positive-java21.json"
                                            : "benchmarks/review/pdf-caption-peerj-transfer/none_raw-java21.json")))
                            .get("results");
            final var candidate =
                    mapper.readTree(Files.readAllBytes(experiment.resolve(population + ".json"))).get("results");
            if (old.size() != (population.equals("old") ? 9 : 3) || candidate.size() != old.size()) {
                throw new AssertionError("Changed original population");
            }
            for (int index = 0; index < old.size(); index++) {
                final var before = old.get(index);
                final var after = candidate.get(index);
                for (final String key :
                        List.of("case", "profile", "outcome", "reference_identity", "all_text_presence")) {
                    if (!before.get(key).equals(after.get(key))) {
                        throw new AssertionError(population + "/" + index + "/" + key);
                    }
                }
                if (!after.get("outcome").asString().equals("SUCCESS")) {
                    throw new AssertionError("Original extraction failure");
                }
                if (population.equals("old") && !before.equals(after)) {
                    throw new AssertionError("Changed previous caption results");
                }
                final var row = new LinkedHashMap<String, Object>();
                row.put("case", after.get("case").asString());
                row.put("profile", after.get("profile").asString());
                row.put("all_text_and_references_exact", true);
                row.put("reference_captions", after.get("reference_identity").get("reference_paragraphs").asInt());
                row.put("caption_units", after.get("classified_caption_elements").asInt());
                row.put("complete_bodies",
                        after.get("classified_caption_presence").get("uniquely_matched_paragraphs").asInt());
                rows.add(row);
                compared++;
            }
        }
        final var horizontal =
                mapper.readTree(Files.readAllBytes(experiment.resolve("horizontal-regression.json"))).get("results");
        final var frozen =
                mapper.readTree(Files.readAllBytes(root.resolve(
                                        "benchmarks/review/pdf-horizontal-figure-captions/positive-java21.json")))
                        .get("results");
        if (!horizontal.equals(frozen)) {
            throw new AssertionError("Historical horizontal policy changed");
        }
        final var negatives = mapper.readTree(Files.readAllBytes(experiment.resolve("negative.json"))).get("results");
        final var oldNegatives =
                mapper.readTree(Files.readAllBytes(root.resolve(
                                        "benchmarks/review/pdf-horizontal-figure-captions/negative-java21.json")))
                        .get("results");
        if (negatives.size() != 18 || !negatives.equals(oldNegatives)) {
            throw new AssertionError("Negative image/caption/payload regression");
        }
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "Exact JSON tree invariants across all fixed original case/profiles; no fuzzy scoring",
                        "original_profiles", compared, "horizontal_regression_profiles", horizontal.size(),
                        "negative_profiles", negatives.size(), "results", rows))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }
}
