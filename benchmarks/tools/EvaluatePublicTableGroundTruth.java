package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.layout.OnnxTableLayout;
import io.docweave.layout.OnnxTableOptions;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Scores existing DocWeave table profiles against Unstructured's public cell-level table ground truth, under the
 * frozen protocol in {@code benchmarks/review/pdf-public-table-ground-truth/PROTOCOL.md}. Labels are read only for
 * scoring after extraction; no label enters extraction. The report holds counts and the publisher's own labelled
 * strings of the misses and additions (public, Apache-2.0 data).
 */
public final class EvaluatePublicTableGroundTruth {
    /** JSON codec. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Prevents construction. */
    private EvaluatePublicTableGroundTruth() {
    }

    /**
     * Runs every frozen profile on every frozen case.
     * @param args manifest, data root ({@code simple-data}), model directory with the three pinned files, NEW report
     *        path, and optionally a comma-separated profile subset, or {@code --upstream <case>=<capture.json>} to
     *        score a pinned Unstructured capture ({@code capture_unstructured_tables.py}) with the same metrics
     * @throws Exception if identities, inputs or the report path are invalid
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 4 || args.length == 5 || args.length == 6 && args[4].equals("--upstream"),
                "Expected manifest, data root, model directory, NEW report path and an optional profile list");
        if (args.length == 6) {
            upstream(args);
            return;
        }
        final var manifest = JSON.readTree(Files.readAllBytes(Path.of(args[0])));
        final Path data = Path.of(args[1]);
        final Path models = Path.of(args[2]);
        for (final var model : manifest.get("models")) {
            require(hash(Files.readAllBytes(models.resolve(model.get("file").asString())))
                            .equals(model.get("sha256").asString()), "Model identity changed");
        }
        final var profiles = profiles(manifest, models);
        final List<String> selected = args.length == 5 ? List.of(args[4].split(",")) : List.copyOf(profiles.keySet());
        final var report = new LinkedHashMap<String, Object>();
        report.put("protocol", "pdf-public-table-ground-truth/PROTOCOL.md");
        report.put("manifest_sha256", hash(Files.readAllBytes(Path.of(args[0]))));
        report.put("java", System.getProperty("java.runtime.version"));
        final var results = new ArrayList<Map<String, Object>>();
        for (final String name : selected) {
            final var factory = profiles.get(name);
            require(factory != null, "Unknown profile " + name);
            for (final var testCase : manifest.get("cases")) {
                results.add(evaluate(name, factory, data, testCase));
            }
        }
        report.put("results", results);
        Files.write(Path.of(args[3]), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
                StandardOpenOption.CREATE_NEW);
        for (final var result : results) {
            System.out.println(result.get("profile") + "\t" + result.get("case") + "\t" + result.get("summary"));
        }
    }

    /**
     * Scores one pinned upstream capture with the same metrics.
     * @param args arguments; the last is {@code <case>=<capture.json>}
     * @throws Exception on identity or I/O failure
     */
    private static void upstream(final String[] args) throws Exception {
        final var manifest = JSON.readTree(Files.readAllBytes(Path.of(args[0])));
        final String[] target = args[5].split("=", 2);
        JsonNode selected = null;
        for (final var testCase : manifest.get("cases")) {
            if (testCase.get("id").asString().equals(target[0])) {
                selected = testCase;
            }
        }
        require(selected != null, "Unknown case");
        final var capture = JSON.readTree(Files.readAllBytes(Path.of(target[1])));
        require("SUCCESS".equals(capture.get("outcome").asString()), "Upstream capture did not succeed");
        require(hash(verified(Path.of(args[1]), selected.get("pdf"))).equals(capture.get("source_sha256").asString()),
                "Upstream capture is of another source");
        final var items = new ArrayList<Item>();
        for (final var element : capture.get("elements")) {
            final var html = element.path("metadata").path("text_as_html");
            items.add(new Item("Table".equals(element.get("type").asString()) && html.isString(),
                    element.path("text").asString(""), html.isString() ? html.asString() : null));
        }
        final var result = score("upstream-unstructured-0.27.28-hi_res", Path.of(args[1]), selected, items, "SUCCESS", 0);
        final var report = new LinkedHashMap<String, Object>();
        report.put("protocol", "pdf-public-table-ground-truth/PROTOCOL.md");
        report.put("upstream_capture_sha256", hash(Files.readAllBytes(Path.of(target[1]))));
        report.put("results", List.of(result));
        Files.write(Path.of(args[3]), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
                StandardOpenOption.CREATE_NEW);
        System.out.println(result.get("profile") + "\t" + result.get("case") + "\t" + result.get("summary"));
    }

    /**
     * One output element as the scorer sees it.
     * @param table whether it is a table with HTML
     * @param text plain text
     * @param html table HTML, or null
     */
    private record Item(boolean table, String text, String html) {}

    /**
     * Declares the frozen profiles.
     * @param manifest manifest with model pins
     * @param models model directory
     * @return profile name to partitioner factory, in protocol order
     */
    private static Map<String, Supplier<PdfPartitioner>> profiles(final JsonNode manifest, final Path models) {
        final var profiles = new LinkedHashMap<String, Supplier<PdfPartitioner>>();
        for (final var mode : List.of(PdfOptions.TableDetection.RULED_ONLY,
                PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT,
                PdfOptions.TableDetection.RULED_AND_MULTILINE_ALIGNED_TEXT,
                PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT,
                PdfOptions.TableDetection.RULED_AND_FINANCIAL_ALIGNED_TEXT,
                PdfOptions.TableDetection.RULED_AND_HORIZONTAL_TEXT)) {
            profiles.put("native-" + mode.name(), () -> PdfPartitioner.create(
                    PdfOptions.defaults().withTableCellCoordinates(true).withTableDetection(mode)));
        }
        final var pins = manifest.get("models");
        final var structure = OnnxTableOptions.defaults(models.resolve(pins.get("tatr_structure").get("file").asString()),
                pins.get("tatr_structure").get("sha256").asString());
        final var tatr = OnnxTableOptions.defaults(models.resolve(pins.get("tatr_detection").get("file").asString()),
                pins.get("tatr_detection").get("sha256").asString());
        final var yolox = OnnxTableOptions.yoloXDefaults(models.resolve(pins.get("yolox").get("file").asString()),
                pins.get("yolox").get("sha256").asString());
        for (final boolean established : new boolean[] {false, true}) {
            final var layout = new PdfNativeLayoutOptions(150, 16_000_000, 1024,
                    PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED)
                    .withLeadingRow(established ? PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW
                                                : PdfNativeLayoutOptions.LeadingRow.PROVIDER_ONLY)
                    .withTrailingRows(established ? PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS
                                                  : PdfNativeLayoutOptions.TrailingRows.PROVIDER_ONLY)
                    .withRowLines(established ? PdfNativeLayoutOptions.RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS
                                              : PdfNativeLayoutOptions.RowLines.PROVIDER_ROWS);
            final String suffix = established ? "established" : "provider";
            profiles.put("yolox-" + suffix, () -> PdfPartitioner.createWithNativeTablesFirst(native_(), layout,
                    open(() -> OnnxTableLayout.createWithYoloX(yolox, structure))));
            profiles.put("tatr-" + suffix, () -> PdfPartitioner.createWithNativeTablesFirst(native_(), layout,
                    open(() -> OnnxTableLayout.create(tatr, structure))));
        }
        return profiles;
    }

    /**
     * Returns the native options of the model profiles.
     * @return options
     */
    private static PdfOptions native_() {
        return PdfOptions.defaults().withTableCellCoordinates(true)
                         .withTableDetection(PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT);
    }

    /** Opens a layout provider (lives for the evaluation process). */
    private interface Opener {
        /**
         * Opens it.
         * @return provider
         * @throws Exception on failure
         */
        OnnxTableLayout open() throws Exception;
    }

    /**
     * Opens a provider, wrapping checked failures.
     * @param opener opener
     * @return provider
     */
    private static OnnxTableLayout open(final Opener opener) {
        try {
            return opener.open();
        } catch (final Exception failure) {
            throw new IllegalStateException("Layout model could not be opened", failure);
        }
    }

    /**
     * Extracts one case with one profile and scores it.
     * @param profile profile name
     * @param factory partitioner factory
     * @param data data root
     * @param testCase manifest case
     * @return result
     * @throws Exception on identity or I/O failure
     */
    private static Map<String, Object> evaluate(final String profile, final Supplier<PdfPartitioner> factory,
            final Path data, final JsonNode testCase) throws Exception {
        final byte[] pdf = verified(data, testCase.get("pdf"));
        final var result = new LinkedHashMap<String, Object>();
        List<Element> elements = List.of();
        final long started = System.nanoTime();
        try {
            elements = DocWeave.create(PartitionerRegistry.of(List.of(factory.get())))
                               .partition(DocumentSource.bytes(pdf, testCase.get("pdf").get("path").asString()))
                               .elements();
            result.put("outcome", "SUCCESS");
        } catch (final PartitionException typed) {
            result.put("outcome", typed.code().name());
        }
        final long millis = (System.nanoTime() - started) / 1_000_000;
        final var items = new ArrayList<Item>();
        for (final Element element : elements) {
            final boolean table =
                    element.type() == ElementType.Table && element.metadata().get("text_as_html") instanceof String;
            items.add(new Item(table, element.text(), table ? (String) element.metadata().get("text_as_html") : null));
        }
        return score(profile, data, testCase, items, (String) result.get("outcome"), millis);
    }

    /**
     * Scores output items of one case under the frozen metrics.
     * @param profile profile name
     * @param data data root
     * @param testCase manifest case
     * @param items output items in order
     * @param outcome extraction outcome
     * @param millis extraction time
     * @return result
     * @throws Exception on identity or I/O failure
     */
    private static Map<String, Object> score(final String profile, final Path data, final JsonNode testCase,
            final List<Item> items, final String outcome, final long millis) throws Exception {
        final var labels = JSON.readTree(verified(data, testCase.get("tables")));
        final String plain = normalize(new String(verified(data, testCase.get("text")), "UTF-8"));
        final var result = new LinkedHashMap<String, Object>();
        result.put("profile", profile);
        result.put("case", testCase.get("id").asString());
        result.put("outcome", outcome);
        result.put("millis", millis);
        // Predicted tables (grids of normalized nonblank cells, row by row) and the page's other text.
        final var predicted = new ArrayList<List<List<String>>>();
        final var others = new ArrayList<String>();
        for (final Item item : items) {
            if (item.table()) {
                predicted.add(grid(item.html()));
            } else {
                others.add(normalize(item.text()));
            }
        }
        // Labelled tables as rows of normalized nonblank cells.
        final var labelled = new ArrayList<List<List<String>>>();
        final var labelledCells = new ArrayList<String>();
        for (final var table : labels) {
            final var rows = new java.util.TreeMap<Integer, java.util.TreeMap<Integer, String>>();
            for (final var cell : table.get("text")) {
                final String text = normalize(cell.get("content").asString());
                if (!text.isEmpty()) {
                    rows.computeIfAbsent(cell.get("y").asInt(), y -> new java.util.TreeMap<>())
                        .put(cell.get("x").asInt(), text);
                    labelledCells.add(text);
                }
            }
            final var ordered = new ArrayList<List<String>>();
            rows.values().forEach(row -> ordered.add(List.copyOf(row.values())));
            labelled.add(ordered);
        }
        // Association by largest multiset overlap, each predicted table at most once.
        final int[] owner = new int[labelled.size()];
        java.util.Arrays.fill(owner, -1);
        final var taken = new boolean[predicted.size()];
        final var pairs = new ArrayList<int[]>();
        for (int l = 0; l < labelled.size(); l++) {
            for (int p = 0; p < predicted.size(); p++) {
                final int overlap = overlap(flatten(labelled.get(l)), flatten(predicted.get(p)));
                if (overlap > 0) {
                    pairs.add(new int[] {overlap, l, p});
                }
            }
        }
        pairs.sort((a, b) -> a[0] != b[0] ? Integer.compare(b[0], a[0]) : a[2] != b[2] ? Integer.compare(a[2], b[2])
                : Integer.compare(a[1], b[1]));
        for (final int[] pair : pairs) {
            if (owner[pair[1]] < 0 && !taken[pair[2]]) {
                owner[pair[1]] = pair[2];
                taken[pair[2]] = true;
            }
        }
        int labelledTotal = 0;
        int recalled = 0;
        int rowsTotal = 0;
        int rowsExact = 0;
        final var missed = new LinkedHashMap<String, List<String>>();
        for (final var kind : List.of("DETECTOR_MISS", "OUTSIDE_TABLE", "CELL_SEGMENTATION", "STRUCTURE_OTHER")) {
            missed.put(kind, new ArrayList<>());
        }
        final var added = new LinkedHashMap<String, List<String>>();
        added.put("PROSE_ADDED", new ArrayList<>());
        added.put("OTHER_ADDED", new ArrayList<>());
        final var tables = new ArrayList<Map<String, Object>>();
        for (int l = 0; l < labelled.size(); l++) {
            final var cells = flatten(labelled.get(l));
            labelledTotal += cells.size();
            rowsTotal += labelled.get(l).size();
            final var table = new LinkedHashMap<String, Object>();
            table.put("labelled_cells", cells.size());
            table.put("labelled_rows", labelled.get(l).size());
            if (owner[l] < 0) {
                missed.get("DETECTOR_MISS").addAll(cells);
                table.put("associated", false);
                table.put("recalled_cells", 0);
                table.put("exact_rows", 0);
                tables.add(table);
                continue;
            }
            final var mine = predicted.get(owner[l]);
            final var remaining = counts(flatten(mine));
            final var unmatched = new ArrayList<String>();
            int hit = 0;
            for (final String cell : cells) {
                if (remaining.merge(cell, -1, Integer::sum) >= 0) {
                    hit++;
                } else {
                    remaining.merge(cell, 1, Integer::sum);
                    unmatched.add(cell);
                }
            }
            recalled += hit;
            final var leftovers = new ArrayList<String>();
            remaining.forEach((text, count) -> {
                for (int i = 0; i < count; i++) {
                    leftovers.add(text);
                }
            });
            for (final String cell : unmatched) {
                if (others.stream().anyMatch(text -> text.contains(cell))) {
                    missed.get("OUTSIDE_TABLE").add(cell);
                } else if (leftovers.stream().anyMatch(text -> text.contains(cell) || cell.contains(text))) {
                    missed.get("CELL_SEGMENTATION").add(cell);
                } else {
                    missed.get("STRUCTURE_OTHER").add(cell);
                }
            }
            leftovers.forEach(text -> added.get(prose(text, plain, labelledCells) ? "PROSE_ADDED" : "OTHER_ADDED")
                                           .add(text));
            int exact = 0;
            for (final var row : labelled.get(l)) {
                if (mine.contains(row)) {
                    exact++;
                }
            }
            rowsExact += exact;
            table.put("associated", true);
            table.put("predicted_table", owner[l]);
            table.put("predicted_shape", mine.size() + " rows");
            table.put("recalled_cells", hit);
            table.put("exact_rows", exact);
            tables.add(table);
        }
        int falseTables = 0;
        for (int p = 0; p < predicted.size(); p++) {
            if (!taken[p]) {
                falseTables++;
                flatten(predicted.get(p)).forEach(text ->
                        added.get(prose(text, plain, labelledCells) ? "PROSE_ADDED" : "OTHER_ADDED").add(text));
            }
        }
        result.put("predicted_tables", predicted.size());
        result.put("unassociated_predicted_tables", falseTables);
        result.put("labelled_tables", labelled.size());
        result.put("labelled_cells", labelledTotal);
        result.put("recalled_cells", recalled);
        result.put("labelled_rows", rowsTotal);
        result.put("exact_rows", rowsExact);
        result.put("tables", tables);
        final var missedCounts = new LinkedHashMap<String, Integer>();
        missed.forEach((kind, list) -> missedCounts.put(kind, list.size()));
        final var addedCounts = new LinkedHashMap<String, Integer>();
        added.forEach((kind, list) -> addedCounts.put(kind, list.size()));
        result.put("missed_counts", missedCounts);
        result.put("added_counts", addedCounts);
        result.put("missed", missed);
        result.put("added", added);
        result.put("summary", "tables " + predicted.size() + "/" + labelled.size() + " cells " + recalled + "/"
                + labelledTotal + " rows " + rowsExact + "/" + rowsTotal + " missed " + missedCounts + " added "
                + addedCounts + " " + result.get("outcome"));
        return result;
    }

    /**
     * Returns whether an added string is labelled page prose rather than table content.
     * @param text normalized string
     * @param plain normalized labelled page text
     * @param labelledCells normalized labelled table cells
     * @return true for prose
     */
    private static boolean prose(final String text, final String plain, final List<String> labelledCells) {
        return plain.contains(text) && labelledCells.stream().noneMatch(cell -> cell.contains(text));
    }

    /**
     * Reads a table's HTML into rows of normalized nonblank cells; a spanning cell appears once, at its anchor.
     * @param html {@code text_as_html}
     * @return rows
     */
    private static List<List<String>> grid(final String html) {
        final var rows = new ArrayList<List<String>>();
        for (final var row : Jsoup.parseBodyFragment(html).select("tr")) {
            final var cells = new ArrayList<String>();
            for (final var cell : row.select("td, th")) {
                final String text = normalize(cell.text());
                if (!text.isEmpty()) {
                    cells.add(text);
                }
            }
            if (!cells.isEmpty()) {
                rows.add(List.copyOf(cells));
            }
        }
        return rows;
    }

    /**
     * Flattens rows.
     * @param rows rows
     * @return cells
     */
    private static List<String> flatten(final List<List<String>> rows) {
        return rows.stream().flatMap(List::stream).toList();
    }

    /**
     * Counts strings.
     * @param cells cells
     * @return multiset
     */
    private static Map<String, Integer> counts(final List<String> cells) {
        final var counts = new HashMap<String, Integer>();
        cells.forEach(cell -> counts.merge(cell, 1, Integer::sum));
        return counts;
    }

    /**
     * Returns the multiset intersection size.
     * @param a cells
     * @param b cells
     * @return shared count
     */
    private static int overlap(final List<String> a, final List<String> b) {
        final var counts = counts(b);
        int shared = 0;
        for (final String cell : a) {
            if (counts.merge(cell, -1, Integer::sum) >= 0) {
                shared++;
            }
        }
        return shared;
    }

    /**
     * Normalizes text for matching only.
     * @param text text
     * @return NFKC, collapsed whitespace, trimmed
     */
    private static String normalize(final String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).replaceAll("\\s+", " ").strip();
    }

    /**
     * Reads a manifest-pinned file.
     * @param data data root
     * @param identity manifest identity
     * @return bytes
     * @throws Exception if the identity differs
     */
    private static byte[] verified(final Path data, final JsonNode identity) throws Exception {
        final byte[] bytes = Files.readAllBytes(data.resolve(identity.get("path").asString()));
        require(bytes.length == identity.get("bytes").asLong()
                        && hash(bytes).equals(identity.get("sha256").asString()),
                "Input identity changed: " + identity.get("path").asString());
        return bytes;
    }

    /**
     * Computes a SHA-256 digest.
     * @param bytes bytes
     * @return lowercase hex
     * @throws Exception if the digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Fails on a violated requirement.
     * @param valid requirement
     * @param message message
     */
    private static void require(final boolean valid, final String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }
}
