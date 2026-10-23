package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Measures whether native row relationships, such as a statement label and its values, survive a reading-order
 * profile.
 *
 * <p>Row pairs come from DocWeave's geometric {@code LINES} layout with the profile's inline-script policy: two
 * horizontally consecutive, disjoint lines on one page whose baselines differ by at most two units. A pair is kept
 * in a profile when both line texts occur exactly once in that page's output, joined with single spaces, and only
 * whitespace separates the first from the second; it is separated when both occur once otherwise, and unmatched
 * when either is missing or repeated. A second measure counts merged rows: located row-bound lines (lines with a
 * same-baseline partner) that share one output element with a located row-bound line of a different baseline, as
 * when separate statement labels are joined into a paragraph. Text comparison collapses ASCII whitespace only. The
 * report holds counts.
 */
public final class CompareRowAssociation {
    /** Prevents construction of this command-line tool. */
    private CompareRowAssociation() {
    }

    /** Row-pair outcomes in one output. */
    enum Outcome {
        /** Both texts located once and adjacent in order. */
        KEPT,
        /** Both texts located once but not adjacent in order. */
        SEPARATED,
        /** At least one text missing or repeated. */
        UNMATCHED
    }

    /**
     * Writes row-pair outcomes for every listed source, profile and declared comparison.
     *
     * @param args TSV of {@code id, path, bytes, sha256} rows, comma-separated profiles, comma-separated
     *         {@code BASE>CANDIDATE} comparisons and a fresh report; or {@code --self-test}
     * @throws Exception if an identity or control fails
     */
    public static void main(final String[] args) throws Exception {
        controls();
        if (args.length == 1 && args[0].equals("--self-test")) {
            System.out.println("Row association controls passed");
            return;
        }
        require(args.length == 4, "Expected sources TSV, profiles, comparisons and a fresh report");
        final var profiles = List.of(args[1].split(","));
        final var comparisons = List.of(args[2].split(","));
        final var sources = new ArrayList<Map<String, Object>>();
        for (final var row : Files.readAllLines(Path.of(args[0]))) {
            final var fields = row.split("\t");
            require(fields.length == 4, "Invalid source row");
            final byte[] bytes = Files.readAllBytes(Path.of(fields[1]));
            require(bytes.length == Long.parseLong(fields[2]) && hash(bytes).equals(fields[3]),
                    "Source identity differs");
            final var populations = new LinkedHashMap<String, Map<Integer, List<String[]>>>();
            final var bound = new LinkedHashMap<String, Map<Integer, List<RowLine>>>();
            final var outcomes = new LinkedHashMap<String, List<Outcome>>();
            final var rows = new ArrayList<Map<String, Object>>();
            for (final var profile : profiles) {
                final String policy = profile.contains("+INLINE_SCRIPTS") ? "+INLINE_SCRIPTS" : "";
                final var pairs = populations.computeIfAbsent(policy, key -> {
                    try {
                        return pairs(extract("LINES" + key, bytes));
                    } catch (final Exception failure) {
                        throw new IllegalStateException("Row extraction failed", failure);
                    }
                });
                final var rowLines = bound.computeIfAbsent(policy, key -> {
                    try {
                        return rowLines(extract("LINES" + key, bytes));
                    } catch (final Exception failure) {
                        throw new IllegalStateException("Row extraction failed", failure);
                    }
                });
                final var output = pages(extract(profile, bytes));
                long merged = 0;
                for (final var page : rowLines.entrySet()) {
                    merged += merged(page.getValue(), output.getOrDefault(page.getKey(), List.of()));
                }
                final var result = new ArrayList<Outcome>();
                for (final var page : pairs.entrySet()) {
                    final var joined = String.join(" ", output.getOrDefault(page.getKey(), List.of()));
                    for (final var pair : page.getValue()) {
                        result.add(outcome(pair[0], pair[1], joined));
                    }
                }
                outcomes.put(profile, result);
                final var counts = new LinkedHashMap<String, Object>();
                counts.put("profile", profile);
                counts.put("row_pairs", result.size());
                for (final var outcome : Outcome.values()) {
                    counts.put(outcome.name().toLowerCase(), result.stream().filter(outcome::equals).count());
                }
                counts.put("row_bound_lines", rowLines.values().stream().mapToLong(List::size).sum());
                counts.put("merged_row_lines", merged);
                rows.add(counts);
            }
            final var compared = new ArrayList<Map<String, Object>>();
            for (final var comparison : comparisons) {
                final var sides = comparison.split(">");
                final var before = outcomes.get(sides[0]);
                final var after = outcomes.get(sides[1]);
                require(before != null && after != null && before.size() == after.size(),
                        "Comparison profiles must share one population");
                final var changes = new TreeMap<String, Long>();
                for (int index = 0; index < before.size(); index++) {
                    if (before.get(index) != after.get(index)) {
                        changes.merge(
                                before.get(index).name().toLowerCase() + "_to_" + after.get(index).name().toLowerCase(),
                                1L, Long::sum);
                    }
                }
                compared.add(Map.of("comparison", comparison, "changed_pairs", changes));
            }
            final var entry = new LinkedHashMap<String, Object>();
            entry.put("id", fields[0]);
            entry.put("source_sha256", fields[3]);
            entry.put("profiles", rows);
            entry.put("comparisons", compared);
            sources.add(entry);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("sources_tsv_sha256", hash(Files.readAllBytes(Path.of(args[0]))));
        report.put("java_version", System.getProperty("java.version"));
        report.put("sources", sources);
        Files.writeString(Path.of(args[3]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Derives horizontally consecutive same-baseline line pairs per page from geometric lines.
     *
     * @param lines native line elements
     * @return text pairs keyed by page
     */
    static Map<Integer, List<String[]>> pairs(final List<Element> lines) {
        final var byPage = new TreeMap<Integer, List<Element>>();
        for (final var line : lines) {
            if (line.type() != ElementType.Image && line.metadata().get("page_number") instanceof Number page) {
                byPage.computeIfAbsent(page.intValue(), key -> new ArrayList<>()).add(line);
            }
        }
        final var result = new TreeMap<Integer, List<String[]>>();
        for (final var page : byPage.entrySet()) {
            final var sorted = new ArrayList<>(page.getValue());
            sorted.sort(Comparator.comparingDouble((Element line) -> box(line)[3])
                            .thenComparingDouble(line -> box(line)[0]));
            final var pairs = new ArrayList<String[]>();
            for (int index = 0; index + 1 < sorted.size(); index++) {
                final var first = box(sorted.get(index));
                final var second = box(sorted.get(index + 1));
                if (Math.abs(second[3] - first[3]) <= 2 && second[0] >= first[2]) {
                    pairs.add(
                            new String[] {collapse(sorted.get(index).text()), collapse(sorted.get(index + 1).text())});
                }
            }
            result.put(page.getKey(), pairs);
        }
        return result;
    }

    /**
     * One row-bound line: its collapsed text and baseline.
     *
     * @param text collapsed text
     * @param baseline displayed bottom
     */
    record RowLine(String text, double baseline) {}

    /**
     * Lists, per page, lines that have a horizontally disjoint neighbour on the same baseline.
     *
     * @param lines native line elements
     * @return row-bound lines keyed by page
     */
    static Map<Integer, List<RowLine>> rowLines(final List<Element> lines) {
        final var byPage = new TreeMap<Integer, List<Element>>();
        for (final var line : lines) {
            if (line.type() != ElementType.Image && line.metadata().get("page_number") instanceof Number page) {
                byPage.computeIfAbsent(page.intValue(), key -> new ArrayList<>()).add(line);
            }
        }
        final var result = new TreeMap<Integer, List<RowLine>>();
        for (final var page : byPage.entrySet()) {
            final var bound = new ArrayList<RowLine>();
            for (final var line : page.getValue()) {
                final var box = box(line);
                final boolean partnered = page.getValue().stream().anyMatch(other -> {
                    final var candidate = box(other);
                    return other != line && Math.abs(candidate[3] - box[3]) <= 2
                            && (candidate[0] >= box[2] || candidate[2] <= box[0]);
                });
                if (partnered) {
                    bound.add(new RowLine(collapse(line.text()), box[3]));
                }
            }
            result.put(page.getKey(), bound);
        }
        return result;
    }

    /**
     * Counts located row-bound lines sharing an output element with a located row-bound line of another baseline.
     *
     * @param lines row-bound lines of one page
     * @param output collapsed output texts of that page in order
     * @return merged row-bound line count
     */
    static long merged(final List<RowLine> lines, final List<String> output) {
        final var joined = String.join(" ", output);
        final var starts = new int[output.size()];
        int offset = 0;
        for (int index = 0; index < output.size(); index++) {
            starts[index] = offset;
            offset += output.get(index).length() + 1;
        }
        final var baselines = new TreeMap<Integer, List<Double>>();
        final var owners = new ArrayList<int[]>();
        for (int index = 0; index < lines.size(); index++) {
            final int at = unique(joined, lines.get(index).text());
            if (at < 0) {
                continue;
            }
            int owner = 0;
            while (owner + 1 < starts.length && starts[owner + 1] <= at) {
                owner++;
            }
            baselines.computeIfAbsent(owner, key -> new ArrayList<>()).add(lines.get(index).baseline());
            owners.add(new int[] {owner, index});
        }
        long merged = 0;
        for (final var owner : owners) {
            final double baseline = lines.get(owner[1]).baseline();
            if (baselines.get(owner[0]).stream().anyMatch(other -> Math.abs(other - baseline) > 2)) {
                merged++;
            }
        }
        return merged;
    }

    /**
     * Classifies one row pair in one page's joined output.
     *
     * @param first left text
     * @param second right text
     * @param joined page output joined with single spaces
     * @return pair outcome
     */
    static Outcome outcome(final String first, final String second, final String joined) {
        final int a = unique(joined, first);
        final int b = unique(joined, second);
        if (a < 0 || b < 0) {
            return Outcome.UNMATCHED;
        }
        return b >= a + first.length() && joined.substring(a + first.length(), b).isBlank() ? Outcome.KEPT
                                                                                            : Outcome.SEPARATED;
    }

    /**
     * Returns the only occurrence of a nonempty text, or -1.
     *
     * @param joined searched text
     * @param text located text
     * @return offset or -1
     */
    private static int unique(final String joined, final String text) {
        final int first = text.isEmpty() ? -1 : joined.indexOf(text);
        return first >= 0 && joined.indexOf(text, first + 1) < 0 ? first : -1;
    }

    /**
     * Groups collapsed non-image output texts by page.
     *
     * @param elements extracted elements
     * @return texts keyed by page
     */
    private static Map<Integer, List<String>> pages(final List<Element> elements) {
        final var result = new TreeMap<Integer, List<String>>();
        for (final var element : elements) {
            if (element.type() != ElementType.Image && element.metadata().get("page_number") instanceof Number page) {
                result.computeIfAbsent(page.intValue(), key -> new ArrayList<>()).add(collapse(element.text()));
            }
        }
        return result;
    }

    /**
     * Returns an element's displayed left, top, right and bottom.
     *
     * @param element extracted element
     * @return box
     */
    private static double[] box(final Element element) {
        final var points = (List<?>) ((Map<?, ?>) element.metadata().get("coordinates")).get("points");
        final var topLeft = (List<?>) points.get(0);
        final var bottomRight = (List<?>) points.get(2);
        return new double[] {((Number) topLeft.get(0)).doubleValue(), ((Number) topLeft.get(1)).doubleValue(),
                ((Number) bottomRight.get(0)).doubleValue(), ((Number) bottomRight.get(1)).doubleValue()};
    }

    /**
     * Extracts with a native layout and optional {@code +INLINE_SCRIPTS} and {@code +SIDE_STREAMS} or
     * {@code +SIDE_STREAMS_ROW_TIES} or {@code +SIDE_STREAMS_ROW_FRAGMENTS} modifiers.
     *
     * @param profile profile name
     * @param bytes original bytes
     * @return elements
     * @throws Exception if extraction fails
     */
    private static List<Element> extract(final String profile, final byte[] bytes) throws Exception {
        final var parts = profile.split("\\+");
        var options = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.valueOf(parts[0]));
        for (int index = 1; index < parts.length; index++) {
            options = switch (parts[index]) {
                case "INLINE_SCRIPTS" -> options.withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
                case "SIDE_STREAMS" -> options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS);
                case "SIDE_STREAMS_ROW_TIES" ->
                    options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES);
                case "SIDE_STREAMS_ROW_FRAGMENTS" ->
                    options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS);
                default -> throw new IllegalArgumentException("Unknown profile modifier");
            };
        }
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(bytes, "source.pdf"))
                .elements();
    }

    /**
     * Collapses ASCII whitespace runs and trims.
     *
     * @param text raw text
     * @return collapsed text
     */
    static String collapse(final String text) {
        return text.replaceAll("[ \\t\\n\\r\\f\\u000B]+", " ").strip();
    }

    /** Checks kept, separated and unmatched classification. */
    private static void controls() {
        require(outcome("Net sales", "1,000", "Net sales 1,000 Cost 2,000") == Outcome.KEPT, "Kept control failed");
        require(outcome("Net sales", "1,000", "Net sales Cost 1,000 2,000") == Outcome.SEPARATED,
                "Separated control failed");
        require(outcome("Net sales", "1,000", "Net sales 1,000 1,000") == Outcome.UNMATCHED, "Repeated control failed");
        require(outcome("Net sales", "1,000", "1,000 Net sales") == Outcome.SEPARATED, "Reversed control failed");
        final var rows = List.of(new RowLine("Net sales", 100), new RowLine("Cost of sales", 115));
        require(merged(rows, List.of("Net sales", "1,000", "Cost of sales", "2,000")) == 0, "Separate rows failed");
        require(merged(rows, List.of("Net sales Cost of sales", "1,000", "2,000")) == 2, "Merged rows failed");
    }

    /**
     * Returns the lowercase SHA-256 of bytes.
     *
     * @param bytes bytes to hash
     * @return hexadecimal digest
     * @throws Exception if SHA-256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Fails with a descriptive message when a condition is false.
     *
     * @param condition checked condition
     * @param message failure description
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
