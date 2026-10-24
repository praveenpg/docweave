package io.docweave.validation;

import io.docweave.core.Element;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PdfNativeLayoutOptions;
import io.docweave.partition.PdfOptions;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Exact complete-row presence diagnostic alongside unchanged ordinal scoring; never repairs or reorders output. */
public final class AuditStatementRowPresence {
    /** Numeric accounting tokens only; ASCII spaces before the closing parenthesis are diagnostic. */
    private static final Pattern ACCOUNTING_PARENTHESIS =
            Pattern.compile("\\(([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?) +\\)");

    /** Prevents standalone diagnostic construction. */
    private AuditStatementRowPresence() {
    }

    /**
     * Reuses the frozen reference parser, region association and full-source extraction for both profiles.
     * Presence consumes exact complete-row occurrences once; unmatched references and extra observations stay.
     * This diagnoses title/header offsets without replacing strict ordinal scores or measuring logical order.
     * @param args replay manifest, source cache, capture root and fresh report, optional --leading-row,
     *         --section-row, --section-row-trailing-spacers, --section-row-trailing-spacers-row-lines (also splits
     *         aligned two-line provider rows), --section-row-trailing-spacers-aligned-text (native-first aligned-text
     *         detection) or --section-row-parenthesis-diagnostic; or --self-test
     * @throws Exception if pins, protocol completeness, controls or extraction infrastructure fail
     */
    public static void main(final String[] args) throws Exception {
        controls();
        if (args.length == 1 && args[0].equals("--self-test")) {
            System.out.println("Exact statement-row presence controls passed");
            return;
        }
        require(args.length == 4
                        || args.length == 5
                                   && (args[4].equals("--leading-row") || args[4].equals("--section-row")
                                           || args[4].equals("--section-row-trailing-spacers")
                                           || args[4].equals("--section-row-trailing-spacers-row-lines")
                                           || args[4].equals("--section-row-trailing-spacers-aligned-text")
                                           || args[4].equals("--section-row-parenthesis-diagnostic")),
                "Expected replay, source cache, captures, fresh report and an optional supported row-policy flag");
        final boolean spacingDiagnostic = args.length == 5 && args[4].equals("--section-row-parenthesis-diagnostic");
        final boolean rowLines = args.length == 5 && args[4].equals("--section-row-trailing-spacers-row-lines");
        final boolean alignedText = args.length == 5 && args[4].equals("--section-row-trailing-spacers-aligned-text");
        final boolean trailingSpacers =
                args.length == 5 && (args[4].equals("--section-row-trailing-spacers") || rowLines || alignedText);
        final var leadingRow = args.length == 4 ? PdfNativeLayoutOptions.LeadingRow.PROVIDER_ONLY
                               : (args[4].equals("--section-row") || spacingDiagnostic || trailingSpacers)
                                       ? PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW
                                       : PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_ROW;
        final var trailingRows = trailingSpacers ? PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS
                                                 : PdfNativeLayoutOptions.TrailingRows.PROVIDER_ONLY;
        final var mapper = JsonMapper.builder().build();
        final var replay = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var referencePath = Path.of(replay.get("reference_manifest").asString());
        require(hash(Files.readAllBytes(referencePath)).equals(replay.get("reference_manifest_sha256").asString()),
                "Reference protocol changed");
        final var scorerPath =
                Path.of("docweave-validation/src/test/java/io/docweave/validation/PdfCurrentBusinessRowsTest.java");
        require(hash(Files.readAllBytes(scorerPath)).equals(replay.get("validation_source_sha256").asString()),
                "Frozen scorer changed");
        final var reference = mapper.readTree(Files.readAllBytes(referencePath));
        for (final var input : reference.get("inputs")) {
            final var path = Path.of(args[1], input.get("filename").asString());
            require(Files.size(path) == input.get("bytes").asLong()
                            && hash(Files.readAllBytes(path)).equals(input.get("sha256").asString()),
                    "Original input changed");
        }
        final var scorer = Class.forName("io.docweave.validation.PdfCurrentBusinessRowsTest");
        final var regionClass = Class.forName(scorer.getName() + "$Region");
        final var constructor = regionClass.getDeclaredConstructor(int.class, List.class);
        constructor.setAccessible(true);
        final var rowReader = scorer.getDeclaredMethod("rows", org.jsoup.nodes.Element.class);
        rowReader.setAccessible(true);
        final var fragments = scorer.getDeclaredMethod("fragments", List.class, List.class);
        fragments.setAccessible(true);
        final var fragmentClass = Class.forName(scorer.getName() + "$Fragments");
        final var observedReader = fragmentClass.getDeclaredMethod("observed");
        observedReader.setAccessible(true);
        final var regionReader = fragmentClass.getDeclaredMethod("regions");
        regionReader.setAccessible(true);
        final var extract = CompareNvidiaCellLines.class.getDeclaredMethod("extract", JsonNode.class, Path.class,
                String.class, Path.class, PdfNativeLayoutOptions.CellTextJoin.class,
                PdfNativeLayoutOptions.LeadingRow.class, PdfNativeLayoutOptions.TrailingRows.class,
                PdfNativeLayoutOptions.RowLines.class, PdfOptions.TableDetection.class);
        extract.setAccessible(true);
        final var html = Jsoup.parse(Files.readString(Path.of(args[1], reference.get("source_html").asString())));
        final var results = new ArrayList<Map<String, Object>>();
        for (final var profile : replay.get("profiles")) {
            final String family = profile.asString();
            List<Element> elements = List.of();
            String outcome = "SUCCESS";
            try {
                @SuppressWarnings("unchecked")
                final var decoded = (List<Element>) extract.invoke(null, replay, Path.of(args[2]), family,
                        Path.of(args[1], reference.get("source_pdf").asString()),
                        PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED, leadingRow, trailingRows,
                        rowLines ? PdfNativeLayoutOptions.RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS
                                 : PdfNativeLayoutOptions.RowLines.PROVIDER_ROWS,
                        alignedText ? PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT
                                    : PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT);
                elements = decoded;
            } catch (final InvocationTargetException failure) {
                if (failure.getCause() instanceof PartitionException typed) {
                    outcome = typed.code().name();
                } else {
                    throw failure;
                }
            }
            for (final var selected : reference.get("cases")) {
                @SuppressWarnings("unchecked")
                final var expected = (List<String>) rowReader.invoke(
                        null, html.select("table").get(selected.get("html_table_index").asInt()));
                require(expected.size() == selected.get("reference_rows").asInt()
                                && hash(String.join("\n", expected).getBytes(StandardCharsets.UTF_8))
                                        .equals(selected.get("reference_rows_sha256").asString()),
                        "Reference row bytes changed");
                final var regions = new ArrayList<Object>();
                for (final var region : selected.get("regions")) {
                    final var bounds = new ArrayList<Double>();
                    region.get("roi_left_top_right_bottom").forEach(value -> bounds.add(value.asDouble()));
                    regions.add(constructor.newInstance(region.get("page").asInt(), List.copyOf(bounds)));
                }
                final var associated = fragments.invoke(null, elements, regions);
                @SuppressWarnings("unchecked") final var observed = (List<String>) observedReader.invoke(associated);
                final var result = new LinkedHashMap<String, Object>(presence(expected, observed));
                if (spacingDiagnostic) {
                    result.put("parenthesis_spacing_diagnostic", spacing(expected, observed));
                }
                result.put("case", selected.get("id").asString());
                result.put("profile", family);
                result.put("outcome", outcome);
                result.put("regions", regionReader.invoke(associated));
                results.add(result);
            }
        }
        require(results.size() == 6, "Complete three-statement/two-profile population required");
        final var report = new LinkedHashMap<String, Object>(Map.of("scope",
                "Exact complete-row presence diagnostic only; original ordinal score unchanged; no fuzzy alignment, output repair or logical-order claim",
                "leading_row", leadingRow.name(), "runtime", Runtime.version().toString(), "replay_manifest_sha256",
                hash(Files.readAllBytes(Path.of(args[0]))), "reference_manifest_sha256",
                hash(Files.readAllBytes(referencePath)), "results", results));
        // Only the explicit trailing profile adds this key, so earlier profiles keep their historical report shape.
        if (trailingSpacers) {
            report.put("trailing_rows", trailingRows.name());
        }
        Files.writeString(Path.of(args[3]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Counts exact one-to-one row occurrences while retaining strict original ordinal equality separately.
     * No reference/output string is changed, and observed indices are never used to rewrite source order.
     * @param expected complete frozen reference rows in original order
     * @param observed complete geometrically associated source rows in emitted order
     * @return numeric counts, every reference occurrence and every unmatched observed index
     */
    private static Map<String, Object> presence(final List<String> expected, final List<String> observed) {
        final var occurrences = new LinkedHashMap<String, ArrayDeque<Integer>>();
        for (int index = 0; index < observed.size(); index++) {
            occurrences.computeIfAbsent(observed.get(index), ignored -> new ArrayDeque<>()).add(index);
        }
        final var rows = new ArrayList<Map<String, Object>>();
        int ordinal = 0;
        int present = 0;
        for (int index = 0; index < expected.size(); index++) {
            final boolean exact = index < observed.size() && expected.get(index).equals(observed.get(index));
            ordinal += exact ? 1 : 0;
            final var candidates = occurrences.get(expected.get(index));
            final int found = candidates == null || candidates.isEmpty() ? -1 : candidates.removeFirst();
            present += found >= 0 ? 1 : 0;
            rows.add(Map.of("reference_index", index, "exact_ordinal", exact, "exact_occurrence_index", found));
        }
        final var extra = occurrences.values().stream().flatMap(java.util.Collection::stream).sorted().toList();
        return Map.of("reference_rows", expected.size(), "observed_rows", observed.size(), "strict_exact_ordinal_rows",
                ordinal, "exact_row_occurrences_present", present, "missing_exact_occurrences",
                expected.size() - present, "reference_occurrences", rows, "unmatched_observed_indices", extra);
    }

    /**
     * Associates remaining whole rows only after exact matches have consumed their observations.
     * Only ASCII spaces before a closing parenthesis in an unsigned numeric token are ignored.
     * This is a presentation diagnostic, not cell alignment, semantic equivalence or a revised score.
     * @param expected original frozen reference rows
     * @param observed original emitted rows
     * @return index-only additional pairs, preserving every strict measurement separately
     */
    private static List<Map<String, Integer>> spacing(final List<String> expected, final List<String> observed) {
        final var unmatched = new LinkedHashMap<String, ArrayDeque<Integer>>();
        for (int index = 0; index < observed.size(); index++) {
            unmatched.computeIfAbsent(observed.get(index), ignored -> new ArrayDeque<>()).add(index);
        }
        final var missing = new ArrayList<Integer>();
        for (int index = 0; index < expected.size(); index++) {
            final var exact = unmatched.get(expected.get(index));
            if (exact != null && !exact.isEmpty()) {
                exact.removeFirst();
            } else {
                missing.add(index);
            }
        }
        final var candidates = new LinkedHashMap<String, ArrayDeque<Integer>>();
        unmatched.values()
                .stream()
                .flatMap(java.util.Collection::stream)
                .sorted()
                .forEach(index
                        -> candidates.computeIfAbsent(accounting(observed.get(index)), ignored -> new ArrayDeque<>())
                                .add(index));
        final var pairs = new ArrayList<Map<String, Integer>>();
        for (final int index : missing) {
            final var available = candidates.get(accounting(expected.get(index)));
            if (available != null && !available.isEmpty()) {
                pairs.add(Map.of("reference_index", index, "observed_index", available.removeFirst()));
            }
        }
        return List.copyOf(pairs);
    }

    /**
     * Produces a temporary comparison key without modifying either captured row.
     * @param row original row text
     * @return key with only the declared numeric-parenthesis spacing removed
     */
    private static String accounting(final String row) {
        return ACCOUNTING_PARENTHESIS.matcher(row).replaceAll("($1)");
    }

    /** Tests offset distinction, duplicate consumption, excess output, missing output and exact whitespace. */
    private static void controls() {
        require(spacing(List.of("Loss (5,491 )"), List.of("Loss (5,491)")).size() == 1,
                "Accounting presentation missed");
        require(spacing(List.of("(2 )", "(2)"), List.of("(2)")).isEmpty(), "Exact occurrence stolen");
        require(spacing(List.of("(2 )", "(2 )"), List.of("(2)")).size() == 1, "Observation reused");
        require(spacing(List.of("(2 )"), List.of("(2 )")).isEmpty(), "Exact match counted twice");
        require(spacing(List.of("(2.50  )"), List.of("(2.50)")).size() == 1, "Decimal presentation missed");
        for (final var pair : List.of(List.of("(2 )", "(3)"), List.of("(2 )", "2"), List.of("(word )", "(word)"),
                     List.of("(5,49 )", "(5,49)"), List.of("(2\u00a0)", "(2)"), List.of("(2 ) A", "(2) a"),
                     List.of("(2 )", "(-2)"), List.of("(2 )", "(2.0)"))) {
            require(spacing(pair.subList(0, 1), pair.subList(1, 2)).isEmpty(), "Nonspacing difference waived");
        }
        final var shifted = presence(List.of("title", "a", "b"), List.of("a", "b"));
        require(shifted.get("strict_exact_ordinal_rows").equals(0)
                        && shifted.get("exact_row_occurrences_present").equals(2),
                "Ordinal/presence conflated");
        require(presence(List.of("a", "a"), List.of("a")).get("exact_row_occurrences_present").equals(1),
                "Observed duplicate reused");
        require(presence(List.of("a"), List.of("a", "a")).get("unmatched_observed_indices").equals(List.of(1)),
                "Excess observed row dropped");
        require(presence(List.of("a"), List.of()).get("missing_exact_occurrences").equals(1), "Missing output dropped");
        require(presence(List.of("(2 )"), List.of("(2)")).get("exact_row_occurrences_present").equals(0),
                "Whitespace difference normalized");
    }

    /**
     * Hashes exact frozen bytes without exposing publisher transcriptions.
     * @param bytes complete source/protocol/reference bytes
     * @return lowercase SHA256
     * @throws Exception if the digest implementation is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects changed evidence without choosing another source or repairing text.
     * @param condition required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
