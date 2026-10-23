package dev.docweave.validation;

import dev.docweave.ocr.TesseractOcr;
import dev.docweave.ocr.TesseractOptions;
import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfWordOcrProvider;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

/** Offline, single-hypothesis column evaluation; labels are used only after geometry processing. */
public final class EvaluateOcrColumnAlignment {
    /** Stable report serialization. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Prevents construction. */
    private EvaluateOcrColumnAlignment() {
    }

    /**
     * Geometry-only internal word boundary.
     * @param line original provider line index
     * @param word internal word index
     * @param x left pixel coordinate
     */
    private record Start(int line, int word, double x) {}

    /**
     * Ranked alignment group; each original line contributes at most one support.
     * @param x median candidate left edge
     * @param support number of distinct original supporting lines
     * @param members original boundaries in ascending x order
     */
    private record Peak(double x, int support, List<Start> members) {}

    /**
     * Candidate lines and numeric-only detection diagnostics.
     * @param lines losslessly regrouped output
     * @param peaks eligible alignment groups in fixed rank order
     * @param additions number of boundaries added beyond wide gaps
     */
    private record Proposal(List<PdfOcrProvider.Line> lines, List<Peak> peaks, int additions) {}

    /**
     * Captures exactly eight pinned OCR observations, or replays retained words without engine execution.
     * @param args capture/replay, manifest, executable, model directory, sources, private captures, fresh report
     * @throws Exception if identity, budgets, scoring or report writing fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 7 || !(args[0].equals("capture") || args[0].equals("replay"))) {
            throw new IllegalArgumentException(
                    "capture/replay manifest executable tessdata sources private-captures report");
        }
        final boolean capture = args[0].equals("capture");
        final Path manifestPath = Path.of(args[1]);
        final var manifest = JSON.readTree(Files.readAllBytes(manifestPath));
        final String manifestHash = hash(Files.readAllBytes(manifestPath));
        final Path executable = Path.of(args[2]);
        final Path data = Path.of(args[3]);
        require(hash(Files.readAllBytes(executable)).equals(manifest.get("engine_sha256").asString()),
                "Engine changed");
        final Path raw = Path.of(args[5]);
        Files.createDirectories(raw);
        final var results = new ArrayList<Map<String, Object>>();
        boolean accepted = true;
        int calls = 0;
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            for (final var pin : selected.get("inputs")) {
                final var bytes = Files.readAllBytes(Path.of(args[4], pin.get("filename").asString()));
                require(bytes.length == pin.get("bytes").asLong() && hash(bytes).equals(pin.get("sha256").asString()),
                        "Source pin changed");
            }
            for (final var profile : manifest.get("profiles")) {
                final String language = profile.get("language").asString();
                require(hash(Files.readAllBytes(data.resolve(profile.get("filename").asString())))
                                .equals(profile.get("sha256").asString()),
                        "Model changed");
                final Path wordsPath = raw.resolve(id + "-" + language + ".json");
                final var row = new LinkedHashMap<String, Object>();
                row.put("id", id);
                row.put("language", language);
                row.put("model_sha256", profile.get("sha256").asString());
                String outcome = "SUCCESS";
                List<PdfWordOcrProvider.WordLine> words = List.of();
                if (capture) {
                    final var decoded = ImageIO.read(Path.of(args[4], selected.get("image").asString()).toFile());
                    require(decoded != null && decoded.getWidth() == selected.get("width").asInt()
                                    && decoded.getHeight() == selected.get("height").asInt(),
                            "Raster differs");
                    final var rgb =
                            new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
                    final var digest = MessageDigest.getInstance("SHA-256");
                    for (int y = 0; y < rgb.getHeight(); y++) {
                        final int[] pixels = decoded.getRGB(0, y, rgb.getWidth(), 1, null, 0, rgb.getWidth());
                        rgb.setRGB(0, y, rgb.getWidth(), 1, pixels, 0, rgb.getWidth());
                        for (final int pixel : pixels) {
                            digest.update((byte) (pixel >> 16));
                            digest.update((byte) (pixel >> 8));
                            digest.update((byte) pixel);
                        }
                    }
                    decoded.flush();
                    row.put("input_rgb_sha256", HexFormat.of().formatHex(digest.digest()));
                    final var options =
                            new TesseractOptions(executable, data, List.of(language), Duration.ofSeconds(60), 1,
                                    20_000_000, 32L * 1024 * 1024, 4 * 1024 * 1024, 10_000, 1_000_000);
                    try {
                        calls++;
                        words = TesseractOcr.createWithWords(options).recognizeWords(
                                rgb, PdfOcrProvider.InputKind.PAGE);
                    } catch (final Exception failure) {
                        outcome = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                    } finally {
                        rgb.flush();
                    }
                    final var saved = new LinkedHashMap<String, Object>(row);
                    saved.put("manifest_sha256", manifestHash);
                    saved.put("outcome", outcome);
                    saved.put("word_lines", words);
                    write(wordsPath, saved);
                }
                final var saved = JSON.readTree(Files.readAllBytes(wordsPath));
                require(saved.get("manifest_sha256").asString().equals(manifestHash), "Capture manifest differs");
                require(saved.get("model_sha256").asString().equals(profile.get("sha256").asString()),
                        "Capture model differs");
                require(saved.get("id").asString().equals(id) && saved.get("language").asString().equals(language),
                        "Capture identity differs");
                words = List.of(JSON.treeToValue(saved.get("word_lines"), PdfWordOcrProvider.WordLine[].class));
                outcome = saved.get("outcome").asString();
                row.put("outcome", outcome);
                row.put("input_rgb_sha256", saved.get("input_rgb_sha256").asString());
                row.put("capture_sha256", hash(Files.readAllBytes(wordsPath)));
                final var rawLines = words.stream().map(PdfWordOcrProvider.WordLine::line).toList();
                final var wide = regroup(words, Map.of());
                final var proposal = propose(words);
                final byte[] xml = Files.readAllBytes(Path.of(args[4], selected.get("xml").asString()));
                row.put("raw", score(xml, rawLines));
                final var baseline = score(xml, wide);
                final var measured = score(xml, proposal.lines());
                row.put("wide_gaps", baseline);
                row.put("alignment", measured);
                row.put("additional_splits", proposal.additions());
                row.put("peaks",
                        proposal.peaks().stream().map(p -> Map.of("x", p.x(), "support", p.support())).toList());
                final boolean textSame =
                        flat(rawLines).equals(flat(wide)) && flat(rawLines).equals(flat(proposal.lines()));
                require(textSame, "Token encounter order/text changed");
                row.put("token_text_identical", textSame);
                final boolean positive = id.equals("buerger_gedichte_1778_0039");
                final boolean pass =
                        outcome.equals("SUCCESS")
                        && (positive ? proposal.additions() > 0
                                                && number(measured, "associated_reference_lines")
                                                           > number(baseline, "associated_reference_lines")
                                                && number(measured, "missing_reference_lines")
                                                           <= number(baseline, "missing_reference_lines")
                                                && number(measured, "predicted_lines_without_reference")
                                                           <= number(baseline, "predicted_lines_without_reference")
                                                && number(measured, "joint_reference_line_character_errors")
                                                           <= number(baseline, "joint_reference_line_character_errors")
                                     : wide.equals(proposal.lines()));
                row.put("primary_acceptance", pass);
                accepted &= pass;
                results.add(row);
                System.out.println(
                        "Complete " + id + " " + language + " additions=" + proposal.additions() + " pass=" + pass);
            }
        }
        if (capture) {
            require(calls == 8, "Unexpected call population");
        }
        write(Path.of(args[6]),
                Map.of("manifest_sha256", manifestHash, "java_version", System.getProperty("java.version"),
                        "primary_acceptance", accepted, "results", results));
    }

    /**
     * Finds exactly one strongest recurring internal word-start cluster under the frozen rule.
     * @param lines actual recognized words, without references
     * @return fixed-rule regrouping and diagnostics
     */
    private static Proposal propose(final List<PdfWordOcrProvider.WordLine> lines) {
        final var heights = lines.stream()
                                    .flatMap(l -> l.words().stream())
                                    .mapToDouble(w -> w.bottom() - w.top())
                                    .sorted()
                                    .toArray();
        if (heights.length == 0) {
            return new Proposal(List.of(), List.of(), 0);
        }
        final double h = heights[heights.length / 2];
        final double left = lines.stream()
                                    .flatMap(l -> l.words().stream())
                                    .mapToDouble(PdfWordOcrProvider.Word::left)
                                    .min()
                                    .orElseThrow();
        final double right = lines.stream()
                                     .flatMap(l -> l.words().stream())
                                     .mapToDouble(PdfWordOcrProvider.Word::right)
                                     .max()
                                     .orElseThrow();
        final var starts = new ArrayList<Start>();
        for (int i = 0; i < lines.size(); i++) {
            final var tokens = lines.get(i).words();
            final double end = tokens.stream().mapToDouble(PdfWordOcrProvider.Word::right).max().orElse(0);
            for (int j = 1; j < tokens.size(); j++) {
                final double x = tokens.get(j).left();
                if (x >= left + 0.25 * (right - left) && x <= left + 0.80 * (right - left)
                        && x >= tokens.get(j - 1).right() && x - tokens.getFirst().left() >= 3 * h
                        && end - x >= 3 * h) {
                    starts.add(new Start(i, j, x));
                }
            }
        }
        starts.sort(Comparator.comparingDouble(Start::x).thenComparingInt(Start::line).thenComparingInt(Start::word));
        final var peaks = new ArrayList<Peak>();
        for (int at = 0; at < starts.size();) {
            final double anchor = starts.get(at).x();
            final var members = new ArrayList<Start>();
            final var supports = new HashSet<Integer>();
            while (at < starts.size() && starts.get(at).x() - anchor <= h) {
                final var start = starts.get(at++);
                members.add(start);
                supports.add(start.line());
            }
            if (supports.size() >= 6) {
                peaks.add(new Peak(members.get(members.size() / 2).x(), supports.size(), members));
            }
        }
        peaks.sort(Comparator.comparingInt(Peak::support).reversed().thenComparingDouble(Peak::x));
        final var selected = new LinkedHashMap<Integer, Integer>();
        if (!peaks.isEmpty()) {
            for (final var start : peaks.getFirst().members()) {
                selected.merge(start.line(), start.word(), Math::min);
            }
        }
        final var candidate = regroup(lines, selected);
        return new Proposal(candidate, List.copyOf(peaks), candidate.size() - regroup(lines, Map.of()).size());
    }

    /**
     * Combines existing wide-gap boundaries and at most one frozen alignment boundary per original line.
     * @param lines actual recognized words in provider order
     * @param additions at most one additional boundary per original line
     * @return immutable lines retaining the original token sequence
     */
    private static List<PdfOcrProvider.Line> regroup(
            final List<PdfWordOcrProvider.WordLine> lines, final Map<Integer, Integer> additions) {
        final var output = new ArrayList<PdfOcrProvider.Line>();
        for (int i = 0; i < lines.size(); i++) {
            final var tokens = lines.get(i).words();
            if (tokens.isEmpty()) {
                output.add(lines.get(i).line());
                continue;
            }
            final double h = tokens.stream().mapToDouble(w -> w.bottom() - w.top()).max().orElseThrow();
            int from = 0;
            for (int j = 1; j <= tokens.size(); j++) {
                if (j == tokens.size() || additions.getOrDefault(i, -1) == j
                        || tokens.get(j).left() - tokens.get(j - 1).right() > 3 * h) {
                    final var segment = tokens.subList(from, j);
                    output.add(new PdfOcrProvider.Line(
                            segment.stream().map(PdfWordOcrProvider.Word::text).collect(Collectors.joining(" ")),
                            segment.stream().mapToDouble(PdfWordOcrProvider.Word::left).min().orElseThrow(),
                            segment.stream().mapToDouble(PdfWordOcrProvider.Word::top).min().orElseThrow(),
                            segment.stream().mapToDouble(PdfWordOcrProvider.Word::right).max().orElseThrow(),
                            segment.stream().mapToDouble(PdfWordOcrProvider.Word::bottom).max().orElseThrow()));
                    from = j;
                }
            }
        }
        return List.copyOf(output);
    }

    /**
     * Reuses the unchanged historical JVM scorer reflectively, without exposing test helpers as public APIs.
     * @param xml independent complete PAGE transcription
     * @param predictions complete selected output
     * @return original scorer metrics with diagnostic XML order
     * @throws Exception if reference parsing or scorer invocation fails
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> score(final byte[] xml, final List<PdfOcrProvider.Line> predictions)
            throws Exception {
        final Class<?> suite = PdfHistoricalTextCorpusTest.class;
        final var refMethod = suite.getDeclaredMethod("reference", byte[].class);
        refMethod.setAccessible(true);
        final var reference = refMethod.invoke(null, (Object) xml);
        final var linesMethod = reference.getClass().getDeclaredMethod("lines");
        linesMethod.setAccessible(true);
        final var references = (List<?>) linesMethod.invoke(reference);
        final Class<?> boxClass = Class.forName(suite.getName() + "$Box");
        final Class<?> lineClass = Class.forName(suite.getName() + "$Line");
        final var boxCtor = boxClass.getDeclaredConstructor(double.class, double.class, double.class, double.class);
        boxCtor.setAccessible(true);
        final var lineCtor = lineClass.getDeclaredConstructor(String.class, String.class, boxClass);
        lineCtor.setAccessible(true);
        final var normalized = new ArrayList<Object>();
        for (final var p : predictions) {
            normalized.add(lineCtor.newInstance(Integer.toString(normalized.size()), normalize(p.text()),
                    boxCtor.newInstance(p.left(), p.top(), p.right(), p.bottom())));
        }
        final var metricMethod = suite.getDeclaredMethod("metrics", List.class, List.class);
        metricMethod.setAccessible(true);
        final var result = new LinkedHashMap<String, Object>(
                (Map<String, Object>) metricMethod.invoke(null, references, normalized));
        final var order = reference.getClass().getDeclaredMethod("declaredLineOrder");
        order.setAccessible(true);
        if (!(boolean) order.invoke(reference)) {
            for (final var key : List.of("character_edits", "character_error_rate", "word_edits", "word_error_rate")) {
                result.put("xml_sequence_" + key, result.remove("global_" + key));
            }
        }
        result.put("xml_order", order(PdfHistoricalTextCorpusTest.referenceLineBoxes(xml), predictions));
        return result;
    }

    /**
     * Adds fixed geometry-only XML-sequence concordance, with all missing/duplicate candidates retained.
     * @param references all independent reference boxes in XML sequence
     * @param predictions complete selected output in provider order
     * @return correct, reversed and unmatched pair counts
     */
    private static Map<String, Object> order(
            final List<Map<String, Object>> references, final List<PdfOcrProvider.Line> predictions) {
        record Pair(int ref, int prediction, double overlap) {}
        final var candidates = new ArrayList<Pair>();
        for (int r = 0; r < references.size(); r++) {
            final var box = (List<?>) references.get(r).get("box");
            final double l = ((Number) box.get(0)).doubleValue(), t = ((Number) box.get(1)).doubleValue();
            final double right = ((Number) box.get(2)).doubleValue(), bottom = ((Number) box.get(3)).doubleValue();
            for (int p = 0; p < predictions.size(); p++) {
                final var q = predictions.get(p);
                final double area = Math.max(0, Math.min(right, q.right()) - Math.max(l, q.left()))
                                    * Math.max(0, Math.min(bottom, q.bottom()) - Math.max(t, q.top()));
                final double iou =
                        area / ((right - l) * (bottom - t) + (q.right() - q.left()) * (q.bottom() - q.top()) - area);
                if (iou >= 0.5) {
                    candidates.add(new Pair(r, p, iou));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Pair::overlap)
                        .reversed()
                        .thenComparingInt(Pair::ref)
                        .thenComparingInt(Pair::prediction));
        final int[] positions = new int[references.size()];
        Arrays.fill(positions, -1);
        final var used = new HashSet<Integer>();
        for (final var pair : candidates) {
            if (positions[pair.ref()] < 0 && used.add(pair.prediction())) {
                positions[pair.ref()] = pair.prediction();
            }
        }
        int correct = 0, reversed = 0, unmatched = 0;
        for (int a = 0; a < positions.length; a++) {
            for (int b = a + 1; b < positions.length; b++) {
                if (positions[a] < 0 || positions[b] < 0) {
                    unmatched++;
                } else if (positions[a] < positions[b]) {
                    correct++;
                } else {
                    reversed++;
                }
            }
        }
        return Map.of("correct", correct, "reversed", reversed, "unmatched", unmatched);
    }

    /**
     * Returns unchanged page token encounter text for lossless regrouping checks.
     * @param lines complete ordered output
     * @return exact joined token sequence
     */
    private static String flat(final List<PdfOcrProvider.Line> lines) {
        return lines.stream().map(PdfOcrProvider.Line::text).collect(Collectors.joining(" "));
    }

    /**
     * Uses the existing scorer's NFC/ASCII-whitespace presentation, only after candidate geometry is chosen.
     * @param text original recognized text
     * @return scorer-compatible text
     */
    private static String normalize(final String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("[ \\t\\r\\n\\f]+", " ").trim();
    }

    /**
     * Reads one integer score.
     * @param map scorer output
     * @param key metric name
     * @return integer value
     */
    private static int number(final Map<String, Object> map, final String key) {
        return ((Number) map.get(key)).intValue();
    }

    /**
     * Computes a source/capture identity.
     * @param bytes complete bytes
     * @return lowercase SHA-256
     * @throws Exception if SHA-256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Fails identity or invariant checks without silently dropping an observation.
     * @param value invariant result
     * @param message explanation on failure
     */
    private static void require(final boolean value, final String message) {
        if (!value) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Writes fresh files only, preserving earlier captures and reports.
     * @param path fresh destination
     * @param value serializable report or capture
     * @throws Exception if encoding fails or the path already exists
     */
    private static void write(final Path path, final Object value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                StandardOpenOption.CREATE_NEW);
    }
}
