package dev.docweave.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Controls preventing empty/duplicate reference predictions from inflating benchmark results, migrated
 * from {@code test_pdf_upstream_summary.py}, plus a byte-level replay of the retained all-20 summary.
 */
class UpstreamTableSummaryTest {
    @TempDir Path temporary;

    /** Creates the controls. */
    UpstreamTableSummaryTest() {
    }

    /**
     * Builds one upstream table record.
     *
     * @param anchors anchor count
     * @param complete complete topology flag
     * @return record
     */
    private static Map<String, Object> table(final long anchors, final boolean complete) {
        final var result = new LinkedHashMap<String, Object>();
        result.put("anchors", anchors);
        result.put("complete_topology", complete);
        result.put("complete_topology_and_text", false);
        result.put("positional_span_matches", anchors);
        return result;
    }

    /**
     * Builds one case.
     *
     * @param profile profile key
     * @param value profile value
     * @return case
     */
    private static Map<String, Object> single(final String profile, final Object value) {
        final var result = new LinkedHashMap<String, Object>();
        result.put(profile, value);
        return result;
    }

    @Test
    void emptyRecognitionIsRetainedWithoutTableCredit() {
        final String profile = UpstreamTableSummary.PROFILES.get(1);
        final var result = UpstreamTableSummary.aggregate(List.of(single(profile, table(0, false))), profile);
        assertEquals(1L, result.get("inputs"));
        assertEquals(0L, result.get("nonempty_tables"));
        assertEquals(0L, result.get("exactly_one_complete_topology"));
    }

    @Test
    void duplicateTablesDoNotDoubleMatchesOrReceiveCompleteInputCredit() {
        final String profile = UpstreamTableSummary.PROFILES.get(0);
        final var result = UpstreamTableSummary.aggregate(
                List.of(single(profile, List.of(table(10, true), table(10, true)))), profile);
        assertEquals(2L, result.get("nonempty_tables"));
        assertEquals(1L, result.get("inputs_with_multiple_tables"));
        assertEquals(10L, result.get("best_candidate_positional_span_matches"));
        assertEquals(0L, result.get("exactly_one_complete_topology"));
    }

    @Test
    void extraPublicElementWithoutHtmlPreventsCompleteInputCredit() {
        final String profile = UpstreamTableSummary.PROFILES.get(2);
        final var entry = single(profile, List.of(table(10, true)));
        entry.put("public_detected_tables", 2L);
        assertEquals(0L, UpstreamTableSummary.aggregate(List.of(entry), profile).get("exactly_one_complete_topology"));
    }

    @Test
    void missesStayInDenominatorAndTextCreditRemainsSeparate() {
        final String profile = UpstreamTableSummary.PROFILES.get(0);
        final var result = UpstreamTableSummary.aggregate(
                List.of(single(profile, List.of()), single(profile, List.of(table(10, true)))), profile);
        assertEquals(2L, result.get("inputs"));
        assertEquals(1L, result.get("inputs_with_table"));
        assertEquals(1L, result.get("exactly_one_complete_topology"));
        assertEquals(0L, result.get("exactly_one_complete_topology_and_text"));
    }

    @Test
    void javaExtraTablesPreventCompleteInputCredit() {
        final var emitted = new LinkedHashMap<String, Object>();
        emitted.put("same_shape", true);
        emitted.put("positional_span_matches", 10L);
        emitted.put("complete_shape_text_match", true);
        final var entry = new LinkedHashMap<String, Object>();
        entry.put("published_anchors", 10L);
        entry.put("emitted_tables", List.of(emitted, emitted));
        final var result = UpstreamTableSummary.candidateSummary(Map.of("results", List.of(entry)));
        assertEquals(2L, result.get("nonempty_tables"));
        assertEquals(0L, result.get("exactly_one_complete_topology"));
    }

    @Test
    void retainedAllTwentySummaryIsReproducedFromItsOwnSourceReports() throws IOException {
        final Path summary = Path.of("benchmarks/review/pdf-upstream-all20/comparison.json");
        final var retained = PythonJson.object(PythonJson.parse(ScoringFiles.readText(summary)), "summary");
        final var hashes = PythonJson.object(retained.get("source_reports_sha256"), "source_reports_sha256");
        final Path reports = Files.createDirectory(temporary.resolve("reports"));
        for (final Object item : PythonJson.array(retained.get("cases"), "cases")) {
            final var entry = PythonJson.object(item, "case");
            final String stem = ((String) entry.get("input")).replaceFirst("\\.png$", "");
            final String name = stem + "-comparison.json";
            final byte[] bytes = (PythonJson.dumps(entry, true) + "\n").getBytes(StandardCharsets.UTF_8);
            assertEquals(hashes.get(name), ScoringFiles.sha256(bytes), name);
            Files.write(reports.resolve(name), bytes);
        }
        final Path output = temporary.resolve("summary.json");
        final var console = new ByteArrayOutputStream();
        new UpstreamTableSummary().run(
                List.of("--reports", reports.toString(), "--candidate",
                        "benchmarks/review/pdf-merge-conflicts/cells-java25.json", "--detector-baseline",
                        "benchmarks/review/pdf-page-detection/pubtabnet-java25.json", "--output", output.toString()),
                new PrintStream(console, true, StandardCharsets.UTF_8));
        final var produced = PythonJson.object(PythonJson.parse(Files.readString(output)), "produced");
        assertEquals(ScoringFiles.sha256(UpstreamTableSummary.SOURCE), produced.get("harness_sha256"));
        produced.put("harness_sha256", retained.get("harness_sha256"));
        assertEquals(Files.readString(summary), PythonJson.dumps(produced, true) + "\n");
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                ()
                        -> new UpstreamTableSummary().run(
                                List.of("--reports", reports.toString(), "--candidate", "x", "--detector-baseline", "y",
                                        "--output", output.toString()),
                                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
    }
}
