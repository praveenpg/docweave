package io.docweave.validation;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds isolated, source-pinned raster-rule prototypes and runs the annotation-free audit.
 *
 * <p>Replaces {@code audit_raster_rules.py}. Production sources and classes are untouched: each profile
 * compiles one edited copy of the exact reviewed source into its own directory and runs the capture
 * launcher with that variant first on the classpath. Fixed semantic edits apply only when the source
 * matches its pinned SHA-256, so drift requires a new review rather than a silent re-pin.
 */
final class RasterRulePrototypeScorer implements BenchmarkScorer {
    /** Reviewed production source digest. */
    static final String SOURCE_SHA = "04475b2ef5b7803a89b7db097e31e6e3de200e623b7bb1a5155b1c3835b83825";
    /** Retained reviewed source, relative to the repository root. */
    static final Path SOURCE_FILE = Path.of("benchmarks/review/pdf-raster-rule-audit/baseline-source.java.txt");
    /** Original dark-pixel predicate. */
    static final String DARK = "return ((rgb >>> 16) & 255) <= 80 && ((rgb >>> 8) & 255) <= 80 && (rgb & 255) <= 80;";
    /** Chromatic predicate replacing {@link #DARK} in chromatic profiles. */
    static final String CHROMATIC = "final int red = (rgb >>> 16) & 255;\n"
                                    + "        final int green = (rgb >>> 8) & 255;\n"
                                    + "        final int blue = rgb & 255;\n"
                                    + "        final int low = Math.min(red, Math.min(green, blue));\n"
                                    + "        final int high = Math.max(red, Math.max(green, blue));\n"
                                    + "        return high <= 80 || (low <= 160 && high <= 224 && high - low >= 32);";
    /** Profile name to {@code (minimum run, minimum column, minimum row)} in pixels, in report order. */
    static final Map<String, List<Long>> PROFILES = profiles();

    /** Creates the scorer. */
    RasterRulePrototypeScorer() {
    }

    /**
     * Builds the ordered profile table.
     *
     * @return immutable insertion-ordered profiles
     */
    private static Map<String, List<Long>> profiles() {
        final var result = new LinkedHashMap<String, List<Long>>();
        result.put("baseline", List.of(64L, 32L, 24L));
        result.put("chromatic", List.of(64L, 32L, 24L));
        result.put("chromatic_compact", List.of(32L, 16L, 12L));
        result.put("compact", List.of(32L, 16L, 12L));
        return java.util.Collections.unmodifiableMap(result);
    }

    @Override
    public String command() {
        return "raster-rule-prototypes";
    }

    /**
     * Applies the fixed edits for one profile to the exact reviewed source ({@code prototype}).
     *
     * @param source reviewed source text after universal-newline translation
     * @param profile profile name
     * @return edited source
     * @throws IllegalArgumentException if the source changed or the profile is unknown
     */
    static String prototype(final String source, final String profile) {
        if (!ScoringFiles.sha256(source.getBytes(StandardCharsets.UTF_8)).equals(SOURCE_SHA)) {
            throw new IllegalArgumentException("Production source changed; review before re-pinning");
        }
        if (!PROFILES.containsKey(profile)) {
            throw new IllegalArgumentException("Unknown prototype");
        }
        String result = source.replace("private static List<PdfPageGraphics.Segment> trimCaps(",
                                      "static List<PdfPageGraphics.Segment> trimCaps(")
                                .replace("private static boolean hasRuleRemnants(", "static boolean hasRuleRemnants(");
        if (profile.equals("chromatic") || profile.equals("chromatic_compact")) {
            result = result.replace(DARK, CHROMATIC);
        }
        if (profile.equals("compact") || profile.equals("chromatic_compact")) {
            result = result.replace("private static final int MIN_RUN = 64;", "private static final int MIN_RUN = 32;")
                             .replace(">= 32;", ">= 16;")
                             .replace(">= 24;", ">= 12;");
        }
        return result;
    }

    @Override
    public void run(final List<String> arguments, final PrintStream console) throws IOException {
        final var options = ScoringArguments.parse(
                arguments, Set.of("jdk", "classpath", "captures", "pins", "output"), Set.of(), Set.of(), 0);
        final String source = ScoringFiles.readText(SOURCE_FILE);
        final String classpath = ScoringFiles.strip(ScoringFiles.readText(options.path("classpath")));
        final Path output = options.path("output");
        Files.createDirectory(output);
        final Path launcher = Path.of("benchmarks/tools/CaptureRasterRuleAudit.java").toAbsolutePath();
        final Path jdk = options.path("jdk");
        final var identities = new LinkedHashMap<String, Object>();
        for (final var profile : PROFILES.entrySet()) {
            final Path directory = Files.createDirectory(output.resolve(profile.getKey()));
            final Path variant = directory.resolve("RasterPdfTableDetector.java");
            Files.writeString(variant, prototype(source, profile.getKey()), StandardCharsets.UTF_8);
            final Path classes = directory.resolve("classes");
            execute(List.of(jdk.resolve("bin/javac").toString(), "--release", "21", "-cp", classpath, "-d",
                            classes.toString(), variant.toString(), launcher.toString()),
                    null);
            execute(List.of(jdk.resolve("bin/javadoc").toString(), "-quiet", "-Xdoclint:all", "-Werror", "-classpath",
                            classes + ":" + classpath, "-d", directory.resolve("docs").toString(), launcher.toString()),
                    null);
            execute(List.of(jdk.resolve("bin/java").toString(), "-Djava.awt.headless=true", "-cp",
                            classes + ":" + classpath, "io.docweave.partition.CaptureRasterRuleAudit",
                            options.path("captures").toString(), options.path("pins").toString(),
                            directory.resolve("capture").toString(), profile.getValue().get(1).toString(),
                            profile.getValue().get(2).toString(), profile.getKey()),
                    directory.resolve("run.log"));
            final var identity = new LinkedHashMap<String, Object>();
            identity.put("source_sha256", ScoringFiles.sha256(variant));
            identity.put("minimum_run", profile.getValue().get(0));
            identity.put("minimum_column", profile.getValue().get(1));
            identity.put("minimum_row", profile.getValue().get(2));
            identities.put(profile.getKey(), identity);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("baseline_source_sha256", SOURCE_SHA);
        report.put("profiles", identities);
        CtdarEvidence.write(output.resolve("profiles.json"), report, false);
    }

    /**
     * Runs one JDK tool and requires success.
     *
     * @param command executable and arguments
     * @param log combined output file, or {@code null} to inherit the console
     * @throws IOException if the process fails, cannot start or is interrupted
     */
    private static void execute(final List<String> command, final Path log) throws IOException {
        final var builder = new ProcessBuilder(new ArrayList<>(command));
        if (log == null) {
            builder.inheritIO();
        } else {
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
        }
        final Process process = builder.start();
        try {
            if (process.waitFor() != 0) {
                throw new IOException("Command failed: " + command.getFirst());
            }
        } catch (final InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + command.getFirst(), interrupted);
        }
    }
}
