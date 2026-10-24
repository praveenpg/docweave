package io.docweave.validation;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** JVM verification of an explicitly prepared local geometry graph against pinned source-model tensor fixtures. */
public final class VerifyPreparedOnnxGeometry {
    /** Prevents standalone verifier construction. */
    private VerifyPreparedOnnxGeometry() {
    }

    /**
     * Runs exactly two predeclared non-square tensor controls using Java CPU ONNX Runtime.
     * @param args prepared local cache, exact preparation JSON SHA256 and fresh numeric report
     * @throws Exception if identities, tensor shapes, finite outputs or numerical tolerances fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 3 && !Files.exists(Path.of(args[2])), "Expected prepared cache, hash and fresh report");
        require(difference(FloatBuffer.wrap(new float[] {1, -2}), FloatBuffer.wrap(new float[] {1, -2})) == 0,
                "Exact tensor control failed");
        require(difference(FloatBuffer.wrap(new float[] {1, -2}), FloatBuffer.wrap(new float[] {2, -2})) == 1,
                "Changed tensor control failed");
        final var root = Path.of(args[0]);
        final byte[] protocol = Files.readAllBytes(root.resolve("preparation.json"));
        require(hash(protocol).equals(args[1]), "Preparation identity changed");
        final var mapper = JsonMapper.builder().build();
        final var preparation = mapper.readTree(protocol);
        require(preparation.get("revision").asString().equals("c8e8338b3aad32867e015e81a7a4fc48b4c955d1")
                        && preparation.get("files")
                                .get("model.safetensors")
                                .get("sha256")
                                .asString()
                                .equals("b9e576cd167f6f460aebb53afc0f799e876751b5c1073b593f929aea5c13e46b"),
                "Declared financial checkpoint changed");
        final var graph = root.resolve("structure-fin.onnx");
        require(Files.size(graph) <= 128 * 1024 * 1024, "Prepared graph exceeds model cap");
        require(hash(Files.readAllBytes(graph))
                        .equals(preparation.get("files").get("structure-fin.onnx").get("sha256").asString()),
                "Prepared graph identity changed");
        require(preparation.get("inputs").size() == 2, "Two-shape numerical population changed");
        final var results = new ArrayList<Map<String, Object>>();
        try (final var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(4);
            options.setInterOpNumThreads(1);
            try (final var environment = OrtEnvironment.getEnvironment();
                    final var session = environment.createSession(graph.toString(), options)) {
                require(session.getInputNames().equals(java.util.Set.of("pixel_values", "pixel_mask"))
                                && session.getOutputNames().equals(java.util.Set.of("logits", "pred_boxes")),
                        "Prepared tensor schema changed");
                int ordinal = 0;
                for (final var control : preparation.get("inputs")) {
                    final String prefix = "parity-" + ordinal;
                    require(control.get("prefix").asString().equals(prefix), "Tensor fixture sequence changed");
                    final long height = ordinal == 0 ? 800 : 1000;
                    final long width = ordinal == 0 ? 1000 : 667;
                    final long[] shape = {1, 3, height, width};
                    require(Arrays.equals(shape, shape(control.get("shape"))), "Predeclared non-square shape changed");
                    final var values = bytes(root, preparation, prefix + "-input.bin").asFloatBuffer();
                    final var mask = bytes(root, preparation, prefix + "-mask.bin").asLongBuffer();
                    require(values.remaining() == 3 * height * width && mask.remaining() == height * width,
                            "Input tensor byte counts changed");
                    try (final var input = OnnxTensor.createTensor(environment, values, shape);
                            final var inputMask =
                                    OnnxTensor.createTensor(environment, mask, new long[] {1, height, width});
                            final var output = session.run(Map.of("pixel_values", input, "pixel_mask", inputMask))) {
                        final var logits = (OnnxTensor) output.get("logits").orElseThrow();
                        final var boxes = (OnnxTensor) output.get("pred_boxes").orElseThrow();
                        require(Arrays.equals(logits.getInfo().getShape(), new long[] {1, 125, 7})
                                        && Arrays.equals(boxes.getInfo().getShape(), new long[] {1, 125, 4}),
                                "Output tensor dimensions changed");
                        final double logitDifference =
                                difference(bytes(root, preparation, prefix + "-logits.bin").asFloatBuffer(),
                                        logits.getFloatBuffer());
                        final double boxDifference =
                                difference(bytes(root, preparation, prefix + "-boxes.bin").asFloatBuffer(),
                                        boxes.getFloatBuffer());
                        results.add(Map.of("height", height, "width", width, "max_absolute_logit_error",
                                logitDifference, "max_absolute_box_error", boxDifference, "logit_values", 875,
                                "box_values", 500, "within_predeclared_tolerances",
                                logitDifference <= 0.001 && boxDifference <= 0.0001));
                    }
                    ordinal++;
                }
            }
        }
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("preparation_sha256", args[1],
                        "java_runtime", System.getProperty("java.runtime.version"), "maximum_logit_error", 0.001,
                        "maximum_box_error", 0.0001, "results", results, "scope",
                        "Two fixed source-model tensor controls only; no DocWeave extraction, preprocessing or recognition-quality score."))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
        require(results.stream().allMatch(result -> Boolean.TRUE.equals(result.get("within_predeclared_tolerances"))),
                "Numerical control failed; complete error report retained");
    }

    /**
     * Validates one small prepared tensor's raw byte identity before reading its little-endian scalar view.
     * @param root explicitly supplied local fixture directory
     * @param preparation pinned file identities
     * @param name fixed internal fixture name
     * @return borrowed bounded byte buffer
     * @throws Exception if counts, hashes or reading fail
     */
    private static ByteBuffer bytes(final Path root, final JsonNode preparation, final String name) throws Exception {
        final var path = root.resolve(name);
        require(Files.size(path) <= 24 * 1024 * 1024, "Tensor fixture exceeds cap");
        final byte[] bytes = Files.readAllBytes(path);
        final var expected = preparation.get("files").get(name);
        require(bytes.length == expected.get("bytes").asLong() && hash(bytes).equals(expected.get("sha256").asString()),
                "Prepared tensor identity changed");
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Reads the complete declared tensor dimensions without accepting inferred or omitted axes.
     * @param node pinned shape array
     * @return exact dimensions
     */
    private static long[] shape(final JsonNode node) {
        final var dimensions = new long[node.size()];
        for (int index = 0; index < dimensions.length; index++) {
            dimensions[index] = node.get(index).asLong();
        }
        return dimensions;
    }

    /**
     * Compares every scalar, rejecting NaN/infinity rather than allowing a misleading small maximum.
     * @param expected source-model float values
     * @param actual Java ONNX Runtime float values
     * @return maximum absolute finite error over the entire tensor
     */
    private static double difference(final FloatBuffer expected, final FloatBuffer actual) {
        require(expected.remaining() == actual.remaining() && expected.remaining() > 0, "Output scalar counts differ");
        double maximum = 0;
        while (expected.hasRemaining()) {
            final float first = expected.get();
            final float second = actual.get();
            require(Float.isFinite(first) && Float.isFinite(second), "Nonfinite tensor value");
            maximum = Math.max(maximum, Math.abs((double) first - second));
        }
        return maximum;
    }

    /**
     * Pins exact graph/protocol/tensor bytes.
     * @param bytes complete bounded input
     * @return lowercase SHA256
     * @throws Exception if the standard digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Stops invalid numerical evidence without changing shapes, fixtures or thresholds.
     * @param condition required invariant
     * @param message bounded failure explanation
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
