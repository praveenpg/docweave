package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionOptions;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import io.docweave.partition.PdfTaggedTableGeometry;
import org.jsoup.Jsoup;
import tools.jackson.databind.json.JsonMapper;

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

/**
 * Text-free diagnostic of same-index publisher/PDF row differences, never an alternate accuracy score.
 * This post-hoc audit cannot establish which source representation is semantically correct.
 */
public final class AuditBusinessRowPresentation {
    /** Prevents launcher construction. */
    private AuditBusinessRowPresentation() {
    }

    /**
     * Runs the explicit source-cell-envelope policy on one complete pinned original document.
     * @param args original business manifest, verified cache, fresh numeric report; or --self-test
     * @throws Exception if source identities, extraction, protocol or output checks fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            require(category("54 %", "54%").equals("ascii_space_only"));
            require(category("A —", "A -").equals("em_dash_hyphen_only"));
            require(category("(3 ) —", "(3) -").equals("ascii_space_and_em_dash_hyphen"));
            for (final var pair : List.of(List.of("3", "4"), List.of("A", "a"), List.of("01", "1"), List.of("−3", "-3"),
                         List.of("a\u2003b", "a b"), List.of("ab", "ba"))) {
                require(category(pair.getFirst(), pair.getLast()).equals("other"));
            }
            return;
        }
        require(args.length == 3 && !Files.exists(Path.of(args[2])));
        final var mapper = JsonMapper.builder().build();
        final var manifestBytes = Files.readAllBytes(Path.of(args[0]));
        final var manifest = mapper.readTree(manifestBytes);
        final var cache = Path.of(args[1]);
        for (final var input : manifest.get("inputs")) {
            final var bytes = Files.readAllBytes(cache.resolve(input.get("filename").asString()));
            require(bytes.length == input.get("bytes").asLong());
            require(digest(bytes).equals(input.get("sha256").asString()));
        }
        final var original = Files.readAllBytes(cache.resolve(manifest.get("source_pdf").asString()));
        final var html = Jsoup.parse(Files.readString(cache.resolve(manifest.get("source_html").asString())));
        final var partitioner =
                PdfPartitioner.createWithTaggedTables(PdfOptions.defaults().withTableCellCoordinates(false))
                        .withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_CELL_TEXT_ENVELOPES);
        final var limits =
                manifest.has("max_input_bytes")
                        ? PartitionOptions.builder().maxInputBytes(manifest.get("max_input_bytes").asInt()).build()
                        : PartitionOptions.defaults();
        List<Element> elements = List.of();
        String outcome = "SUCCESS";
        try {
            elements = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                               .partition(DocumentSource.bytes(original, manifest.get("source_pdf").asString()), limits)
                               .elements();
        } catch (final PartitionException failure) {
            outcome = failure.code().name();
        }
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            require(!selected.has("regions"));
            final var expected = rows(html.select("table").get(selected.get("html_table_index").asInt()));
            require(expected.size() == selected.get("reference_rows").asInt());
            require(digest(String.join("\n", expected).getBytes(StandardCharsets.UTF_8))
                            .equals(selected.get("reference_rows_sha256").asString()));
            final int page = selected.get("page").asInt();
            final double[] roi = new double[4];
            for (int i = 0; i < 4; i++) {
                roi[i] = selected.get("roi_left_top_right_bottom").get(i).asDouble();
            }
            Element chosen = null;
            double best = 0;
            for (final var element : elements) {
                if (element.type() == ElementType.Table
                        && ((Number) element.metadata().get("page_number")).intValue() == page) {
                    final double overlap = iou(roi, (Map<?, ?>) element.metadata().get("coordinates"));
                    if (overlap >= .5 && overlap > best) {
                        chosen = element;
                        best = overlap;
                    }
                }
            }
            final var actual =
                    chosen == null
                            ? List.<String>of()
                            : rows(Jsoup.parse(chosen.metadata().get("text_as_html").toString()).selectFirst("table"));
            final var counts = new LinkedHashMap<String, Integer>();
            for (final String category : List.of("exact", "ascii_space_only", "em_dash_hyphen_only",
                         "ascii_space_and_em_dash_hyphen", "other")) {
                counts.put(category, 0);
            }
            final var differences = new ArrayList<Map<String, Object>>();
            for (int row = 0; row < Math.min(expected.size(), actual.size()); row++) {
                final String category = category(expected.get(row), actual.get(row));
                counts.merge(category, 1, Integer::sum);
                if (!category.equals("exact")) {
                    differences.add(Map.of("row", row, "diagnostic_category", category));
                }
            }
            final var result = new LinkedHashMap<String, Object>();
            result.put("case", selected.get("id").asString());
            result.put("source_page", page);
            result.put("outcome", outcome);
            result.put("associated", chosen != null);
            result.put("association_iou", best);
            result.put("reference_content_rows", expected.size());
            result.put("observed_content_rows", actual.size());
            result.put("missing_row_positions", Math.max(0, expected.size() - actual.size()));
            result.put("extra_row_positions", Math.max(0, actual.size() - expected.size()));
            result.put("same_index_diagnostic_counts", counts);
            result.put("nonexact_positions", differences);
            results.add(result);
        }
        Files.write(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("runtime",
                        Runtime.version().toString(), "manifest_sha256", digest(manifestBytes), "mode",
                        "SOURCE_TAGGED_CELL_ENVELOPE_TABLES", "scope",
                        "Post-hoc same-index difference classification; strict scores unchanged; no source truth or accuracy claim",
                        "results", results)),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Classifies only explicit character transforms after the unchanged strict comparison.
     * @param expected original normalized publisher row
     * @param actual original normalized emitted row at the same index
     * @return mutually exclusive diagnostic category; never used to credit a strict match
     */
    private static String category(final String expected, final String actual) {
        if (expected.equals(actual)) {
            return "exact";
        }
        if (expected.replace(" ", "").equals(actual.replace(" ", ""))) {
            return "ascii_space_only";
        }
        if (expected.replace('—', '-').equals(actual.replace('—', '-'))) {
            return "em_dash_hyphen_only";
        }
        return expected.replace(" ", "").replace('—', '-').equals(actual.replace(" ", "").replace('—', '-'))
                ? "ascii_space_and_em_dash_hyphen"
                : "other";
    }

    /**
     * Reads direct cell text, nonblank rows and the historical ASCII-whitespace/NBSP normalization only.
     * @param table original HTML or emitted table
     * @return immutable source-order rows without repairing spans or strings
     */
    private static List<String> rows(final org.jsoup.nodes.Element table) {
        final var result = new ArrayList<String>();
        for (final var row : table.select("tr")) {
            if (row.closest("table") == table) {
                final String value =
                        String.join(" ", row.children()
                                                 .stream()
                                                 .filter(cell
                                                         -> cell.tagName().equals("td") || cell.tagName().equals("th"))
                                                 .map(org.jsoup.nodes.Element::wholeText)
                                                 .toList())
                                .replace('\u00a0', ' ')
                                .replaceAll("[ \\t\\r\\n\\f\\u000B]+", " ")
                                .replaceAll("^ | $", "");
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Associates only by original displayed parent geometry with no string access.
     * @param roi independently frozen region
     * @param coordinates emitted table coordinates
     * @return ordinary intersection over union
     */
    private static double iou(final double[] roi, final Map<?, ?> coordinates) {
        require(coordinates.get("system").equals("PDFUserSpace"));
        final var points = (List<?>) coordinates.get("points");
        final var first = (List<?>) points.getFirst();
        final var last = (List<?>) points.get(2);
        final double left = ((Number) first.getFirst()).doubleValue();
        final double top = ((Number) first.get(1)).doubleValue();
        final double right = ((Number) last.getFirst()).doubleValue();
        final double bottom = ((Number) last.get(1)).doubleValue();
        final double intersection = Math.max(0, Math.min(roi[2], right) - Math.max(roi[0], left))
                                    * Math.max(0, Math.min(roi[3], bottom) - Math.max(roi[1], top));
        final double union = (roi[2] - roi[0]) * (roi[3] - roi[1]) + (right - left) * (bottom - top) - intersection;
        return union > 0 ? intersection / union : 0;
    }

    /**
     * Computes the exact byte identity of source inputs and reference row sequences.
     * @param bytes unchanged bytes
     * @return lowercase SHA256
     * @throws Exception if SHA256 is unavailable
     */
    private static String digest(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects invalid protocol input without silently repairing the diagnostic.
     * @param condition required invariant
     */
    private static void require(final boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid business row presentation protocol");
        }
    }
}
