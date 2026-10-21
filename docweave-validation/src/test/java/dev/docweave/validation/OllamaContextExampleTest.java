package dev.docweave.validation;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

/** Local HTTP protocol controls for the real-model launcher; no actual model or external server is invoked. */
class OllamaContextExampleTest {
    /** Isolated authored source, compiled launcher and fresh report paths. */
    @TempDir Path root;
    /** Existing JSON codec for independent HTTP fixtures. */
    private final JsonMapper json = JsonMapper.builder().build();

    /** Creates real HTTP transport controls without model weights or network dependencies. */
    OllamaContextExampleTest() {
    }

    /**
     * The source launcher sends text-only explicit requests and exports untouched originals plus generated context.
     * @throws Exception if compilation, local HTTP or source execution fails
     */
    @Test
    void compiledLauncherUsesExplicitModelAndExportsOriginalsSeparately() throws Exception {
        compile();
        try (final var server = server()) {
            invoke(server, "owned-chat:8b", "success.json");
            final var report = json.readTree(Files.readString(root.resolve("success.json")));
            assertEquals("owned-chat:8b", report.get("model").asString());
            assertEquals("1".repeat(64), report.get("model_digest").asString());
            assertEquals("NOT_EVALUATED", report.get("retrieval_quality").asString());
            final var originals = new java.util.HashMap<String, String>();
            for (final var original : report.get("original_chunks")) {
                originals.put(original.get("element_id").asString(), original.get("text").asString());
                assertTrue(original.get("metadata").has("orig_elements"));
            }
            assertFalse(originals.isEmpty());
            assertEquals(originals.size(), report.get("contextual_chunks").size());
            for (final var context : report.get("contextual_chunks")) {
                assertEquals("Owned report context.", context.get("context").asString());
                assertEquals("Owned report context.\n\n" + originals.get(context.get("chunk_id").asString()),
                        context.get("embedding_text").asString());
                assertEquals(100, context.get("usage").get("prompt_eval_count").asInt());
            }
            assertEquals(originals.size(), server.requests().size());
            for (final var request : server.requests()) {
                assertEquals("owned-chat:8b", request.get("model").asString());
                assertFalse(request.get("stream").asBoolean());
                assertFalse(request.get("think").asBoolean());
                assertEquals(32768, request.get("options").get("num_ctx").asInt());
                final var source = json.readTree(request.get("messages").get(1).get("content").asString());
                assertEquals(2, source.size());
                assertTrue(source.get("document_text").asString().contains("Acme"));
                assertFalse(source.get("chunk_text").asString().isBlank());
            }
        }
    }

    /**
     * Truncation, wrong models, response overflow and retagging fail without partial/overwritten output.
     * @throws Exception if compilation or local transport setup fails
     */
    @Test
    void invalidGenerationAndModelDriftDoNotProduceSuccessfulReports() throws Exception {
        compile();
        try (final var server = server()) {
            for (final var mode : List.of("TRUNCATED", "WRONG_MODEL", "TOO_LARGE", "DRIFT")) {
                server.mode().set(mode);
                server.digest().set("1".repeat(64));
                assertThrows(java.io.IOException.class, () -> invoke(server, "owned-chat:8b", mode + ".json"), mode);
                assertFalse(Files.exists(root.resolve(mode + ".json")));
            }
            final int priorCalls = server.requests().size();
            assertThrows(java.io.IOException.class, () -> invoke(server, "missing-model:8b", "missing.json"));
            assertEquals(priorCalls, server.requests().size());
            Files.writeString(root.resolve("owner.json"), "owner output");
            assertThrows(java.nio.file.FileAlreadyExistsException.class,
                    () -> invoke(server, "owned-chat:8b", "owner.json"));
            assertEquals("owner output", Files.readString(root.resolve("owner.json")));
            assertEquals(priorCalls, server.requests().size());
        }
    }

    /**
     * Frozen original IDs survive generation without repartitioning or remapping relevance labels.
     * @throws Exception if source compilation, bounded HTTP or identity preservation fails
     */
    @Test
    void frozenChunksKeepReferenceIdentitiesAcrossGeneration() throws Exception {
        compile();
        try (final var server = server()) {
            invoke(server, "owned-chat:8b", "first.json");
            final var originals = json.readTree(Files.readString(root.resolve("first.json"))).get("original_chunks");
            final var frozen = json.createObjectNode();
            frozen.put("document_text", "Acme 2026 report. Revenue increased fifteen percent.");
            frozen.set("original_chunks", originals);
            Files.write(root.resolve("frozen.json"), json.writeValueAsBytes(frozen));
            try (final var loader = new URLClassLoader(
                         new java.net.URL[] {root.toUri().toURL()}, getClass().getClassLoader())) {
                final var entry = loader.loadClass("OllamaContextDocument").getDeclaredMethod("main", String[].class);
                entry.setAccessible(true);
                entry.invoke(null, (Object) new String[] {"--frozen-chunks", root.resolve("frozen.json").toString(),
                                           "http://127.0.0.1:" + server.server().getAddress().getPort(),
                                           "owned-chat:8b", root.resolve("second.json").toString()});
            }
            final var second = json.readTree(Files.readString(root.resolve("second.json")));
            assertEquals(originals, second.get("original_chunks"));
            for (int index = 0; index < originals.size(); index++) {
                assertEquals(originals.get(index).get("element_id").asString(),
                        second.get("contextual_chunks").get(index).get("chunk_id").asString());
            }
        }
    }

    /**
     * Actual HTTP embedding capture preserves common queries, deterministic ties and failure denominators.
     * @throws Exception if standalone compilation, capture or independent numeric assertions fail
     */
    @Test
    void liveCaptureUsesFrozenQueriesAndRetainsInvalidVectorFailures() throws Exception {
        compile();
        try (final var server = server()) {
            invoke(server, "owned-chat:8b", "generation.json");
            final var generation = json.readTree(Files.readString(root.resolve("generation.json")));
            final var id = generation.get("original_chunks").get(0).get("element_id").asString();
            Files.writeString(
                    root.resolve("queries.json"), json.writeValueAsString(List.of(Map.of("id", "independent", "text",
                                                          "What increased?", "relevant_chunk_ids", List.of(id)))));
            for (final var mode : List.of("SUCCESS", "ZERO_VECTOR", "WRONG_DIMENSION")) {
                server.mode().set(mode);
                final var output = root.resolve("capture-" + mode);
                if (mode.equals("SUCCESS")) {
                    capture(server, output);
                } else {
                    assertThrows(java.io.IOException.class, () -> capture(server, output));
                }
                final var report = json.readTree(Files.readString(output.resolve("report.json")));
                for (final var profile : List.of("baseline", "contextual")) {
                    final var score = report.get(profile).get("score");
                    assertEquals(1, score.get("reference_queries").asInt());
                    assertEquals(mode.equals("SUCCESS") ? 1.0 : 0.0, score.get("hit_rate_at_k").asDouble());
                    assertEquals(mode.equals("SUCCESS") ? 0 : 1, score.get("failed_queries").asInt());
                }
            }
        }
    }

    /**
     * Invokes the actual capture in one JVM runtime package with its shared transport and replay helpers.
     * @param server independent loopback responses
     * @param output fresh capture directory
     * @throws Exception if the capture fails or exceeds its test deadline
     */
    private void capture(final LocalServer server, final Path output) throws Exception {
        // A separate JVM keeps package-private replay helpers and the CLI in one runtime package.
        final var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", root + java.io.File.pathSeparator + System.getProperty("java.class.path"),
                "CaptureOllamaRetrieval", root.resolve("generation.json").toString(),
                root.resolve("queries.json").toString(), "http://127.0.0.1:" + server.server().getAddress().getPort(),
                "owned-chat:8b", "1", output.toString())
                                    .redirectErrorStream(true)
                                    .redirectOutput(root.resolve(output.getFileName() + ".log").toFile())
                                    .start();
        try {
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new java.io.IOException("Owned capture process timed out");
            }
            if (process.exitValue() != 0) {
                throw new java.io.IOException("Owned capture rejected invalid response; see local capture log");
            }
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * Compiles the actual standalone source on Java21 and authors a small independent native input.
     * @throws Exception if source compilation or fixture output fails
     */
    private void compile() throws Exception {
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        assertEquals(0,
                compiler.run(null, null, null, "--release", "21", "-proc:none", "-classpath",
                        System.getProperty("java.class.path"), "-d", root.toString(),
                        "examples/OllamaContextDocument.java", "benchmarks/tools/CaptureOllamaRetrieval.java",
                        "benchmarks/tools/EvaluateContextualRetrieval.java",
                        "docweave-validation/src/test/java/dev/docweave/validation/ContextualRetrievalReplay.java",
                        "docweave-validation/src/test/java/dev/docweave/validation/ContextualRetrievalEvaluation.java"));
        Files.writeString(root.resolve("report.txt"), "Acme 2026 report\n\nRevenue increased fifteen percent.");
    }

    /**
     * Runs the original launcher with a controlled local origin and exact installed model tag.
     * @param server owned HTTP server
     * @param model explicit installed/missing model
     * @param output fresh report path
     * @throws Exception original launcher failure without reflection wrapping
     */
    private void invoke(final LocalServer server, final String model, final String output) throws Exception {
        try (final var loader =
                        new URLClassLoader(new java.net.URL[] {root.toUri().toURL()}, getClass().getClassLoader())) {
            final var entry = loader.loadClass("OllamaContextDocument").getDeclaredMethod("main", String[].class);
            entry.setAccessible(true);
            try {
                entry.invoke(null, (Object) new String[] {root.resolve("report.txt").toString(),
                                           "http://127.0.0.1:" + server.server().getAddress().getPort(), model,
                                           root.resolve(output).toString()});
            } catch (final InvocationTargetException failure) {
                if (failure.getCause() instanceof final Exception exception) {
                    throw exception;
                }
                throw (Error) failure.getCause();
            }
        }
    }

    /**
     * Owned loopback-only HTTP lifecycle and independent observed request log.
     * @param server ephemeral-port HTTP server
     * @param mode selected independent response fault
     * @param digest currently installed model identity
     * @param requests exact received chat requests, without manufactured model quality claims
     */
    private record LocalServer(HttpServer server, AtomicReference<String> mode, AtomicReference<String> digest,
            ConcurrentLinkedQueue<JsonNode> requests) implements AutoCloseable {
        /** Stops only the test-owned HTTP listener, including after assertion/launcher failures. */
        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * Creates independent Ollama-shaped responses while retaining the actual sent request bodies.
     * @return owned local-only server
     * @throws Exception if listener binding or fixture setup fails
     */
    private LocalServer server() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var state = new LocalServer(server, new AtomicReference<>("SUCCESS"),
                new AtomicReference<>("1".repeat(64)), new ConcurrentLinkedQueue<>());
        server.createContext("/api/tags", exchange -> {
            final byte[] body = json.writeValueAsBytes(
                    Map.of("models", List.of(Map.of("name", "owned-chat:8b", "digest", state.digest().get()))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (final var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/api/chat", exchange -> {
            try (final var input = exchange.getRequestBody()) {
                state.requests().add(json.readTree(input.readNBytes(64 * 1024)));
            }
            final String mode = state.mode().get();
            final byte[] body = mode.equals("TOO_LARGE")
                                        ? json.writeValueAsBytes(Map.of("padding", "x".repeat(256 * 1024)))
                                        : json.writeValueAsBytes(Map.of("model",
                                                  mode.equals("WRONG_MODEL") ? "other-model" : "owned-chat:8b", "done",
                                                  true, "done_reason", mode.equals("TRUNCATED") ? "length" : "stop",
                                                  "message", Map.of("content", "Owned report context."),
                                                  "prompt_eval_count", 100, "eval_count", 12));
            if (mode.equals("DRIFT")) {
                state.digest().set("2".repeat(64));
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (final var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/api/embed", exchange -> {
            final JsonNode request;
            try (final var input = exchange.getRequestBody()) {
                request = json.readTree(input.readAllBytes());
            }
            final var vectors = new java.util.ArrayList<List<Integer>>();
            for (int index = 0; index < request.get("input").size(); index++) {
                vectors.add(state.mode().get().equals("ZERO_VECTOR")                     ? List.of(0, 0)
                            : state.mode().get().equals("WRONG_DIMENSION") && index == 1 ? List.of(1, 0, 0)
                                                                                         : List.of(3, 4));
            }
            final boolean valid = request.has("truncate") && !request.get("truncate").asBoolean()
                                  && request.get("model").asString().equals("owned-chat:8b")
                                  && request.get("input").size() == 3;
            final byte[] body = json.writeValueAsBytes(Map.of("model", "owned-chat:8b", "embeddings", vectors));
            exchange.sendResponseHeaders(valid ? 200 : 400, body.length);
            try (final var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return state;
    }
}
