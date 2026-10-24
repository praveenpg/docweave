package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfParagraphAssembler;
import io.docweave.partition.PdfPartitioner;
import io.docweave.partition.PdfTaggedParagraphOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Bounded diagnostic edit traces; exact reference scoring and candidate text are never repaired. */
public final class AuditScientificTextDifferences {
    /** Fixed source-only prefix/suffix length in Unicode code points. */
    private static final int ANCHOR = 32;
    /** Maximum allocated edit-distance cells for one uniquely anchored span. */
    private static final long MAX_CELLS = 16_000_000;

    /** Prevents utility construction. */
    private AuditScientificTextDifferences() {
    }

    /**
     * Extracts complete originals without reference input, then preserves strict scoring alongside diagnostics.
     * @param args frozen protocol, verified original cache and NEW numeric report; or --self-test
     * @throws Exception if provenance, extraction baseline or bounded diagnostic requirements fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            controls();
            return;
        }
        require(args.length == 3, "Expected protocol, sources and NEW report");
        final var mapper = JsonMapper.builder().build();
        final var protocolBytes = bounded(Path.of(args[0]));
        final var protocol = mapper.readTree(protocolBytes);
        final int anchor = protocol.get("anchor_code_points").asInt();
        final boolean displaySpaces = protocol.has("anchor_space_equivalence")
                                      && protocol.get("anchor_space_equivalence").asString().equals("ascii_nbsp_only");
        require((anchor == ANCHOR && !protocol.has("anchor_space_equivalence") || anchor == 128 && displaySpaces)
                        && protocol.get("maximum_alignment_cells").asLong() == MAX_CELLS,
                "Fixed diagnostic policy");
        final var manifestBytes = bounded(Path.of(protocol.get("source_manifest").asString()));
        final var baselineBytes = bounded(Path.of(protocol.get("baseline_report").asString()));
        require(hash(manifestBytes).equals(protocol.get("source_manifest_sha256").asString()), "Source manifest");
        require(hash(baselineBytes).equals(protocol.get("baseline_report_sha256").asString()), "Baseline report");
        final var manifest = mapper.readTree(manifestBytes);
        final var baseline = mapper.readTree(baselineBytes);
        require(protocol.get("profiles").equals(mapper.valueToTree(List.of("LINES", "SOURCE_PARAGRAPHS_ASSEMBLED")))
                        || protocol.get("profiles")
                                .equals(mapper.valueToTree(List.of("LINES", "SOURCE_PARAGRAPHS_ASSEMBLED",
                                        "SOURCE_PARAGRAPHS_LINE_WRAPS_ASSEMBLED")))
                        || protocol.get("profiles")
                                .equals(mapper.valueToTree(
                                        List.of("LINES", "SOURCE_PARAGRAPHS_INLINE_SCRIPTS_ASSEMBLED"))),
                "Complete fixed profile set");
        require(manifest.get("cases").size() == protocol.get("source_documents").asInt(), "Document coverage");
        final var rows = new ArrayList<Map<String, Object>>();
        int changed = 0;
        int referenceCount = 0;
        for (final var source : manifest.get("cases")) {
            final var verified = new LinkedHashMap<String, byte[]>();
            for (final var input : source.get("inputs")) {
                final var bytes = bounded(Path.of(args[1], input.get("filename").asString()));
                require(bytes.length == input.get("bytes").asLong()
                                && hash(bytes).equals(input.get("sha256").asString()),
                        "Original source bytes");
                require(verified.put(input.get("filename").asString(), bytes) == null, "Unique source alias");
            }
            final var references = ScientificReadingEvaluation.references(verified.get(source.get("jats").asString()));
            require(source.get("reference")
                            .equals(mapper.valueToTree(ScientificReadingEvaluation.identity(references))),
                    "Full reference identity");
            referenceCount += references.size();
            final var pdf = verified.get(source.get("pdf").asString());
            for (final var profile : protocol.get("profiles")) {
                final String name = profile.asString();
                var strategy = PdfPartitioner.create(PdfOptions.defaults());
                final boolean assembled = name.equals("SOURCE_PARAGRAPHS_ASSEMBLED")
                                          || name.equals("SOURCE_PARAGRAPHS_LINE_WRAPS_ASSEMBLED")
                                          || name.equals("SOURCE_PARAGRAPHS_INLINE_SCRIPTS_ASSEMBLED");
                if (assembled) {
                    var paragraphs =
                            PdfTaggedParagraphOptions.defaults()
                                    .withWordWrap(name.equals("SOURCE_PARAGRAPHS_ASSEMBLED")
                                                          ? PdfTaggedParagraphOptions.WordWrap.SOURCE_SOFT_HYPHENS
                                                          : PdfTaggedParagraphOptions.WordWrap.SOURCE_LINE_WRAPS)
                                    .withPageBoundary(PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS);
                    if (name.equals("SOURCE_PARAGRAPHS_INLINE_SCRIPTS_ASSEMBLED")) {
                        paragraphs = paragraphs.withInlineScripts(
                                PdfTaggedParagraphOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
                    }
                    strategy = strategy.withTaggedParagraphs(paragraphs);
                } else {
                    require(name.equals("LINES"), "Declared profile");
                }
                String output = "";
                String outcome = "SUCCESS";
                try {
                    final var elements = DocWeave.create(PartitionerRegistry.of(List.of(strategy)))
                                                 .partition(DocumentSource.bytes(pdf, source.get("pdf").asString()))
                                                 .elements();
                    final var projected = assembled ? PdfParagraphAssembler.create().assemble(elements) : elements;
                    output = projected.stream()
                                     .filter(element -> element.type() != ElementType.Image)
                                     .map(element -> element.text())
                                     .collect(Collectors.joining("\n"));
                } catch (final PartitionException failure) {
                    outcome = failure.code().name();
                }
                final var strict = ScientificReadingEvaluation.score(references, output);
                final var previous = new ArrayList<JsonNode>();
                for (final var row : baseline.get("results")) {
                    if (row.get("id").equals(source.get("id")) && row.get("profile").asString().equals(name)) {
                        previous.add(row);
                    }
                }
                require(previous.size() == 1, "Unique baseline profile");
                boolean equal = previous.getFirst().get("outcome").asString().equals(outcome);
                for (final var field : strict.entrySet()) {
                    equal &= mapper.valueToTree(field.getValue()).equals(previous.getFirst().get(field.getKey()));
                }
                changed += equal ? 0 : 1;
                final String candidate = ScientificReadingEvaluation.normalize(output);
                final var diagnostics = new ArrayList<Map<String, Object>>();
                for (int ordinal = 0; ordinal < references.size(); ordinal++) {
                    final var diagnostic = new LinkedHashMap<>(
                            compare(references.get(ordinal).text(), candidate, anchor, displaySpaces));
                    diagnostic.put("ordinal", ordinal);
                    diagnostics.add(diagnostic);
                }
                final var row = new LinkedHashMap<String, Object>();
                row.put("id", source.get("id").asString());
                row.put("profile", name);
                row.put("outcome", outcome);
                row.put("strict_baseline_equal", equal);
                row.put("strict", strict);
                row.put("diagnostics", diagnostics);
                rows.add(row);
            }
        }
        require(referenceCount == protocol.get("reference_paragraphs_per_profile").asInt()
                        && rows.size() == protocol.get("source_documents").asInt() * protocol.get("profiles").size(),
                "All paragraph and profile denominators");
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("runtime", Runtime.version().toString(), "protocol_sha256", hash(protocolBytes),
                                "changed_strict_baselines", changed, "results", rows))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
        require(changed == 0, "Strict baseline changed; complete diagnostic report retained");
    }

    /**
     * Uses exact unique source prefix/suffix anchors only to locate a diagnostic span after extraction.
     * Alignment is not an accuracy score or a reference repair. Missing/duplicate/reversed anchors remain explicit.
     * @param reference complete normalized reference paragraph
     * @param candidate complete normalized document output
     * @return numeric status and optional bounded edit trace, without publisher text
     */
    static Map<String, Object> compare(final String reference, final String candidate) {
        return compare(reference, candidate, ANCHOR, false);
    }

    /**
     * Locates with a frozen anchor policy while tracing original unmodified code points.
     * Optional ASCII/NBSP equivalence applies only to anchors and preserves UTF16 offsets;
     * it never changes strict matching, extracted text, traced spans or reference identities.
     * @param reference original normalized reference
     * @param candidate complete independently extracted output
     * @param anchor fixed protocol-selected code-point length
     * @param displaySpaces whether anchors alone treat ASCII space and NBSP alike
     * @return complete numeric diagnostic retaining ambiguity and original edit accounting
     */
    private static Map<String, Object> compare(
            final String reference, final String candidate, final int anchor, final boolean displaySpaces) {
        final var result = new LinkedHashMap<String, Object>();
        final int points = reference.codePointCount(0, reference.length());
        result.put("reference_code_points", points);
        final int exact = occurrences(candidate, reference);
        result.put("exact_occurrences", exact);
        if (exact > 0) {
            result.put("status", exact == 1 ? "EXACT" : "DUPLICATED_EXACT");
            return result;
        }
        if (points < 2 * anchor) {
            result.put("status", "SHORT_REFERENCE");
            return result;
        }
        final String search = displaySpaces ? candidate.replace('\u00a0', ' ') : candidate;
        final String anchors = displaySpaces ? reference.replace('\u00a0', ' ') : reference;
        final String prefix = anchors.substring(0, anchors.offsetByCodePoints(0, anchor));
        final String suffix = anchors.substring(anchors.offsetByCodePoints(anchors.length(), -anchor));
        final int prefixes = occurrences(search, prefix);
        final int suffixes = occurrences(search, suffix);
        result.put("prefix_occurrences", prefixes);
        result.put("suffix_occurrences", suffixes);
        if (prefixes != 1 || suffixes != 1) {
            result.put("status", "MISSING_OR_AMBIGUOUS_ANCHORS");
            return result;
        }
        final int start = search.indexOf(prefix);
        final int suffixStart = search.indexOf(suffix);
        if (suffixStart < start + prefix.length()) {
            result.put("status", "REVERSED_OR_OVERLAPPING_ANCHORS");
            return result;
        }
        final int end = suffixStart + suffix.length();
        final int actualPoints = candidate.codePointCount(start, end);
        result.put("candidate_span_code_points", actualPoints);
        result.put("candidate_start_utf16", start);
        result.put("candidate_end_utf16", end);
        if ((points + 1L) * (actualPoints + 1L) > MAX_CELLS) {
            result.put("status", "ALIGNMENT_BUDGET_EXCEEDED");
            return result;
        }
        result.put("status", "UNIQUE_ANCHOR_DIAGNOSTIC");
        result.putAll(trace(reference.codePoints().toArray(), candidate.substring(start, end).codePoints().toArray()));
        return result;
    }

    /**
     * Computes one deterministic optimal character edit trace without changing source or candidate.
     * Tie counts describe visited trace steps only, not uniqueness of every possible alignment.
     * @param expected original reference code points
     * @param actual independently anchored output code points
     * @return operation/category counts, not causal error labels or normalized accuracy
     */
    private static Map<String, Object> trace(final int[] expected, final int[] actual) {
        final int width = actual.length + 1;
        final int[] cells = new int[(expected.length + 1) * width];
        for (int row = 0; row <= expected.length; row++) {
            cells[row * width] = row;
        }
        for (int column = 0; column <= actual.length; column++) {
            cells[column] = column;
        }
        for (int row = 1; row <= expected.length; row++) {
            for (int column = 1; column <= actual.length; column++) {
                final int diagonal =
                        cells[(row - 1) * width + column - 1] + (expected[row - 1] == actual[column - 1] ? 0 : 1);
                cells[row * width + column] = Math.min(
                        diagonal, Math.min(cells[(row - 1) * width + column] + 1, cells[row * width + column - 1] + 1));
            }
        }
        int row = expected.length;
        int column = actual.length;
        int insertions = 0;
        int deletions = 0;
        int substitutions = 0;
        int nonbreakingSpaces = 0;
        int spacesAfterHyphens = 0;
        int hyphenInsertions = 0;
        int hyphenDeletions = 0;
        int ties = 0;
        while (row > 0 || column > 0) {
            final int current = cells[row * width + column];
            final boolean diagonal = row > 0 && column > 0
                                     && current
                                                == cells[(row - 1) * width + column - 1]
                                                           + (expected[row - 1] == actual[column - 1] ? 0 : 1);
            final boolean delete = row > 0 && current == cells[(row - 1) * width + column] + 1;
            final boolean insert = column > 0 && current == cells[row * width + column - 1] + 1;
            ties += (diagonal ? 1 : 0) + (delete ? 1 : 0) + (insert ? 1 : 0) > 1 ? 1 : 0;
            if (diagonal) {
                if (expected[row - 1] != actual[column - 1]) {
                    substitutions++;
                    if ((expected[row - 1] == ' ' && actual[column - 1] == 0xa0)
                            || (expected[row - 1] == 0xa0 && actual[column - 1] == ' ')) {
                        nonbreakingSpaces++;
                    }
                }
                row--;
                column--;
            } else if (delete) {
                hyphenDeletions += expected[row - 1] == '-' ? 1 : 0;
                deletions++;
                row--;
            } else {
                require(insert, "Optimal trace predecessor");
                hyphenInsertions += actual[column - 1] == '-' ? 1 : 0;
                spacesAfterHyphens += column > 1 && actual[column - 1] == ' ' && actual[column - 2] == '-' ? 1 : 0;
                insertions++;
                column--;
            }
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("diagnostic_character_edits", cells[cells.length - 1]);
        result.put("trace_insertions", insertions);
        result.put("trace_deletions", deletions);
        result.put("trace_substitutions", substitutions);
        result.put("trace_space_nbsp_substitutions", nonbreakingSpaces);
        result.put("trace_spaces_inserted_after_hyphen", spacesAfterHyphens);
        result.put("trace_hyphen_insertions", hyphenInsertions);
        result.put("trace_hyphen_deletions", hyphenDeletions);
        result.put("trace_steps_with_multiple_optimal_predecessors", ties);
        require(insertions + deletions + substitutions == cells[cells.length - 1], "Complete trace accounting");
        return result;
    }

    /**
     * Counts every occurrence, including overlaps, without best-match selection.
     * @param document full candidate text
     * @param needle nonempty exact anchor or paragraph
     * @return complete occurrence count
     */
    private static int occurrences(final String document, final String needle) {
        require(!needle.isEmpty(), "Nonempty source text");
        int count = 0;
        for (int index = document.indexOf(needle); index >= 0; index = document.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    /** Checks exactness, full edit accounting, Unicode, ambiguous anchors and unchanged strict scoring. */
    private static void controls() {
        final String left = "A".repeat(ANCHOR);
        final String right = "Z".repeat(ANCHOR);
        final String expected = left + " alpha-beta gamma 😀 " + right;
        require(compare(expected, expected).get("status").equals("EXACT"), "Exact control");
        final var spaces = compare(expected, left + " alpha- beta\u00a0gamma 😀 " + right);
        require(spaces.get("diagnostic_character_edits").equals(2)
                        && spaces.get("trace_space_nbsp_substitutions").equals(1)
                        && spaces.get("trace_spaces_inserted_after_hyphen").equals(1),
                "Physical whitespace control");
        final var hyphen = compare(left + " alphabeta " + right, left + " alpha-beta " + right);
        require(hyphen.get("trace_hyphen_insertions").equals(1), "Inserted physical hyphen");
        final var deletion = compare(left + " alpha-beta " + right, left + " alphabeta " + right);
        require(deletion.get("trace_hyphen_deletions").equals(1), "Deleted physical hyphen");
        require(compare(expected, expected + expected).get("status").equals("DUPLICATED_EXACT"), "Duplicate exact");
        require(compare(expected, "").get("status").equals("MISSING_OR_AMBIGUOUS_ANCHORS"), "Missing output");
        require(compare(expected, left + " X " + right + left).get("status").equals("MISSING_OR_AMBIGUOUS_ANCHORS"),
                "Duplicate anchor");
        require(compare(expected, right + " X " + left).get("status").equals("REVERSED_OR_OVERLAPPING_ANCHORS"),
                "Reversed anchors");
        require(compare("short", "different").get("status").equals("SHORT_REFERENCE"), "Short reference");
        require(compare(left + "B".repeat(4000) + right, left + "C".repeat(4000) + right)
                        .get("status")
                        .equals("ALIGNMENT_BUDGET_EXCEEDED"),
                "Prospective cell budget");
        final var strict =
                ScientificReadingEvaluation.score(List.of(new ScientificReadingEvaluation.Paragraph("owned", expected)),
                        left + " alpha- beta\u00a0gamma 😀 " + right);
        require(strict.get("uniquely_matched_paragraphs").equals(0), "Diagnostics cannot repair exact score");
        final String longLeft = "A".repeat(60) + "\u00a0"
                                + "😀".repeat(67);
        final String longRight = "Z".repeat(128);
        final String original = longLeft + " middle " + longRight;
        final String rendered = original.replace('\u00a0', ' ');
        final var located = compare(original, "leading " + rendered, 128, true);
        require(located.get("status").equals("UNIQUE_ANCHOR_DIAGNOSTIC") && located.get("exact_occurrences").equals(0)
                        && located.get("candidate_start_utf16").equals(8)
                        && located.get("candidate_end_utf16").equals(8 + rendered.length())
                        && located.get("trace_space_nbsp_substitutions").equals(1)
                        && located.get("diagnostic_character_edits").equals(1),
                "Anchor-only space equivalence preserves original Unicode and offsets");
        require(compare(original, rendered + " " + longLeft, 128, true)
                        .get("status")
                        .equals("MISSING_OR_AMBIGUOUS_ANCHORS"),
                "Space-equivalent duplicate anchors remain ambiguous");
        require(compare(original, "", 128, true).get("status").equals("MISSING_OR_AMBIGUOUS_ANCHORS"),
                "Long anchor missing output remains explicit");
    }

    /**
     * Limits complete protocol/source/report bytes before parsing or extraction.
     * @param path caller-selected local input
     * @return complete bounded bytes
     * @throws Exception if unavailable or larger than32MiB
     */
    private static byte[] bounded(final Path path) throws Exception {
        require(Files.size(path) <= 32_000_000, "Bounded source input");
        return Files.readAllBytes(path);
    }

    /**
     * Binds exact source/protocol bytes without exporting their content.
     * @param bytes complete original bytes
     * @return lowercase SHA-256
     * @throws Exception if mandatory SHA-256 support is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Fails provenance/owned controls instead of repairing inputs or dropping observations.
     * @param condition required invariant
     * @param message non-publisher diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
