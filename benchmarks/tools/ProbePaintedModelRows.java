package dev.docweave.partition;
import org.apache.pdfbox.Loader;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Fixed source-painted-divider diagnosis of the complete frozen synchronized-row population; no refinement. */
public final class ProbePaintedModelRows {
    /** Prevents standalone diagnostic construction. */
    private ProbePaintedModelRows() {
    }

    /**
     * Requires a complete existing horizontal source segment inside every open native glyph gap.
     * The existing0.5point ruled tolerance applies to orientation and horizontal endpoint coverage.
     * @param cells complete candidate row anchors, including empty cells
     * @param glyphs all original native positions
     * @param segments supported original source paint
     * @param sx raster-to-source horizontal scale
     * @param sy raster-to-source vertical scale
     * @param cuts frozen original open-gap midpoints
     * @return whether every prospective division has the same explicit full-width source-paint evidence
     */
    private static boolean painted(final List<String[]> cells, final List<PdfPageText.Glyph> glyphs,
            final List<PdfPageGraphics.Segment> segments, final double sx, final double sy, final List<Double> cuts) {
        final double left = cells.stream().mapToDouble(c -> Double.parseDouble(c[6]) * sx).min().orElseThrow(),
                     right = cells.stream().mapToDouble(c -> Double.parseDouble(c[8]) * sx).max().orElseThrow();
        final var owned = glyphs.stream()
                                  .filter(g -> !g.text().isBlank() && cells.stream().anyMatch(c -> {
                                      final double x = g.x() + g.width() / 2, y = g.y() - g.height() / 2;
                                      return x >= Double.parseDouble(c[6]) * sx && x < Double.parseDouble(c[8]) * sx
                                              && y >= Double.parseDouble(c[7]) * sy
                                              && y < Double.parseDouble(c[9]) * sy;
                                  }))
                                  .toList();
        if (cuts.isEmpty())
            return false;
        for (final double cut : cuts) {
            final double previous = owned.stream()
                                            .filter(g -> g.y() - g.height() / 2 < cut)
                                            .mapToDouble(PdfPageText.Glyph::y)
                                            .max()
                                            .orElseThrow(),
                         next = owned.stream()
                                        .filter(g -> g.y() - g.height() / 2 >= cut)
                                        .mapToDouble(g -> g.y() - g.height())
                                        .min()
                                        .orElseThrow();
            if (segments.stream().noneMatch(s
                        -> Math.abs(s.y1() - s.y2()) < .5 && Math.min(s.x1(), s.x2()) <= left + .5
                                   && Math.max(s.x1(), s.x2()) >= right - .5 && Math.min(s.y1(), s.y2()) > previous
                                   && Math.max(s.y1(), s.y2()) < next))
                return false;
        }
        return true;
    }

    /**
     * Invokes the unchanged pinned historical native-gap experiment without copying its algorithm.
     * @param cells complete original row anchors
     * @param glyphs exact source positions
     * @param sx horizontal source scale
     * @param sy vertical source scale
     * @return historical separators, including an empty rejected result
     * @throws Exception if the pinned method contract or geometry fails
     */
    @SuppressWarnings("unchecked")
    private static List<Double> cuts(final List<String[]> cells, final List<PdfPageText.Glyph> glyphs, final double sx,
            final double sy) throws Exception {
        final var method = RefineSynchronizedModelRows.class.getDeclaredMethod(
                "separators", List.class, List.class, double.class, double.class, PdfTableWork.class);
        method.setAccessible(true);
        return (List<Double>) method.invoke(null, cells, glyphs, sx, sy, new PdfTableWork());
    }

    /**
     * Pins complete source, model-capture or protocol bytes without textual repair.
     * @param p exact existing file
     * @return lowercase SHA256
     * @throws Exception if bytes or standard digest cannot be read
     */
    private static String hash(final Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    /**
     * Stops altered or incomplete evidence instead of changing membership.
     * @param b required invariant
     * @param m bounded diagnostic description
     */
    private static void require(final boolean b, final String m) {
        if (!b)
            throw new IllegalStateException(m);
    }

    /**
     * Reads the complete pinned numeric TSV body, preserving empty fields and source order.
     * @param p exact previously hashed capture
     * @return all original rows
     * @throws Exception if capture reading fails
     */
    private static List<String[]> fields(final Path p) throws Exception {
        return Files.readAllLines(p).stream().skip(1).map(l -> l.split("\t", -1)).toList();
    }

    /**
     * Checks both owned ambiguity controls, every original page/profile and all292 original row records.
     * @param args source PDF, replay manifest, raster root, capture root, gate, predeclared selection and fresh report
     * @throws Exception if identities, controls, geometry or complete populations fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 7 && !Files.exists(Path.of(args[6])), "Expected fixed inputs and fresh report");
        final var mapper = JsonMapper.builder().build();
        final var selection = mapper.readTree(Files.readAllBytes(Path.of(args[5])));
        for (final var property : selection.get("identities").properties())
            require(hash(Path.of(property.getKey())).equals(property.getValue().asString()), "Pin changed");
        require(hash(Path.of(args[0])).equals(selection.get("source_sha256").asString()), "Source changed");
        final var factory = ProbeNativeRowAmbiguity.class.getDeclaredMethod("source", boolean.class);
        factory.setAccessible(true);
        final var controls = new ArrayList<Map<String, Object>>();
        final var cells = List.of(new String[] {"0", "0", "0", "0", "1", "1", "30", "100", "200", "160"},
                new String[] {"0", "0", "0", "1", "1", "1", "200", "100", "370", "160"});
        for (final boolean divider : List.of(false, true))
            try (final var doc = Loader.loadPDF((byte[]) factory.invoke(null, divider))) {
                final var text = new PdfPageText(PdfOptions.defaults());
                text.getText(doc);
                final var graphics = new PdfPageGraphics(doc.getPage(0), PdfOptions.defaults(),
                        new PdfImageBudget(PdfOptions.defaults()), new PdfPageGeometry(doc.getPage(0)));
                graphics.processPage(doc.getPage(0));
                final var cuts = cuts(cells, text.glyphs(), 1, 1);
                final boolean supported = painted(cells, text.glyphs(), graphics.segments(), 1, 1, cuts);
                require(supported == divider, "Authored row ambiguity unresolved");
                controls.add(Map.of("divider", divider, "source_gap_cuts", cuts.size(), "paint_supported", supported));
            }
        final var protocol = mapper.readTree(Files.readAllBytes(Path.of(args[1])));
        final var gate = mapper.readTree(Files.readAllBytes(Path.of(args[4])));
        require(gate.get("results").size() == 22, "Profile population changed");
        final var results = new ArrayList<Map<String, Object>>();
        int totalRows = 0, candidates = 0, accepted = 0;
        try (final var doc = Loader.loadPDF(Path.of(args[0]).toFile())) {
            require(doc.getNumberOfPages() == 11 && protocol.get("pages").size() == 11, "Page population changed");
            for (int index = 0; index < 11; index++) {
                final var page = doc.getPage(index);
                require(page.getRotation() == 0 && page.getUserUnit() == 1 && page.getCropBox().getLowerLeftX() == 0
                                && page.getCropBox().getLowerLeftY() == 0,
                        "Unsupported frame");
                final var pin = protocol.get("pages").get(index);
                final String id = pin.get("capture_id").asString();
                final var raster = Path.of(args[2], id, "render.png");
                require(hash(raster).equals(pin.get("render_png_sha256").asString()), "Raster changed");
                final var image = ImageIO.read(raster.toFile());
                final double sx = page.getCropBox().getWidth() / (double) image.getWidth(),
                             sy = page.getCropBox().getHeight() / (double) image.getHeight();
                image.flush();
                final var text = new PdfPageText(PdfOptions.defaults());
                text.setStartPage(index + 1);
                text.setEndPage(index + 1);
                text.getText(doc);
                final var graphics = new PdfPageGraphics(page, PdfOptions.defaults(),
                        new PdfImageBudget(PdfOptions.defaults()), new PdfPageGeometry(page));
                graphics.processPage(page);
                for (final String family : List.of("tatr", "yolox")) {
                    final var directory = Path.of(args[3], id, family);
                    for (final String file : List.of("tables.tsv", "anchors.tsv", "outcome.txt"))
                        require(hash(directory.resolve(file)).equals(pin.get(family).get(file).asString()),
                                "Capture changed");
                    final var anchors = fields(directory.resolve("anchors.tsv"));
                    final var selected = new ArrayList<tools.jackson.databind.JsonNode>();
                    for (final var g : gate.get("results"))
                        if (g.get("page").asInt() == index + 1 && g.get("family").asString().equals(family))
                            selected.add(g);
                    require(selected.size() == 1, "Ambiguous gate record");
                    final var rows = new ArrayList<Map<String, Object>>();
                    for (final var row : selected.getFirst().get("rows")) {
                        totalRows++;
                        if (!row.get("gate").asString().equals("SYNCHRONIZED_CANDIDATE")) {
                            rows.add(Map.of("gate", row.get("gate").asString(), "row", row.get("row").asInt(), "table",
                                    row.get("table").asInt(), "crop", row.get("crop").asInt()));
                            continue;
                        }
                        candidates++;
                        final var members =
                                anchors.stream()
                                        .filter(c
                                                -> Integer.parseInt(c[0]) == row.get("crop").asInt()
                                                           && Integer.parseInt(c[1]) == row.get("table").asInt()
                                                           && Integer.parseInt(c[2]) == row.get("row").asInt())
                                        .toList();
                        final var cuts = cuts(members, text.glyphs(), sx, sy);
                        final boolean supported = painted(members, text.glyphs(), graphics.segments(), sx, sy, cuts);
                        if (supported)
                            accepted++;
                        rows.add(Map.of("gate", row.get("gate").asString(), "row", row.get("row").asInt(), "table",
                                row.get("table").asInt(), "crop", row.get("crop").asInt(), "source_gap_cuts", cuts,
                                "paint_supported", supported));
                    }
                    results.add(Map.of("page", index + 1, "family", family, "source_segments",
                            graphics.segments().size(), "rows", rows));
                }
            }
        }
        require(totalRows == 292 && candidates == 5, "Frozen row population changed");
        Files.writeString(Path.of(args[6]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("results", results, "owned_controls",
                        controls, "total_rows", totalRows, "candidates", candidates, "paint_supported_candidates",
                        accepted, "selection_sha256", hash(Path.of(args[5])), "scope",
                        "Fixed full-width painted divider gate only; no topology modification or accuracy gain"))
                        + "\n",
                java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("Candidates=" + candidates + " paint-supported=" + accepted);
    }
}
