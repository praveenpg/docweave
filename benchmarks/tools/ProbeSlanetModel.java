package dev.docweave.validation;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** JVM-only raw sequence-model capture on complete frozen detector crops; no reference labels or row repair. */
public final class ProbeSlanetModel {
    /** Fixed published maximum resized side, padded after normalization. */
    private static final int SIDE = 488;
    /** Official wrapper's channel normalization, applied to BGR input. */
    private static final double[] MEAN = {.485, .456, .406}, STD = {.229, .224, .225};

    /** Publisher-pinned model protocols; callers freeze one before original inference. */
    private enum Model {
        /** SLANet-Plus uses quadrilaterals scaled by the original longest side. */
        PLUS("d57a942af6a2f57d6a4a0372573c696a2379bf5857c45e2ac69993f3b334514b", 7_758_305, 50, 8),
        /** English SLANet uses axis-aligned boxes scaled independently by original width and height. */
        ENGLISH("2cae17d16a16f9df7229e21665fe3fbe06f3ca85b2024772ee3e3142e955aa60", 7_704_409, 30, 4);

        /** Exact graph identity. */
        private final String hash;
        /** Published graph byte length. */
        private final long bytes;
        /** Complete vocabulary including sentinels. */
        private final int labels;
        /** Number of coordinates emitted per predicted cell. */
        private final int coordinates;

        /**
         * Stores the fixed graph contract.
         * @param hash exact graph SHA256
         * @param bytes graph byte length
         * @param labels vocabulary size
         * @param coordinates output coordinate count
         */
        Model(final String hash, final long bytes, final int labels, final int coordinates) {
            this.hash = hash;
            this.bytes = bytes;
            this.labels = labels;
            this.coordinates = coordinates;
        }
    }

    /** Prevents construction of the standalone probe. */
    private ProbeSlanetModel() {
    }

    /**
     * Captures every fixed page/profile/crop without parsing or repairing model table topology.
     * @param args model, frozen manifest, raster root, original crop root and fresh numeric report
     * @throws Exception if source/model identities, schema, owned controls or report freshness fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 5 && !Files.exists(Path.of(args[4])),
                "Expected model, pins, rasters, crops and fresh report");
        final var mapper = JsonMapper.builder().build();
        final var manifestBytes = Files.readAllBytes(Path.of(args[1]));
        final var manifest = mapper.readTree(manifestBytes);
        final Model model = Model.valueOf(manifest.path("model_protocol").asString("PLUS"));
        require(Files.size(Path.of(args[0])) == model.bytes && hash(Path.of(args[0])).equals(model.hash)
                        && manifest.get("model_sha256").asString().equals(model.hash),
                "Graph identity changed");
        controls();
        require(manifest.get("pages").size() == 11, "Complete eleven-page population required");
        require(hash(Path.of("benchmarks/tools/ProbeSlanetModel.java")).equals(manifest.get("code_sha256").asString()),
                "Frozen capture source changed");
        final var reports = new ArrayList<Map<String, Object>>();
        final var environment = OrtEnvironment.getEnvironment();
        try (final var configuration = new OrtSession.SessionOptions();
                final var timer = Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name("slanet-probe-cancellation").factory())) {
            configuration.setIntraOpNumThreads(1);
            configuration.setInterOpNumThreads(1);
            // The separately retained smoke log records the graph's repetitive scalar/{1} shape warnings.
            configuration.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            try (final var session = environment.createSession(args[0], configuration)) {
                require(session.getInputNames().equals(java.util.Set.of("x")), "Unexpected model input");
                final var vocabulary =
                        new ArrayList<>(session.getMetadata().getCustomMetadata().get("character").lines().toList());
                vocabulary.remove("<td>");
                if (!vocabulary.contains("<td></td>")) {
                    vocabulary.add("<td></td>");
                }
                vocabulary.addFirst("sos");
                vocabulary.add("eos");
                require(vocabulary.size() == model.labels, "Model vocabulary changed");
                int expectedPage = 1;
                for (final var page : manifest.get("pages")) {
                    require(page.get("page").asInt() == expectedPage++, "Missing, duplicate or reordered source page");
                    final String id = page.get("id").asString();
                    require(id.matches("original-page-[0-9]{4}"), "Invalid source identity");
                    final var rasterPath = Path.of(args[2], id, "render.png");
                    require(Files.size(rasterPath) <= 64 * 1024 * 1024
                                    && hash(rasterPath).equals(page.get("raster_sha256").asString()),
                            "Original raster changed");
                    final var image = ImageIO.read(rasterPath.toFile());
                    require(image != null && (long) image.getWidth() * image.getHeight() <= 16_000_000,
                            "Invalid source raster");
                    try {
                        for (final String profile : List.of("tatr", "yolox")) {
                            final var cropPath = Path.of(args[3], id, profile, "crops.tsv");
                            require(hash(cropPath).equals(page.get(profile + "_crops_sha256").asString()),
                                    "Frozen crops changed");
                            final var rows = Files.readAllLines(cropPath);
                            require(!rows.isEmpty() && rows.getFirst().equals("crop\tleft\ttop\tright\tbottom")
                                            && rows.size() <= 65,
                                    "Invalid crop population");
                            final var captures = new ArrayList<Map<String, Object>>();
                            for (int index = 1; index < rows.size(); index++) {
                                final var values = rows.get(index).split("\t", -1);
                                require(values.length == 5 && Integer.parseInt(values[0]) == index - 1,
                                        "Invalid crop order");
                                final int left = Integer.parseInt(values[1]), top = Integer.parseInt(values[2]);
                                final int right = Integer.parseInt(values[3]), bottom = Integer.parseInt(values[4]);
                                require(left >= 0 && top >= 0 && right > left && bottom > top
                                                && right <= image.getWidth() && bottom <= image.getHeight(),
                                        "Invalid source crop");
                                final var capture = new LinkedHashMap<String, Object>();
                                capture.put("crop", index - 1);
                                capture.put("bounds", List.of(left, top, right, bottom));
                                final long started = System.nanoTime();
                                // Crop views borrow the unchanged page raster; only the owning page is flushed.
                                final var crop = image.getSubimage(left, top, right - left, bottom - top);
                                try (final var run = new OrtSession.RunOptions()) {
                                    final var active = new boolean[] {true};
                                    final var expired = new java.util.concurrent.atomic.AtomicBoolean();
                                    final var watch = timer.schedule(() -> {
                                        synchronized (run) {
                                            if (active[0]) {
                                                expired.set(true);
                                                try {
                                                    run.setTerminate(true);
                                                } catch (final OrtException failure) {
                                                    throw new IllegalStateException(
                                                            "Native termination failed", failure);
                                                }
                                            }
                                        }
                                    }, 30, TimeUnit.SECONDS);
                                    try {
                                        final var pixels = pixels(crop);
                                        try (final var input = OnnxTensor.createTensor(environment,
                                                     FloatBuffer.wrap(pixels), new long[] {1, 3, SIDE, SIDE});
                                                final var result = session.run(Map.of("x", input), run)) {
                                            final var boxesTensor = (OnnxTensor) result.get(0);
                                            final var scoresTensor = (OnnxTensor) result.get(1);
                                            final var shape = boxesTensor.getInfo().getShape();
                                            final var scoresShape = scoresTensor.getInfo().getShape();
                                            require(shape.length == 3 && scoresShape.length == 3 && shape[0] == 1
                                                            && scoresShape[0] == 1 && shape[1] > 0 && shape[1] <= 2048
                                                            && shape[1] == scoresShape[1]
                                                            && shape[2] == model.coordinates
                                                            && scoresShape[2] == model.labels,
                                                    "Unexpected sequence output dimensions");
                                            final var decoded = decode(((float[][][]) boxesTensor.getValue())[0],
                                                    ((float[][][]) scoresTensor.getValue())[0], vocabulary,
                                                    crop.getWidth(), crop.getHeight(), model);
                                            require(!expired.get()
                                                            && System.nanoTime() - started
                                                                       <= TimeUnit.SECONDS.toNanos(30),
                                                    "Complete model call deadline consumed");
                                            capture.putAll(decoded);
                                            capture.put("outcome", Boolean.TRUE.equals(decoded.get("end_token"))
                                                                           ? "OK"
                                                                           : "NO_END_TOKEN");
                                        }
                                    } catch (final Exception failure) {
                                        capture.put("outcome", expired.get()
                                                                               || System.nanoTime() - started
                                                                                          > TimeUnit.SECONDS.toNanos(30)
                                                                       ? "NATIVE_TIMEOUT"
                                                                       : "INFERENCE_FAILURE");
                                        capture.put("failure_class", failure.getClass().getName());
                                    } finally {
                                        watch.cancel(false);
                                        // Serialize callback deactivation with its access before the native options
                                        // close.
                                        synchronized (run) {
                                            active[0] = false;
                                        }
                                    }
                                }
                                capture.put("elapsed_millis", (System.nanoTime() - started) / 1_000_000.0);
                                captures.add(capture);
                            }
                            reports.add(Map.of("page", page.get("page").asInt(), "id", id, "profile", profile,
                                    "raster_sha256", page.get("raster_sha256").asString(), "captures", captures));
                        }
                    } finally {
                        image.flush();
                    }
                }
            }
        }
        require(reports.size() == 22, "Incomplete page/profile population");
        Files.writeString(Path.of(args[4]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("java_runtime",
                        System.getProperty("java.runtime.version"), "model_sha256", model.hash, "manifest_sha256",
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)), "scope",
                        "Raw model tokens and boxes on all frozen crops; no topology repair, OCR or text accuracy claim",
                        "preprocessing",
                        "BGR, longest-side488 truncation, explicit half-pixel bilinear8bit nearest rounding, official normalization then zero padding; OpenCV bit identity unverified",
                        "results", reports))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Uses the published tensor layout with explicit JVM bilinear rounding, without source mutation.
     * @param image borrowed opaque RGB crop
     * @return fixed NCHW normalized tensor with zero-valued normalized padding
     * @throws InterruptedIOException if the calling thread is interrupted
     */
    private static float[] pixels(final BufferedImage image) throws InterruptedIOException {
        final double ratio = SIDE / (double) Math.max(image.getWidth(), image.getHeight());
        final int width = (int) (image.getWidth() * ratio), height = (int) (image.getHeight() * ratio);
        require(width > 0 && height > 0, "Unsupported extreme aspect ratio");
        final var result = new float[3 * SIDE * SIDE];
        for (int y = 0; y < height; y++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Model preprocessing interrupted");
            }
            final double fy = (y + .5) * image.getHeight() / height - .5;
            final int iy = (int) Math.floor(fy);
            final double ty = fy - iy;
            final int y0 = Math.clamp(iy, 0, image.getHeight() - 1), y1 = Math.clamp(iy + 1, 0, image.getHeight() - 1);
            for (int x = 0; x < width; x++) {
                final double fx = (x + .5) * image.getWidth() / width - .5;
                final int ix = (int) Math.floor(fx);
                final double tx = fx - ix;
                final int x0 = Math.clamp(ix, 0, image.getWidth() - 1),
                          x1 = Math.clamp(ix + 1, 0, image.getWidth() - 1);
                final int a = image.getRGB(x0, y0), b = image.getRGB(x1, y0), c = image.getRGB(x0, y1),
                          d = image.getRGB(x1, y1);
                for (int channel = 0; channel < 3; channel++) {
                    final int shift = channel * 8;
                    final double upper = (a >>> shift & 255) * (1 - tx) + (b >>> shift & 255) * tx;
                    final double lower = (c >>> shift & 255) * (1 - tx) + (d >>> shift & 255) * tx;
                    final int value = (int) Math.round(upper * (1 - ty) + lower * ty);
                    final float scaled = value * (1f / 255f);
                    result[channel * SIDE * SIDE + y * SIDE + x] = (float) ((scaled - MEAN[channel]) / STD[channel]);
                }
            }
        }
        return result;
    }

    /**
     * Preserves source model sequence order and unmodified local cell quadrilaterals; does not parse a table.
     * @param boxes complete returned cell-coordinate tensor
     * @param scores complete returned token probabilities
     * @param vocabulary exact model vocabulary including start/end sentinels
     * @param width original crop width
     * @param height original crop height
     * @param model frozen graph coordinate protocol
     * @return copied tokens, associated eight-coordinate cells, token scores and explicit termination state
     */
    private static Map<String, Object> decode(final float[][] boxes, final float[][] scores,
            final List<String> vocabulary, final int width, final int height, final Model model) {
        final var tokens = new ArrayList<String>();
        final var cells = new ArrayList<List<Double>>();
        final var probabilities = new ArrayList<Double>();
        boolean ended = false;
        for (int position = 0; position < scores.length; position++) {
            int best = 0;
            for (int label = 0; label < scores[position].length; label++) {
                require(Float.isFinite(scores[position][label]), "Nonfinite token probability");
                if (scores[position][label] > scores[position][best]) {
                    best = label;
                }
            }
            final String token = vocabulary.get(best);
            if (position > 0 && token.equals("eos")) {
                ended = true;
                break;
            }
            if (token.equals("sos") || token.equals("eos")) {
                continue;
            }
            tokens.add(token);
            probabilities.add((double) scores[position][best]);
            if (token.equals("<td") || token.equals("<td></td>")) {
                final var bounds = new ArrayList<Double>();
                for (int index = 0; index < boxes[position].length; index++) {
                    final float coordinate = boxes[position][index];
                    require(Float.isFinite(coordinate), "Nonfinite model cell coordinate");
                    final int extent =
                            model == Model.PLUS ? Math.max(width, height) : (index % 2 == 0 ? width : height);
                    bounds.add((double) coordinate * extent);
                }
                // Four-coordinate models describe the same rectangle as this explicit corner expansion.
                // Preserve inverted/outside coordinates for downstream rejection instead of repairing them.
                cells.add(model == Model.PLUS ? List.copyOf(bounds)
                                              : List.of(bounds.get(0), bounds.get(1), bounds.get(2), bounds.get(1),
                                                        bounds.get(2), bounds.get(3), bounds.get(0), bounds.get(3)));
            }
        }
        return Map.of("tokens", tokens, "cells_local", cells, "token_probabilities", probabilities, "end_token", ended);
    }

    /**
     * Validates independently specified constant BGR channels, normalized padding and exact sequence termination.
     * @throws Exception if owned tensor or decoder controls fail
     */
    private static void controls() throws Exception {
        final var image = new BufferedImage(1, 2, BufferedImage.TYPE_INT_RGB);
        try {
            image.setRGB(0, 0, 0xffff0080);
            image.setRGB(0, 1, 0xffff0080);
            final var tensor = pixels(image);
            for (int channel = 0; channel < 3; channel++) {
                final int value = new int[] {128, 0, 255}[channel];
                final float expected = (float) ((value * (1f / 255f) - MEAN[channel]) / STD[channel]);
                require(tensor[channel * SIDE * SIDE] == expected, "BGR/normalization contract changed");
                require(tensor[channel * SIDE * SIDE + SIDE - 1] == 0, "Normalized padding is not zero");
            }
            require(image.getRGB(0, 0) == 0xffff0080, "Source raster mutated");
        } finally {
            image.flush();
        }
        final var decoded =
                decode(new float[][] {{0, 0, 0, 0, 0, 0, 0, 0}, {0, 0, 1, 0, 1, 1, 0, 1}, {0, 0, 0, 0, 0, 0, 0, 0}},
                        new float[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}, List.of("sos", "<td></td>", "eos"), 100, 50,
                        Model.PLUS);
        require(decoded.get("tokens").equals(List.of("<td></td>")) && Boolean.TRUE.equals(decoded.get("end_token")),
                "Sequence sentinels changed");
        require(decoded.get("cells_local").equals(List.of(List.of(0.0, 0.0, 100.0, 0.0, 100.0, 100.0, 0.0, 100.0))),
                "Original crop coordinate scaling changed");
        final var english = decode(new float[][] {{0, 0, 0, 0}, {.25f, .5f, .75f, 1}, {0, 0, 0, 0}},
                new float[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}, List.of("sos", "<td></td>", "eos"), 200, 80,
                Model.ENGLISH);
        require(english.get("cells_local").equals(List.of(List.of(50.0, 40.0, 150.0, 40.0, 150.0, 80.0, 50.0, 80.0))),
                "English independent-axis box scaling changed");
    }

    /**
     * Computes the complete raw file identity.
     * @param path pinned local input
     * @return lowercase SHA256
     * @throws Exception if reading or hashing fails
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Stops protocol changes instead of silently selecting cases or repairing predictions.
     * @param condition required invariant
     * @param message numeric/source-safe diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
