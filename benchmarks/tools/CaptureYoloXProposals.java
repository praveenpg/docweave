package dev.docweave.layout;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit JVM-only YOLOX proposal diagnostic; no model download, OCR, reference input or production API. */
public final class CaptureYoloXProposals {
    /** Fixed exported input height. */
    private static final int HEIGHT = 1024;
    /** Fixed exported input width. */
    private static final int WIDTH = 768;
    /** Raw anchor count for fixed strides8/16/32. */
    private static final int QUERIES = 16128;

    /**
     * One source-pixel prediction after grid decoding and before class-agnostic NMS.
     * @param query stable original query index
     * @param label strongest of eleven published classes; table is8
     * @param score objectness times strongest class score
     * @param left source-pixel left
     * @param top source-pixel top
     * @param right source-pixel right
     * @param bottom source-pixel bottom
     */
    private record Proposal(int query, int label, double score, double left, double top, double right, double bottom) {}

    /** Prevents utility construction. */
    private CaptureYoloXProposals() {
    }

    /**
     * Runs every original PDF page with explicit local graph identity and fresh numeric output.
     * Use a process time limit for this diagnostic; it is not a production lifecycle/deadline adapter.
     * JVM pixel-center bilinear rounding is declared separately from upstream OpenCV interpolation.
     * @param args PDF/hash, model/hash and fresh output; or --self-test for owned numerical controls
     * @throws Exception if source/schema/model identity or inference/output validation fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            selfTest();
            System.out.println("YOLOX JVM numerical controls passed");
            return;
        }
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected PDF/hash, model/hash and fresh report");
        }
        final byte[] pdf = pinned(Path.of(args[0]), args[1], 10 * 1024 * 1024);
        pinned(Path.of(args[2]), args[3], 256 * 1024 * 1024);
        final var environment = OrtEnvironment.getEnvironment();
        environment.setTelemetry(false);
        final var pages = new ArrayList<Map<String, Object>>();
        try (final var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(4);
            options.setInterOpNumThreads(1);
            try (final var session = environment.createSession(args[2], options);
                    final var document = Loader.loadPDF(pdf)) {
                require(session.getInputNames().equals(Set.of("images"))
                                && session.getOutputNames().equals(Set.of("output")),
                        "Unexpected graph names");
                final var input = (TensorInfo) session.getInputInfo().get("images").getInfo();
                final var output = (TensorInfo) session.getOutputInfo().get("output").getInfo();
                require(input.type == OnnxJavaType.FLOAT && output.type == OnnxJavaType.FLOAT
                                && Arrays.equals(input.getShape(), new long[] {1, 3, HEIGHT, WIDTH})
                                && Arrays.equals(output.getShape(), new long[] {1, QUERIES, 16}),
                        "Unexpected graph schema");
                require(document.getNumberOfPages() <= 1000, "Too many original pages");
                final var renderer = new PDFRenderer(document);
                for (int index = 0; index < document.getNumberOfPages(); index++) {
                    final var page = document.getPage(index);
                    final var frame = page.getCropBox();
                    final long width = (long) Math.ceil(frame.getWidth() * 150.0 / 72);
                    final long height = (long) Math.ceil(frame.getHeight() * 150.0 / 72);
                    require(width > 0 && height > 0 && width <= 16_000_000 / height, "Render pixels exceeded");
                    final var image = renderer.renderImageWithDPI(index, 150, ImageType.RGB);
                    try {
                        final double ratio =
                                Math.min((double) HEIGHT / image.getHeight(), (double) WIDTH / image.getWidth());
                        try (final var tensor = OnnxTensor.createTensor(
                                     environment, preprocess(image), new long[] {1, 3, HEIGHT, WIDTH});
                                final var result = session.run(Map.of("images", tensor))) {
                            final var value = (OnnxTensor) result.get(0);
                            require(Arrays.equals(value.getInfo().getShape(), new long[] {1, QUERIES, 16}),
                                    "Unexpected runtime output");
                            final var candidates = decode(value.getFloatBuffer(), ratio);
                            final var kept = suppress(candidates);
                            final var tables = new ArrayList<Map<String, Object>>();
                            for (final var proposal : kept) {
                                if (proposal.label() == 8) {
                                    final double left = Math.max(0, proposal.left());
                                    final double top = Math.max(0, proposal.top());
                                    final double right = Math.min(image.getWidth(), proposal.right());
                                    final double bottom = Math.min(image.getHeight(), proposal.bottom());
                                    if (right > left && bottom > top) {
                                        tables.add(Map.of("score", proposal.score(), "query", proposal.query(),
                                                "bounds", List.of(left, top, right, bottom)));
                                    }
                                }
                            }
                            final var entry = new LinkedHashMap<String, Object>();
                            entry.put("source_page", index + 1);
                            entry.put("source_crop_width", frame.getWidth());
                            entry.put("source_crop_height", frame.getHeight());
                            entry.put("source_rotation", page.getRotation());
                            entry.put("raster_width", image.getWidth());
                            entry.put("raster_height", image.getHeight());
                            entry.put("pre_nms_candidates", candidates.size());
                            entry.put("post_nms_objects", kept.size());
                            entry.put("table_proposals", tables);
                            pages.add(entry);
                        }
                    } finally {
                        image.flush();
                    }
                }
            }
        }
        Files.writeString(Path.of(args[4]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime",
                        Runtime.version().toString(), "pdf_sha256", args[1], "model_sha256", args[3], "scope",
                        "JVM diagnostic: RGB unnormalized CHW1024x768, top-left114padding, pixel-center bilinear uint8 rounding; strides8/16/32; class argmax, objectness product>.25; class-agnostic inclusive-area NMS.1 before table-class selection; stable query ties; clip table boxes after NMS. Not an upstream preprocessing parity or extraction-quality claim.",
                        "pages", pages))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Builds unnormalized RGB planes with top-left aspect-preserving resize and114 padding.
     * @param image original opaque RGB page
     * @return contiguous fixed-shape tensor values
     */
    private static FloatBuffer preprocess(final BufferedImage image) {
        final double ratio = Math.min((double) HEIGHT / image.getHeight(), (double) WIDTH / image.getWidth());
        final int width = (int) (image.getWidth() * ratio);
        final int height = (int) (image.getHeight() * ratio);
        require(width > 0 && height > 0, "Empty resized raster");
        final var data = new float[3 * WIDTH * HEIGHT];
        Arrays.fill(data, 114);
        for (int y = 0; y < height; y++) {
            final double sourceY = (y + .5) * image.getHeight() / height - .5;
            final int baseY = (int) Math.floor(sourceY);
            final double fy = sourceY - baseY;
            for (int x = 0; x < width; x++) {
                final double sourceX = (x + .5) * image.getWidth() / width - .5;
                final int baseX = (int) Math.floor(sourceX);
                final double fx = sourceX - baseX;
                for (int channel = 0; channel < 3; channel++) {
                    final double a = pixel(image, baseX, baseY, channel) * (1 - fx)
                                     + pixel(image, baseX + 1, baseY, channel) * fx;
                    final double b = pixel(image, baseX, baseY + 1, channel) * (1 - fx)
                                     + pixel(image, baseX + 1, baseY + 1, channel) * fx;
                    data[channel * WIDTH * HEIGHT + y * WIDTH + x] = Math.round(a * (1 - fy) + b * fy);
                }
            }
        }
        return FloatBuffer.wrap(data);
    }

    /**
     * Reads one replicated-edge RGB channel without alpha or normalization changes.
     * @param image original RGB raster
     * @param x possibly outside left/right edge
     * @param y possibly outside top/bottom edge
     * @param channel RGB channel index
     * @return original unsigned channel value
     */
    private static int pixel(final BufferedImage image, final int x, final int y, final int channel) {
        return image.getRGB(Math.clamp(x, 0, image.getWidth() - 1), Math.clamp(y, 0, image.getHeight() - 1))
                >>> (16 - channel * 8)
                & 255;
    }

    /**
     * Decodes fixed-grid raw predictions before NMS, preserving all competing classes.
     * @param values fixed16128x16 float output
     * @param ratio original aspect-preserving resize ratio
     * @return thresholded bounded proposals in source pixels
     */
    private static List<Proposal> decode(final FloatBuffer values, final double ratio) {
        require(values.remaining() == QUERIES * 16 && Double.isFinite(ratio) && ratio > 0, "Invalid output frame");
        final var result = new ArrayList<Proposal>();
        int query = 0;
        for (final int stride : new int[] {8, 16, 32}) {
            for (int row = 0; row < HEIGHT / stride; row++) {
                for (int column = 0; column < WIDTH / stride; column++) {
                    final int offset = query * 16;
                    int label = 0;
                    float strongest = values.get(offset + 5);
                    require(Float.isFinite(strongest) && strongest >= 0 && strongest <= 1, "Invalid class score");
                    for (int category = 1; category < 11; category++) {
                        final float score = values.get(offset + 5 + category);
                        require(Float.isFinite(score) && score >= 0 && score <= 1, "Invalid class score");
                        if (score > strongest) {
                            strongest = score;
                            label = category;
                        }
                    }
                    final float objectness = values.get(offset + 4);
                    require(Float.isFinite(strongest) && strongest >= 0 && strongest <= 1 && Float.isFinite(objectness)
                                    && objectness >= 0 && objectness <= 1,
                            "Invalid confidence");
                    final float score = objectness * strongest;
                    if (score > .25f) {
                        final double centerX = (values.get(offset) + column) * stride;
                        final double centerY = (values.get(offset + 1) + row) * stride;
                        final double width = (float) Math.exp(values.get(offset + 2)) * stride;
                        final double height = (float) Math.exp(values.get(offset + 3)) * stride;
                        require(Double.isFinite(centerX) && Double.isFinite(centerY) && Double.isFinite(width)
                                        && Double.isFinite(height) && width > 0 && height > 0,
                                "Invalid predicted box");
                        result.add(new Proposal(query, label, score, (centerX - width / 2) / ratio,
                                (centerY - height / 2) / ratio, (centerX + width / 2) / ratio,
                                (centerY + height / 2) / ratio));
                        require(result.size() <= 2048, "Candidate ceiling exceeded");
                    }
                    query++;
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Applies class-agnostic source-pixel inclusive-area NMS before table selection.
     * @param proposals finite thresholded candidates
     * @return stable score-ordered retained objects of all classes
     */
    private static List<Proposal> suppress(final List<Proposal> proposals) {
        final var kept = new ArrayList<Proposal>();
        for (final var candidate : proposals.stream()
                        .sorted(Comparator.comparingDouble(Proposal::score)
                                        .reversed()
                                        .thenComparingInt(Proposal::query))
                        .toList()) {
            boolean suppressed = false;
            for (final var prior : kept) {
                final double width = Math.max(
                        0, Math.min(candidate.right(), prior.right()) - Math.max(candidate.left(), prior.left()) + 1);
                final double height = Math.max(
                        0, Math.min(candidate.bottom(), prior.bottom()) - Math.max(candidate.top(), prior.top()) + 1);
                final double intersection = width * height;
                final double area =
                        (candidate.right() - candidate.left() + 1) * (candidate.bottom() - candidate.top() + 1);
                final double priorArea = (prior.right() - prior.left() + 1) * (prior.bottom() - prior.top() + 1);
                if (intersection / (area + priorArea - intersection) > .1) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) {
                kept.add(candidate);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * Checks exact supplied bytes before graph initialization or rendering.
     * @param path explicit trusted local input
     * @param expected expected SHA256
     * @param cap maximum encoded bytes
     * @return hash-checked bounded bytes
     * @throws Exception if reading, digest or identity checks fail
     */
    private static byte[] pinned(final Path path, final String expected, final int cap) throws Exception {
        require(Files.size(path) <= cap, "Encoded input exceeds cap");
        final byte[] bytes;
        try (final var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(cap + 1);
        }
        require(bytes.length <= cap
                        && HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                                .equals(expected),
                "Input identity mismatch");
        return bytes;
    }

    /** Checks owned channel/padding/interpolation, fixed-grid decoding and competing-class suppression controls. */
    private static void selfTest() {
        final var image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        try {
            image.setRGB(0, 0, 0xff0000);
            image.setRGB(1, 0, 0x0000ff);
            final var pixels = preprocess(image);
            require(pixels.get(0) == 255 && pixels.get(WIDTH * HEIGHT) == 0 && pixels.get(2 * WIDTH * HEIGHT) == 0,
                    "RGB origin control");
            require(pixels.get(WIDTH - 1) == 0 && pixels.get(2 * WIDTH * HEIGHT + WIDTH - 1) == 255,
                    "RGB far-edge control");
            require(pixels.get(384 * WIDTH) == 114 && pixels.get(383) == 128
                            && pixels.get(2 * WIDTH * HEIGHT + 383) == 127,
                    "Padding/interpolation control");
        } finally {
            image.flush();
        }
        final var raw = FloatBuffer.allocate(QUERIES * 16);
        raw.put(0, 3);
        raw.put(1, 3);
        raw.put(2, (float) Math.log(2));
        raw.put(3, (float) Math.log(2));
        raw.put(4, .9f);
        raw.put(13, .9f);
        final var proposals = decode(raw, 1);
        require(proposals.size() == 1 && proposals.getFirst().label() == 8
                        && Math.abs(proposals.getFirst().left() - 16) < .0001,
                "Grid/class control");
        final var competing = new Proposal(1, 9, .95, 16, 16, 32, 32);
        require(suppress(List.of(proposals.getFirst(), competing)).equals(List.of(competing)), "All-class NMS control");
        final var separate = new Proposal(2, 8, .8, 100, 100, 120, 120);
        require(suppress(List.of(proposals.getFirst(), separate)).size() == 2, "Disjoint NMS control");
        raw.put(4, .5f);
        raw.put(13, .5f);
        require(decode(raw, 1).isEmpty(), "Strict score threshold control");
    }

    /**
     * Fails diagnostics without repairing inputs or printing publisher text.
     * @param valid required invariant
     * @param message content-free failure reason
     */
    private static void require(final boolean valid, final String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
