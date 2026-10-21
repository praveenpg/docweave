package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.DocxOptions;
import dev.docweave.partition.PartitionOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Development-only corpus runner using public Java services directly, without a child process.
 * Source text is confined to memory. Reports contain counts, hashes and structural paths.
 * A completed report is written even on contract failure; malformed/unpinned inputs reject before extraction.
 */
final class CorpusReview {
    /** Historical global substring diagnostics and stricter ownership diagnostics remain distinct. */
    enum Baseline { OWNERSHIP, SUBSTRING }

    static final Path OWNED = Path.of("benchmarks/review/docx-paragraph-ownership");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Stable multiset order keeps repeated diagnostics significant while ignoring traversal order. */
    private static final Comparator<Map<String, String>> WARNING_ORDER =
            Comparator.comparing((Map<String, String> warning) -> warning.get("code"))
                    .thenComparing(warning -> warning.get("message"));

    /** Hash-verified input bytes are cloned to prevent mutation after verification. */
    record Input(String filename, String sha256, byte[] bytes, JsonNode contract) {
        Input {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** Validates portable basenames, byte sizes and exact source hashes before parser invocation. */
    static Input input(final JsonNode contract, final Path cache) throws IOException {
        final String name = contract.get("filename").asString();
        if (name.contains("/") || name.contains("\\") || !name.endsWith(".docx") || name.equals(".docx")) {
            throw new IOException("Corpus filenames must be plain .docx basenames");
        }
        final Path path = cache.resolve(name);
        final byte[] bytes = Files.readAllBytes(path);
        final String hash = DocxOwnership.sha256(bytes);
        if (!hash.equals(contract.get("sha256").asString())) {
            throw new IOException("Corpus input hash mismatch: " + name);
        }
        if (contract.has("bytes") && contract.get("bytes").asLong() != bytes.length) {
            throw new IOException("Corpus input size mismatch: " + name);
        }
        return new Input(name, hash, bytes, contract);
    }

    /**
     * Runs defaults and stored-part policies on each pinned input; source owners are read independently.
     * @param manifestPath explicit manifest; external sources must already exist in the local cache
     * @param cache basename-only input directory; no download is performed
     * @return text-free report; checks_passed is false on any contract diagnostic failure
     * @throws IOException on invalid manifest/input/XML or extraction failure
     */
    static Map<String, Object> run(final Path manifestPath, final Path cache) throws IOException {
        return run(manifestPath, cache, Baseline.OWNERSHIP, null);
    }

    /** Optional upstream references apply only to the historical substring baseline, not ownership mappings. */
    static Map<String, Object> run(final Path manifestPath, final Path cache, final Baseline baseline,
            final Path referencePath) throws IOException {
        if (referencePath != null && baseline != Baseline.SUBSTRING) {
            throw new IOException(
                    "Upstream references require the substring baseline; ownership mappings are undefined");
        }
        final var reference = referencePath == null ? null : CorpusReference.read(referencePath);
        final byte[] manifestBytes = Files.readAllBytes(manifestPath);
        final var manifest = JSON.readTree(manifestBytes);
        if (!manifest.has("documents") || !manifest.get("documents").isArray() || manifest.get("documents").isEmpty()) {
            throw new IOException("Corpus manifest requires nonempty documents");
        }
        final var validator = DocxOwnership.create();
        final var documents = new ArrayList<Map<String, Object>>();
        boolean passed = true;
        for (final var contract : manifest.get("documents")) {
            final var input = input(contract, cache);
            final var snapshot = DocxCorpusSource.create().inspect(cache.resolve(input.filename()));
            CorpusFeatureChecks.source(snapshot, contract);
            final var omissions = contract.has("required_source_omissions")
                                          ? CorpusOmissionSource.inspect(cache.resolve(input.filename()))
                                          : Map.<String, Object>of();
            if (contract.has("required_source_omissions")
                    && !JSON.valueToTree(omissions).equals(contract.get("required_source_omissions"))) {
                throw new IOException("Corpus omission source evidence differs from manifest");
            }
            final var expectedDetails = contract.has("expected_warnings") ? expectedWarnings(contract) : null;
            final var owners = baseline == Baseline.OWNERSHIP ? validator.read(cache.resolve(input.filename()))
                                                              : List.<DocxOwnership.Owner>of();
            final var paragraphs = baseline == Baseline.SUBSTRING ? snapshot.paragraphs() : List.<String>of();
            if (contract.has("minimum_deleted_fragments_checked") && baseline != Baseline.OWNERSHIP) {
                throw new IOException("Deleted-fragment checks require accepted-source ownership");
            }
            final var deleted = contract.has("minimum_deleted_fragments_checked")
                                        ? CorpusFeatureChecks.deletedFragments(
                                                  XmlOwnerReader.document(cache.resolve(input.filename())), owners)
                                        : List.<String>of();
            final var referenceMetrics = reference == null
                                                 ? Map.<String, Object>of()
                                                 : reference.metrics(input.filename(), input.sha256(), paragraphs);
            // Recheck after the independent ZIP/XML read so a changed cache cannot mix input versions.
            if (!input.sha256().equals(DocxOwnership.sha256(Files.readAllBytes(cache.resolve(input.filename()))))) {
                throw new IOException("Corpus input changed during source review");
            }
            final var modes = new LinkedHashMap<String, Object>();
            for (final boolean include : List.of(false, true)) {
                final var defaults = DocxOptions.defaults();
                final var options = new DocxOptions(defaults.maxArchiveEntries(), defaults.maxExpandedBytes(),
                        defaults.maxEntryBytes(), defaults.maxXmlDepth(), defaults.maxXmlNodes(), include,
                        defaults.tableLimits(), defaults.contentOptions());
                final var partitionOptions = PartitionOptions.builder().docxOptions(options).build();
                final var result = DocWeave.create().partition(
                        DocumentSource.bytes(input.bytes(), input.filename()), partitionOptions);
                final var featureChecks = new LinkedHashMap<String, Map<String, Object>>();
                if (contract.has("expected_nested_descriptors")) {
                    featureChecks.put("preservation",
                            CorpusFeatureChecks.preservation(input, options, result,
                                    CorpusFeatureChecks.count(contract, "expected_nested_descriptors", 0, 100000)));
                }
                if (contract.has("minimum_deleted_fragments_checked")) {
                    featureChecks.put("deleted_text", CorpusFeatureChecks.deletedText(deleted, result.elements(),
                                                              CorpusFeatureChecks.count(contract,
                                                                      "minimum_deleted_fragments_checked", 1, 100000)));
                }
                final var ownership =
                        baseline == Baseline.OWNERSHIP ? validator.check(result.elements(), owners) : null;
                final var legacy =
                        baseline == Baseline.SUBSTRING
                                ? DocxCorpusSource.metrics(
                                          result.elements().stream().map(DocxCorpusSource.TextElement::from).toList(),
                                          paragraphs)
                                : Map.<String, Object>of();
                final var types = new TreeMap<String, Long>();
                result.elements().forEach(element -> types.merge(element.type().name(), 1L, Long::sum));
                final String text = DocxOwnership.normalize(
                        String.join(" ", result.elements().stream().map(Element::text).toList()));
                final var anchors = new LinkedHashMap<String, Boolean>();
                for (final var anchor : contract.get("required_text")) {
                    anchors.put(anchor.asString(), text.contains(anchor.asString()));
                }
                final var expectedWarnings = new ArrayList<String>();
                if (contract.has("expected_warning_codes")) {
                    contract.get("expected_warning_codes").forEach(code -> expectedWarnings.add(code.asString()));
                }
                final var warnings = result.warnings().stream().map(w -> w.code().name()).sorted().toList();
                final boolean warningMatch = warnings.equals(expectedWarnings.stream().sorted().toList());
                final var warningDetails =
                        result.warnings()
                                .stream()
                                .map(warning -> Map.of("code", warning.code().name(), "message", warning.message()))
                                .sorted(WARNING_ORDER)
                                .toList();
                final boolean detailsMatch = expectedDetails == null || expectedDetails.equals(warningDetails);
                boolean storedMatch = true;
                for (final String kind : List.of("Header", "Footer")) {
                    if (include && baseline == Baseline.SUBSTRING
                            && (!contract.has("expected_stored_parts")
                                    || !contract.get("expected_stored_parts").has(kind))) {
                        continue; // Preserve the legacy check's optional stored-part expectations.
                    }
                    final long expected = include && contract.has("expected_stored_parts")
                                                          && contract.get("expected_stored_parts").has(kind)
                                                  ? contract.get("expected_stored_parts").get(kind).asLong()
                                                  : 0;
                    storedMatch &= types.getOrDefault(kind, 0L) == expected;
                }
                final boolean modePassed =
                        (ownership == null ? (int) legacy.get("body_paragraphs_unmatched") == 0 : ownership.passed())
                        && warningMatch && detailsMatch && storedMatch
                        && types.getOrDefault(ElementType.Table.name(), 0L) >= contract.get("minimum_tables").asInt()
                        && anchors.values().stream().allMatch(Boolean::booleanValue)
                        && featureChecks.values().stream().allMatch(
                                check -> Boolean.TRUE.equals(check.get("checks_passed")));
                final var summary = new LinkedHashMap<String, Object>();
                if (ownership != null) {
                    summary.put("ownership", ownership.json());
                } else {
                    summary.putAll(legacy);
                }
                if (!featureChecks.isEmpty()) {
                    summary.put("feature_checks", featureChecks);
                }
                summary.put("element_count", result.elements().size());
                summary.put("types", types);
                summary.put("text_code_points",
                        result.elements().stream().mapToInt(e -> e.text().codePointCount(0, e.text().length())).sum());
                summary.put("required_text_present", anchors);
                summary.put("warning_codes", warnings);
                summary.put("warnings", warningDetails);
                if (expectedDetails != null) {
                    summary.put("warning_details_match", detailsMatch);
                }
                summary.put("warning_codes_match", warningMatch);
                summary.put("stored_part_counts_match", storedMatch);
                summary.put("checks_passed", modePassed);
                modes.put(include ? "include_headers_footers" : "defaults", summary);
                passed &= modePassed;
            }
            final var document = new LinkedHashMap<String, Object>();
            document.put("filename", input.filename());
            document.put("sha256", input.sha256());
            document.put("source_tables", snapshot.body().getOrDefault("tables", 0));
            document.put("inventory", snapshot.inventory());
            if (contract.has("required_source_omissions")) {
                document.put("source_omission_evidence", omissions);
            }
            document.put("java", modes);
            if (reference != null) {
                document.put("upstream", referenceMetrics);
            }
            documents.add(document);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("implementation", "docweave-validation Java/JUnit corpus diagnostics");
        report.put("comparison_mode", reference == null ? "candidate_only" : "upstream_reference");
        report.put("paragraph_baseline", baseline == Baseline.OWNERSHIP ? "ownership-v1" : "substring");
        report.put("scope", manifest.get("scope").asString());
        report.put("metric_limit",
                "Stored body/text-box ownership and disjoint normalized substrings only; "
                        + "no exact-text, table cell/order, glyph, layout or rendering claim. Human review pending.");
        report.put("manifest_sha256", DocxOwnership.sha256(manifestBytes));
        report.put("java_version", Runtime.version().toString());
        report.put("java_vendor", System.getProperty("java.vendor"));
        report.put("validation_sources", ValidationProvenance.sources());
        report.put("runtime_classpath", ValidationProvenance.classpath());
        report.put("checks_passed", passed);
        report.put("documents", documents);
        if (baseline == Baseline.SUBSTRING) {
            report.put("metric_limit",
                    "Substring presence after whitespace normalization does not prove multiplicity, order, ownership, layout or correct categories. Human review pending.");
        }
        if (reference != null) {
            report.put("upstream_reference_sha256", reference.sha256());
            report.put("upstream_provenance", reference.provenance());
        }
        return report;
    }

    /** Reads exact code/message expectations, retaining duplicates as part of the diagnostic contract. */
    private static List<Map<String, String>> expectedWarnings(final JsonNode contract) throws IOException {
        final var result = new ArrayList<Map<String, String>>();
        for (final var warning : DefaultWordEvidenceComparator.array(contract, "expected_warnings")) {
            result.add(Map.of("code", DefaultWordEvidenceComparator.text(warning, "code"), "message",
                    DefaultWordEvidenceComparator.text(warning, "message")));
        }
        return result.stream().sorted(WARNING_ORDER).toList();
    }

    /** Exclusive creation protects historical evidence; default Maven reports have unique names. */
    static void write(final Path output, final Object report) throws IOException {
        final Path parent = output.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    static Path defaultOutput() {
        return Path.of("docweave-validation/target/docx-ownership/report-" + UUID.randomUUID() + ".json");
    }
}
