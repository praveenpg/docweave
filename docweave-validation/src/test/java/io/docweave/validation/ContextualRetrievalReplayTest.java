package io.docweave.validation;

import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Frozen-capture protocol controls; synthetic rank observations do not establish model/retrieval quality. */
class ContextualRetrievalReplayTest {
    /** Per-test isolated source/capture population. */
    @TempDir Path root;
    /** Existing immutable JSON codec, shared only for bounded owned fixtures. */
    private final JsonMapper json = JsonMapper.builder().build();

    /** Creates protocol controls without external model or network access. */
    ContextualRetrievalReplayTest() {
    }

    /**
     * Valid captures retain every query and distinguish failed retrieval from absent observations.
     * @throws Exception if fixture authoring or scoring fails
     */
    @Test
    void validReplayKeepsCompleteQueryPopulationAndIndependentLabels() throws Exception {
        fixture();
        final var report = run("baseline.json", "contextual.json", "report.json");
        final var baseline = report.get("baseline").get("score");
        final var contextual = report.get("contextual").get("score");
        assertEquals(2, baseline.get("reference_queries").asInt());
        assertEquals(2, contextual.get("reference_queries").asInt());
        assertEquals(1, baseline.get("failed_queries").asInt());
        assertEquals(1, contextual.get("missing_queries").asInt());
        assertEquals(0.25, baseline.get("macro_recall_at_k").asDouble());
        assertEquals(0.5, contextual.get("macro_recall_at_k").asDouble());
        assertEquals(0.25, baseline.get("mean_reciprocal_rank_at_k").asDouble());
        assertEquals(0.5, contextual.get("mean_reciprocal_rank_at_k").asDouble());
        assertEquals(hash(root.resolve("queries.json")), report.get("queries_sha256").asString());
        assertFalse(Files.readString(root.resolve("report.json")).contains("Independent query text"));
    }

    /**
     * Source/content/query identities, profile, model and retrieval configuration cannot silently drift.
     * @throws Exception if fixture authoring fails
     */
    @Test
    void presentCaptureDriftAndFrozenArtifactChangesAreRejected() throws Exception {
        fixture();
        final var original = (ObjectNode) json.readTree(Files.readString(root.resolve("baseline.json")));
        for (final var field :
                List.of("source_chunks_sha256", "content_sha256", "queries_sha256", "profile", "model", "config")) {
            final var corrupt = original.deepCopy();
            if (field.equals("model")) {
                ((ObjectNode) corrupt.get("embedding_model")).put("model_id", "changed");
            } else if (field.equals("config")) {
                ((ObjectNode) corrupt.get("retrieval_config")).put("top_k", 3);
            } else {
                corrupt.put(field, "changed");
            }
            write("bad.json", corrupt);
            assertThrows(
                    IllegalArgumentException.class, () -> run("bad.json", "contextual.json", "bad-report.json"), field);
            assertFalse(Files.exists(root.resolve("bad-report.json")));
        }
        Files.writeString(root.resolve("queries.json"), Files.readString(root.resolve("queries.json")) + " ");
        assertThrows(
                IllegalArgumentException.class, () -> run("baseline.json", "contextual.json", "changed-query.json"));
    }

    /**
     * Missing/failed frames preserve zero-score populations; partial failures and overwrites are rejected.
     * @throws Exception if fixture authoring or scoring fails
     */
    @Test
    void missingFramesFailedFramesAndFreshOutputRulesRemainExplicit() throws Exception {
        fixture();
        final var absent = run("absent-baseline.json", "absent-contextual.json", "absent-report.json");
        for (final var profile : List.of("baseline", "contextual")) {
            assertEquals(2, absent.get(profile).get("score").get("missing_queries").asInt());
            assertEquals(0.0, absent.get(profile).get("score").get("macro_recall_at_k").asDouble());
        }
        final var failed = (ObjectNode) json.readTree(Files.readString(root.resolve("baseline.json")));
        failed.put("outcome", "FAILED");
        write("failed.json", failed);
        assertThrows(
                IllegalArgumentException.class, () -> run("failed.json", "contextual.json", "partial-report.json"));
        failed.putArray("observations");
        write("failed.json", failed);
        final var report = run("failed.json", "contextual.json", "failed-report.json");
        assertEquals(2, report.get("baseline").get("score").get("failed_queries").asInt());
        assertEquals(0.0, report.get("baseline").get("score").get("mean_reciprocal_rank_at_k").asDouble());
        Files.writeString(root.resolve("owner-report.json"), "owner content");
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> run("baseline.json", "contextual.json", "owner-report.json"));
        assertEquals("owner content", Files.readString(root.resolve("owner-report.json")));
    }

    /**
     * Freezes independent original chunks/queries before authoring synthetic capture observations.
     * @throws Exception if owned fixture writes or hashing fail
     */
    private void fixture() throws Exception {
        final var chunks = List.of("a", "b", "c", "d")
                                   .stream()
                                   .map(id -> new Element(id, ElementType.CompositeElement, "Original " + id, Map.of()))
                                   .toList();
        Files.writeString(root.resolve("chunks.json"), ElementJson.create().write(chunks));
        write("queries.json", List.of(Map.of("id", "first", "text", "Independent query text one", "relevant_chunk_ids",
                                              List.of("a", "c")),
                                      Map.of("id", "second", "text", "Independent query text two", "relevant_chunk_ids",
                                              List.of("d"))));
        write("contexts.json", Map.of("fixture_only", true, "context_provider", "owned-capture-control"));
        final var model =
                Map.of("provider_id", "owned-fixture", "model_id", "fixture-not-an-embedding-model", "revision", "v1");
        final var config = Map.of("top_k", 2, "index_configuration", "fixed-owned-control");
        write("manifest.json", Map.of("schema_version", 1, "source_chunks", identity("chunks.json"), "queries",
                                       identity("queries.json"), "contexts", identity("contexts.json"),
                                       "embedding_model", model, "retrieval_config", config));
        final var frame = json.createObjectNode();
        frame.put("source_chunks_sha256", hash(root.resolve("chunks.json")));
        frame.put("queries_sha256", hash(root.resolve("queries.json")));
        frame.set("embedding_model", json.valueToTree(model));
        frame.set("retrieval_config", json.valueToTree(config));
        frame.put("profile", "BASELINE");
        frame.put("content_sha256", hash(root.resolve("chunks.json")));
        frame.put("outcome", "SUCCESS");
        frame.set("observations",
                json.valueToTree(List.of(
                        Map.of("query_id", "first", "outcome", "SUCCESS", "ranked_chunk_ids", List.of("b", "c", "a")),
                        Map.of("query_id", "second", "outcome", "FAILED", "ranked_chunk_ids", List.of()))));
        write("baseline.json", frame);
        frame.put("profile", "CONTEXTUAL");
        frame.put("content_sha256", hash(root.resolve("contexts.json")));
        frame.set("observations", json.valueToTree(List.of(Map.of("query_id", "first", "outcome", "SUCCESS",
                                          "ranked_chunk_ids", List.of("a", "c", "b")))));
        write("contextual.json", frame);
    }

    /**
     * Pins one unchanged encoded input artifact.
     * @param name root-relative source filename
     * @return path/hash identity
     * @throws Exception if hashing fails
     */
    private Map<String, String> identity(final String name) throws Exception {
        return Map.of("path", name, "sha256", hash(root.resolve(name)));
    }

    /**
     * Executes the same replay path used by the standalone CLI.
     * @param baseline root-relative baseline capture, possibly absent
     * @param contextual root-relative contextual capture, possibly absent
     * @param report fresh numeric output filename
     * @return emitted numeric report
     * @throws Exception if protocol validation/scoring/output fails
     */
    private JsonNode run(final String baseline, final String contextual, final String report) throws Exception {
        ContextualRetrievalReplay.run(new String[] {root.resolve("manifest.json").toString(), root.toString(),
                root.resolve(baseline).toString(), root.resolve(contextual).toString(),
                root.resolve(report).toString()});
        return json.readTree(Files.readString(root.resolve(report)));
    }

    /**
     * Writes one independent bounded owned fixture representation.
     * @param name root-relative target
     * @param value fixture tree/value
     * @throws Exception if encoding/writing fails
     */
    private void write(final String name, final Object value) throws Exception {
        Files.writeString(root.resolve(name), json.writeValueAsString(value));
    }

    /**
     * Hashes exact owned fixture bytes without normalization.
     * @param path existing source file
     * @return lowercase SHA256
     * @throws Exception if reading/digest access fails
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
