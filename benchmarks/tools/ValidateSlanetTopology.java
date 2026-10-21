package dev.docweave.partition;

import org.w3c.dom.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Strict model-token topology and unchanged native-provider geometry validation; never repairs a prediction. */
public final class ValidateSlanetTopology {
    /** Prevents standalone validator construction. */
    private ValidateSlanetTopology() {
    }

    /**
     * Retains every raw page/profile/crop, including unsupported topology and invalid source geometry.
     * @param args raw captures, raster root, fresh report; optionally prior replay manifest and fresh export root
     * @throws Exception if capture populations, source pins or fresh output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        require((args.length == 3 || args.length == 5) && !Files.exists(Path.of(args[2])),
                "Expected captures, raster root, fresh output and optional replay/export");
        final Path export = args.length == 5 ? Files.createDirectory(Path.of(args[4])) : null;
        controls();
        final var mapper = JsonMapper.builder().build();
        final var rawPath = Path.of(args[0]);
        require(Files.size(rawPath) <= 16 * 1024 * 1024, "Capture exceeds bounded JSON size");
        final var raw = mapper.readTree(Files.readAllBytes(rawPath));
        require(raw.get("results").size() == 22, "Complete22page/profile population required");
        final var reports = new ArrayList<Map<String, Object>>();
        int ordinal = 0;
        for (final var selected : raw.get("results")) {
            final int page = selected.get("page").asInt();
            final String profile = selected.get("profile").asString();
            require(page == ordinal / 2 + 1 && profile.equals(ordinal % 2 == 0 ? "tatr" : "yolox"),
                    "Missing/reordered/duplicate page/profile");
            ordinal++;
            final String id = selected.get("id").asString();
            require(id.matches("original-page-[0-9]{4}"), "Invalid source identity");
            final var imagePath = Path.of(args[1], id, "render.png");
            require(hash(imagePath).equals(selected.get("raster_sha256").asString()), "Original raster changed");
            final var image = ImageIO.read(imagePath.toFile());
            require(image != null && (long) image.getWidth() * image.getHeight() <= 16_000_000,
                    "Unsupported source frame");
            try {
                final var captures = new ArrayList<Map<String, Object>>();
                final var accepted = new LinkedHashMap<Integer, PdfTableLayoutProvider.Table>();
                int expectedCrop = 0;
                for (final var candidate : selected.get("captures")) {
                    require(candidate.get("crop").asInt() == expectedCrop++, "Missing/duplicate crop");
                    final var report = new LinkedHashMap<String, Object>();
                    report.put("crop", candidate.get("crop").asInt());
                    report.put("raw_outcome", candidate.get("outcome").asString());
                    if (!candidate.get("outcome").asString().equals("OK")) {
                        report.put("validation", "RAW_FAILURE");
                    } else {
                        try {
                            final var tokens = new ArrayList<String>();
                            candidate.get("tokens").forEach(token -> tokens.add(token.asString()));
                            final var structure = structure(tokens);
                            report.put("rows", structure.rows());
                            report.put("columns", structure.columns());
                            report.put("anchors", structure.slots().size());
                            report.put("topology", "COMPLETE");
                            final var table = table(structure, candidate);
                            PdfTableLayouts.validate(List.of(table), image, TableLimits.defaults(),
                                    new PdfOcrOptions(150, 16_000_000, 100, 1_000_000,
                                            PdfOcrOptions.Selection.TEXTLESS_PAGES, 128,
                                            PdfOcrOptions.TableDetection.PROVIDER_LAYOUT, 1024),
                                    new PdfTableWork());
                            report.put("validation", "PROVIDER_VALID");
                            accepted.put(candidate.get("crop").asInt(), table);
                        } catch (final Exception failure) {
                            report.put("validation", "REJECTED");
                            report.put("failure_class", failure.getClass().getName());
                            if (failure instanceof final PartitionException typed) {
                                report.put("code", typed.code().name());
                            } else if (failure instanceof IllegalStateException) {
                                report.put("code", failure.getMessage());
                            } else {
                                report.put("code", "INVALID_SYNTAX_OR_RECTANGLE");
                            }
                        }
                    }
                    captures.add(report);
                }
                if (export != null) {
                    export(export.resolve(id).resolve(profile), accepted);
                }
                reports.add(Map.of("page", page, "profile", profile, "captures", captures));
            } finally {
                image.flush();
            }
        }
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("capture_sha256", hash(rawPath),
                        "java_runtime", System.getProperty("java.runtime.version"), "scope",
                        "Unrepaired model logical slots and enclosing quadrilateral bounds through existing provider validation; no native-text score",
                        "results", reports))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
        if (export != null) {
            replay(mapper, Path.of(args[3]), rawPath, Path.of(args[2]), raw, export);
        }
    }

    /**
     * Exports only independently provider-compatible candidates, retaining unsupported predictions in the report.
     * All original reference populations remain for the separate full-document native replay.
     * @param directory fresh page/profile capture directory
     * @param accepted unmodified model candidates passing the existing provider contract
     * @throws Exception if writing the numeric capture fails
     */
    private static void export(final Path directory, final Map<Integer, PdfTableLayoutProvider.Table> accepted)
            throws Exception {
        Files.createDirectories(directory);
        final var tables = new ArrayList<String>();
        final var anchors = new ArrayList<String>();
        tables.add("crop\ttable\trows\tcolumns\tleft\ttop\tright\tbottom");
        anchors.add("crop\ttable\trow\tcolumn\trow_span\tcolumn_span\tleft\ttop\tright\tbottom");
        for (final var entry : accepted.entrySet()) {
            final var table = entry.getValue();
            tables.add(entry.getKey() + "\t0\t" + table.rows() + "\t" + table.columns() + "\t" + box(table.bounds()));
            for (final var anchor : table.anchors()) {
                anchors.add(entry.getKey() + "\t0\t" + anchor.row() + "\t" + anchor.column() + "\t" + anchor.rowSpan()
                            + "\t" + anchor.columnSpan() + "\t" + box(anchor.bounds()));
            }
        }
        Files.write(directory.resolve("tables.tsv"), tables, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.write(directory.resolve("anchors.tsv"), anchors, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve("outcome.txt"), "OK\n", StandardOpenOption.CREATE_NEW);
    }

    /**
     * Serializes original bounds without coordinate repair or reduced decimal precision.
     * @param bounds unchanged model envelope
     * @return four tab-separated source-pixel coordinates
     */
    private static String box(final PdfTableLayoutProvider.Box bounds) {
        return bounds.left() + "\t" + bounds.top() + "\t" + bounds.right() + "\t" + bounds.bottom();
    }

    /**
     * Pins exported candidate subsets to the unchanged full-source scoring protocol; no reference row is removed.
     * @param mapper JSON codec
     * @param priorPath original frozen replay protocol
     * @param rawPath complete raw-model capture
     * @param validationPath complete validation/failure report
     * @param raw parsed raw report
     * @param root fresh exported numeric capture root
     * @throws Exception if provenance, population or fresh output checks fail
     */
    private static void replay(final tools.jackson.databind.json.JsonMapper mapper, final Path priorPath,
            final Path rawPath, final Path validationPath, final JsonNode raw, final Path root) throws Exception {
        final var prior = mapper.readTree(Files.readAllBytes(priorPath));
        require(prior.get("pages").size() == 11, "Complete prior source protocol required");
        final var pages = new ArrayList<Map<String, Object>>();
        for (final var source : prior.get("pages")) {
            final var page = new LinkedHashMap<String, Object>();
            final String id = source.get("capture_id").asString();
            page.put("source_page", source.get("source_page").asInt());
            page.put("capture_id", id);
            page.put("render_png_sha256", source.get("render_png_sha256").asString());
            for (final String profile : List.of("tatr", "yolox")) {
                final var hashes = new LinkedHashMap<String, String>();
                for (final String file : List.of("tables.tsv", "anchors.tsv", "outcome.txt")) {
                    hashes.put(file, hash(root.resolve(id).resolve(profile).resolve(file)));
                }
                page.put(profile, hashes);
            }
            pages.add(page);
        }
        final var replay = new LinkedHashMap<String, Object>();
        for (final String field : List.of("reference_manifest", "reference_manifest_sha256", "validation_source_sha256",
                     "detector_sha256", "profiles")) {
            replay.put(field, prior.get(field));
        }
        replay.put("structure_sha256", raw.get("model_sha256").asString());
        replay.put("raw_capture_sha256", hash(rawPath));
        replay.put("validation_report_sha256", hash(validationPath));
        replay.put("prior_replay_sha256", hash(priorPath));
        replay.put("pages", pages);
        replay.put("scope",
                "All11pages/bothdetectors, only strict provider-compatible raw candidates; every rejected candidate retained separately and all110reference rows remain. No repaired spans/geometry or source winner.");
        Files.writeString(root.resolve("replay-manifest.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(replay) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Parses exact predicted token grammar and fills only explicitly predicted rowspan/colspan ownership.
     * @param tokens complete model sequence before the end sentinel
     * @return immutable complete logical layout
     * @throws Exception if token grammar, dimensions, spans or coverage are unsupported
     */
    private static Structure structure(final List<String> tokens) throws Exception {
        require(tokens.size() <= 2048, "TOKEN_LIMIT");
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        final var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                ("<table>" + String.join("", tokens) + "</table>").getBytes(StandardCharsets.UTF_8)));
        final var rows = new ArrayList<Element>();
        for (final var child : children(document.getDocumentElement())) {
            if (child.getTagName().equals("tr")) {
                rows.add(child);
            } else {
                require(child.getTagName().equals("thead") || child.getTagName().equals("tbody"), "UNKNOWN_ROW_GROUP");
                for (final var row : children(child)) {
                    require(row.getTagName().equals("tr"), "UNKNOWN_ROW_TAG");
                    rows.add(row);
                }
            }
        }
        require(!rows.isEmpty() && rows.size() <= 256, "ROW_LIMIT");
        final var occupied = new boolean[rows.size()][64];
        final var slots = new ArrayList<Slot>();
        int columns = 0;
        for (int row = 0; row < rows.size(); row++) {
            int column = 0;
            for (final var cell : children(rows.get(row))) {
                require(cell.getTagName().equals("td") && children(cell).isEmpty(), "UNKNOWN_CELL_TAG");
                while (column < 64 && occupied[row][column]) {
                    column++;
                }
                final int rowSpan = span(cell, "rowspan"), columnSpan = span(cell, "colspan");
                require(row + rowSpan <= rows.size() && column + columnSpan <= 64, "SPAN_OUTSIDE_LAYOUT");
                for (int r = row; r < row + rowSpan; r++) {
                    for (int c = column; c < column + columnSpan; c++) {
                        require(!occupied[r][c], "OVERLAPPING_LOGICAL_SPANS");
                        occupied[r][c] = true;
                    }
                }
                slots.add(new Slot(row, column, rowSpan, columnSpan));
                require(slots.size() <= 1024, "ANCHOR_LIMIT");
                column += columnSpan;
                columns = Math.max(columns, column);
            }
        }
        require(columns > 0 && (long) columns * rows.size() <= 1024, "SLOT_LIMIT");
        for (final var row : occupied) {
            for (int column = 0; column < columns; column++) {
                require(row[column], "MISSING_LOGICAL_SLOT");
            }
        }
        return new Structure(rows.size(), columns, List.copyOf(slots));
    }

    /**
     * Copies each model quadrilateral into its enclosing rectangle without clipping, moving or fixing overlaps.
     * @param structure complete model logical ownership
     * @param capture raw model coordinates and exact detector crop offset
     * @return raw provider claim with unspecified header roles
     */
    private static PdfTableLayoutProvider.Table table(final Structure structure, final JsonNode capture) {
        final var boxes = capture.get("cells_local");
        require(boxes.size() == structure.slots().size(), "CELL_BOX_COUNT_MISMATCH");
        final var crop = capture.get("bounds");
        require(crop.size() == 4, "INVALID_CROP");
        final double left = crop.get(0).asDouble(), top = crop.get(1).asDouble();
        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (int index = 0; index < structure.slots().size(); index++) {
            final var coordinates = boxes.get(index);
            require(coordinates.size() == 8, "INVALID_QUADRILATERAL");
            double x1 = Double.POSITIVE_INFINITY, y1 = Double.POSITIVE_INFINITY;
            double x2 = Double.NEGATIVE_INFINITY, y2 = Double.NEGATIVE_INFINITY;
            for (int point = 0; point < 4; point++) {
                final double x = coordinates.get(point * 2).asDouble(), y = coordinates.get(point * 2 + 1).asDouble();
                require(Double.isFinite(x) && Double.isFinite(y), "NONFINITE_COORDINATE");
                x1 = Math.min(x1, x);
                y1 = Math.min(y1, y);
                x2 = Math.max(x2, x);
                y2 = Math.max(y2, y);
            }
            final var slot = structure.slots().get(index);
            anchors.add(new PdfTableLayoutProvider.Anchor(slot.row(), slot.column(), slot.rowSpan(), slot.columnSpan(),
                    new PdfTableLayoutProvider.Box(left + x1, top + y1, left + x2, top + y2)));
        }
        return new PdfTableLayoutProvider.Table(structure.rows(), structure.columns(),
                new PdfTableLayoutProvider.Box(left, top, crop.get(2).asDouble(), crop.get(3).asDouble()), anchors,
                "slanet_plus_raw_quadrilateral_envelopes_v1");
    }

    /**
     * Returns only explicit child elements; model text content is never interpreted as a cell label.
     * @param parent fixed grammar element
     * @return child elements in exact model order
     */
    private static List<Element> children(final Element parent) {
        final var result = new ArrayList<Element>();
        for (int index = 0; index < parent.getChildNodes().getLength(); index++) {
            final var child = parent.getChildNodes().item(index);
            if (child instanceof final Element element) {
                result.add(element);
            } else {
                require(child.getTextContent().isBlank(), "UNEXPECTED_MODEL_TEXT");
            }
        }
        return List.copyOf(result);
    }

    /**
     * Reads only bounded explicit spans; missing attributes mean one slot, as in the model grammar.
     * @param cell model cell element
     * @param name rowspan or colspan
     * @return positive bounded span
     */
    private static int span(final Element cell, final String name) {
        final int span = cell.hasAttribute(name) ? Integer.parseInt(cell.getAttribute(name)) : 1;
        require(span > 0 && span <= 20, "SPAN_LIMIT");
        return span;
    }

    /**
     * Checks row-spanning positives and independent holes/crossing negatives before original validation.
     * @throws Exception if owned parser controls fail
     */
    private static void controls() throws Exception {
        final var positive = structure(List.of("<tr><td rowspan=\"2\"></td><td></td></tr><tr><td></td></tr>"));
        require(positive.rows() == 2 && positive.columns() == 2 && positive.slots().size() == 3, "Owned span lost");
        for (final String negative : List.of("<tr><td></td><td></td></tr><tr><td></td></tr>",
                     "<tr><td rowspan=\"2\"></td></tr>", "<tbody><td></td></tbody>")) {
            boolean rejected = false;
            try {
                structure(List.of(negative));
            } catch (final IllegalStateException expected) {
                rejected = true;
            }
            require(rejected, "Malformed model ownership was repaired");
        }
    }

    /**
     * Hashes complete original bytes.
     * @param path pinned local input
     * @return lowercase SHA256
     * @throws Exception if reading or hashing fails
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects unsupported predictions rather than repairing topology or dropping failed cases.
     * @param condition required protocol invariant
     * @param message stable diagnostic code
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * One directly predicted logical cell.
     * @param row original zero-based anchor row
     * @param column original zero-based anchor column
     * @param rowSpan original positive vertical span
     * @param columnSpan original positive horizontal span
     */
    private record Slot(int row, int column, int rowSpan, int columnSpan) {}

    /**
     * Complete predicted logical slots, separate from potentially incompatible physical geometry.
     * @param rows model row count
     * @param columns model atomic column count
     * @param slots original row-major anchor ownership
     */
    private record Structure(int rows, int columns, List<Slot> slots) {}
}
