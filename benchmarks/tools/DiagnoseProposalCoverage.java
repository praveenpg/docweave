package io.docweave.validation;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Separates detector recall from table assembly for the frozen NVIDIA statement regions; text-free diagnostic.
 *
 * <p>Every visible source text line (PDFBox glyphs grouped on a common baseline within two units) whose centre lies
 * in a case's reference region is classified, per detector profile, as outside every captured proposal crop
 * (recall), inside a crop but outside every emitted table envelope (assembly or boundary), or inside an emitted
 * table. Crops and tables are captured raster pixels converted with the captured render resolution. No model runs;
 * the report contains counts and hashes only.
 */
public final class DiagnoseProposalCoverage {
    /** Prevents construction of this command-line tool. */
    private DiagnoseProposalCoverage() {
    }

    /**
     * Writes coverage counts per case, region and profile.
     *
     * @param args reference manifest, source directory, capture directory and a fresh report
     * @throws Exception if an identity check, parse or write fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("reference-manifest sources captures report");
        }
        final var mapper = JsonMapper.builder().build();
        final byte[] manifestBytes = Files.readAllBytes(Path.of(args[0]));
        final JsonNode manifest = mapper.readTree(manifestBytes);
        final var input = manifest.get("inputs").get(0);
        final byte[] pdf = Files.readAllBytes(Path.of(args[1], input.get("filename").asString()));
        require(hash(pdf).equals(input.get("sha256").asString()), "Source changed");
        final var rows = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            for (final var region : selected.get("regions")) {
                final int page = region.get("page").asInt();
                final var roi = region.get("roi_left_top_right_bottom");
                final var lines =
                        lines(pdf, page)
                                .stream()
                                .filter(line
                                        -> line[4] >= roi.get(0).asDouble() && line[4] <= roi.get(2).asDouble()
                                                   && line[5] >= roi.get(1).asDouble()
                                                   && line[5] <= roi.get(3).asDouble())
                                .toList();
                final Path capture = Path.of(args[2], String.format("original-page-%04d", page));
                final var frame = ScoringFiles.rows(capture.resolve("source.tsv")).getFirst();
                require(frame.get("pdf_sha256").equals(hash(pdf)), "Capture source changed");
                final double scale = Double.parseDouble(frame.get("render_dpi")) / 72;
                for (final String profile : List.of("tatr", "yolox")) {
                    final var crops = boxes(capture.resolve(profile).resolve("crops.tsv"), scale);
                    final var tables = Files.exists(capture.resolve(profile).resolve("tables.tsv"))
                                               ? boxes(capture.resolve(profile).resolve("tables.tsv"), scale)
                                               : List.<double[]>of();
                    int outside = 0;
                    int cropOnly = 0;
                    int table = 0;
                    for (final double[] line : lines) {
                        if (tables.stream().anyMatch(box -> inside(box, line))) {
                            table++;
                        } else if (crops.stream().anyMatch(box -> inside(box, line))) {
                            cropOnly++;
                        } else {
                            outside++;
                        }
                    }
                    final var row = new LinkedHashMap<String, Object>();
                    row.put("case", selected.get("id").asString());
                    row.put("page", page);
                    row.put("profile", profile);
                    row.put("region_lines", lines.size());
                    row.put("outside_every_crop", outside);
                    row.put("inside_crop_outside_tables", cropOnly);
                    row.put("inside_emitted_table", table);
                    row.put("crops", crops.size());
                    row.put("tables", tables.size());
                    rows.add(row);
                    System.out.println(row);
                }
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Line-level detector-crop and emitted-table coverage of frozen NVIDIA reference regions; "
                                    + "counts only, no model run, no reference text.");
        report.put("reference_manifest_sha256", hash(manifestBytes));
        report.put("java_version", System.getProperty("java.version"));
        report.put("rows", rows);
        Files.writeString(Path.of(args[3]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Reads pixel boxes and converts them to points.
     *
     * @param path TSV with left/top/right/bottom pixel columns
     * @param scale pixels per point
     * @return point boxes
     * @throws IOException if reading fails
     */
    private static List<double[]> boxes(final Path path, final double scale) throws IOException {
        return ScoringFiles.rows(path)
                .stream()
                .map(row
                        -> new double[] {Double.parseDouble(row.get("left")) / scale,
                                Double.parseDouble(row.get("top")) / scale,
                                Double.parseDouble(row.get("right")) / scale,
                                Double.parseDouble(row.get("bottom")) / scale})
                .toList();
    }

    /**
     * Tests whether a line centre lies inside a box.
     *
     * @param box point box
     * @param line line {@code left, top, right, bottom, centreX, centreY}
     * @return containment
     */
    private static boolean inside(final double[] box, final double[] line) {
        return line[4] >= box[0] && line[4] <= box[2] && line[5] >= box[1] && line[5] <= box[3];
    }

    /**
     * Groups visible glyphs of one page into baseline lines.
     *
     * @param pdf source bytes
     * @param page one-based page
     * @return lines as {@code left, top, right, bottom, centreX, centreY} in displayed points
     * @throws IOException if parsing fails
     */
    static List<double[]> lines(final byte[] pdf, final int page) throws IOException {
        final var glyphs = new ArrayList<TextPosition>();
        try (var document = Loader.loadPDF(pdf)) {
            final var stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(final TextPosition text) {
                    if (!text.getUnicode().isBlank()) {
                        glyphs.add(text);
                    }
                }
            };
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            stripper.getText(document);
        }
        glyphs.sort((a, b) -> Float.compare(a.getYDirAdj(), b.getYDirAdj()));
        final var result = new ArrayList<double[]>();
        double[] current = null;
        double baseline = Double.NaN;
        for (final var glyph : glyphs) {
            final double y = glyph.getYDirAdj();
            if (current == null || Math.abs(y - baseline) > 2) {
                if (current != null) {
                    result.add(finish(current));
                }
                current = new double[] {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                baseline = y;
            }
            current[0] = Math.min(current[0], glyph.getXDirAdj());
            current[1] = Math.min(current[1], y - glyph.getHeightDir());
            current[2] = Math.max(current[2], glyph.getXDirAdj() + glyph.getWidthDirAdj());
            current[3] = Math.max(current[3], y);
        }
        if (current != null) {
            result.add(finish(current));
        }
        return result;
    }

    /**
     * Adds the centre to a line envelope.
     *
     * @param box left, top, right, bottom
     * @return envelope with centre
     */
    private static double[] finish(final double[] box) {
        return new double[] {box[0], box[1], box[2], box[3], (box[0] + box[2]) / 2, (box[1] + box[3]) / 2};
    }

    /**
     * Returns the lowercase SHA-256 of bytes.
     *
     * @param bytes bytes
     * @return digest
     * @throws Exception if SHA-256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Fails on a broken invariant.
     *
     * @param condition invariant
     * @param message failure message
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
