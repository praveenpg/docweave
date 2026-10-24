package io.docweave.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real Java replay plus explicitly injected historical candidates for disagreement/failure tests. */
class WordEvidenceReplayTest {
    private static final Path REVIEW = Path.of("benchmarks/review");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path temporary;

    private static Map<String, String> sourceHashes() throws IOException {
        final var result = new LinkedHashMap<String, String>();
        for (final String packet : List.of("word-style-cases", "word-style-followups", "word-outline-controls")) {
            try (final var paths = Files.walk(REVIEW.resolve(packet))) {
                for (final var path : paths.filter(Files::isRegularFile).toList()) {
                    result.put(path.toString(), DocxOwnership.sha256(Files.readAllBytes(path)));
                }
            }
        }
        return result;
    }

    private Path copySources() throws IOException {
        final var root = temporary.resolve("source-" + UUID.randomUUID());
        Files.createDirectory(root);
        Files.copy(REVIEW.resolve(DefaultWordEvidenceReplay.CATALOG), root.resolve(DefaultWordEvidenceReplay.CATALOG));
        for (final String packet : List.of("word-style-cases", "word-style-followups", "word-outline-controls")) {
            try (final var paths = Files.walk(REVIEW.resolve(packet))) {
                for (final var source : paths.toList()) {
                    final var target = root.resolve(REVIEW.relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(source, target);
                    }
                }
            }
        }
        return root;
    }

    @Test
    void currentLibraryMatchesAllSixteenSavedCasesWithoutMutatingAnyEvidence() throws IOException {
        final var before = sourceHashes();
        final String configured = System.getProperty("docweave.word.output");
        final var output = configured == null
                                   ? Path.of("docweave-validation/target/word-evidence/run-" + UUID.randomUUID())
                                   : Path.of(configured);
        final var report = WordEvidenceReplay.create().run(REVIEW, output);
        assertEquals(16, report.cases());
        assertEquals(
                0, report.differenceCount(), "Saved Word properties differ; inspect the preserved comparison reports");
        assertEquals(WordEvidenceComparator.STATUS, report.json().get("status"));
        assertEquals(before, sourceHashes());
        assertEquals(DocxOwnership.sha256(Files.readAllBytes(REVIEW.resolve(DefaultWordEvidenceReplay.CATALOG))),
                report.catalogSha256());
        assertFalse(JSON.valueToTree(report.json()).get("runtime_classpath").isEmpty());
        for (final var packet : report.packets()) {
            final var directory = output.resolve(packet.packet());
            assertEquals(packet.candidateSha256(),
                    DocxOwnership.sha256(Files.readAllBytes(directory.resolve("candidate.json"))));
            assertEquals(packet.comparisonSha256(),
                    DocxOwnership.sha256(Files.readAllBytes(directory.resolve("comparison.json"))));
            assertEquals(packet.observationsSha256(),
                    DocxOwnership.sha256(Files.readAllBytes(directory.resolve("observations.json"))));
            assertArrayEquals(Files.readAllBytes(REVIEW.resolve(packet.packet()).resolve("candidate.json")),
                    Files.readAllBytes(directory.resolve("historical-candidate.json")));
        }
        assertThrows(UnsupportedOperationException.class, () -> report.packets().clear());
        assertThrows(IOException.class, () -> WordEvidenceReplay.create().run(REVIEW, output));
    }

    @Test
    void historicalCandidatesRetainDisagreementsPendingStatusAndExactSourceBytes() throws IOException {
        final var before = sourceHashes();
        // Explicit injection exercises failure reporting. It never supplies or changes Word observations.
        final WordCandidateCollector historical = (packet, manifest)
                -> JSON.readTree(Files.readAllBytes(REVIEW.resolve(packet.getFileName()).resolve("candidate.json")));
        final var output = temporary.resolve("historical-replay");
        final var result = new DefaultWordEvidenceReplay(historical).run(REVIEW, output);
        assertEquals(16, result.cases());
        assertTrue(result.differenceCount() > 0);
        assertEquals(WordEvidenceComparator.STATUS, result.json().get("status"));
        assertFalse(result.json().containsKey("approved"));
        assertEquals(before, sourceHashes());
        for (final var packet : result.packets()) {
            assertEquals(
                    JSON.readTree(Files.readAllBytes(REVIEW.resolve(packet.packet()).resolve("word-comparison.json"))),
                    JSON.readTree(Files.readAllBytes(output.resolve(packet.packet()).resolve("comparison.json"))));
        }
    }

    @Test
    void tamperedInputsObservationsManifestAndCatalogFailBeforeCreatingOutput() throws IOException {
        for (final String target : List.of("unsized-bands.docx", "word-repeat-observations.json", "manifest.json")) {
            final var source = copySources();
            Files.writeString(source.resolve("word-style-cases").resolve(target), "tampered owned test bytes");
            final var output = temporary.resolve("rejected-" + UUID.randomUUID());
            assertThrows(IOException.class, () -> WordEvidenceReplay.create().run(source, output));
            assertFalse(Files.exists(output));
        }
        final var source = copySources();
        final var catalog =
                (ObjectNode) JSON.readTree(Files.readAllBytes(source.resolve(DefaultWordEvidenceReplay.CATALOG)));
        catalog.put("version", 99);
        Files.writeString(source.resolve(DefaultWordEvidenceReplay.CATALOG), JSON.writeValueAsString(catalog));
        assertThrows(
                IOException.class, () -> WordEvidenceReplay.create().run(source, temporary.resolve("unknown-version")));
        assertFalse(Files.exists(temporary.resolve("unknown-version")));
    }

    @Test
    void candidateSnapshotPinsAndExistingOutputGuardAreEnforced() throws IOException {
        final var source = copySources();
        final var manifest = JSON.readTree(Files.readAllBytes(source.resolve("word-style-cases/manifest.json")));
        final var packet = source.resolve("word-style-cases");
        Files.writeString(
                packet.resolve(manifest.get("cases").get(0).get("file").asString()), "changed after source validation");
        assertThrows(IOException.class, () -> WordCandidateCollector.create().collect(packet, manifest));
        final var existing = temporary.resolve("existing");
        Files.createDirectory(existing);
        assertThrows(IOException.class, () -> WordEvidenceReplay.create().run(REVIEW, existing));
        try (final var files = Files.list(existing)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void duplicatePacketsAndRepinnedButInconsistentRepeatsStillReject() throws IOException {
        final var duplicateRoot = copySources();
        final var catalogPath = duplicateRoot.resolve(DefaultWordEvidenceReplay.CATALOG);
        final var catalog = JSON.readTree(Files.readAllBytes(catalogPath));
        ((tools.jackson.databind.node.ArrayNode) catalog.get("packets"))
                .set(1, catalog.get("packets").get(0).deepCopy());
        Files.writeString(catalogPath, JSON.writeValueAsString(catalog));
        assertThrows(IOException.class,
                () -> WordEvidenceReplay.create().run(duplicateRoot, temporary.resolve("duplicate-output")));
        assertFalse(Files.exists(temporary.resolve("duplicate-output")));

        final var alteredRoot = copySources();
        final var observationPath = alteredRoot.resolve("word-style-cases/word-observations.json");
        final var first = JSON.readTree(Files.readAllBytes(observationPath));
        ((ObjectNode) first.get("cases").get(0).get("cells").get(0))
                .put("list_string", "TEST ONLY: inconsistent repeat");
        Files.writeString(observationPath, JSON.writeValueAsString(first));
        final var alteredCatalogPath = alteredRoot.resolve(DefaultWordEvidenceReplay.CATALOG);
        final var alteredCatalog = JSON.readTree(Files.readAllBytes(alteredCatalogPath));
        ((ObjectNode) alteredCatalog.get("packets").get(0).get("files"))
                .put("word-observations.json", DocxOwnership.sha256(Files.readAllBytes(observationPath)));
        Files.writeString(alteredCatalogPath, JSON.writeValueAsString(alteredCatalog));
        assertThrows(IOException.class,
                () -> WordEvidenceReplay.create().run(alteredRoot, temporary.resolve("inconsistent-output")));
        assertFalse(Files.exists(temporary.resolve("inconsistent-output")));
    }

    @Test
    void followupSeedProvenanceAndExcludedHeadingControlsRemainExplicit() throws IOException {
        final var manifest = JSON.readTree(Files.readAllBytes(REVIEW.resolve("word-style-followups/manifest.json")));
        assertEquals(10, manifest.get("cases").size());
        for (final var entry : manifest.get("cases")) {
            assertEquals("pending", entry.get("independent_review").asString());
            assertEquals(entry.get("seed_sha256").asString(),
                    DocxOwnership.sha256(Files.readAllBytes(
                            REVIEW.resolve("word-style-cases").resolve(entry.get("seed_file").asString()))));
        }
        final var controls = REVIEW.resolve("word-outline-controls");
        final byte[] bytes = Files.readAllBytes(controls.resolve("manifest.json"));
        final var controlManifest = JSON.readTree(bytes);
        assertEquals(4, controlManifest.get("cases").size());
        assertFalse(Files.exists(controls.resolve("candidate.json")),
                "Direct heading controls are not comparator candidates");
        final var observed = DefaultWordEvidenceComparator.observationCases(controlManifest,
                JSON.readTree(Files.readAllBytes(controls.resolve("word-observations.json"))),
                DocxOwnership.sha256(bytes));
        assertEquals(4, observed.size());
        for (final var entry : controlManifest.get("cases")) {
            assertEquals(entry.get("sha256").asString(),
                    DocxOwnership.sha256(Files.readAllBytes(controls.resolve(entry.get("file").asString()))));
        }
    }

    @Test
    void optionalNewObservationComparisonUsesCurrentJavaCandidatesAndFreshOutput() throws IOException {
        final String packet = System.getProperty("docweave.word.packet");
        final String observations = System.getProperty("docweave.word.observations");
        final String output = System.getProperty("docweave.word.comparison");
        if (packet == null && observations == null && output == null) {
            return;
        }
        assertNotNull(packet, "Set docweave.word.packet, observations and comparison together");
        assertNotNull(observations, "Set docweave.word.packet, observations and comparison together");
        assertNotNull(output, "Set docweave.word.packet, observations and comparison together");
        assertFalse(Files.exists(Path.of(output)), "Comparison output must be a fresh path");
        final byte[] manifest = Files.readAllBytes(Path.of(packet).resolve("manifest.json"));
        final var candidate = WordCandidateCollector.create().collect(Path.of(packet), JSON.readTree(manifest));
        final var comparison = WordEvidenceComparator.create().compare(JSON.readTree(manifest), candidate,
                JSON.readTree(Files.readAllBytes(Path.of(observations))), DocxOwnership.sha256(manifest));
        CorpusReview.write(Path.of(output), comparison.json());
        assertEquals(0, comparison.differenceCount(), "Differences recorded; human review is still pending");
    }
}
