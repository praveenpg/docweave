package dev.docweave.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regenerates every retained cTDaR scorer report from retained numeric captures and requires byte identity.
 *
 * <p>The original cTDaR images and XML labels are fetch-only evaluation inputs and are not redistributed,
 * so this replay runs only when {@code -Ddocweave.scoring.ctdar.cache=DIR} names a local cache matching the
 * pinned manifest. It proves that the JVM scorers reproduce the historical Python reports exactly.
 */
class CtdarScorerReplayTest {
    /** Opt-in cache property. */
    private static final String CACHE = "docweave.scoring.ctdar.cache";
    /** Pinned original manifest. */
    private static final String MANIFEST = "benchmarks/review/pdf-physical-scans/extended-manifest.json";
    /** Retained evidence root. */
    private static final Path REVIEW = Path.of("benchmarks/review");
    @TempDir Path temporary;

    /** Creates the replay. */
    CtdarScorerReplayTest() {
    }

    /**
     * Runs one scorer and compares its new report with retained bytes.
     *
     * @param command scorer command
     * @param arguments options without {@code --output}
     * @param retained historical report, or {@code null} for verification-only commands
     * @throws IOException if the scorer fails
     */
    private void replay(final String command, final List<String> arguments, final Path retained) throws IOException {
        final Path output = temporary.resolve(command + "-" + Math.abs(arguments.hashCode()) + ".json");
        final var all = new ArrayList<>(arguments);
        all.add("--output");
        all.add(output.toString());
        BenchmarkScorer.named(command).orElseThrow().run(
                all, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        if (retained != null) {
            assertArrayEquals(Files.readAllBytes(retained), Files.readAllBytes(output), command);
        }
    }

    @Test
    void retainedCtdarReportsAreReproducedByteForByte() throws IOException {
        final String cache = System.getProperty(CACHE, "");
        if (cache.isBlank()) {
            // Opt-in like the other original-source replays: the labels are fetch-only and not redistributed.
            return;
        }
        replay("tiled-regions",
                List.of("--capture21", "benchmarks/review/pdf-tiled-regions/java21", "--capture25",
                        "benchmarks/review/pdf-tiled-regions/java25", "--cache", cache, "--manifest", MANIFEST),
                REVIEW.resolve("pdf-tiled-regions/summary.json"));
        replay("detector-structures",
                List.of("compare", "--capture21", "benchmarks/review/pdf-detector-structures/java21", "--capture25",
                        "benchmarks/review/pdf-detector-structures/java25", "--cache", cache, "--manifest", MANIFEST,
                        "--proposals", "benchmarks/review/pdf-detector-structures/proposals"),
                REVIEW.resolve("pdf-detector-structures/summary.json"));
        replay("compact-layout",
                List.of("--capture21", "benchmarks/review/pdf-compact-ruled/java21", "--capture25",
                        "benchmarks/review/pdf-compact-ruled/java25", "--cache", cache, "--manifest", MANIFEST),
                REVIEW.resolve("pdf-compact-ruled/summary.json"));
        for (final String dpi : List.of("150", "300")) {
            final String prefix = dpi.equals("150") ? "" : "dpi300-";
            replay("ruled-scans",
                    List.of("--capture21", "benchmarks/review/pdf-whole-page-ruled-composition/" + prefix + "java21",
                            "--capture25", "benchmarks/review/pdf-whole-page-ruled-composition/" + prefix + "java25",
                            "--cache", cache, "--dpi", dpi, "--manifest", MANIFEST, "--baseline",
                            "benchmarks/review/pdf-detector-structures"),
                    REVIEW.resolve("pdf-whole-page-ruled-composition/" + prefix + "summary.json"));
        }
        // The retained audit flattens each profile's capture directory; rebuild the tool's original layout.
        final Path audit = temporary.resolve("audit");
        for (final String jdk : List.of("java21", "java25")) {
            for (final String profile : RasterRulePrototypeScorer.PROFILES.keySet()) {
                copy(REVIEW.resolve("pdf-raster-rule-audit").resolve(jdk).resolve(profile),
                        audit.resolve(jdk).resolve(profile).resolve("capture"));
            }
            Files.copy(
                    REVIEW.resolve("pdf-raster-rule-audit/profiles.json"), audit.resolve(jdk).resolve("profiles.json"));
        }
        replay("raster-rule-audit",
                List.of("--java21", audit.resolve("java21").toString(), "--java25", audit.resolve("java25").toString(),
                        "--cache", cache, "--manifest", MANIFEST),
                REVIEW.resolve("pdf-raster-rule-audit/summary.json"));
        replay("scan-detectors-verify",
                List.of("--report", "benchmarks/review/pdf-alternate-detectors/summary.json", "--capture21",
                        "benchmarks/review/pdf-alternate-detectors/java21", "--cache", cache, "--manifest", MANIFEST),
                null);
    }

    /**
     * Copies a directory tree.
     *
     * @param source source root
     * @param target new target root
     * @throws IOException if copying fails
     */
    private static void copy(final Path source, final Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (final Path path : paths.toList()) {
                final Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }
}
