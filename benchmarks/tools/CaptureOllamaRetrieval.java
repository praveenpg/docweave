import dev.docweave.json.ElementJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Explicit live embedding capture with identical query vectors and frozen labels for both retrieval conditions. */
public final class CaptureOllamaRetrieval {
    /** Existing JSON codec for bounded experiment artifacts. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Prevents standalone tool construction. */
    private CaptureOllamaRetrieval() {
    }

    /**
     * Embeds frozen originals/contextual text and independent queries with one explicitly installed model.
     * No context is regenerated, no label is inferred from rankings, and no missing input is truncated.
     * All source chunks participate; ties use original source ordinal rather than relevance labels.
     * @param args generation report, independent queries, Ollama origin, embedding model, k and fresh output directory
     * @throws Exception if inputs/model identities drift, embedding fails or output already exists
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 6,
                "Expected generation report, query file, origin, embedding model, k and fresh output directory");
        final byte[] generationBytes = read(Path.of(args[0]));
        final byte[] queryBytes = read(Path.of(args[1]));
        final var generation = JSON.readTree(generationBytes);
        final var queryNodes = JSON.readTree(queryBytes);
        final var originals = ElementJson.create().read(generation.get("original_chunks").toString());
        require(!originals.isEmpty() && originals.size() <= 100 && queryNodes.isArray() && !queryNodes.isEmpty()
                        && queryNodes.size() <= 100,
                "Live pilot accepts1..100 complete chunks and queries");
        final int topK = Integer.parseInt(args[4]);
        require(topK >= 1 && topK <= 1000, "Invalid common rank depth");
        final var base = URI.create(args[2]);
        require(("http".equals(base.getScheme()) || "https".equals(base.getScheme())) && base.getHost() != null
                        && base.getUserInfo() == null && base.getQuery() == null && base.getFragment() == null
                        && (base.getPath().isEmpty() || base.getPath().equals("/")),
                "Expected explicit Ollama origin");
        final var destination = Path.of(args[5]);
        if (Files.exists(destination)) {
            throw new java.nio.file.FileAlreadyExistsException(destination.toString());
        }
        final var ids = originals.stream().map(element -> element.elementId()).toList();
        final var known = new HashSet<>(ids);
        require(known.size() == ids.size(), "Duplicate original chunk identity");
        final var contextual = generation.get("contextual_chunks");
        require(contextual.isArray() && contextual.size() == originals.size(), "Incomplete contextual population");
        final var inputs = new ArrayList<String>();
        for (final var original : originals) {
            inputs.add(original.text());
        }
        for (int index = 0; index < originals.size(); index++) {
            final var original = originals.get(index);
            final var row = contextual.get(index);
            require(text(row, "chunk_id").equals(original.elementId()) && text(row, "outcome").equals("ENRICHED")
                            && text(row, "embedding_text").equals(text(row, "context") + "\n\n" + original.text()),
                    "Contextual text/source identity differs");
            inputs.add(text(row, "embedding_text"));
        }
        final var queryIds = new HashSet<String>();
        for (final var query : queryNodes) {
            require(queryIds.add(text(query, "id")), "Duplicate independent query");
            require(query.get("relevant_chunk_ids").isArray() && !query.get("relevant_chunk_ids").isEmpty(),
                    "Missing complete relevance population");
            final var relevance = new HashSet<String>();
            for (final var id : query.get("relevant_chunk_ids")) {
                require(id.isString() && known.contains(id.asString()) && relevance.add(id.asString()),
                        "Unknown/duplicate relevant original chunk");
            }
            inputs.add(text(query, "text"));
        }
        for (final var input : inputs) {
            require(input.length() <= 20_000 && input.codePointCount(0, input.length()) <= 10_000,
                    "Embedding input exceeds10000 code points; no input was truncated");
        }
        try (final var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            final var model = model(client, base, args[3]);
            final var embeddingIdentity =
                    Map.of("provider_id", "ollama", "model_id", model.get("name"), "revision", model.get("digest"));
            final var config = Map.of("top_k", topK, "metric", "cosine", "tie_break", "original_chunk_ordinal",
                    "query_representation", "same_raw_query_for_both", "truncate", false);
            Files.createDirectory(destination);
            final byte[] chunksBytes = ElementJson.create().write(originals).getBytes(StandardCharsets.UTF_8);
            write(destination.resolve("chunks.json"), chunksBytes);
            write(destination.resolve("queries.json"), queryBytes);
            write(destination.resolve("contexts.json"), generationBytes);
            writeJson(destination.resolve("manifest.json"),
                    Map.of("schema_version", 1, "source_chunks", identity("chunks.json", chunksBytes), "queries",
                            identity("queries.json", queryBytes), "contexts",
                            identity("contexts.json", generationBytes), "embedding_model", embeddingIdentity,
                            "retrieval_config", config));
            try {
                final var vectors = new ArrayList<double[]>();
                final var raw = new ArrayList<JsonNode>();
                int dimensions = 0;
                for (int start = 0; start < inputs.size(); start += 16) {
                    final var batch = inputs.subList(start, Math.min(inputs.size(), start + 16));
                    final var request = JSON.createObjectNode();
                    request.put("model", model.get("name"));
                    request.put("truncate", false);
                    request.set("input", JSON.valueToTree(batch));
                    final var reply = OllamaContextDocument.send(client,
                            HttpRequest.newBuilder(base.resolve("/api/embed"))
                                    .timeout(Duration.ofMinutes(2))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                                    .build(),
                            8 * 1024 * 1024);
                    require(text(reply, "model").equals(model.get("name")) && reply.get("embeddings").isArray()
                                    && reply.get("embeddings").size() == batch.size(),
                            "Incomplete/unexpected embedding model response");
                    for (final var row : reply.get("embeddings")) {
                        final var unit = unit(row);
                        if (dimensions == 0) {
                            dimensions = unit.length;
                        }
                        require(unit.length == dimensions, "Embedding dimensions changed");
                        vectors.add(unit);
                        raw.add(row);
                    }
                }
                require(model.equals(model(client, base, model.get("name"))),
                        "Embedding model digest changed during capture");
                writeJson(destination.resolve("vectors.json"),
                        Map.of("embedding_model", embeddingIdentity, "dimensions", dimensions, "input_sha256",
                                inputs.stream().map(value -> hash(value.getBytes(StandardCharsets.UTF_8))).toList(),
                                "vectors", raw));
                for (final boolean enriched : List.of(false, true)) {
                    final var observations = new ArrayList<Map<String, Object>>();
                    for (int query = 0; query < queryNodes.size(); query++) {
                        final var queryVector = vectors.get(2 * originals.size() + query);
                        final int offset = enriched ? originals.size() : 0;
                        final var ranks = new ArrayList<Integer>();
                        for (int index = 0; index < originals.size(); index++) {
                            ranks.add(index);
                        }
                        ranks.sort(Comparator
                                        .comparingDouble(
                                                (Integer index) -> cosine(queryVector, vectors.get(offset + index)))
                                        .reversed()
                                        .thenComparingInt(Integer::intValue));
                        observations.add(Map.of("query_id", text(queryNodes.get(query), "id"), "outcome", "SUCCESS",
                                "ranked_chunk_ids", ranks.stream().map(ids::get).toList()));
                    }
                    writeJson(destination.resolve(enriched ? "contextual.json" : "baseline.json"),
                            frame(enriched, hash(chunksBytes), hash(queryBytes), hash(generationBytes),
                                    embeddingIdentity, config, "SUCCESS", observations));
                }
            } catch (final Exception failure) {
                for (final boolean enriched : List.of(false, true)) {
                    final var failedFrame = destination.resolve(enriched ? "contextual.json" : "baseline.json");
                    // A completed frame is evidence; never overwrite it while recording a later I/O failure.
                    if (!Files.exists(failedFrame)) {
                        writeJson(
                                failedFrame, frame(enriched, hash(chunksBytes), hash(queryBytes), hash(generationBytes),
                                                     embeddingIdentity, config, "FAILED", List.of()));
                    }
                }
                replay(destination);
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw failure;
            }
            replay(destination);
            System.out.println("Captured both complete retrieval conditions for " + queryNodes.size() + " queries and "
                               + originals.size() + " chunks. See " + destination.resolve("report.json"));
        }
    }

    /**
     * Supplies the exact guarded replay protocol for one unchanged original population.
     * @param contextual whether generated context was prepended
     * @param source frozen original chunk digest
     * @param queries frozen independent query digest
     * @param contexts frozen generated context artifact digest
     * @param model common actual embedding model identity
     * @param config common metric/index configuration
     * @param outcome complete success/failure
     * @param observations complete original ranked-query observations, empty on frame failure
     * @return capture frame without rewriting source labels
     */
    private static Map<String, Object> frame(final boolean contextual, final String source, final String queries,
            final String contexts, final Map<String, String> model, final Map<String, ?> config, final String outcome,
            final List<Map<String, Object>> observations) {
        return Map.of("profile", contextual ? "CONTEXTUAL" : "BASELINE", "source_chunks_sha256", source,
                "content_sha256", contextual ? contexts : source, "queries_sha256", queries, "embedding_model", model,
                "retrieval_config", config, "outcome", outcome, "observations", observations);
    }

    /**
     * Replays exactly the saved capture files through the independently tested complete-population scorer.
     * @param root owned fresh run directory
     * @throws Exception if protocol identities or numeric scoring fail
     */
    private static void replay(final Path root) throws Exception {
        dev.docweave.validation.EvaluateContextualRetrieval.main(new String[] {root.resolve("manifest.json").toString(),
                root.toString(), root.resolve("baseline.json").toString(), root.resolve("contextual.json").toString(),
                root.resolve("report.json").toString()});
    }

    /**
     * Resolves one explicit installed model and its original server-reported digest without pulling weights.
     * @param client caller-owned transport
     * @param base explicit server origin
     * @param requested exact installed model tag
     * @return canonical name and digest
     * @throws Exception if inventory access or model resolution fails
     */
    private static Map<String, String> model(final HttpClient client, final URI base, final String requested)
            throws Exception {
        final var reply = OllamaContextDocument.send(client,
                HttpRequest.newBuilder(base.resolve("/api/tags")).timeout(Duration.ofSeconds(30)).GET().build());
        require(reply.get("models").isArray(), "Missing installed embedding model inventory");
        final var matches = new ArrayList<Map<String, String>>();
        for (final var item : reply.get("models")) {
            final String name = text(item, "name");
            if (name.equals(requested) || name.equals(requested + ":latest")) {
                matches.add(Map.of("name", name, "digest", text(item, "digest")));
            }
        }
        require(matches.size() == 1, "Expected one explicitly installed embedding model");
        return matches.getFirst();
    }

    /**
     * Validates and L2-normalizes actual finite nonzero model vectors for stable cosine comparisons.
     * @param row original model vector
     * @return normalized numeric copy without changing captured raw values
     */
    private static double[] unit(final JsonNode row) {
        require(row.isArray() && !row.isEmpty() && row.size() <= 4096, "Invalid embedding vector dimension");
        final double[] values = new double[row.size()];
        double norm = 0;
        for (int index = 0; index < values.length; index++) {
            require(row.get(index).isNumber(), "Embedding coordinate is not numeric");
            values[index] = row.get(index).asDouble();
            require(Double.isFinite(values[index]), "Embedding coordinate is not finite");
            norm = Math.hypot(norm, values[index]);
        }
        require(norm > 0 && Double.isFinite(norm), "Embedding norm must be positive and finite");
        for (int index = 0; index < values.length; index++) {
            values[index] /= norm;
        }
        return values;
    }

    /**
     * Computes cosine from validated equal-dimension unit vectors, preserving deterministic source-ordinal ties.
     * @param first fixed query vector
     * @param second original or contextual source vector
     * @return finite similarity
     */
    private static double cosine(final double[] first, final double[] second) {
        double total = 0;
        for (int index = 0; index < first.length; index++) {
            total += first[index] * second[index];
        }
        return total;
    }

    /**
     * Reads encoded-size-bounded original evidence without modifying its bytes.
     * @param path original file
     * @return complete bytes, at most16MiB
     * @throws Exception if reading or the encoded ceiling fails
     */
    private static byte[] read(final Path path) throws Exception {
        try (final var input = Files.newInputStream(path)) {
            final byte[] raw = input.readNBytes(16 * 1024 * 1024 + 1);
            require(raw.length <= 16 * 1024 * 1024, "Capture input exceeds16MiB");
            return raw;
        }
    }

    /**
     * Pins one unchanged encoded artifact with a root-relative filename.
     * @param path original output filename
     * @param raw original bytes
     * @return source identity
     */
    private static Map<String, String> identity(final String path, final byte[] raw) {
        return Map.of("path", path, "sha256", hash(raw));
    }

    /**
     * Hashes exact bounded source/vector configuration bytes.
     * @param raw original representation
     * @return lowercase SHA256
     */
    private static String hash(final byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (final java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Writes one fresh encoded artifact without overwriting an existing run.
     * @param path owned fresh destination
     * @param raw complete bytes
     * @throws Exception if output exists or writing fails
     */
    private static void write(final Path path, final byte[] raw) throws Exception {
        Files.write(path, raw, StandardOpenOption.CREATE_NEW);
    }

    /**
     * Writes a complete JSON tree/value as a fresh experiment artifact.
     * @param path owned fresh destination
     * @param value complete artifact value
     * @throws Exception if serialization/output fails
     */
    private static void writeJson(final Path path, final Object value) throws Exception {
        write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    /**
     * Reads required exact nonblank text without coercing model/configuration identities.
     * @param node containing original JSON object
     * @param key required field
     * @return original text
     */
    private static String text(final JsonNode node, final String key) {
        require(node != null && node.get(key) != null && node.get(key).isString()
                        && !node.get(key).asString().isBlank(),
                "Missing capture identity/text");
        return node.get(key).asString();
    }

    /**
     * Rejects malformed/drifting evidence without repairing labels or truncating an input population.
     * @param value required invariant
     * @param reason content-free diagnostic
     */
    private static void require(final boolean value, final String reason) {
        if (!value) {
            throw new IllegalArgumentException(reason);
        }
    }
}
