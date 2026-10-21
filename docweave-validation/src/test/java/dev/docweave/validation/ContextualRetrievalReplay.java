package dev.docweave.validation;

import dev.docweave.json.ElementJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JVM replay of caller-supplied baseline/contextual retrieval captures with frozen source/configuration guards. */
final class ContextualRetrievalReplay {
    /** Shared stateless JSON codec for bounded evidence files. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Per-file encoded evidence ceiling, checked before parsing. */
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    /** Prevents standalone utility construction. */
    private ContextualRetrievalReplay() {
    }

    /**
     * Scores every independent query with unchanged source identities, model and retrieval configuration.
     * Missing capture files score every query as missing; declared complete-frame failures score every
     * query as failed. Neither path removes queries/relevance labels from the metric denominator.
     * This tool does not generate context, call a model, build an index or rerun retrieval.
     * @param args manifest, evidence root, baseline capture, contextual capture and fresh output report
     * @throws Exception if evidence/configuration drifts, parsing fails or report output already exists
     */
    static void run(final String[] args) throws Exception {
        require(args.length == 5, "Expected manifest, evidence root, baseline, contextual and fresh report");
        final var manifest = JSON.readTree(bytes(Path.of(args[0])));
        require(manifest.get("schema_version").isIntegralNumber()
                        && manifest.get("schema_version").toString().equals("1"),
                "Unknown retrieval evidence schema");
        final var root = Path.of(args[1]).toAbsolutePath().normalize();
        final byte[] originals = pinned(root, manifest.get("source_chunks"));
        final byte[] queryBytes = pinned(root, manifest.get("queries"));
        final byte[] contexts = pinned(root, manifest.get("contexts"));
        final String sourceHash = hash(originals);
        final String queryHash = hash(queryBytes);
        final String contextHash = hash(contexts);
        require(manifest.get("embedding_model").isObject() && !manifest.get("embedding_model").isEmpty()
                        && manifest.get("retrieval_config").isObject(),
                "Missing fixed model/retrieval identity");
        text(manifest.get("embedding_model"), "provider_id");
        text(manifest.get("embedding_model"), "model_id");
        text(manifest.get("embedding_model"), "revision");
        final var depth = manifest.get("retrieval_config").get("top_k");
        require(depth != null && depth.isIntegralNumber() && depth.toString().length() <= 4 && depth.asLong() >= 1
                        && depth.asLong() <= 1000,
                "Invalid fixed rank depth");
        final int topK = depth.asInt();
        final var chunks = ElementJson.create().read(new String(originals, StandardCharsets.UTF_8));
        final var ids = chunks.stream().map(element -> element.elementId()).toList();
        final var queryArray = JSON.readTree(queryBytes);
        require(queryArray.isArray() && !queryArray.isEmpty() && queryArray.size() <= 10_000,
                "Invalid complete query population");
        final var queries = new ArrayList<ContextualRetrievalEvaluation.Query>();
        for (final var query : queryArray) {
            require(query.get("text").isString() && !query.get("text").asString().isBlank(),
                    "Missing independent query text");
            queries.add(new ContextualRetrievalEvaluation.Query(
                    text(query, "id"), strings(query.get("relevant_chunk_ids"))));
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("schema_version", 1);
        report.put("source_chunks_sha256", sourceHash);
        report.put("queries_sha256", queryHash);
        report.put("contexts_sha256", contextHash);
        report.put("embedding_model", manifest.get("embedding_model"));
        report.put("retrieval_config", manifest.get("retrieval_config"));
        report.put("baseline",
                capture(Path.of(args[2]), "BASELINE", sourceHash, sourceHash, queryHash, manifest, ids, queries, topK));
        report.put("contextual", capture(Path.of(args[3]), "CONTEXTUAL", sourceHash, contextHash, queryHash, manifest,
                                         ids, queries, topK));
        Files.writeString(Path.of(args[4]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Validates one complete capture frame before scoring its original query outcomes.
     * @param path original actual-ranker capture, or absent path
     * @param profile expected baseline/contextual role
     * @param sourceHash frozen complete source chunks
     * @param contentHash expected original/context artifact identity
     * @param queryHash frozen complete independent query file
     * @param manifest fixed model/retrieval configuration
     * @param chunks complete source identities
     * @param queries complete references
     * @param topK common fixed rank depth
     * @return complete numeric score with capture identity and outcome
     * @throws Exception if a present frame is malformed or differs from frozen source/configuration
     */
    private static Map<String, Object> capture(final Path path, final String profile, final String sourceHash,
            final String contentHash, final String queryHash, final JsonNode manifest, final List<String> chunks,
            final List<ContextualRetrievalEvaluation.Query> queries, final int topK) throws Exception {
        String outcome = "MISSING";
        String digest = "MISSING";
        final var observations = new ArrayList<ContextualRetrievalEvaluation.Capture>();
        if (Files.exists(path)) {
            final byte[] raw = bytes(path);
            digest = hash(raw);
            final var frame = JSON.readTree(raw);
            require(text(frame, "profile").equals(profile) && text(frame, "source_chunks_sha256").equals(sourceHash)
                            && text(frame, "content_sha256").equals(contentHash)
                            && text(frame, "queries_sha256").equals(queryHash)
                            && manifest.get("embedding_model").equals(frame.get("embedding_model"))
                            && manifest.get("retrieval_config").equals(frame.get("retrieval_config")),
                    "Retrieval capture source/model/configuration drift");
            outcome = text(frame, "outcome");
            require((outcome.equals("SUCCESS") || outcome.equals("FAILED")) && frame.get("observations").isArray(),
                    "Invalid complete retrieval capture outcome");
            if (outcome.equals("FAILED")) {
                require(frame.get("observations").isEmpty(), "Failed complete capture contains partial observations");
                for (final var query : queries) {
                    observations.add(new ContextualRetrievalEvaluation.Capture(query.id(), false, List.of()));
                }
            } else {
                for (final var query : frame.get("observations")) {
                    final String state = text(query, "outcome");
                    require(state.equals("SUCCESS") || state.equals("FAILED"), "Invalid captured query outcome");
                    observations.add(new ContextualRetrievalEvaluation.Capture(
                            text(query, "query_id"), state.equals("SUCCESS"), strings(query.get("ranked_chunk_ids"))));
                }
            }
        }
        return Map.of("capture_sha256", digest, "outcome", outcome, "score",
                ContextualRetrievalEvaluation.score(chunks, queries, observations, topK));
    }

    /**
     * Reads an encoded-size-bounded immutable evidence snapshot.
     * @param path existing regular evidence file
     * @return original bounded bytes
     * @throws Exception if file access or the encoded limit fails
     */
    private static byte[] bytes(final Path path) throws Exception {
        require(Files.size(path) <= MAX_BYTES, "Retrieval evidence exceeds16MiB");
        try (final var input = Files.newInputStream(path)) {
            final byte[] data = input.readNBytes(MAX_BYTES + 1);
            require(data.length <= MAX_BYTES, "Retrieval evidence changed beyond16MiB");
            return data;
        }
    }

    /**
     * Verifies an explicitly root-relative source artifact against its frozen raw bytes.
     * @param root explicit evidence directory
     * @param identity relative path and lowercase SHA256
     * @return original bounded bytes
     * @throws Exception if root/path/identity or file content differs
     */
    private static byte[] pinned(final Path root, final JsonNode identity) throws Exception {
        final var relative = Path.of(text(identity, "path"));
        require(!relative.isAbsolute(), "Evidence path must be root-relative");
        final var path = root.resolve(relative).normalize();
        require(path.startsWith(root), "Evidence path escapes the explicit root");
        final byte[] raw = bytes(path);
        require(hash(raw).equals(text(identity, "sha256")), "Frozen retrieval input changed");
        return raw;
    }

    /**
     * Extracts one original nonempty string without coercing other JSON types.
     * @param node containing object
     * @param field required textual field
     * @return exact original string
     */
    private static String text(final JsonNode node, final String field) {
        require(node != null && node.get(field) != null && node.get(field).isString()
                        && !node.get(field).asString().isBlank(),
                "Missing retrieval identity string");
        return node.get(field).asString();
    }

    /**
     * Retains a complete captured/reference identity list without deduplication or repair.
     * @param node original JSON array
     * @return immutable original order
     */
    private static List<String> strings(final JsonNode node) {
        require(node != null && node.isArray() && node.size() <= 10_000, "Invalid retrieval identity array");
        final var values = new ArrayList<String>();
        for (final var item : node) {
            require(item.isString() && !item.asString().isBlank(), "Invalid captured/reference identity");
            values.add(item.asString());
        }
        return List.copyOf(values);
    }

    /**
     * Hashes unchanged input/capture bytes without exporting document/query text to numeric reports.
     * @param data bounded encoded evidence
     * @return lowercase SHA256
     * @throws Exception if the standard digest is unavailable
     */
    private static String hash(final byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /**
     * Rejects drift or malformed evidence instead of silently changing a metric population.
     * @param valid required invariant
     * @param reason content-free diagnostic
     */
    private static void require(final boolean valid, final String reason) {
        if (!valid) {
            throw new IllegalArgumentException(reason);
        }
    }
}
