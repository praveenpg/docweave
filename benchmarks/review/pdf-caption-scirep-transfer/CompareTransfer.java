import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/** Exact tree regression check for the complete fixed three-profile transfer. */
final class CompareTransfer {
    /** Prevents utility construction. */
    private CompareTransfer() {
    }

    /**
     * Verifies all source/reference/text invariants without editing scores or dropping failure cases.
     * @param args baseline, candidate and NEW numeric comparison report
     * @throws Exception if source profiles differ or output cannot be created fresh
     */
    public static void main(final String[] args) throws Exception {
        final var mapper = JsonMapper.builder().build();
        final var old = mapper.readTree(Files.readAllBytes(Path.of(args[0]))).get("results");
        final var candidate = mapper.readTree(Files.readAllBytes(Path.of(args[1]))).get("results");
        if (old.size() != 3 || candidate.size() != 3) {
            throw new AssertionError("Changed profile denominator");
        }
        for (int index = 0; index < 3; index++) {
            for (final String key : List.of("case", "profile", "outcome", "reference_identity", "all_text_presence")) {
                if (!old.get(index).get(key).equals(candidate.get(index).get(key))) {
                    throw new AssertionError("Changed profile field: " + index + "/" + key);
                }
            }
        }
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("profiles", 3, "outcomes_references_and_full_text_exact", true, "normalization",
                                "No score or source modification; exact JSON tree equality"))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }
}
