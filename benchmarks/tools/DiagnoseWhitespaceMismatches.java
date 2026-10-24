package io.docweave.validation;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Counts business-comparison mismatches that disappear under an all-Unicode-whitespace diagnostic normalization.
 *
 * <p>The official JVM business scorer collapses ASCII whitespace only; this diagnostic never replaces it. For every
 * case and profile, the official geometry-only selection and anchor projection are reused, and each mismatched
 * reference position is re-compared after collapsing all Unicode whitespace (including no-break and ideographic
 * spaces) and removing zero-width spaces. Reports contain counts and position indices only.
 */
public final class DiagnoseWhitespaceMismatches {
    /** Unicode whitespace runs collapsed by the diagnostic. */
    private static final Pattern SPACE = Pattern.compile("[\\s\\u00a0\\u2000-\\u200a\\u202f\\u205f\\u3000]+");

    /** Prevents construction. */
    private DiagnoseWhitespaceMismatches() {
    }

    /**
     * Writes the diagnostic.
     *
     * @param args manifest, pinned cache, capture root, fresh report and profile names
     * @throws Exception if an input cannot be read
     */
    public static void main(final String[] args) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException("manifest cache captures report profiles...");
        }
        final var manifest = PythonJson.object(PythonJson.parse(ScoringFiles.readText(Path.of(args[0]))), "manifest");
        final var rows = new ArrayList<Map<String, Object>>();
        for (final Object item : PythonJson.array(manifest.get("cases"), "cases")) {
            final var entry = PythonJson.object(item, "case");
            final var reference = ScoringFiles.csv(Path.of(args[1]).resolve((String) entry.get("csv")));
            final var roi = PythonJson.array(entry.get("roi_left_top_right_bottom"), "roi");
            final var box =
                    new TableGeometry.Box(((Number) roi.get(0)).doubleValue(), ((Number) roi.get(1)).doubleValue(),
                            ((Number) roi.get(2)).doubleValue(), ((Number) roi.get(3)).doubleValue());
            for (int index = 4; index < args.length; index++) {
                final String profile = args[index];
                final var capture = PythonJson.object(PythonJson.parse(ScoringFiles.readText(Path.of(
                                                              args[2], (String) entry.get("id"), profile + ".json"))),
                        "capture");
                final var tables = PythonJson.array(capture.get("tables"), "tables")
                                           .stream()
                                           .map(table -> PythonJson.object(table, "table"))
                                           .toList();
                final var selection = BusinessCellTextScorer.select(tables, box);
                final Map<List<Integer>, String> slots =
                        selection.table() == null ? Map.of()
                                                  : BusinessCellTextScorer.project(selection.table()).slots();
                int mismatches = 0;
                final var whitespaceOnly = new ArrayList<Object>();
                for (int r = 0; r < reference.size(); r++) {
                    for (int c = 0; c < reference.get(r).size(); c++) {
                        final String expected = BusinessCellTextScorer.normalize(reference.get(r).get(c));
                        final String actual = slots.get(List.of(r, c));
                        if (actual != null && actual.equals(expected)) {
                            continue;
                        }
                        mismatches++;
                        if (actual != null && collapse(actual).equals(collapse(expected))) {
                            whitespaceOnly.add(List.of(r, c));
                        }
                    }
                }
                final var row = new LinkedHashMap<String, Object>();
                row.put("case", entry.get("id"));
                row.put("profile", profile);
                row.put("mismatches", mismatches);
                row.put("whitespace_only_mismatches", whitespaceOnly.size());
                row.put("whitespace_only_positions", whitespaceOnly);
                rows.add(row);
                System.out.println(row);
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put(
                "scope", "All-Unicode-whitespace diagnostic re-comparison of official mismatches; counts and indices "
                                 + "only; official ASCII-only scoring unchanged.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("rows", rows);
        Files.writeString(Path.of(args[3]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Collapses all Unicode whitespace and removes zero-width spaces.
     *
     * @param value text
     * @return diagnostic form
     */
    private static String collapse(final String value) {
        return SPACE.matcher(value.replace("​", "")).replaceAll(" ").strip();
    }
}
