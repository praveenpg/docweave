package dev.docweave.partition;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Independent Poppler RGB checks for the owned rectangle-clip controls; no library runtime dependency. */
public final class VerifyRectangleClipPixels {
    /** Prevents construction of this explicit external-renderer benchmark. */
    private VerifyRectangleClipPixels() {
    }

    /**
     * Authors complete owned sources, invokes only the explicitly supplied renderer and compares every RGB pixel.
     * @param args executable, fresh scratch directory and fresh numeric report
     * @throws Exception if authoring, rendering, output bounds, exact pixels or report freshness fails
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 3 && !Files.exists(Path.of(args[1])) && !Files.exists(Path.of(args[2])),
                "Expected renderer, fresh scratch and fresh report");
        final Path executable = Path.of(args[0]).toRealPath();
        require(Files.isExecutable(executable), "Explicit renderer is not executable");
        final Path root = Files.createDirectory(Path.of(args[1]));
        run(List.of(executable.toString(), "-v"), root.resolve("version.txt"));
        final var source = Class.forName("dev.docweave.partition.PdfEmptyFormClipTest")
                                   .getDeclaredMethod("source", String.class, String.class, int.class, boolean.class);
        source.setAccessible(true);
        final var results = new ArrayList<Map<String, Object>>();
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final Path ordinary = root.resolve("ordinary-" + rotation + ".pdf");
            Files.write(ordinary, (byte[]) source.invoke(null, "none", "stroke", rotation, false),
                    StandardOpenOption.CREATE_NEW);
            final Path baseline = render(executable, ordinary);
            for (final String clip : List.of("path-empty", "path-disjoint")) {
                final Path candidate = root.resolve(clip + "-" + rotation + ".pdf");
                Files.write(candidate, (byte[]) source.invoke(null, clip, "stroke", rotation, false),
                        StandardOpenOption.CREATE_NEW);
                final Path rendered = render(executable, candidate);
                compare(baseline, rendered);
                results.add(Map.of("clip", clip, "rotation", rotation, "paint", "stroke", "all_rgb_equal", true,
                        "source_sha256", hash(candidate), "render_png_sha256", hash(rendered), "baseline_source_sha256",
                        hash(ordinary), "baseline_png_sha256", hash(baseline)));
            }
        }
        final Path visible = root.resolve("visible.pdf");
        Files.write(
                visible, (byte[]) source.invoke(null, "visible", "stroke", 0, false), StandardOpenOption.CREATE_NEW);
        final Path baseline = render(executable, visible);
        for (final String clip : List.of("path-visible-evenodd", "path-visible-mirrored", "path-visible-scaled",
                     "path-visible-compound", "path-visible-curved", "path-visible-skewed")) {
            final Path candidate = root.resolve(clip + ".pdf");
            Files.write(
                    candidate, (byte[]) source.invoke(null, clip, "stroke", 0, false), StandardOpenOption.CREATE_NEW);
            final Path rendered = render(executable, candidate);
            compare(baseline, rendered);
            results.add(Map.of("clip", clip, "rotation", 0, "paint", "stroke", "all_rgb_equal", true, "source_sha256",
                    hash(candidate), "render_png_sha256", hash(rendered), "baseline_source_sha256", hash(visible),
                    "baseline_png_sha256", hash(baseline)));
        }
        require(results.size() == 14, "Incomplete owned pair population");
        Files.writeString(Path.of(args[2]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("java_runtime",
                        System.getProperty("java.runtime.version"), "renderer_version",
                        Files.readString(root.resolve("version.txt")), "renderer_sha256", hash(executable),
                        "source_author_sha256",
                        hash(Path.of("docweave-pdf/src/test/java/dev/docweave/partition/PdfEmptyFormClipTest.java")),
                        "probe_sha256", hash(Path.of("benchmarks/tools/VerifyRectangleClipPixels.java")), "scope",
                        "Fourteen owned complete-source pairs, explicit Poppler72DPI/cropbox, exact RGB; no corpus accuracy claim",
                        "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Renders one complete owned page at a fixed independent resolution without crop selection.
     * @param executable explicitly supplied Poppler renderer
     * @param source complete one-page PDF
     * @return rendered PNG path
     * @throws Exception if the bounded external call or expected output fails
     */
    private static Path render(final Path executable, final Path source) throws Exception {
        final String stem = source.toString().substring(0, source.toString().length() - 4);
        run(List.of(executable.toString(), "-cropbox", "-r", "72", "-singlefile", "-png", source.toString(), stem),
                Path.of(stem + ".log"));
        final Path png = Path.of(stem + ".png");
        require(Files.isRegularFile(png) && Files.size(png) <= 16_000_000, "Missing or oversized owned render");
        return png;
    }

    /**
     * Enforces a finite renderer call and preserves diagnostics without accumulating unbounded pipe output.
     * @param command exact argument vector, without shell interpolation
     * @param log fresh source-specific diagnostic path
     * @throws Exception if execution times out, is interrupted or returns an error
     */
    private static void run(final List<String> command, final Path log) throws Exception {
        final var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("LC_ALL", "C");
        final var process = builder.start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Owned renderer deadline exceeded");
            }
            require(process.exitValue() == 0, "Owned renderer failed; retain its diagnostic log");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Requires matching dimensions and every decoded ARGB sample, including the source-native background.
     * @param baseline independent no-grid or visible-grid PNG
     * @param candidate independently rendered clipped source
     * @throws Exception if decoding, owned pixel bounds or equality fails
     */
    private static void compare(final Path baseline, final Path candidate) throws Exception {
        final var before = ImageIO.read(baseline.toFile());
        final var after = ImageIO.read(candidate.toFile());
        require(before != null && after != null, "Owned render could not be decoded");
        try {
            require(before.getWidth() == after.getWidth() && before.getHeight() == after.getHeight()
                            && (long) before.getWidth() * before.getHeight() <= 4_000_000,
                    "Owned render dimensions differ or exceed fixed pixel bounds");
            require(Arrays.equals(
                            before.getRGB(0, 0, before.getWidth(), before.getHeight(), null, 0, before.getWidth()),
                            after.getRGB(0, 0, after.getWidth(), after.getHeight(), null, 0, after.getWidth())),
                    "Independent rendered RGB differs for " + candidate.getFileName());
        } finally {
            before.flush();
            after.flush();
        }
    }

    /**
     * Pins a small owned input, numeric protocol source or explicitly supplied executable.
     * @param path local file
     * @return exact SHA256
     * @throws Exception if reading or hashing fails
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Stops evidence creation when a source/render protocol invariant fails.
     * @param condition expected invariant
     * @param message source-safe failure description
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
