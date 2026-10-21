package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Whole-original native compound-wrap preservation check, separate from publisher caption scoring. */
public final class CompareNativeCompoundText {
    /** Prevents standalone utility construction. */
    private CompareNativeCompoundText() {
    }

    /**
     * Retains all five original inputs and three uniform profiles, including typed extraction failures.
     * Each pair must preserve element types/order, all metadata except two declared wrap fields and
     * every text character after removing only ASCII space. The text length decrease must equal the
     * declared omitted-separator count; this does not independently prove that every space was generated.
     * Exact native ownership and explicit-source-space controls establish that separate requirement.
     * @param args private four-column source TSV and NEW numeric report path
     * @throws Exception if source pins or fresh output requirements fail, or a completed comparison has failures
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 2 && !Files.exists(Path.of(args[1])), "Expected source TSV and NEW report");
        final var inputs = Files.readAllLines(Path.of(args[0]));
        require(inputs.size() == 5, "Incomplete original source population");
        final var identities = new java.util.HashSet<String>();
        final var frozen = new LinkedHashMap<String, tools.jackson.databind.JsonNode>();
        final var mapper = JsonMapper.builder().build();
        for (final String file : List.of("positive-java21.json", "peerj-java21.json", "scirep-java21.json")) {
            final var baseline =
                    mapper.readTree(Files.readAllBytes(Path.of("benchmarks/review/pdf-edge-aligned-captions", file)))
                            .get("results");
            for (final var record : baseline) {
                require(frozen.put(record.get("case").asString() + "/" + record.get("profile").asString(), record)
                                == null,
                        "Duplicate frozen baseline profile");
            }
        }
        require(frozen.size() == 15, "Incomplete frozen baseline");
        final var results = new ArrayList<Map<String, Object>>();
        boolean valid = true;
        for (final String input : inputs) {
            final var fields = input.split("\t", -1);
            require(fields.length == 4 && fields[0].matches("[a-z][a-z0-9_-]{0,30}") && identities.add(fields[0]),
                    "Invalid original source row");
            final var path = Path.of(fields[1]);
            require(Files.size(path) == Long.parseLong(fields[2]) && Files.size(path) <= 16 * 1024 * 1024,
                    "Original source length changed");
            final byte[] bytes = Files.readAllBytes(path);
            require(hash(bytes).equals(fields[3]), "Original source identity changed");
            for (final String profile : List.of("NATIVE_LINES", "NATIVE_COLUMNS", "SOURCE_TAGGED_LINES")) {
                final var row = new LinkedHashMap<String, Object>();
                row.put("case", fields[0]);
                row.put("profile", profile);
                row.put("source_sha256", fields[3]);
                try {
                    final var before = processor(profile, false)
                                               .partition(DocumentSource.bytes(bytes, path.getFileName().toString()))
                                               .elements();
                    final var after = processor(profile, true)
                                              .partition(DocumentSource.bytes(bytes, path.getFileName().toString()))
                                              .elements();
                    final var baseline = frozen.get(fields[0] + "/" + profile);
                    require(baseline != null, "Unknown original/profile");
                    final String previousText = ScientificReadingEvaluation.normalize(
                            String.join("\n", before.stream()
                                                      .filter(element -> element.type() != ElementType.Image)
                                                      .map(element -> element.text())
                                                      .toList()));
                    final String previousCaptions = ScientificReadingEvaluation.normalize(
                            String.join("\n", before.stream()
                                                      .filter(element -> element.type() == ElementType.FigureCaption)
                                                      .map(element -> element.text())
                                                      .toList()));
                    final boolean frozenText =
                            hash(previousText.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                    .equals(baseline.get("all_text_presence").get("candidate_sha256").asString());
                    final boolean frozenCaptions =
                            hash(previousCaptions.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                    .equals(baseline.get("classified_caption_presence")
                                                    .get("candidate_sha256")
                                                    .asString())
                            && before.stream().filter(element -> element.type() == ElementType.FigureCaption).count()
                                       == baseline.get("classified_caption_elements").asInt();
                    boolean exact = before.size() == after.size() && frozenText && frozenCaptions;
                    int changed = 0;
                    int omitted = 0;
                    for (int index = 0; index < Math.min(before.size(), after.size()); index++) {
                        final var previous = before.get(index);
                        final var candidate = after.get(index);
                        final var metadata = new LinkedHashMap<>(candidate.metadata());
                        final int joins =
                                ((Number) metadata.getOrDefault("pdf_native_retained_hyphen_wraps", 0)).intValue();
                        if (joins > 0) {
                            exact &= "retained_hyphen_wraps_v1".equals(metadata.get("pdf_native_word_wrap_policy"));
                            metadata.remove("pdf_native_retained_hyphen_wraps");
                            metadata.remove("pdf_native_word_wrap_policy");
                        }
                        exact &= previous.type() == candidate.type() && previous.metadata().equals(metadata)
                                 && previous.text().replace(" ", "").equals(candidate.text().replace(" ", ""))
                                 && previous.text().length() - candidate.text().length() == joins;
                        if (previous.text().equals(candidate.text())) {
                            exact &= previous.equals(candidate);
                        } else {
                            changed++;
                        }
                        omitted += joins;
                    }
                    row.put("outcome", "SUCCESS");
                    row.put("baseline_element_count", before.size());
                    row.put("candidate_element_count", after.size());
                    row.put("frozen_baseline_text_exact", frozenText);
                    row.put("frozen_baseline_caption_text_exact", frozenCaptions);
                    row.put("changed_text_units", changed);
                    row.put("declared_omitted_separators", omitted);
                    row.put("all_preservation_invariants", exact);
                    row.put("ordered_non_space_text_sha256",
                            hash(String.join("\n", after.stream()
                                                           .filter(element -> element.type() != ElementType.Image)
                                                           .map(element -> element.text().replace(" ", ""))
                                                           .toList())
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    valid &= exact;
                } catch (final PartitionException failure) {
                    row.put("outcome", failure.code().name());
                    valid = false;
                } catch (final IOException failure) {
                    row.put("outcome", "IO_FAILURE");
                    valid = false;
                }
                results.add(row);
            }
        }
        require(results.size() == 15, "Incomplete original/profile population");
        Files.writeString(Path.of(args[1]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "All fixed source/profile pairs; ordered non-space characters, geometry, metadata, unit counts and declared separator accounting; not caption correctness or independent generated-space ground truth",
                        "runtime", Runtime.version().toString(), "source_tsv_sha256",
                        hash(Files.readAllBytes(Path.of(args[0]))), "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
        require(valid, "Original preservation comparison failed; all outcomes retained");
    }

    /**
     * Uses the same public factories and caption policy with only the selected native column interpretation changed.
     * @param profile fixed native or source-table profile
     * @param compounds whether native column paragraphs may omit proven generated separators
     * @return fresh document service without providers or reference text
     */
    private static DocWeave processor(final String profile, final boolean compounds) {
        final var layout = profile.equals("NATIVE_COLUMNS")
                                   ? (compounds ? PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS
                                                : PdfOptions.TextLayout.COLUMN_PARAGRAPHS)
                                   : PdfOptions.TextLayout.LINES;
        final var options = PdfOptions.defaults().withTextLayout(layout).withFigureCaptions(
                PdfOptions.FigureCaptions.IMAGE_EDGE_ALIGNED_NUMBERED_TITLES);
        final var partitioner = profile.equals("SOURCE_TAGGED_LINES") ? PdfPartitioner.createWithTaggedTables(options)
                                                                      : PdfPartitioner.create(options);
        return DocWeave.create(PartitionerRegistry.of(List.of(partitioner)));
    }

    /**
     * Returns a source/report identity without exposing document content.
     * @param bytes complete original data
     * @return lowercase SHA256
     * @throws Exception if the mandatory digest cannot be initialized
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Enforces fixed scope, source identities and complete verification without partial score credit.
     * @param condition required invariant
     * @param message content-free failure context
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
