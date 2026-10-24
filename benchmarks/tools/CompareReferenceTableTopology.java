package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import io.docweave.partition.PdfTaggedTableGeometry;
import org.apache.pdfbox.Loader;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Whole-source JVM topology/role diagnostic using frozen independent table references, without OCR or models. */
public final class CompareReferenceTableTopology {
    /**
     * An original logical anchor independent of text, geometry and header classification.
     * @param row source logical row
     * @param column source logical column
     * @param rows positive source row span
     * @param columns positive source column span
     */
    private record Anchor(int row, int column, int rows, int columns) {
        /** Rejects impossible logical positions before constructing reference or output comparisons. */
        Anchor {
            require(row >= 0 && column >= 0 && rows > 0 && columns > 0);
        }
    }

    /** Prevents utility construction. */
    private CompareReferenceTableTopology() {
    }

    /**
     * Extracts each complete original once per predeclared profile and retains every case/failure denominator.
     * @param args frozen manifest, verified source cache and fresh numeric report
     * @throws Exception if identity, source-frame or unexpected scoring requirements fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 3 && !Files.exists(Path.of(args[2])));
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        require(manifest.get("cases").size() > 0 && manifest.get("cases").size() <= 32
                && manifest.get("profiles").size() > 0 && manifest.get("profiles").size() <= 8);
        require(hash(Files.readAllBytes(Path.of(manifest.get("tool").asString())))
                        .equals(manifest.get("tool_sha256").asString()));
        require(hash(Files.readAllBytes(Path.of(manifest.get("parser").asString())))
                        .equals(manifest.get("parser_sha256").asString()));
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final String filename = selected.get("filename").asString();
            require(filename.matches("[A-Za-z0-9_.-]{1,100}\\.pdf"));
            final var sourcePath = Path.of(args[1]).resolve(filename);
            require(Files.size(sourcePath) <= 1_048_576);
            final var bytes = Files.readAllBytes(sourcePath);
            require(bytes.length == selected.get("bytes").asInt()
                    && hash(bytes).equals(selected.get("sha256").asString()));
            try (final var document = Loader.loadPDF(bytes)) {
                require(document.getNumberOfPages() == 1);
                final var page = document.getPage(0);
                final var box = page.getCropBox();
                require(page.getRotation() == 0 && page.getUserUnit() == 1 && box.getLowerLeftX() == 0
                        && box.getLowerLeftY() == 0 && box.getWidth() == 612 && box.getHeight() == 792);
            }
            final var expected = new LinkedHashMap<Anchor, Boolean>();
            final int referenceRows = selected.get("rows").asInt();
            final int referenceColumns = selected.get("columns").asInt();
            require(referenceRows > 0 && referenceRows <= 1024 && referenceColumns > 0 && referenceColumns <= 1024);
            final var occupied = new java.util.HashSet<Long>();
            for (final var cell : selected.get("anchors")) {
                final var anchor = anchor(cell);
                require((long) anchor.row() + anchor.rows() <= referenceRows
                        && (long) anchor.column() + anchor.columns() <= referenceColumns
                        && expected.put(anchor, cell.get("header").asBoolean()) == null);
                for (int row = anchor.row(); row < anchor.row() + anchor.rows(); row++) {
                    for (int column = anchor.column(); column < anchor.column() + anchor.columns(); column++) {
                        require(occupied.add(((long) row << 32) | column));
                    }
                }
            }
            require(expected.size() == selected.get("expected_anchors").asInt()
                    && occupied.size() == referenceRows * referenceColumns);
            for (final var profile : manifest.get("profiles")) {
                final String mode = profile.asString();
                final var options = PdfOptions.defaults();
                final PdfPartitioner service = switch (mode) {
                    case "NATIVE" -> PdfPartitioner.create(options);
                    case "SOURCE_TAGGED" -> PdfPartitioner.createWithTaggedTables(options);
                    case "SOURCE_LOGICAL" ->
                        PdfPartitioner.createWithTaggedTables(options).withTaggedTableGeometry(
                                PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE);
                    case "SOURCE_CELL_ENVELOPES" ->
                        PdfPartitioner.createWithTaggedTables(options).withTaggedTableGeometry(
                                PdfTaggedTableGeometry.LOGICAL_CELL_TEXT_ENVELOPES);
                    default -> throw new IllegalArgumentException("Unfrozen table profile");
                };
                String outcome = "SUCCESS";
                List<Element> elements = List.of();
                List<String> warnings = List.of();
                try {
                    final var extracted = DocWeave.create(PartitionerRegistry.of(List.of(service)))
                                                  .partition(DocumentSource.bytes(bytes, filename));
                    elements = extracted.elements();
                    warnings = extracted.warnings().stream().map(warning -> warning.code().name()).toList();
                } catch (final PartitionException failure) {
                    outcome = failure.code().name();
                }
                results.add(score(selected, expected, mode, outcome, elements, warnings));
            }
        }
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime",
                        Runtime.version().toString(), "manifest_sha256", hash(Files.readAllBytes(Path.of(args[0]))),
                        "scope", manifest.get("scope").asString(), "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Associates by geometry only, then compares complete positional/span sets and separate header roles.
     * @param selected unchanged independent case
     * @param expected frozen positional anchors with source roles
     * @param mode predeclared public factory profile
     * @param outcome complete-document outcome
     * @param elements complete result, empty on typed failure
     * @param warnings original warning codes
     * @return numeric observation retaining every reference anchor and invalid/missing table outcome
     */
    private static Map<String, Object> score(final JsonNode selected, final Map<Anchor, Boolean> expected,
            final String mode, final String outcome, final List<Element> elements, final List<String> warnings) {
        final var tables = elements.stream().filter(element -> element.type() == ElementType.Table).toList();
        final var roi = selected.get("roi_left_top_right_bottom");
        final double[] reference = {
                roi.get(0).asDouble(), roi.get(1).asDouble(), roi.get(2).asDouble(), roi.get(3).asDouble()};
        Element chosen = null;
        double best = 0;
        for (final var table : tables) {
            final double overlap = iou(reference, rectangle((Map<?, ?>) table.metadata().get("coordinates")));
            if (overlap >= .5 && overlap > best) {
                chosen = table;
                best = overlap;
            }
        }
        final var observed = new HashMap<Anchor, Boolean>();
        boolean valid = false;
        int rows = 0;
        int columns = 0;
        if (chosen != null) {
            try {
                final var structure = UpstreamTableCapture.structure(chosen.metadata().get("text_as_html").toString());
                final var geometry = (Map<?, ?>) structure.get("pdf_table_cells");
                rows = ((Number) geometry.get("row_count")).intValue();
                columns = ((Number) geometry.get("column_count")).intValue();
                final var anchors = (List<?>) geometry.get("cells");
                final var cells = Jsoup.parse(structure.get("text_as_html").toString()).select("tr > td, tr > th");
                require(cells.size() == anchors.size());
                for (int index = 0; index < anchors.size(); index++) {
                    final var anchor = (Map<?, ?>) anchors.get(index);
                    final var key = new Anchor(number(anchor, "row_index"), number(anchor, "column_index"),
                            number(anchor, "row_span"), number(anchor, "column_span"));
                    require(observed.put(key, cells.get(index).tagName().equals("th")) == null);
                }
                valid = true;
            } catch (final IllegalArgumentException invalid) {
                observed.clear();
                rows = columns = 0;
            }
        }
        int matched = 0;
        int roles = 0;
        int headerMatches = 0;
        for (final var entry : expected.entrySet()) {
            final var actual = observed.get(entry.getKey());
            if (actual != null) {
                matched++;
                if (actual.equals(entry.getValue())) {
                    roles++;
                    if (entry.getValue()) {
                        headerMatches++;
                    }
                }
            }
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("case", selected.get("id").asString());
        result.put("profile", mode);
        result.put("outcome", outcome);
        result.put("document_elements", elements.size());
        result.put("tables_on_original_page", tables.size());
        result.put("associated", chosen != null);
        result.put("association_iou", best);
        result.put("table_method",
                chosen == null ? "none" : chosen.metadata().getOrDefault("pdf_table_method", "unspecified"));
        result.put("selected_structure_valid", valid);
        result.put("expected_rows", selected.get("rows").asInt());
        result.put("expected_columns", selected.get("columns").asInt());
        result.put("observed_rows", rows);
        result.put("observed_columns", columns);
        result.put("expected_anchors", expected.size());
        result.put("observed_anchors", observed.size());
        result.put("matched_anchor_keys", matched);
        result.put("missing_reference_keys", expected.size() - matched);
        result.put("extra_prediction_keys", observed.size() - matched);
        result.put("expected_header_anchors", expected.values().stream().filter(Boolean::booleanValue).count());
        result.put("matched_header_anchors", headerMatches);
        result.put("exact_header_roles", roles);
        result.put("complete_topology", valid && rows == selected.get("rows").asInt()
                                                && columns == selected.get("columns").asInt()
                                                && expected.keySet().equals(observed.keySet()));
        result.put("warning_codes", warnings);
        return result;
    }

    /**
     * Reads a frozen reference anchor without inferring positions from text or model output.
     * @param cell independently declared anchor
     * @return positional span key
     */
    private static Anchor anchor(final JsonNode cell) {
        return new Anchor(cell.get("row").asInt(), cell.get("column").asInt(), cell.get("row_span").asInt(),
                cell.get("column_span").asInt());
    }

    /**
     * Reads a numeric canonical HTML anchor property from the existing validated parser.
     * @param cell original parsed anchor
     * @param key required geometry key
     * @return source integer
     */
    private static int number(final Map<?, ?> cell, final String key) {
        return ((Number) cell.get(key)).intValue();
    }

    /**
     * Converts original displayed polygon coordinates into their enclosing source rectangle.
     * @param coordinates public element coordinate map
     * @return positive finite rectangle
     */
    private static double[] rectangle(final Map<?, ?> coordinates) {
        final double[] bounds = {
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (final var raw : (List<?>) coordinates.get("points")) {
            final var point = (List<?>) raw;
            final double x = ((Number) point.get(0)).doubleValue();
            final double y = ((Number) point.get(1)).doubleValue();
            require(Double.isFinite(x) && Double.isFinite(y));
            bounds[0] = Math.min(bounds[0], x);
            bounds[1] = Math.min(bounds[1], y);
            bounds[2] = Math.max(bounds[2], x);
            bounds[3] = Math.max(bounds[3], y);
        }
        require(bounds[2] > bounds[0] && bounds[3] > bounds[1]);
        return bounds;
    }

    /**
     * Computes geometry-only overlap without using source text or header labels.
     * @param first independent source rectangle
     * @param second emitted source rectangle
     * @return intersection over union
     */
    private static double iou(final double[] first, final double[] second) {
        final double intersection = Math.max(0, Math.min(first[2], second[2]) - Math.max(first[0], second[0]))
                                    * Math.max(0, Math.min(first[3], second[3]) - Math.max(first[1], second[1]));
        return intersection
                / ((first[2] - first[0]) * (first[3] - first[1]) + (second[2] - second[0]) * (second[3] - second[1])
                        - intersection);
    }

    /**
     * Computes complete source/protocol identity without printing protected document text.
     * @param bytes complete original bytes
     * @return lowercase SHA-256 digest
     * @throws Exception if mandatory digest support is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects invalid controls without repairing or omitting observations.
     * @param valid required invariant
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Reference topology comparison requirement failed");
        }
    }
}
