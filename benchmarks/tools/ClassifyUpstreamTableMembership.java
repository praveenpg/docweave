package dev.docweave.validation;

import org.apache.pdfbox.Loader;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Classifies whether frozen statement rows are members of actual pinned Unstructured {@code hi_res} Table elements.
 *
 * <p>The target file is frozen before the upstream run and stays private because it carries source text. This tool
 * reads only the raw capture, the original PDFs (for page sizes) and that target file, and writes a content-free
 * report holding row identifiers, membership classes and numeric geometry. Cell text is compared after collapsing
 * ASCII whitespace runs only; it is never repaired. Membership by text and by geometry are reported separately,
 * because neither alone establishes the other.
 */
public final class ClassifyUpstreamTableMembership {
    /** Prevents construction of this command-line tool. */
    private ClassifyUpstreamTableMembership() {
    }

    /**
     * Verifies every identity and writes one classification per frozen row, or fails without a report.
     *
     * @param args manifest, private target file, original source directory, raw capture directory and a fresh
     *         report path; or {@code --self-test}
     * @throws Exception if an identity, schema or classification control fails
     */
    public static void main(final String[] args) throws Exception {
        controls();
        if (args.length == 1 && args[0].equals("--self-test")) {
            System.out.println("Upstream table membership controls passed");
            return;
        }
        require(args.length == 5, "Expected manifest, targets, sources, captures and a fresh report");
        final var mapper = JsonMapper.builder().build();
        final var manifestPath = Path.of(args[0]);
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        final var targetsPath = Path.of(args[1]);
        require(hash(targetsPath).equals(manifest.get("membership_targets_sha256").asString()),
                "Frozen membership targets differ");
        final var targets = mapper.readTree(Files.readAllBytes(targetsPath));
        final var captures = Path.of(args[3]);
        final var runtime = mapper.readTree(Files.readAllBytes(captures.resolve("runtime.json")));
        require(runtime.get("manifest_sha256").asString().equals(hash(manifestPath)), "Executed manifest differs");
        require(runtime.get("packages")
                        .get("unstructured")
                        .asString()
                        .equals(manifest.get("unstructured_version").asString()),
                "Executed runtime differs");
        final var cases = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            final var source = Path.of(args[2]).resolve(selected.get("pdf").asString());
            require(Files.size(source) == selected.get("bytes").asLong()
                            && hash(source).equals(selected.get("sha256").asString()),
                    "Original source differs");
            final var capturePath = captures.resolve(id + ".json");
            final var capture = mapper.readTree(Files.readAllBytes(capturePath));
            require(capture.get("source_sha256").asString().equals(selected.get("sha256").asString()),
                    "Capture source differs");
            final var target = targets.get("cases").get(id);
            final int page = target.get("page").asInt();
            require(page == selected.get("declared_page").asInt(), "Declared page differs");
            final float pageHeight;
            try (final var document = Loader.loadPDF(source.toFile())) {
                pageHeight = document.getPage(page - 1).getMediaBox().getHeight();
            }
            final var tables = new ArrayList<CapturedTable>();
            final var others = new ArrayList<String>();
            if (capture.get("outcome").asString().equals("SUCCESS")) {
                for (final var element : capture.get("elements")) {
                    if (element.get("metadata").get("page_number").asInt() != page) {
                        continue;
                    }
                    if (element.get("type").asString().equals("Table")) {
                        tables.add(CapturedTable.of(element, pageHeight));
                    } else {
                        others.add(collapse(element.get("text").asString()));
                    }
                }
            }
            final var rows = new ArrayList<Map<String, Object>>();
            for (final var row : target.get("rows")) {
                final var label = collapse(row.get("label").asString());
                final var values = new ArrayList<String>();
                row.get("values").forEach(value -> values.add(value.asString()));
                final double baseline = row.get("y").asDouble();
                final var result = new LinkedHashMap<String, Object>();
                result.put("id", row.get("id").asString());
                result.put("role", row.get("role").asString());
                result.put("text_membership", classify(label, values, tables, others).name());
                result.put("evidence_items", 1 + values.size());
                result.put("evidence_items_in_separate_non_table_elements", scattered(label, values, others));
                result.put("geometric_table_cover",
                        tables.stream().anyMatch(table -> table.top() <= baseline && baseline <= table.bottom()));
                rows.add(result);
            }
            final var observed = new LinkedHashMap<String, Object>();
            observed.put("id", id);
            observed.put("outcome", capture.get("outcome").asString());
            observed.put("capture_sha256", hash(capturePath));
            observed.put("page", page);
            observed.put(
                    "page_tables", tables.stream()
                                           .map(table
                                                   -> Map.of("top_pt", round(table.top()), "bottom_pt",
                                                           round(table.bottom()), "html_rows", table.rows().size()))
                                           .toList());
            observed.put("rows", rows);
            cases.add(observed);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("manifest_sha256", hash(manifestPath));
        report.put("targets_sha256", hash(targetsPath));
        report.put("runtime_sha256", hash(captures.resolve("runtime.json")));
        report.put("java_version", System.getProperty("java.version"));
        report.put("cases", cases);
        Files.writeString(Path.of(args[4]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /** Text membership classes, from strongest structured evidence to none. */
    enum Membership {
        /** One table row holds the label in one cell and every value in its cells. */
        TABLE_ROW,
        /** One table row holds every value, but no cell of that row holds the label. */
        TABLE_ROW_VALUES_ONLY,
        /** The table text holds the evidence, but no single table row does. */
        TABLE_TEXT,
        /** A non-table element on the declared page holds the evidence. */
        OUTSIDE_TABLE,
        /** No element on the declared page holds the evidence. */
        ABSENT
    }

    /**
     * One captured Table element in top-down PDF points with its parsed HTML rows.
     *
     * @param top    top edge in points from the page top
     * @param bottom bottom edge in points from the page top
     * @param rows   collapsed cell text, one list per HTML row
     * @param text   collapsed element text
     */
    record CapturedTable(double top, double bottom, List<List<String>> rows, String text) {
        /**
         * Converts one public element, scaling its pixel-space box by the declared layout height.
         *
         * @param element    the raw upstream element
         * @param pageHeight the original page height in points
         * @return the captured table
         */
        static CapturedTable of(final JsonNode element, final float pageHeight) {
            final var coordinates = element.get("metadata").get("coordinates");
            final double scale = pageHeight / coordinates.get("layout_height").asDouble();
            double top = Double.MAX_VALUE;
            double bottom = -Double.MAX_VALUE;
            for (final var point : coordinates.get("points")) {
                top = Math.min(top, point.get(1).asDouble() * scale);
                bottom = Math.max(bottom, point.get(1).asDouble() * scale);
            }
            final var html = element.get("metadata").get("text_as_html");
            final var rows = new ArrayList<List<String>>();
            if (html != null && html.isString()) {
                for (final var row : Jsoup.parseBodyFragment(html.asString()).select("tr")) {
                    rows.add(row.select("td, th").stream().map(cell -> collapse(cell.text())).toList());
                }
            }
            return new CapturedTable(top, bottom, List.copyOf(rows), collapse(element.get("text").asString()));
        }
    }

    /**
     * Applies the frozen class order; label-only rows use the label as their only evidence.
     *
     * @param label  the collapsed row label
     * @param values the exact row values, possibly empty
     * @param tables Table elements on the declared page
     * @param others collapsed text of every other element on that page
     * @return the strongest matching class
     */
    static Membership classify(final String label, final List<String> values, final List<CapturedTable> tables,
            final List<String> others) {
        final var evidence = values.isEmpty() ? List.of(label) : values;
        boolean valuesOnly = false;
        for (final var table : tables) {
            for (final var row : table.rows()) {
                final boolean labelled = row.stream().anyMatch(cell -> cell.equals(label));
                final boolean valued =
                        values.stream().allMatch(value -> row.stream().anyMatch(cell -> holds(cell, value)));
                if (labelled && valued) {
                    return Membership.TABLE_ROW;
                }
                valuesOnly |= !values.isEmpty() && valued;
            }
        }
        if (valuesOnly) {
            return Membership.TABLE_ROW_VALUES_ONLY;
        }
        if (tables.stream().anyMatch(table -> evidence.stream().allMatch(table.text()::contains))) {
            return Membership.TABLE_TEXT;
        }
        if (others.stream().anyMatch(text -> evidence.stream().allMatch(text::contains))) {
            return Membership.OUTSIDE_TABLE;
        }
        return Membership.ABSENT;
    }

    /**
     * Counts label and value items found in some non-table element of the declared page.
     *
     * <p>This post-classification diagnostic was added after the frozen classes were first reported. It explains an
     * {@link Membership#ABSENT} class whose evidence is split across several elements; it never changes the class.
     * The label must equal a whole element, and a value must be a token of an element under {@link #holds}.
     *
     * @param label  the collapsed row label
     * @param values the exact row values
     * @param others collapsed text of every non-table element on the declared page
     * @return the number of items found, at most one plus the number of values
     */
    static int scattered(final String label, final List<String> values, final List<String> others) {
        final int labelled = others.contains(label) ? 1 : 0;
        return labelled
                + (int) values.stream().filter(value -> others.stream().anyMatch(text -> holds(text, value))).count();
    }

    /**
     * Returns whether one value is a whitespace-delimited token of a cell, ignoring a leading currency token.
     *
     * @param cell  collapsed cell text
     * @param value exact value
     * @return whether the cell holds the value
     */
    static boolean holds(final String cell, final String value) {
        final var tokens = new ArrayList<>(Arrays.asList(cell.split(" ")));
        if (!tokens.isEmpty() && tokens.getFirst().equals("$")) {
            tokens.removeFirst();
        }
        return tokens.contains(value);
    }

    /**
     * Collapses ASCII whitespace runs and trims; no other normalization is applied.
     *
     * @param text raw text
     * @return collapsed text
     */
    static String collapse(final String text) {
        return text.replaceAll("[ \\t\\n\\r\\f\\u000B]+", " ").strip();
    }

    /** Checks each class, the currency rule and the absence of Unicode or punctuation repair. */
    private static void controls() {
        final var table = new CapturedTable(100, 200,
                List.of(List.of("Net sales", "$ 1,000", "2,000"), List.of("3,000", "4,000"), List.of("Total")),
                "Net sales $ 1,000 2,000 3,000 4,000 Total 5,000 6,000");
        final var others = List.of("Footnote 7,000 8,000");
        require(classify("Net sales", List.of("1,000", "2,000"), List.of(table), others) == Membership.TABLE_ROW,
                "Labelled row control failed");
        require(classify("Other", List.of("3,000", "4,000"), List.of(table), others)
                        == Membership.TABLE_ROW_VALUES_ONLY,
                "Values-only control failed");
        require(classify("Total", List.of("5,000", "6,000"), List.of(table), others) == Membership.TABLE_TEXT,
                "Table-text control failed");
        require(classify("Footnote", List.of("7,000", "8,000"), List.of(table), others) == Membership.OUTSIDE_TABLE,
                "Outside-table control failed");
        require(classify("Missing", List.of("9,000"), List.of(table), others) == Membership.ABSENT,
                "Absent control failed");
        require(classify("Total", List.of(), List.of(table), others) == Membership.TABLE_ROW,
                "Label-only row control failed");
        require(!holds("1.000", "1,000") && !holds("1,000\u00a0", "1,000") && holds("$ 1,000", "1,000"),
                "Strict value control failed");
        require(scattered("Footnote", List.of("7,000", "9,000"), List.of("Footnote", "7,000 $", "8,000")) == 2,
                "Scattered evidence control failed");
        require(collapse(" a \t b\n").equals("a b") && collapse("a\u00a0b").equals("a\u00a0b"),
                "Whitespace control failed");
    }

    /**
     * Rounds a coordinate to one decimal place for the report.
     *
     * @param value coordinate in points
     * @return rounded coordinate
     */
    private static double round(final double value) {
        return Math.round(value * 10) / 10.0;
    }

    /**
     * Returns the lowercase SHA-256 of a file.
     *
     * @param path file to hash
     * @return hexadecimal digest
     * @throws Exception if the file cannot be read
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Fails with a descriptive message when a condition is false.
     *
     * @param condition checked condition
     * @param message   failure description
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
