package io.docweave.partition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Offline comparison of published text envelopes and predicted row bands; never repairs labels or predictions. */
public final class AuditPublishedRowBands {
    /** Prevents utility construction. */
    private AuditPublishedRowBands() {
    }

    /**
     * Finite positive vertical extent in displayed PDF coordinates.
     * @param top upper edge
     * @param bottom lower edge
     */
    private record Band(double top, double bottom) {
        /** Rejects unusable geometry rather than fabricating a row. */
        private Band {
            if (!Double.isFinite(top) || !Double.isFinite(bottom) || top >= bottom) {
                throw new IllegalArgumentException("Invalid vertical envelope");
            }
        }
    }

    /**
     * Audits original XML single-row text boxes against unchanged full-document capture geometry.
     * Multirow labels are counted but never split. No XML content strings are read into reports.
     * @param args original XML, source JSON, captured page JSON, original region id, NEW report; or --self-test
     * @throws Exception if geometry, secure XML, source-frame, budget or fresh-output checks fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            controls();
            return;
        }
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected XML, source JSON, capture JSON, region id, NEW report");
        }
        final var mapper = JsonMapper.builder().build();
        final byte[] xml = bounded(Path.of(args[0]));
        final byte[] frame = bounded(Path.of(args[1]));
        final byte[] captured = bounded(Path.of(args[2]));
        final var source = mapper.readTree(frame);
        final var capture = mapper.readTree(captured);
        if (!capture.get("outcome").asString().equals("SUCCESS") || capture.get("tables").size() != 1) {
            throw new IllegalArgumentException("Requires one successful captured table; no text-based selection");
        }
        final var table = capture.get("tables").get(0);
        if (table.get("page_number").asInt() != source.get("source_page").asInt()) {
            throw new IllegalArgumentException("Captured source pages differ");
        }
        final var coordinates = table.get("coordinates");
        final double height = source.get("page_height").asDouble();
        if (!coordinates.get("system").asString().equals("PDFUserSpace")
                || coordinates.get("layout_height").asDouble() != height
                || coordinates.get("layout_width").asDouble() != source.get("page_width").asDouble()) {
            throw new IllegalArgumentException("Captured source frames differ");
        }
        final var regions = parse(xml).getElementsByTagName("region");
        org.w3c.dom.Element selected = null;
        for (int index = 0; index < regions.getLength(); index++) {
            final var region = (org.w3c.dom.Element) regions.item(index);
            if (region.getAttribute("page").equals(Integer.toString(source.get("source_page").asInt()))
                    && region.getAttribute("id").equals(args[3])) {
                if (selected != null) {
                    throw new IllegalArgumentException("Duplicate original region");
                }
                selected = region;
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException("Missing original region");
        }
        final var work = new PdfTableWork();
        final var references = new TreeMap<Integer, List<Band>>();
        int multirow = 0;
        int origin = Integer.MAX_VALUE;
        final var cells = selected.getElementsByTagName("cell");
        for (int index = 0; index < cells.getLength(); index++) {
            work.step();
            final var cell = (org.w3c.dom.Element) cells.item(index);
            final int row = Integer.parseInt(cell.getAttribute("start-row"));
            origin = Math.min(origin, row);
            final int end = cell.hasAttribute("end-row") ? Integer.parseInt(cell.getAttribute("end-row")) : row;
            if (end < row || row < 0) {
                throw new IllegalArgumentException("Invalid original row span");
            }
            if (end != row) {
                multirow++;
                continue;
            }
            final var box = (org.w3c.dom.Element) cell.getElementsByTagName("bounding-box").item(0);
            final var band = new Band(height - Double.parseDouble(box.getAttribute("y2")),
                    height - Double.parseDouble(box.getAttribute("y1")));
            references.computeIfAbsent(row, ignored -> new ArrayList<>()).add(band);
        }
        final var bands = predicted(table, work);
        final var rows = new ArrayList<Map<String, Object>>();
        for (final var entry : references.entrySet()) {
            final var envelope = new Band(entry.getValue().stream().mapToDouble(Band::top).min().orElseThrow(),
                    entry.getValue().stream().mapToDouble(Band::bottom).max().orElseThrow());
            int crossing = 0;
            int unmatched = 0;
            final var centers = new TreeMap<Integer, Integer>();
            for (final var box : entry.getValue()) {
                final var overlaps = overlaps(box, bands, work);
                if (overlaps.size() > 1) {
                    crossing++;
                }
                final double center = (box.top() + box.bottom()) / 2;
                Integer owner = null;
                for (final var target : bands.entrySet()) {
                    work.step();
                    if (center >= target.getValue().top() && center < target.getValue().bottom()) {
                        if (owner != null) {
                            throw new IllegalArgumentException("Ambiguous row center");
                        }
                        owner = target.getKey();
                    }
                }
                if (owner == null) {
                    unmatched++;
                } else {
                    centers.merge(owner, 1, Integer::sum);
                }
            }
            rows.add(Map.of("reference_row", entry.getKey() - origin, "single_row_text_boxes", entry.getValue().size(),
                    "text_envelope", List.of(envelope.top(), envelope.bottom()), "overlapping_model_rows",
                    overlaps(envelope, bands, work), "boxes_crossing_model_boundaries", crossing,
                    "box_centers_per_model_row", centers, "unassigned_box_centers", unmatched));
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("source_page", source.get("source_page").asInt());
        result.put("pdf_sha256", source.get("pdf_sha256").asString());
        result.put("xml_sha256", hash(xml));
        result.put("capture_sha256", hash(captured));
        result.put("reference_anchors", cells.getLength());
        result.put("multirow_reference_anchors_not_split", multirow);
        result.put("model_rows", table.get("pdf_table_cells").get("row_count").asInt());
        result.put("recovered_model_rows", bands.size());
        result.put("reference_rows_with_single_row_text_boxes", rows);
        result.put("model_bands", bands.entrySet()
                                          .stream()
                                          .map(entry
                                                  -> Map.of("row", entry.getKey(), "top", entry.getValue().top(),
                                                          "bottom", entry.getValue().bottom()))
                                          .toList());
        Files.write(Path.of(args[4]), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Recovers only exact single-row prediction bands, preserving their original indices and missing rows.
     * @param table original captured table
     * @param work shared bounded counter
     * @return model row indices and finite extents
     * @throws Exception if columns disagree on row bounds or work is exhausted
     */
    private static Map<Integer, Band> predicted(final JsonNode table, final PdfTableWork work) throws Exception {
        final var bands = new TreeMap<Integer, Band>();
        for (final var cell : table.get("pdf_table_cells").get("cells")) {
            work.step();
            if (cell.get("row_span").asInt() == 1) {
                final var points = cell.get("coordinates").get("points");
                final var band = new Band(points.get(0).get(1).asDouble(), points.get(2).get(1).asDouble());
                final int row = cell.get("row_index").asInt();
                final var prior = bands.putIfAbsent(row, band);
                if (row < 0 || row >= table.get("pdf_table_cells").get("row_count").asInt()
                        || (prior != null && !prior.equals(band))) {
                    throw new IllegalArgumentException("Invalid or inconsistent model row geometry");
                }
            }
        }
        Band previous = null;
        for (final var band : bands.values()) {
            if (previous != null && band.top() < previous.bottom()) {
                throw new IllegalArgumentException("Overlapping model rows");
            }
            previous = band;
        }
        return bands;
    }

    /**
     * Returns strictly positive vertical intersections without assigning text or changing row indices.
     * @param source original text envelope, not a physical cell edge
     * @param targets predicted row bands
     * @param work shared bounded counter
     * @return unchanged indices intersecting the envelope; exact edge contact is excluded
     * @throws Exception if work or cancellation prevents comparison
     */
    private static List<Integer> overlaps(final Band source, final Map<Integer, Band> targets, final PdfTableWork work)
            throws Exception {
        final var result = new ArrayList<Integer>();
        for (final var entry : targets.entrySet()) {
            work.step();
            if (Math.max(source.top(), entry.getValue().top()) < Math.min(source.bottom(), entry.getValue().bottom())) {
                result.add(entry.getKey());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Reads one bounded local input without accepting unbounded source or capture files.
     * @param path explicit local file
     * @return original bytes
     * @throws Exception if the input is missing or exceeds16MiB
     */
    private static byte[] bounded(final Path path) throws Exception {
        if (Files.size(path) > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Diagnostic input exceeds16MiB");
        }
        return Files.readAllBytes(path);
    }

    /**
     * Parses source XML with no external entities, schemas or DTDs.
     * @param bytes unchanged local source bytes
     * @return secure parsed tree; content strings are not included in reports
     * @throws Exception if XML is forbidden or invalid
     */
    private static org.w3c.dom.Document parse(final byte[] bytes) throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    /**
     * Computes a byte identity without exporting source strings.
     * @param bytes unchanged input
     * @return lowercase SHA256
     * @throws Exception if the required digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Checks boundary, split, missing-band and forbidden-XML behavior without external inputs or engines.
     * @throws Exception if a diagnostic contract regresses
     */
    private static void controls() throws Exception {
        final var bands = new TreeMap<>(Map.of(0, new Band(0, 10), 2, new Band(10, 20)));
        if (!overlaps(new Band(5, 15), bands, new PdfTableWork()).equals(List.of(0, 2))
                || !overlaps(new Band(20, 30), bands, new PdfTableWork()).isEmpty()) {
            throw new IllegalStateException("Split/edge/missing row identities regressed");
        }
        try {
            parse("<!DOCTYPE a SYSTEM 'file:///unavailable'><a/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            throw new IllegalStateException("External XML declaration accepted");
        } catch (final org.xml.sax.SAXParseException expected) {
            // Required secure-parser rejection; no external resource is read.
        }
    }
}
