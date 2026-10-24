package io.docweave.partition;

import org.apache.pdfbox.Loader;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Numeric-only native-baseline inventory over complete frozen model captures; never changes table topology. */
public final class AuditModelCellBaselines {
    /** Prevents utility construction. */
    private AuditModelCellBaselines() {
    }

    /**
     * Assigns upright glyph centers to unchanged half-open model cells on every original page.
     * The baseline tolerance is the existing two-point native grouping tolerance, not a tuned row separator.
     * Multiple baselines may represent wrapping or headers and are never treated as a correctness oracle.
     * @param args original PDF, replay manifest, raster root, structure root and NEW numeric report; or --self-test
     * @throws Exception if identities, geometry, population, cancellation or fresh-output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        controls();
        if (args.length == 1 && args[0].equals("--self-test")) {
            System.out.println("Native cell baseline controls passed");
            return;
        }
        require(args.length == 5 && !Files.exists(Path.of(args[4])),
                "Expected original, manifest, roots and NEW report");
        final var source = Path.of(args[0]);
        require(Files.size(source) <= 16 * 1024 * 1024, "Source exceeds audit budget");
        final var mapper = JsonMapper.builder().build();
        final var protocol = mapper.readTree(Files.readAllBytes(Path.of(args[1])));
        final var reference = Path.of(protocol.get("reference_manifest").asString());
        require(hash(reference).equals(protocol.get("reference_manifest_sha256").asString()),
                "Source protocol changed");
        final var identity = mapper.readTree(Files.readAllBytes(reference));
        require(hash(source).equals(identity.get("inputs").get(0).get("sha256").asString()), "Original source changed");
        final var pages = new ArrayList<Map<String, Object>>();
        try (final var document = Loader.loadPDF(source.toFile())) {
            require(document.getNumberOfPages() == protocol.get("pages").size(), "Incomplete original pages");
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                final var page = document.getPage(index);
                final var crop = page.getCropBox();
                require(page.getRotation() == 0 && page.getUserUnit() == 1 && crop.getLowerLeftX() == 0
                                && crop.getLowerLeftY() == 0,
                        "Unsupported source coordinate frame");
                final var pin = protocol.get("pages").get(index);
                final String id = pin.get("capture_id").asString();
                require(id.matches("original-page-[0-9]{4}") && pin.get("source_page").asInt() == index + 1,
                        "Original sequence changed");
                final var raster = Path.of(args[2], id, "render.png");
                require(hash(raster).equals(pin.get("render_png_sha256").asString()), "Frozen raster changed");
                final var image = ImageIO.read(raster.toFile());
                require(image != null, "Missing raster decoder");
                final double scaleX;
                final double scaleY;
                try {
                    require((long) image.getWidth() * image.getHeight() <= 16_000_000, "Raster exceeds audit budget");
                    scaleX = crop.getWidth() / (double) image.getWidth();
                    scaleY = crop.getHeight() / (double) image.getHeight();
                } finally {
                    image.flush();
                }
                final var text = new PdfPageText(PdfOptions.defaults());
                text.setStartPage(index + 1);
                text.setEndPage(index + 1);
                text.getText(document);
                require(text.glyphs().stream().allMatch(glyph -> glyph.direction() == 0), "Nonupright native glyphs");
                for (final String family : List.of("tatr", "yolox")) {
                    // Each independent page/profile has its own unchanged per-page geometry ceiling.
                    final var work = new PdfTableWork();
                    final var root = Path.of(args[3], id, family);
                    for (final String file : List.of("tables.tsv", "anchors.tsv", "outcome.txt")) {
                        require(hash(root.resolve(file)).equals(pin.get(family).get(file).asString()),
                                "Frozen model output changed");
                    }
                    final String outcome = Files.readString(root.resolve("outcome.txt")).strip();
                    final var cells = new ArrayList<Map<String, Object>>();
                    int assigned = 0;
                    final var owned = new boolean[text.glyphs().size()];
                    final var lines = Files.readAllLines(root.resolve("anchors.tsv"));
                    require(lines.size() <= 8193
                                    && lines.getFirst().equals(
                                            "crop\ttable\trow\tcolumn\trow_span\tcolumn_span\tleft\ttop\tright\tbottom"),
                            "Unsupported anchor population/schema");
                    for (final String line : lines.subList(1, lines.size())) {
                        final var fields = line.split("\t", -1);
                        require(fields.length == 10, "Invalid anchor schema");
                        final double left = Double.parseDouble(fields[6]) * scaleX;
                        final double top = Double.parseDouble(fields[7]) * scaleY;
                        final double right = Double.parseDouble(fields[8]) * scaleX;
                        final double bottom = Double.parseDouble(fields[9]) * scaleY;
                        require(Double.isFinite(left) && Double.isFinite(top) && Double.isFinite(right)
                                        && Double.isFinite(bottom) && left < right && top < bottom,
                                "Invalid anchor geometry");
                        final var baselines = new ArrayList<Double>();
                        for (int glyphIndex = 0; glyphIndex < text.glyphs().size(); glyphIndex++) {
                            work.step();
                            final var glyph = text.glyphs().get(glyphIndex);
                            final double x = glyph.x() + glyph.width() / 2;
                            final double y = glyph.y() - glyph.height() / 2;
                            if (!glyph.text().isBlank() && contains(x, y, left, top, right, bottom)) {
                                require(!owned[glyphIndex], "Overlapping captured native ownership");
                                owned[glyphIndex] = true;
                                assigned++;
                                baselines.add((double) glyph.y());
                            }
                        }
                        final var row = new LinkedHashMap<String, Object>();
                        for (int field = 0; field < 6; field++) {
                            row.put(List.of("crop", "table", "row", "column", "row_span", "column_span").get(field),
                                    Integer.parseInt(fields[field]));
                        }
                        row.put("native_nonblank_glyphs", baselines.size());
                        row.put("native_baselines", cluster(baselines));
                        cells.add(row);
                    }
                    pages.add(Map.of("page", index + 1, "family", family, "outcome", outcome, "source_nonblank_glyphs",
                            text.glyphs().stream().filter(glyph -> !glyph.text().isBlank()).count(),
                            "assigned_nonblank_glyphs", assigned, "cells", cells));
                }
            }
        }
        Files.writeString(Path.of(args[4]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "Complete frozen model-cell native-baseline inventory; no labels, text, inference or topology repair",
                        "runtime", Runtime.version().toString(), "source_sha256", hash(source),
                        "replay_manifest_sha256", hash(Path.of(args[1])), "baseline_tolerance_points", 2, "results",
                        pages))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Tests half-open point ownership, shared with native cell extraction conventions.
     * @param x horizontal source center
     * @param y vertical source center
     * @param left lower horizontal bound
     * @param top lower vertical bound
     * @param right exclusive upper horizontal bound
     * @param bottom exclusive upper vertical bound
     * @return whether the center belongs to the cell
     */
    private static boolean contains(final double x, final double y, final double left, final double top,
            final double right, final double bottom) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    /**
     * Groups sorted baseline positions within two points of the first position of each cluster.
     * Avoids transitive chaining across separate native text lines.
     * @param source original glyph baselines
     * @return distinct cluster starts, never text
     */
    private static List<Double> cluster(final List<Double> source) {
        final var sorted = new ArrayList<>(source);
        sorted.sort(Comparator.naturalOrder());
        final var result = new ArrayList<Double>();
        for (final double baseline : sorted) {
            require(Double.isFinite(baseline), "Nonfinite source baseline");
            if (result.isEmpty() || baseline - result.getLast() > 2) {
                result.add(baseline);
            }
        }
        return List.copyOf(result);
    }

    /** Exercises inclusive tolerance, nontransitive grouping, empty input and half-open shared-cell ownership. */
    private static void controls() {
        require(cluster(List.of(14.0, 10.0, 12.0)).equals(List.of(10.0, 14.0)), "Baseline chaining/tolerance changed");
        require(cluster(List.of()).isEmpty(), "Empty cell gained a baseline");
        require(!contains(10, 5, 0, 0, 10, 10) && contains(10, 5, 10, 0, 20, 10), "Shared edge duplicates glyph");
        require(!contains(5, 10, 0, 0, 10, 10) && contains(0, 0, 0, 0, 10, 10), "Half-open ownership changed");
    }

    /**
     * Hashes complete local evidence bytes without recording source content.
     * @param path exact evaluated input
     * @return lowercase SHA256
     * @throws Exception if input access or hashing fails
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects protocol violations without exposing source text.
     * @param condition required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
