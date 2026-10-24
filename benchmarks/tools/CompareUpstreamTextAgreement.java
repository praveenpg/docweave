package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Measures complete-document text agreement between DocWeave defaults and actual pinned Unstructured {@code fast}
 * output on a frozen case list, with failures and extra output kept in the denominators.
 *
 * <p>Text of non-image elements is joined in output order and split on ASCII whitespace only. Bag agreement counts
 * matched, missing (upstream only) and extra (DocWeave only) tokens. Order agreement uses tokens that occur exactly
 * once in both outputs as anchors and reports how many lie on the longest common increasing order. Agreement is a
 * compatibility observation, not source accuracy; the report carries counts and hashes only.
 */
public final class CompareUpstreamTextAgreement {
    /** Prevents construction of this command-line tool. */
    private CompareUpstreamTextAgreement() {
    }

    /**
     * Writes one agreement row per frozen case plus totals.
     *
     * @param args frozen manifest, original source directory, raw upstream capture directory, a fresh report and
     *         optionally {@code --inline-scripts}, which adds the explicit native small-glyph run policy to the
     *         defaults, or {@code --profile=LAYOUT[+MODIFIER]...}, which selects a native layout with explicit
     *         policies ({@code INLINE_SCRIPTS}, {@code SIDE_STREAMS}, {@code SIDE_STREAMS_ROW_TIES} or
     *         {@code SIDE_STREAMS_ROW_FRAGMENTS}); or
     *         {@code --self-test}
     * @throws Exception if an identity or control fails
     */
    public static void main(final String[] args) throws Exception {
        controls();
        if (args.length == 1 && args[0].equals("--self-test")) {
            System.out.println("Upstream text agreement controls passed");
            return;
        }
        require(args.length == 4
                        || args.length == 5 && (args[4].equals("--inline-scripts") || args[4].startsWith("--profile=")),
                "Expected manifest, sources, captures, a fresh report and an optional policy");
        final String profileName = args.length == 4                     ? "PdfOptions.defaults()"
                                   : args[4].equals("--inline-scripts") ? "PdfOptions.defaults()+NEARBY_SMALL_GLYPHS"
                                                                        : args[4].substring("--profile=".length());
        final var options = args.length == 4                     ? PdfOptions.defaults()
                            : args[4].equals("--inline-scripts") ? PdfOptions.defaults().withInlineScripts(
                                                                           PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS)
                                                                 : profile(profileName);
        final var mapper = JsonMapper.builder().build();
        final var manifestPath = Path.of(args[0]);
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        final var captures = Path.of(args[2]);
        final var runtime = mapper.readTree(Files.readAllBytes(captures.resolve("runtime.json")));
        require(runtime.get("packages")
                        .get("unstructured")
                        .asString()
                        .equals(manifest.get("unstructured_version").asString()),
                "Executed runtime differs");
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
        final var results = new ArrayList<Map<String, Object>>();
        final var keys = List.of("upstream_tokens", "docweave_tokens", "matched_tokens", "missing_tokens",
                "extra_tokens", "anchors", "ordered_anchors");
        final var totals = new long[keys.size()];
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            final var input = selected.get("inputs").get(0);
            final var source = Path.of(args[1]).resolve(input.get("filename").asString());
            final byte[] bytes = Files.readAllBytes(source);
            require(bytes.length == input.get("bytes").asLong() && hash(bytes).equals(input.get("sha256").asString()),
                    "Original source differs");
            final var capturePath = captures.resolve(id + ".json");
            final var row = new LinkedHashMap<String, Object>();
            row.put("id", id);
            if (!Files.exists(capturePath)) {
                row.put("upstream_outcome", "UPSTREAM_CAPTURE_MISSING");
                results.add(row);
                continue;
            }
            final var capture = mapper.readTree(Files.readAllBytes(capturePath));
            final String upstreamOutcome = capture.get("outcome").asString();
            require(upstreamOutcome.equals("SUCCESS") || capture.get("elements").isEmpty(), "Failed partial capture");
            final var upstream = tokens(capture.get("elements")
                            .valueStream()
                            .filter(element -> !element.get("type").asString().equals("Image"))
                            .map(element -> element.get("text").asString())
                            .collect(Collectors.joining(" ")));
            String outcome = "SUCCESS";
            List<String> docweave = List.of();
            try {
                docweave = tokens(processor.partition(DocumentSource.bytes(bytes, input.get("filename").asString()))
                                .elements()
                                .stream()
                                .filter(element -> element.type() != ElementType.Image)
                                .map(element -> element.text())
                                .collect(Collectors.joining(" ")));
            } catch (final PartitionException failure) {
                // A whole-document failure keeps every upstream token in the missing count.
                outcome = failure.code().name();
            }
            row.put("upstream_outcome", upstreamOutcome);
            row.put("docweave_outcome", outcome);
            row.put("capture_sha256", hash(Files.readAllBytes(capturePath)));
            row.putAll(agreement(upstream, docweave));
            for (int index = 0; index < keys.size(); index++) {
                totals[index] += ((Number) row.get(keys.get(index))).longValue();
            }
            results.add(row);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("manifest_sha256", hash(Files.readAllBytes(manifestPath)));
        report.put("runtime_sha256", hash(Files.readAllBytes(captures.resolve("runtime.json"))));
        report.put("java_version", System.getProperty("java.version"));
        report.put("docweave_profile", profileName);
        final var summed = new LinkedHashMap<String, Long>();
        for (int index = 0; index < keys.size(); index++) {
            summed.put(keys.get(index), totals[index]);
        }
        report.put("totals", summed);
        report.put("results", results);
        Files.writeString(Path.of(args[3]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Builds a native layout profile with optional explicit policy modifiers separated by {@code +}.
     *
     * @param name layout name followed by optional {@code INLINE_SCRIPTS} and {@code SIDE_STREAMS} or
     *         {@code SIDE_STREAMS_ROW_TIES} or {@code SIDE_STREAMS_ROW_FRAGMENTS} modifiers
     * @return extraction options
     */
    static PdfOptions profile(final String name) {
        final var parts = name.split("\\+");
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
        return options;
    }

    /**
     * Computes bag and anchor-order agreement between two token sequences.
     *
     * @param upstream reference-side tokens in output order
     * @param docweave candidate-side tokens in output order
     * @return counts in declaration order
     */
    static Map<String, Object> agreement(final List<String> upstream, final List<String> docweave) {
        final var upstreamCounts = counts(upstream);
        final var docweaveCounts = counts(docweave);
        long matched = 0;
        for (final var entry : upstreamCounts.entrySet()) {
            matched += Math.min(entry.getValue(), docweaveCounts.getOrDefault(entry.getKey(), 0));
        }
        final var docweavePosition = new HashMap<String, Integer>();
        for (int index = 0; index < docweave.size(); index++) {
            if (docweaveCounts.get(docweave.get(index)) == 1) {
                docweavePosition.put(docweave.get(index), index);
            }
        }
        final var anchors = new ArrayList<Integer>();
        for (final var token : upstream) {
            if (upstreamCounts.get(token) == 1 && docweavePosition.containsKey(token)) {
                anchors.add(docweavePosition.get(token));
            }
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("upstream_tokens", (long) upstream.size());
        result.put("docweave_tokens", (long) docweave.size());
        result.put("matched_tokens", matched);
        result.put("missing_tokens", upstream.size() - matched);
        result.put("extra_tokens", docweave.size() - matched);
        result.put("anchors", (long) anchors.size());
        result.put("ordered_anchors", (long) increasing(anchors));
        return result;
    }

    /**
     * Returns the length of the longest strictly increasing subsequence in O(n log n).
     *
     * @param values positions in reference order
     * @return subsequence length
     */
    static int increasing(final List<Integer> values) {
        final var tails = new int[values.size()];
        int length = 0;
        for (final int value : values) {
            int position = Arrays.binarySearch(tails, 0, length, value);
            if (position < 0) {
                position = -position - 1;
            }
            tails[position] = value;
            length = Math.max(length, position + 1);
        }
        return length;
    }

    /**
     * Splits on ASCII whitespace only.
     *
     * @param text joined element text
     * @return nonempty tokens
     */
    static List<String> tokens(final String text) {
        return Arrays.stream(text.split("[ \\t\\n\\r\\f\\u000B]+")).filter(token -> !token.isEmpty()).toList();
    }

    /**
     * Counts token occurrences.
     *
     * @param tokens tokens
     * @return counts by token
     */
    private static Map<String, Integer> counts(final List<String> tokens) {
        final var counts = new HashMap<String, Integer>();
        tokens.forEach(token -> counts.merge(token, 1, Integer::sum));
        return counts;
    }

    /** Checks bag counts, anchor order, failures and the ASCII-only whitespace rule. */
    private static void controls() {
        final var result = agreement(tokens("a b c d d"), tokens("b a c d x"));
        require(result.get("matched_tokens").equals(4L) && result.get("missing_tokens").equals(1L)
                        && result.get("extra_tokens").equals(1L),
                "Bag control failed");
        require(result.get("anchors").equals(3L) && result.get("ordered_anchors").equals(2L), "Order control failed");
        final var failed = agreement(tokens("a b"), List.of());
        require(failed.get("missing_tokens").equals(2L) && failed.get("ordered_anchors").equals(0L),
                "Failure control failed");
        require(tokens("a\u00a0b c").equals(List.of("a\u00a0b", "c")), "Whitespace control failed");
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
     * @param message   failure description
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
