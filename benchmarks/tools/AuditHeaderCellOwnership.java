package io.docweave.partition;

import org.apache.pdfbox.Loader;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Offline numeric audit of native glyph centers against unchanged model atomic cells. */
public final class AuditHeaderCellOwnership {
    /** Prevents utility construction. */
    private AuditHeaderCellOwnership() {
    }

    /**
     * Audits every atomic cell, including body controls, without exporting source strings.
     * @param args original PDF, original-page capture directory, new numeric output; or --self-test
     * @throws Exception if source identity, frame, geometry, work or output checks fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            controls();
            return;
        }
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected PDF, original-page capture directory, NEW report");
        }
        final var mapper = JsonMapper.builder().build();
        final var root = Path.of(args[1]);
        final var pdf = bounded(Path.of(args[0]));
        final var frame = mapper.readTree(bounded(root.resolve("original-frame.json")));
        if (!hash(pdf).equals(frame.get("pdf_sha256").asString()) || frame.get("dpi").asInt() != 150) {
            throw new IllegalArgumentException("Source identity or diagnostic DPI mismatch");
        }
        final var crops = lines(root.resolve("crops.tsv"));
        if (crops.size() != 2) {
            throw new IllegalArgumentException("This audit requires exactly one captured table crop");
        }
        final var crop = crops.get(1).split("\t");
        if (crop.length != 5 || !crop[0].equals("0")) {
            throw new IllegalArgumentException("Invalid crop frame");
        }
        final double offsetX = Double.parseDouble(crop[1]);
        final double offsetY = Double.parseDouble(crop[2]);
        final var xs = new TreeMap<Integer, Double>();
        final var ys = new TreeMap<Integer, Double>();
        final var anchors = lines(root.resolve("crop-0-anchors.tsv"));
        for (final var line : anchors.subList(1, anchors.size())) {
            final var a = line.split("\t");
            if (a.length != 9 || !a[0].equals("0")) {
                throw new IllegalArgumentException("Invalid anchor record");
            }
            final int row = Integer.parseInt(a[1]);
            final int col = Integer.parseInt(a[2]);
            boundary(xs, col, Double.parseDouble(a[5]));
            boundary(xs, col + Integer.parseInt(a[4]), Double.parseDouble(a[7]));
            boundary(ys, row, Double.parseDouble(a[6]));
            boundary(ys, row + Integer.parseInt(a[3]), Double.parseDouble(a[8]));
        }
        final var x = axis(xs);
        final var y = axis(ys);
        if ((long) (x.length - 1) * (y.length - 1) > 1024) {
            throw new IllegalArgumentException("Atomic grid exceeds1024 cells");
        }
        final var work = new PdfTableWork();
        final var cells = new ArrayList<Map<String, Object>>();
        try (final var document = Loader.loadPDF(pdf)) {
            final int pageNumber = frame.get("source_page").asInt();
            final var page = document.getPage(pageNumber - 1);
            if (page.getRotation() != 0 || page.getUserUnit() != 1 || page.getCropBox().getLowerLeftX() != 0
                    || page.getCropBox().getLowerLeftY() != 0) {
                throw new IllegalArgumentException("Audit requires upright unit-scaled zero-origin source");
            }
            if (frame.get("source_rotation").asInt() != 0
                    || frame.get("render_width").asInt() != (int) (page.getCropBox().getWidth() * (150 / 72.0f))
                    || frame.get("render_height").asInt() != (int) (page.getCropBox().getHeight() * (150 / 72.0f))
                    || !Double.isFinite(offsetX) || !Double.isFinite(offsetY) || offsetX < 0 || offsetY < 0
                    || Double.parseDouble(crop[3]) > frame.get("render_width").asInt()
                    || Double.parseDouble(crop[4]) > frame.get("render_height").asInt()) {
                throw new IllegalArgumentException("Capture frame does not match original page");
            }
            final var text = new PdfPageText(PdfOptions.defaults());
            text.setStartPage(pageNumber);
            text.setEndPage(pageNumber);
            text.getText(document);
            final var geometry = new PdfPageGeometry(page);
            final int[][] counts = new int[y.length - 1][x.length - 1];
            final var baselines = new ArrayList<List<Double>>();
            for (int i = 0; i < counts.length * counts[0].length; i++) {
                baselines.add(new ArrayList<>());
            }
            int outside = 0;
            for (final var glyph : text.glyphs()) {
                work.step();
                if (glyph.direction() != 0) {
                    throw new IllegalArgumentException("Audit requires upright native glyphs");
                }
                if (glyph.text().isBlank()) {
                    continue;
                }
                final var center = geometry.center(glyph);
                // Original-page capture uses fixed 150 DPI; crop translation is applied exactly once.
                final int col = owner(x, center.x * 150 / 72.0 - offsetX);
                final int row = owner(y, center.y * 150 / 72.0 - offsetY);
                if (row < 0 || col < 0) {
                    outside++;
                    continue;
                }
                counts[row][col]++;
                baselines.get(row * counts[0].length + col).add((double) glyph.y());
            }
            for (int row = 0; row < counts.length; row++) {
                for (int col = 0; col < counts[0].length; col++) {
                    work.step();
                    final var values = baselines.get(row * counts[0].length + col)
                                               .stream()
                                               .mapToDouble(Double::doubleValue)
                                               .sorted()
                                               .toArray();
                    cells.add(Map.of("row", row, "column", col, "glyphs", counts[row][col], "baseline_min",
                            values.length == 0 ? 0 : values[0], "baseline_max",
                            values.length == 0 ? 0 : values[values.length - 1]));
                }
            }
            Files.write(Path.of(args[2]),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("pdf_sha256", hash(pdf),
                            "anchors_sha256", hash(bounded(root.resolve("crop-0-anchors.tsv"))), "source_page",
                            pageNumber, "rows", counts.length, "columns", counts[0].length,
                            "outside_nonblank_positions", outside, "cells", cells)),
                    StandardOpenOption.CREATE_NEW);
        }
    }

    /**
     * Assigns shared edges to the following cell; the outer right/bottom edge is excluded.
     * @param edges increasing atomic boundaries
     * @param point native center in the same crop pixel frame
     * @return unique atomic index or minus one outside the grid
     */
    private static int owner(final double[] edges, final double point) {
        for (int i = 0; i < edges.length - 1; i++) {
            if (point >= edges[i] && point < edges[i + 1]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Requires consistent repeated boundaries from adjacent or merged anchors.
     * @param edges indexed boundaries
     * @param index atomic boundary index
     * @param value finite crop coordinate
     */
    private static void boundary(final TreeMap<Integer, Double> edges, final int index, final double value) {
        if (index < 0 || index > 1024 || !Double.isFinite(value)) {
            throw new IllegalArgumentException("Invalid boundary");
        }
        final var prior = edges.putIfAbsent(index, value);
        if (prior != null && Math.abs(prior - value) > 1e-6) {
            throw new IllegalArgumentException("Inconsistent repeated boundary");
        }
    }

    /**
     * Requires a complete, increasing grid without filling missing internal boundaries.
     * @param edges anchor-derived boundary map
     * @return ordered coordinates
     */
    private static double[] axis(final TreeMap<Integer, Double> edges) {
        final var values = edges.values().stream().mapToDouble(Double::doubleValue).toArray();
        if (values.length < 2 || edges.firstKey() != 0 || edges.lastKey() != values.length - 1) {
            throw new IllegalArgumentException("Incomplete atomic grid");
        }
        for (int i = 1; i < values.length; i++) {
            if (values[i] <= values[i - 1]) {
                throw new IllegalArgumentException("Nonincreasing atomic grid");
            }
        }
        return values;
    }

    /**
     * Reads bounded UTF-8 numeric TSV lines.
     * @param path capture file
     * @return decoded numeric records
     * @throws Exception if input is unavailable or exceeds the bound
     */
    private static List<String> lines(final Path path) throws Exception {
        return new String(bounded(path), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
    }

    /**
     * Reads an explicitly supplied local input with a 16 MiB bound.
     * @param path source or capture
     * @return input bytes
     * @throws Exception if the file cannot be read or exceeds the bound
     */
    private static byte[] bounded(final Path path) throws Exception {
        if (Files.size(path) > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Input exceeds16MiB");
        }
        return Files.readAllBytes(path);
    }

    /**
     * Computes exact input identity.
     * @param bytes original bytes
     * @return lowercase SHA256
     * @throws Exception if SHA256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Checks edge ownership, outside centers and missing-grid rejection. */
    private static void controls() {
        final double[] edges = {0, 10, 20};
        if (owner(edges, 10) != 1 || owner(edges, 0) != 0 || owner(edges, 20) != -1 || owner(edges, -1) != -1
                || owner(edges, Double.NaN) != -1) {
            throw new IllegalStateException("Atomic ownership regression");
        }
        final var missing = new TreeMap<Integer, Double>();
        missing.put(0, 0.0);
        missing.put(2, 20.0);
        try {
            axis(missing);
            throw new IllegalStateException("Incomplete grid accepted");
        } catch (final IllegalArgumentException expected) {
            // Missing boundaries cannot be reconstructed from text or guessed evenly.
        }
    }
}
