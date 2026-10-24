package io.docweave.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real owned extraction plus synthetic reference-schema tests; synthetic records are never upstream evidence. */
class CorpusLegacyTest {
    @TempDir Path temporary;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path CACHE = Path.of("benchmarks/review/word-style-cases");
    private static final String FILE = "unsized-bands.docx";

    private Path manifest(final boolean missingAnchor) throws IOException {
        final var root = JSON.createObjectNode();
        root.put("scope", "Owned legacy contract test, not upstream evidence");
        final var doc = root.putArray("documents").addObject();
        doc.put("filename", FILE);
        doc.put("sha256", DocxOwnership.sha256(Files.readAllBytes(CACHE.resolve(FILE))));
        doc.put("bytes", Files.size(CACHE.resolve(FILE)));
        doc.putArray("required_text").add(missingAnchor ? "Missing owned anchor" : "unsized-bands-r0c0");
        doc.put("minimum_tables", 1);
        final var path = temporary.resolve("manifest.json");
        Files.writeString(path, JSON.writeValueAsString(root));
        return path;
    }

    private Path reference(final String hash) throws IOException {
        final var root = JSON.createObjectNode();
        root.putObject("reference").put("scope", "TEST ONLY synthetic reference, not real upstream output");
        final var entry = root.putArray("cases").addObject();
        entry.put("input", FILE);
        entry.put("input_sha256", hash);
        entry.putArray("upstream_output")
                .addObject()
                .put("type", "NarrativeText")
                .put("text", "Synthetic unmatched output");
        final var path = temporary.resolve("reference.json");
        Files.writeString(path, JSON.writeValueAsString(root));
        return path;
    }

    @Test
    void candidateOnlyLegacyChecksUseBothActualJavaModesWithoutImplyingUpstreamComparison() throws IOException {
        final var report = CorpusReview.run(manifest(false), CACHE, CorpusReview.Baseline.SUBSTRING, null);
        assertEquals(true, report.get("checks_passed"));
        final var node = JSON.valueToTree(report);
        assertFalse(node.has("upstream_provenance"));
        assertFalse(node.get("documents").get(0).has("upstream"));
        for (final String mode : List.of("defaults", "include_headers_footers")) {
            assertEquals(
                    0, node.get("documents").get(0).get("java").get(mode).get("body_paragraphs_unmatched").asInt());
        }
        assertEquals(false,
                CorpusReview.run(manifest(true), CACHE, CorpusReview.Baseline.SUBSTRING, null).get("checks_passed"));
    }

    @Test
    void referenceProvenanceAndMetricsArePreservedWithoutTurningUpstreamOutputIntoGroundTruth() throws IOException {
        final var report = CorpusReview.run(manifest(false), CACHE, CorpusReview.Baseline.SUBSTRING,
                reference(DocxOwnership.sha256(Files.readAllBytes(CACHE.resolve(FILE)))));
        assertEquals(true, report.get("checks_passed"), "Only Java contract checks determine this flag");
        final var node = JSON.valueToTree(report);
        assertEquals("upstream_reference", node.get("comparison_mode").asString());
        assertTrue(node.has("upstream_reference_sha256"));
        assertTrue(node.get("documents").get(0).get("upstream").get("body_paragraphs_unmatched").asInt() > 0);
    }

    @Test
    void referenceInputHashDuplicateCasesAndUndefinedOwnershipMappingsReject() throws IOException {
        final var manifest = manifest(false);
        final var ref = reference("0".repeat(64));
        assertThrows(IOException.class, () -> CorpusReview.run(manifest, CACHE, CorpusReview.Baseline.SUBSTRING, ref));
        assertThrows(IOException.class, () -> CorpusReview.run(manifest, CACHE, CorpusReview.Baseline.OWNERSHIP, ref));
        final var data = (ObjectNode) JSON.readTree(Files.readAllBytes(ref));
        ((tools.jackson.databind.node.ArrayNode) data.get("cases")).add(data.get("cases").get(0).deepCopy());
        Files.writeString(ref, JSON.writeValueAsString(data));
        assertThrows(IOException.class, () -> CorpusReference.read(ref));
    }

    @Test
    void actualPinnedNativeReferenceIsConsumedWithoutRegenerationOrMockedUpstreamExecution() throws IOException {
        final var path = Path.of("docweave-integration-tests/src/test/resources/docx-native-reference.json");
        final var reference = CorpusReference.read(path);
        assertEquals(DocxOwnership.sha256(Files.readAllBytes(path)), reference.sha256());
        assertEquals(2, reference.cases().size());
        for (final var entry : reference.cases().entrySet()) {
            final String hash =
                    DocxOwnership.sha256(Files.readAllBytes(Path.of("benchmarks/fixtures").resolve(entry.getKey())));
            final var metrics = reference.metrics(entry.getKey(), hash, List.of());
            assertEquals(entry.getValue().get("upstream_output").size(), metrics.get("element_count"));
        }
    }
}
