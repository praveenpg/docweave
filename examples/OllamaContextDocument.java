import dev.docweave.DocWeave;
import dev.docweave.chunking.ChunkContextProvider;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.chunking.ContextualChunkOptions;
import dev.docweave.chunking.ContextualChunker;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

/** Explicit real-model example using a caller-selected Ollama server; no model download or default provider changes. */
final class OllamaContextDocument {
    /** Bounded JSON codec; existing convenience-artifact runtime dependency. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Accepted original document code points; larger documents fail without truncation. */
    private static final int MAX_DOCUMENT = 20_000;
    /** Conservative serialized-prompt byte guard, separate from model-specific token budgeting. */
    private static final int MAX_PROMPT_BYTES = 24_000;
    /** Response retention ceiling for model inventory and nonstreaming chat replies. */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    /** Prevents construction of the source-file launcher. */
    private OllamaContextDocument() {
    }

    /**
     * Generates context for an actual native document using one explicitly named installed chat model.
     * @param args optional --frozen-chunks, input document or frozen JSON, server origin, model and fresh report
     * @throws Exception if input limits, model HTTP calls, generation or output writing fail
     */
    public static void main(final String[] args) throws Exception {
        final boolean frozen = args.length == 5 && args[0].equals("--frozen-chunks");
        final String[] input = frozen ? java.util.Arrays.copyOfRange(args, 1, args.length) : args;
        if (input.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: OllamaContextDocument.java input.pdf http://HOST:11434 MODEL output.json");
        }
        final var base = URI.create(input[1]);
        if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null
                || base.getQuery() != null || base.getFragment() != null || base.getUserInfo() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
            throw new IllegalArgumentException("Ollama URL must be an HTTP(S) origin without credentials/query/path");
        }
        final var output = Path.of(input[3]);
        if (Files.exists(output)) {
            throw new java.nio.file.FileAlreadyExistsException(output.toString());
        }
        final String document;
        final List<Element> chunks;
        final Object warnings;
        if (frozen) {
            final byte[] raw;
            try (final var stream = Files.newInputStream(Path.of(input[0]))) {
                raw = stream.readNBytes(16 * 1024 * 1024 + 1);
            }
            if (raw.length > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("Frozen source exceeds16MiB");
            }
            final var source = JSON.readTree(raw);
            if (!source.has("document_text") || !source.get("document_text").isString()
                    || !source.has("original_chunks") || !source.get("original_chunks").isArray()) {
                throw new IllegalArgumentException("Expected frozen document_text and original_chunks");
            }
            document = source.get("document_text").asString();
            chunks = ElementJson.create().read(source.get("original_chunks").toString());
            warnings = List.of();
        } else {
            final var partitioned = DocWeave.create().partition(Path.of(input[0]));
            document = documentText(partitioned.elements());
            chunks = Chunker.create().byTitle(partitioned.elements(), ChunkOptions.builder()
                                                                              .maxCharacters(1500)
                                                                              .newAfterCharacters(1000)
                                                                              .combineTextUnderCharacters(0)
                                                                              .build());
            warnings = partitioned.warnings();
        }
        if (document.isBlank()) {
            throw new IllegalArgumentException("No native document text; configure OCR before contextual enrichment");
        }
        if (chunks.size() > 100) {
            throw new IllegalArgumentException("Example accepts at most100 complete chunks");
        }
        final var limits = new ContextualChunkOptions(MAX_DOCUMENT, 1500, 1500, 100, 1, Duration.ofMinutes(10),
                ContextualChunkOptions.FailurePolicy.FAIL_DOCUMENT);
        try (final var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            final var model = installedModel(client, base, input[2]);
            final var provider = new OllamaProvider(client, base, model);
            final var results = ContextualChunker.create(provider).enrich(document, chunks, limits);
            if (!model.equals(installedModel(client, base, model.name()))) {
                throw new IOException("Installed model identity changed during generation");
            }
            final var report = JSON.createObjectNode();
            report.put("created_at", Instant.now().toString());
            report.put("ollama_origin", base.toString());
            report.put("model", model.name());
            report.put("model_digest", model.digest());
            report.put("document_code_points", document.codePointCount(0, document.length()));
            report.put("context_window", 32768);
            report.put("temperature", 0);
            report.put("maximum_generated_tokens", 256);
            report.put("thinking", false);
            report.put("retrieval_quality", "NOT_EVALUATED");
            report.set("original_chunks", JSON.readTree(ElementJson.create().write(chunks)));
            report.set("partition_warnings", JSON.valueToTree(warnings));
            final var enriched = report.putArray("contextual_chunks");
            for (int index = 0; index < results.size(); index++) {
                final var result = results.get(index);
                final var context = result.context().orElseThrow();
                final var row = enriched.addObject();
                row.put("chunk_id", result.original().elementId());
                row.put("outcome", result.outcome().name());
                row.put("context", context.text());
                row.put("provider_id", context.providerId());
                row.put("model_id", context.modelId());
                row.put("embedding_text", result.embeddingText());
                row.set("usage", JSON.valueToTree(provider.usage.get(index)));
            }
            Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                    StandardOpenOption.CREATE_NEW);
            System.out.println("Generated model context for " + results.size()
                               + " chunks; original text/provenance retained. Wrote " + output);
        }
    }

    /**
     * Stages explicit native text only, charging separators before growing the bounded context string.
     * @param elements complete partitioned source units
     * @return source text, excluding image payloads and metadata
     */
    private static String documentText(final List<Element> elements) {
        final var result = new StringBuilder();
        int used = 0;
        for (final var element : elements) {
            if (element.type() == ElementType.Image || element.text().isBlank()) {
                continue;
            }
            final int separator = result.isEmpty() ? 0 : 2;
            if (element.text().length() > 2L * MAX_DOCUMENT
                    || element.text().codePointCount(0, element.text().length()) > MAX_DOCUMENT - used - separator) {
                throw new IllegalArgumentException(
                        "Example document context exceeds20000 code points; input was not truncated");
            }
            if (separator != 0) {
                result.append("\n\n");
            }
            result.append(element.text());
            used += separator + element.text().codePointCount(0, element.text().length());
        }
        return result.toString();
    }

    /**
     * Original installed model identity, checked before/after the explicitly requested generation run.
     * @param name canonical local model tag
     * @param digest server-reported installed artifact digest
     */
    private record Model(String name, String digest) {}

    /**
     * Looks up an installed model; never pulls missing weights or silently substitutes another model.
     * @param client caller-owned HTTP client
     * @param base explicit Ollama origin
     * @param requested explicit model tag
     * @return original installed descriptor
     * @throws Exception if the inventory is unavailable/malformed or the requested model is absent
     */
    private static Model installedModel(final HttpClient client, final URI base, final String requested)
            throws Exception {
        final var inventory = send(
                client, HttpRequest.newBuilder(base.resolve("/api/tags")).timeout(Duration.ofMinutes(2)).GET().build());
        if (inventory.get("models") == null || !inventory.get("models").isArray()) {
            throw new IOException("Ollama returned no installed model inventory");
        }
        final var matches = new ArrayList<Model>();
        for (final var item : inventory.get("models")) {
            final String name = text(item, "name");
            if (name.equals(requested) || name.equals(requested + ":latest")) {
                matches.add(new Model(name, text(item, "digest")));
            }
        }
        if (matches.size() != 1) {
            throw new IOException("The requested model must be installed with one unambiguous tag");
        }
        return matches.getFirst();
    }

    /** Caller-owned model adapter; context requests contain source text only, not images/provenance. */
    private static final class OllamaProvider implements ChunkContextProvider {
        /** Shared caller-owned thread-safe HTTP transport. */
        private final HttpClient client;
        /** Explicit server origin. */
        private final URI base;
        /** Frozen installed tag/digest. */
        private final Model model;
        /** Bounded per-chunk generation diagnostics, exported only after complete success. */
        private final Map<Integer, Map<String, Object>> usage = new ConcurrentHashMap<>();

        /**
         * Captures explicit transport/model identity without invoking generation.
         * @param client caller-owned HTTP client
         * @param base selected server origin
         * @param model installed model descriptor
         */
        OllamaProvider(final HttpClient client, final URI base, final Model model) {
            this.client = client;
            this.base = base;
            this.model = model;
        }

        @Override
        public Context generate(final Request request) throws IOException {
            final String instructions =
                    "Write at most two concise sentences situating the chunk in the supplied document. "
                    + "Include identifying document/company/time/section information only when supported by the source. "
                    + "Use no outside facts. Return context only, without rewriting the chunk or adding commentary.";
            final String source = JSON.writeValueAsString(
                    Map.of("document_text", request.documentText(), "chunk_text", request.chunkText()));
            final int promptBytes = instructions.getBytes(StandardCharsets.UTF_8).length
                                    + source.getBytes(StandardCharsets.UTF_8).length;
            if (promptBytes > MAX_PROMPT_BYTES) {
                throw new IOException("Example prompt exceeds conservative24000-byte guard; no prompt was truncated");
            }
            final var body = JSON.createObjectNode();
            body.put("model", model.name());
            body.put("stream", false);
            body.put("think", false);
            body.set("options", JSON.valueToTree(Map.of("temperature", 0, "num_predict", 256, "num_ctx", 32768)));
            body.set("messages", JSON.valueToTree(List.of(Map.of("role", "system", "content", instructions),
                                         Map.of("role", "user", "content", source))));
            final JsonNode reply;
            try {
                reply = send(client, HttpRequest.newBuilder(base.resolve("/api/chat"))
                                             .timeout(Duration.ofMinutes(2))
                                             .header("Content-Type", "application/json")
                                             .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                                             .build());
            } catch (final InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("Ollama request interrupted");
            }
            if (!model.name().equals(text(reply, "model")) || reply.get("done") == null
                    || !reply.get("done").isBoolean() || !reply.get("done").asBoolean()
                    || !"stop".equals(text(reply, "done_reason"))) {
                throw new IOException("Ollama generation was incomplete, truncated or used an unexpected model");
            }
            usage.put(request.chunkIndex(),
                    Map.of("prompt_bytes", promptBytes, "prompt_eval_count", number(reply, "prompt_eval_count"),
                            "eval_count", number(reply, "eval_count")));
            return new Context(text(reply.get("message"), "content"), "ollama", model.name());
        }
    }

    /**
     * Sends one bounded nonstreaming JSON request; the body subscriber rejects oversize content before retaining it.
     * @param client explicit owned HTTP client
     * @param request complete inventory/chat request with a transport deadline
     * @return parsed successful JSON reply
     * @throws IOException if status, bounded body or JSON parsing fails
     * @throws InterruptedException if caller/provider cancellation interrupts transport
     */
    static JsonNode send(final HttpClient client, final HttpRequest request) throws IOException, InterruptedException {
        return send(client, request, MAX_RESPONSE_BYTES);
    }

    /**
     * Shares bounded example transport with the explicitly invoked retrieval capture tool.
     * @param client caller-owned HTTP transport
     * @param request complete original request
     * @param maximum positive retained response bytes, at most16MiB
     * @return complete parsed successful reply
     * @throws IOException if status, response limit or JSON parsing fails
     * @throws InterruptedException if caller cancellation interrupts transport
     */
    static JsonNode send(final HttpClient client, final HttpRequest request, final int maximum)
            throws IOException, InterruptedException {
        if (maximum < 1 || maximum > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid retained Ollama response limit");
        }
        final var response = client.send(request, ignored -> boundedBody(maximum));
        if (response.statusCode() != 200) {
            throw new IOException("Ollama returned HTTP " + response.statusCode());
        }
        return JSON.readTree(response.body());
    }

    /**
     * Creates a request-confined response collector with a fixed retained-byte ceiling.
     * @param maximum accepted retained response bytes
     * @return subscriber whose completion includes the complete response body
     */
    private static HttpResponse.BodySubscriber<byte[]> boundedBody(final int maximum) {
        return new HttpResponse.BodySubscriber<>() {
            /** Complete bounded response or transport/limit failure. */
            private final CompletableFuture<byte[]> result = new CompletableFuture<>();
            /** Retained response bytes, never exceeding the declared ceiling. */
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            /** Current HTTP body flow subscription, cancelled on budget failure. */
            private Flow.Subscription subscription;

            /** @return future completed only after the full bounded response */
            @Override
            public CompletionStage<byte[]> getBody() {
                return result;
            }

            /**
             * Requests one bounded flow delivery at a time.
             * @param value HTTP transport subscription
             */
            @Override
            public void onSubscribe(final Flow.Subscription value) {
                subscription = value;
                value.request(1);
            }

            /**
             * Checks each delivered buffer before copying any bytes into the retained response.
             * @param buffers current transport delivery
             */
            @Override
            public void onNext(final List<ByteBuffer> buffers) {
                for (final var buffer : buffers) {
                    if (buffer.remaining() > maximum - bytes.size()) {
                        subscription.cancel();
                        result.completeExceptionally(new IOException("Ollama response exceeds retained byte limit"));
                        return;
                    }
                    final byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    bytes.writeBytes(data);
                }
                subscription.request(1);
            }

            /**
             * Propagates an HTTP body failure without manufacturing a partial JSON result.
             * @param failure original transport failure
             */
            @Override
            public void onError(final Throwable failure) {
                result.completeExceptionally(failure);
            }

            /** Completes one full bounded body; failed/oversize responses remain exceptional. */
            @Override
            public void onComplete() {
                result.complete(bytes.toByteArray());
            }
        };
    }

    /**
     * Reads a required exact response string without coercion or silently replacing empty generation.
     * @param node containing reply object
     * @param name required field
     * @return unchanged response text
     * @throws IOException if required content/provenance is absent
     */
    private static String text(final JsonNode node, final String name) throws IOException {
        if (node == null || node.get(name) == null || !node.get(name).isString()
                || node.get(name).asString().isBlank()) {
            throw new IOException("Ollama response lacks a required text field");
        }
        return node.get(name).asString();
    }

    /**
     * Reads nonnegative original token-count diagnostics rather than inventing model usage.
     * @param node original reply
     * @param name required count field
     * @return original integer count
     * @throws IOException if a usage count is absent/invalid
     */
    private static long number(final JsonNode node, final String name) throws IOException {
        if (node.get(name) == null || !node.get(name).isIntegralNumber() || node.get(name).asLong() < 0) {
            throw new IOException("Ollama response lacks a valid usage count");
        }
        return node.get(name).asLong();
    }
}
