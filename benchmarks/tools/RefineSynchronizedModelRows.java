package dev.docweave.partition;

import org.apache.pdfbox.Loader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Experimental native-gap refinement of frozen model captures; does not change production extraction. */
public final class RefineSynchronizedModelRows {
    /** Prevents construction of the standalone experiment. */
    private RefineSynchronizedModelRows() {
    }

    /**
     * Copies every frozen capture into a fresh directory, refining only rows admitted by the frozen gate.
     * Every new separator must fit a common open vertical gap between original upright glyph extents;
     * source glyphs, column boundaries, table envelopes and all rejected rows remain unchanged.
     * @param args original PDF, replay manifest, raster root, capture root, gate report and fresh directory; or
     *         --self-test
     * @throws Exception if identities, frames, geometry, budgets or fresh-output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        controls();
        if (args.length == 1 && args[0].equals("--self-test")) {
            System.out.println("Native-gap refinement controls passed");
            return;
        }
        require(args.length == 6, "Expected source, replay, raster/capture roots, gate and new directory");
        final var mapper = JsonMapper.builder().build();
        final var protocol = (ObjectNode) mapper.readTree(Files.readAllBytes(Path.of(args[1])));
        final var gate = mapper.readTree(Files.readAllBytes(Path.of(args[4])));
        require(gate.get("inventory_sha256")
                        .asString()
                        .equals(hash(Path.of("benchmarks/review/pdf-model-cell-baselines/java21.json"))),
                "Gate inventory changed");
        require(hash(Path.of(args[0])).equals(gate.get("source_sha256").asString()), "Original source changed");
        final var reference = Path.of(protocol.get("reference_manifest").asString());
        require(hash(reference).equals(protocol.get("reference_manifest_sha256").asString()), "Reference changed");
        final var output = Path.of(args[5]);
        require(!Files.exists(output), "Output directory already exists");
        Files.createDirectory(output);
        final var results = new ArrayList<Map<String, Object>>();
        try (final var document = Loader.loadPDF(Path.of(args[0]).toFile())) {
            require(document.getNumberOfPages() == 11 && protocol.get("pages").size() == 11
                            && gate.get("results").size() == 22,
                    "Incomplete page population");
            for (int page = 0; page < 11; page++) {
                final var physical = document.getPage(page);
                final var box = physical.getCropBox();
                require(physical.getRotation() == 0 && physical.getUserUnit() == 1 && box.getLowerLeftX() == 0
                                && box.getLowerLeftY() == 0,
                        "Unsupported original frame");
                final var pin = protocol.get("pages").get(page);
                final String id = pin.get("capture_id").asString();
                require(id.equals("original-page-%04d".formatted(page + 1)), "Capture sequence changed");
                final var raster = Path.of(args[2], id, "render.png");
                require(hash(raster).equals(pin.get("render_png_sha256").asString()), "Raster changed");
                final var image = ImageIO.read(raster.toFile());
                final double sx = box.getWidth() / (double) image.getWidth();
                final double sy = box.getHeight() / (double) image.getHeight();
                image.flush();
                final var text = new PdfPageText(PdfOptions.defaults());
                text.setStartPage(page + 1);
                text.setEndPage(page + 1);
                text.getText(document);
                require(text.glyphs().stream().allMatch(glyph -> glyph.direction() == 0), "Nonupright original");
                for (final String family : List.of("tatr", "yolox")) {
                    final var directory = Path.of(args[3], id, family);
                    final var destination = output.resolve(id).resolve(family);
                    Files.createDirectories(destination);
                    for (final String file : List.of("tables.tsv", "anchors.tsv", "outcome.txt")) {
                        require(hash(directory.resolve(file)).equals(pin.get(family).get(file).asString()),
                                "Frozen model capture changed");
                    }
                    final var tables = fields(directory.resolve("tables.tsv"));
                    var anchors = fields(directory.resolve("anchors.tsv"));
                    final var work = new PdfTableWork();
                    int candidates = 0;
                    int refined = 0;
                    for (final var observation : gate.get("results")) {
                        if (observation.get("page").asInt() != page + 1
                                || !observation.get("family").asString().equals(family)) {
                            continue;
                        }
                        // Descending row ordinals retain the frozen identities of not-yet-processed candidates.
                        final var rows = new ArrayList<tools.jackson.databind.JsonNode>();
                        observation.get("rows").forEach(rows::add);
                        rows.sort(java.util.Comparator
                                        .comparingInt((tools.jackson.databind.JsonNode row) -> row.get("row").asInt())
                                        .reversed());
                        for (final var row : rows) {
                            if (!row.get("gate").asString().equals("SYNCHRONIZED_CANDIDATE")) {
                                continue;
                            }
                            candidates++;
                            final int crop = row.get("crop").asInt();
                            final int table = row.get("table").asInt();
                            final int ordinal = row.get("row").asInt();
                            final var members =
                                    anchors.stream()
                                            .filter(anchor
                                                    -> same(anchor, crop, table) && integer(anchor, 2) == ordinal)
                                            .toList();
                            final boolean crosses = anchors.stream().anyMatch(anchor
                                    -> same(anchor, crop, table) && integer(anchor, 2) < ordinal
                                               && integer(anchor, 2) + integer(anchor, 4) > ordinal);
                            if (crosses || members.size() < 2
                                    || members.stream().anyMatch(
                                            anchor -> integer(anchor, 4) != 1 || integer(anchor, 5) != 1)) {
                                continue;
                            }
                            final var cuts = separators(members, text.glyphs(), sx, sy, work);
                            if (cuts.isEmpty()) {
                                continue;
                            }
                            final var replacement = new ArrayList<String[]>();
                            for (final var anchor : anchors) {
                                if (!same(anchor, crop, table)) {
                                    replacement.add(anchor);
                                    continue;
                                }
                                final int originalRow = integer(anchor, 2);
                                if (originalRow == ordinal) {
                                    for (int part = 0; part <= cuts.size(); part++) {
                                        final var cell = anchor.clone();
                                        cell[2] = Integer.toString(ordinal + part);
                                        if (part > 0) {
                                            cell[7] = Double.toString(cuts.get(part - 1) / sy);
                                        }
                                        if (part < cuts.size()) {
                                            cell[9] = Double.toString(cuts.get(part) / sy);
                                        }
                                        replacement.add(cell);
                                    }
                                } else {
                                    final var cell = anchor.clone();
                                    if (originalRow > ordinal) {
                                        cell[2] = Integer.toString(originalRow + cuts.size());
                                    }
                                    replacement.add(cell);
                                }
                            }
                            anchors = replacement;
                            for (final var capturedTable : tables) {
                                if (same(capturedTable, crop, table)) {
                                    capturedTable[2] = Integer.toString(integer(capturedTable, 2) + cuts.size());
                                }
                            }
                            refined++;
                        }
                    }
                    write(destination.resolve("tables.tsv"), "crop\ttable\trows\tcolumns\tleft\ttop\tright\tbottom",
                            tables);
                    write(destination.resolve("anchors.tsv"),
                            "crop\ttable\trow\tcolumn\trow_span\tcolumn_span\tleft\ttop\tright\tbottom", anchors);
                    Files.copy(directory.resolve("outcome.txt"), destination.resolve("outcome.txt"));
                    for (final String file : List.of("tables.tsv", "anchors.tsv", "outcome.txt")) {
                        ((ObjectNode) pin.get(family)).put(file, hash(destination.resolve(file)));
                    }
                    results.add(Map.of("page", page + 1, "family", family, "candidates", candidates, "refined_rows",
                            refined, "anchors", anchors.size()));
                }
            }
        }
        protocol.put("scope",
                "Experimental synchronized native-gap refinement; all original captures/pages retained; no reference text or default changes");
        Files.writeString(output.resolve("replay-manifest.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(protocol) + "\n",
                StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("refinement.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime",
                        Runtime.version().toString(), "source_sha256", hash(Path.of(args[0])), "gate_sha256",
                        hash(Path.of(args[4])), "original_replay_sha256", hash(Path.of(args[1])), "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Finds common open gaps between equal native baseline clusters, retaining all original glyph extents.
     * @param cells complete candidate row anchors
     * @param glyphs original page glyphs
     * @param sx displayed points per raster pixel horizontally
     * @param sy displayed points per raster pixel vertically
     * @param work unchanged shared per-page/profile geometry ceiling
     * @return common source-point separators or empty for any ambiguity
     * @throws Exception if the shared work/cancellation budget fails
     */
    private static List<Double> separators(final List<String[]> cells, final List<PdfPageText.Glyph> glyphs,
            final double sx, final double sy, final PdfTableWork work) throws Exception {
        final var lines = new ArrayList<List<PdfPageText.Glyph>>();
        double top = Double.NEGATIVE_INFINITY;
        double bottom = Double.POSITIVE_INFINITY;
        int expected = 0;
        int occupied = 0;
        for (final var cell : cells) {
            final double left = Double.parseDouble(cell[6]) * sx;
            final double right = Double.parseDouble(cell[8]) * sx;
            final double upper = Double.parseDouble(cell[7]) * sy;
            final double lower = Double.parseDouble(cell[9]) * sy;
            top = Math.max(top, upper);
            bottom = Math.min(bottom, lower);
            final var owned = new ArrayList<PdfPageText.Glyph>();
            for (final var glyph : glyphs) {
                work.step();
                final double x = glyph.x() + glyph.width() / 2;
                final double y = glyph.y() - glyph.height() / 2;
                if (x >= left && x < right && y >= upper && y < lower) {
                    owned.add(glyph);
                }
            }
            if (owned.stream().allMatch(glyph -> glyph.text().isBlank())) {
                continue;
            }
            occupied++;
            owned.sort(java.util.Comparator.comparingDouble(PdfPageText.Glyph::y));
            final var groups = new ArrayList<List<PdfPageText.Glyph>>();
            for (final var glyph : owned) {
                if (groups.isEmpty() || glyph.y() - groups.getLast().getFirst().y() > 2) {
                    groups.add(new ArrayList<>());
                }
                groups.getLast().add(glyph);
            }
            if (groups.size() < 2 || expected != 0 && groups.size() != expected) {
                return List.of();
            }
            if (expected == 0) {
                expected = groups.size();
                groups.forEach(group -> lines.add(new ArrayList<>(group)));
            } else {
                for (int index = 0; index < expected; index++) {
                    if (Math.abs(groups.get(index).getFirst().y() - lines.get(index).getFirst().y()) > 2) {
                        return List.of();
                    }
                    lines.get(index).addAll(groups.get(index));
                }
            }
        }
        if (expected < 2 || occupied < 2) {
            return List.of();
        }
        final var cuts = new ArrayList<Double>();
        for (int index = 0; index + 1 < lines.size(); index++) {
            final double previousBottom =
                    lines.get(index).stream().mapToDouble(PdfPageText.Glyph::y).max().orElseThrow();
            final double nextTop =
                    lines.get(index + 1).stream().mapToDouble(glyph -> glyph.y() - glyph.height()).min().orElseThrow();
            final double cut = (previousBottom + nextTop) / 2;
            if (previousBottom >= nextTop || cut <= top || cut >= bottom) {
                return List.of();
            }
            cuts.add(cut);
        }
        return List.copyOf(cuts);
    }

    /**
     * Checks common source gaps, unequal lines, tall glyph overlap, empty rows and interruption.
     * @throws Exception if an authored geometric control fails
     */
    private static void controls() throws Exception {
        final var first = new String[] {"0", "0", "0", "0", "1", "1", "0", "0", "10", "40"};
        final var second = new String[] {"0", "0", "0", "1", "1", "1", "10", "0", "20", "40"};
        final var cells = List.of(first, second);
        final var glyphs = List.of(glyph(2, 10, 6), glyph(2, 24, 6), glyph(12, 10, 6), glyph(12, 24, 6));
        require(separators(cells, glyphs, 1, 1, new PdfTableWork()).equals(List.of(14.0)),
                "Common open source gap changed");
        require(separators(cells, glyphs.subList(0, 3), 1, 1, new PdfTableWork()).isEmpty(),
                "Unequal native baseline counts accepted");
        final var overlapping = List.of(glyph(2, 10, 6), glyph(2, 24, 20), glyph(12, 10, 6), glyph(12, 24, 6));
        require(separators(cells, overlapping, 1, 1, new PdfTableWork()).isEmpty(), "Overlapping glyph extents split");
        require(separators(cells, List.of(), 1, 1, new PdfTableWork()).isEmpty(), "Empty row gained separator");
        require(separators(cells, glyphs, 2, 1, new PdfTableWork()).isEmpty(),
                "Single occupied native column accepted");
        Thread.currentThread().interrupt();
        try {
            separators(cells, glyphs, 1, 1, new PdfTableWork());
            throw new IllegalStateException("Interrupted experiment continued");
        } catch (final java.io.InterruptedIOException expected) {
            require(Thread.currentThread().isInterrupted(), "Cancellation cleared caller interrupt");
        } finally {
            // The owned control sets the flag itself; clear it before the real experiment resumes.
            Thread.interrupted();
        }
    }

    /**
     * Creates one immutable upright owned glyph with explicit approximate native extent.
     * @param x native advance start
     * @param y native baseline
     * @param height original approximate native height
     * @return authored glyph used only by JVM controls
     */
    private static PdfPageText.Glyph glyph(final float x, final float y, final float height) {
        return new PdfPageText.Glyph("x", x, y, 1, height, 3, 0);
    }

    /**
     * Reads numeric TSV data after its historical header.
     * @param file pinned original capture
     * @return mutable experiment-local numeric rows
     * @throws Exception if source access fails
     */
    private static List<String[]> fields(final Path file) throws Exception {
        return new ArrayList<>(Files.readAllLines(file).stream().skip(1).map(line -> line.split("\t", -1)).toList());
    }

    /**
     * Writes a complete new capture without replacing originals.
     * @param file fresh derived capture
     * @param header historical schema
     * @param rows every retained/refined numeric row
     * @throws Exception if fresh output fails
     */
    private static void write(final Path file, final String header, final List<String[]> rows) throws Exception {
        Files.writeString(file,
                header + "\n"
                        + rows.stream()
                                .map(row -> String.join("\t", row))
                                .collect(java.util.stream.Collectors.joining("\n", "", rows.isEmpty() ? "" : "\n")),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Compares capture table identities.
     * @param fields numeric capture row
     * @param crop expected crop ordinal
     * @param table expected table ordinal
     * @return whether the row belongs to this table
     */
    private static boolean same(final String[] fields, final int crop, final int table) {
        return integer(fields, 0) == crop && integer(fields, 1) == table;
    }

    /**
     * Reads an exact numeric ordinal.
     * @param fields capture row
     * @param index ordinal field
     * @return parsed integer
     */
    private static int integer(final String[] fields, final int index) {
        return Integer.parseInt(fields[index]);
    }

    /**
     * Fingerprints complete source/protocol bytes.
     * @param path exact local file
     * @return lowercase SHA256
     * @throws Exception if file access or digest creation fails
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects changed source/protocols without recording source text.
     * @param condition required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
