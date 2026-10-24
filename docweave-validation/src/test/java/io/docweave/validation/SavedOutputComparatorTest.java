package io.docweave.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Historical Python parity vectors, original schema cases and real process exit-code verification. */
class SavedOutputComparatorTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path VECTORS =
            Path.of("docweave-validation/src/test/resources/saved-output-comparison-vectors.json");
    @TempDir Path temporary;

    /** Creates an owned compatibility envelope without depending on current production enum validation. */
    private static JsonNode elements(final String text, final Map<String, Object> metadata) {
        return JSON.valueToTree(
                List.of(Map.of("element_id", "sample", "type", "NarrativeText", "text", text, "metadata", metadata)));
    }

    @Test
    void historicalCodePointSimilarityAndCompleteReportsMatchWithoutPython() throws IOException {
        final byte[] bytes = Files.readAllBytes(VECTORS);
        final var vectors = JSON.readTree(bytes);
        int similarities = 0;
        for (final var entry : vectors.get("similarity")) {
            assertEquals(entry.get(2).asDouble(),
                    CodePointSimilarity.ratio(entry.get(0).asString(), entry.get(1).asString()),
                    "Historical similarity vector " + similarities++);
        }
        int reports = 0;
        for (final var entry : vectors.get("reports")) {
            assertEquals(entry.get("expected"),
                    JSON.valueToTree(
                            SavedOutputComparator.create().compare(entry.get("reference"), entry.get("candidate"))),
                    "Historical report " + reports++);
        }
        for (final var entry : vectors.get("encoded_reports")) {
            assertEquals(entry.get("expected"), JSON.valueToTree(SavedOutputComparator.create().compare(
                                                        JSON.readTree(entry.get("reference").asString()),
                                                        JSON.readTree(entry.get("candidate").asString()))));
        }
        assertEquals(1767, similarities);
        assertEquals(14, reports);
        final Path output =
                Path.of("docweave-validation/target/saved-output-comparison/run-" + UUID.randomUUID() + ".json");
        CorpusReview.write(output,
                Map.of("kind", "Owned saved-output diagnostic parity; not upstream/Word evidence", "vectors_sha256",
                        DocxOwnership.sha256(bytes), "historical_provenance", vectors.get("provenance"),
                        "similarity_vectors", similarities, "complete_reports", reports, "encoded_numeric_reports",
                        vectors.get("encoded_reports").size(), "validation_sources", ValidationProvenance.sources(),
                        "java_version", Runtime.version().toString()));
    }

    @Test
    void identityMetadataSegmentationMissingTextAndUnicodeRetainOriginalBehavior() throws IOException {
        final var comparator = SavedOutputComparator.create();
        final var original = elements("Hello 🧵", Map.of());
        final var identity = comparator.compare(original, original);
        assertEquals(true, identity.get("exact_match"));
        assertEquals(7, identity.get("reference_code_points"));
        assertEquals(1.0, identity.get("text_similarity"));
        final var metadata = comparator.compare(elements("Hello 🧵", Map.of("page_number", 1)), original);
        assertEquals(false, metadata.get("exact_match"));
        assertEquals(1, metadata.get("paired_metadata_mismatches"));
        assertEquals(0, metadata.get("paired_text_mismatches"));
        final var split =
                JSON.createArrayNode().add(elements("One", Map.of()).get(0)).add(elements("Two", Map.of()).get(0));
        final var segmentation = comparator.compare(elements("One\n\nTwo", Map.of()), split);
        assertEquals(1.0, segmentation.get("text_similarity"));
        assertEquals(false, segmentation.get("category_sequence_equal"));
        assertEquals(1, segmentation.get("unpaired_elements"));
        final var missing = comparator.compare(original, JSON.createArrayNode());
        assertEquals(0.0, missing.get("text_similarity"));
        assertEquals(1, missing.get("unpaired_elements"));
        assertThrows(UnsupportedOperationException.class, () -> identity.clear());
    }

    @Test
    void invalidSchemaRejectsMissingNullWrongTypeAndEmptyFields() {
        for (final String invalid : List.of("{}", "[null]", "[{\"type\":\"Title\"}]",
                     "[{\"type\":\"X\",\"element_id\":\"x\",\"text\":\"\",\"metadata\":[]}]",
                     "[{\"type\":\"\",\"element_id\":\"x\",\"text\":\"\",\"metadata\":{}}]",
                     "[{\"type\":\"X\",\"element_id\":\"\",\"text\":\"\",\"metadata\":{}}]",
                     "[{\"type\":\"X\",\"element_id\":\"x\",\"text\":null,\"metadata\":{}}]")) {
            assertThrows(IOException.class,
                    () -> SavedOutputComparator.create().compare(JSON.readTree(invalid), JSON.createArrayNode()));
        }
        assertThrows(IOException.class, () -> SavedOutputComparator.create().compare(null, JSON.createArrayNode()));
    }

    @Test
    void orderingAsymmetryPopularSymbolsAndUnknownFieldsRemainObservable() throws IOException {
        assertEquals(0.25, CodePointSimilarity.ratio("tide", "diet"));
        assertEquals(0.5, CodePointSimilarity.ratio("diet", "tide"));
        assertEquals(1.0, CodePointSimilarity.ratio("", ""));
        final var left = elements("Same", Map.of());
        final var right = left.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) right.get(0)).put("extra", "retained difference");
        final var compared = SavedOutputComparator.create().compare(left, right);
        assertEquals(false, compared.get("exact_match"));
        assertEquals(0, compared.get("paired_metadata_mismatches"));
        assertEquals(1.0, compared.get("text_similarity"));
    }

    @Test
    void cliReportsIdenticalDifferentAndInvalidInputsWithHistoricalExitCodes()
            throws IOException, InterruptedException {
        final Path reference = temporary.resolve("reference.json");
        final Path candidate = temporary.resolve("candidate.json");
        Files.writeString(reference, JSON.writeValueAsString(elements("Owned text", Map.of())));
        for (final int expected : List.of(0, 1, 2)) {
            Files.writeString(candidate, expected == 0   ? Files.readString(reference)
                                         : expected == 1 ? JSON.writeValueAsString(elements("Changed text", Map.of()))
                                                         : "[null]");
            final Path stdout = temporary.resolve("stdout-" + expected);
            final Path stderr = temporary.resolve("stderr-" + expected);
            final String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            final String classpath =
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            final var process = new ProcessBuilder(executable, "--class-path", classpath,
                    SavedOutputComparisonCli.class.getName(), reference.toString(), candidate.toString())
                                        .redirectOutput(stdout.toFile())
                                        .redirectError(stderr.toFile())
                                        .start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Comparator subprocess exceeded test timeout");
                assertEquals(expected, process.exitValue());
                if (expected == 2) {
                    assertEquals("", Files.readString(stdout));
                    assertTrue(Files.readString(stderr).startsWith("Invalid benchmark input:"));
                } else {
                    assertEquals(
                            expected == 0, JSON.readTree(Files.readAllBytes(stdout)).get("exact_match").asBoolean());
                }
            } finally {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void cliRejectsMalformedNonJsonNumbersMissingFilesAndWrongArgumentCount() throws IOException {
        final Path reference = temporary.resolve("reference.json");
        final Path candidate = temporary.resolve("candidate.json");
        Files.writeString(reference, "[]");
        final var stdout = new ByteArrayOutputStream();
        final var stderr = new ByteArrayOutputStream();
        try (final var output = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                final var errors = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            for (final String invalid :
                    List.of("malformed", "[NaN]", "[Infinity]", "[-Infinity]", "[] []", "\ufeff[]")) {
                Files.writeString(candidate, invalid);
                assertEquals(2,
                        SavedOutputComparisonCli.run(
                                new String[] {reference.toString(), candidate.toString()}, output, errors),
                        invalid);
            }
            assertEquals(2, SavedOutputComparisonCli.run(
                                    new String[] {reference.toString(), temporary.resolve("absent").toString()}, output,
                                    errors));
            for (final byte[] invalid : List.of("[]".getBytes(StandardCharsets.UTF_16), new byte[] {(byte) 0xff})) {
                Files.write(candidate, invalid);
                assertEquals(2, SavedOutputComparisonCli.run(
                                        new String[] {reference.toString(), candidate.toString()}, output, errors));
            }
            assertEquals(2, SavedOutputComparisonCli.run(new String[0], output, errors));
        }
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void optionalSavedArrayComparisonWritesFreshReportBeforeFailingOnDifferences() throws IOException {
        final String reference = System.getProperty("docweave.compare.reference");
        final String candidate = System.getProperty("docweave.compare.candidate");
        final String output = System.getProperty("docweave.compare.report");
        if (reference == null && candidate == null && output == null) {
            return;
        }
        assertNotNull(reference);
        assertNotNull(candidate);
        assertNotNull(output);
        assertFalse(Files.exists(Path.of(output)), "Use a fresh comparison report");
        final var report = SavedOutputComparator.create().compare(
                SavedOutputComparisonCli.load(Path.of(reference)), SavedOutputComparisonCli.load(Path.of(candidate)));
        CorpusReview.write(Path.of(output), report);
        assertEquals(true, report.get("exact_match"), "Saved outputs differ; inspect the preserved comparison report");
    }
}
