package io.docweave.validation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated prototype construction against source drift or broader policy changes, migrated from
 * {@code test_raster_rule_audit.py}.
 */
class RasterRulePrototypeScorerTest {
    /** Creates the guards. */
    RasterRulePrototypeScorerTest() {
    }

    @Test
    void baselineChangesAccessOnlyAndUnknownSourceIsRejected() throws IOException {
        final String source = ScoringFiles.readText(RasterRulePrototypeScorer.SOURCE_FILE);
        final String expected =
                source.replace("private static List<PdfPageGraphics.Segment> trimCaps(",
                              "static List<PdfPageGraphics.Segment> trimCaps(")
                        .replace("private static boolean hasRuleRemnants(", "static boolean hasRuleRemnants(");
        assertEquals(expected, RasterRulePrototypeScorer.prototype(source, "baseline"));
        assertThrows(
                IllegalArgumentException.class, () -> RasterRulePrototypeScorer.prototype(source + "\n", "baseline"));
        assertThrows(IllegalArgumentException.class, () -> RasterRulePrototypeScorer.prototype(source, "unknown"));
    }

    @Test
    void compactPreservesDarkPredicateAndResourceCaps() throws IOException {
        final String source = RasterRulePrototypeScorer.prototype(
                ScoringFiles.readText(RasterRulePrototypeScorer.SOURCE_FILE), "compact");
        assertTrue(source.contains(RasterRulePrototypeScorer.DARK));
        assertTrue(source.contains("MIN_RUN = 32;"));
        for (final String unchanged :
                List.of("MAX_RUNS = 4096;", "MAX_THICKNESS = 8;", "MAX_PIXEL_VISITS = 64_000_000;")) {
            assertTrue(source.contains(unchanged), unchanged);
        }
        assertEquals(List.of(32L, 16L, 12L), RasterRulePrototypeScorer.PROFILES.get("compact"));
    }

    @Test
    void everyRetainedPrototypeIdentityIsReproduced() throws IOException {
        final String source = ScoringFiles.readText(RasterRulePrototypeScorer.SOURCE_FILE);
        final var identities = PythonJson.object(PythonJson.parse(ScoringFiles.readText(Path.of(
                                                         "benchmarks/review/pdf-raster-rule-audit/profiles.json"))),
                "profiles");
        assertEquals(RasterRulePrototypeScorer.SOURCE_SHA, identities.get("baseline_source_sha256"));
        final var profiles = PythonJson.object(identities.get("profiles"), "profiles");
        assertEquals(List.copyOf(RasterRulePrototypeScorer.PROFILES.keySet()), List.copyOf(profiles.keySet()));
        for (final var profile : RasterRulePrototypeScorer.PROFILES.entrySet()) {
            final var identity = PythonJson.object(profiles.get(profile.getKey()), profile.getKey());
            assertEquals(identity.get("source_sha256"),
                    ScoringFiles.sha256(RasterRulePrototypeScorer.prototype(source, profile.getKey())
                                    .getBytes(StandardCharsets.UTF_8)),
                    profile.getKey());
            assertEquals(
                    List.of(identity.get("minimum_run"), identity.get("minimum_column"), identity.get("minimum_row")),
                    profile.getValue());
        }
    }
}
