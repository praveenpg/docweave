package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionResult;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Direct public-API style audit; counts/hashes only, with source/derivative evidence clearly separated. */
final class StyleCorpusReview {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StyleCorpusReview() {
    }

    /** Runs a hash-pinned manifest offline; temporary derivatives are always removed. */
    static Map<String, Object> run(final Path manifestPath, final Path cache) throws IOException {
        final byte[] manifestBytes = Files.readAllBytes(manifestPath);
        final var manifest = JSON.readTree(manifestBytes);
        final var documents = new ArrayList<Map<String, Object>>();
        boolean passed = true;
        final var probe = DocxStyleProbe.create();
        for (final var contract : DefaultWordEvidenceComparator.array(manifest, "documents")) {
            final var input = CorpusReview.input(contract, cache);
            final Path work = Files.createTempDirectory("docweave-style-");
            try {
                final Path source = work.resolve("source.docx");
                Files.write(source, input.bytes());
                final var inventory = probe.inspect(source);
                final boolean hasText =
                        ((Number) inventory.get("stored_table_paragraph_visits_nonblank")).intValue() > 0;
                final var original = summarize(DocWeave.create().partition(DocumentSource.of(source)));
                final var probes = new LinkedHashMap<String, Object>();
                for (final var mode : DocxStyleProbe.Mode.values()) {
                    final byte[] bytes = probe.derive(source, mode);
                    final Path derived = work.resolve(mode.name().toLowerCase(Locale.ROOT) + ".docx");
                    Files.write(derived, bytes);
                    final var observed = summarize(DocWeave.create().partition(DocumentSource.of(derived)));
                    final var checks = checks(original, observed, mode, hasText);
                    passed &= checks.values().stream().allMatch(Boolean::booleanValue);
                    probes.put(mode.name().toLowerCase(Locale.ROOT),
                            Map.of("kind", "instrumented derivative, not an unchanged external sample", "input_sha256",
                                    DocxOwnership.sha256(bytes), "observed", observed, "checks", checks,
                                    "positive_text_probe_applicable", hasText));
                }
                documents.add(Map.of("filename", input.filename(), "source_sha256", input.sha256(), "source_inventory",
                        inventory, "unchanged_original", original, "derived_probes", probes));
            } finally {
                try (final var paths = Files.walk(work)) {
                    for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
            }
        }
        return Map.of("review",
                "Automated source inventory/metamorphic diagnostics; independent human/Word-rendered review pending",
                "probe_version", 2, "manifest_sha256", DocxOwnership.sha256(manifestBytes), "validation_sources",
                ValidationProvenance.sources(), "runtime_classpath_entries", ValidationProvenance.classpath(),
                "java_version", Runtime.version().toString(), "checks_passed", passed, "documents", documents);
    }

    /** Aggregates metadata; the historical Python text/type hash encoding uses comma/colon spacing. */
    static Map<String, Object> summarize(final PartitionResult extracted) {
        final var types = new TreeMap<String, Integer>();
        final var outlines = new TreeMap<String, Integer>();
        final var regions = new TreeMap<String, Integer>();
        final var pairs = new ArrayList<String>();
        int ranges = 0;
        for (final var element : extracted.elements()) {
            types.merge(element.type().name(), 1, Integer::sum);
            pairs.add("[" + JSON.writeValueAsString(element.type().name()) + ", "
                      + JSON.writeValueAsString(element.text()) + "]");
            if (element.metadata().get("docx_table_style_paragraphs") instanceof List<?> paragraphs) {
                for (final var paragraph : paragraphs) {
                    final var range = (Map<?, ?>) paragraph;
                    ranges++;
                    if (range.containsKey("outline_level")) {
                        outlines.merge(range.get("outline_level").toString(), 1, Integer::sum);
                    }
                    if (range.get("regions") instanceof List<?> memberships) {
                        memberships.forEach(region -> regions.merge(region.toString(), 1, Integer::sum));
                    }
                }
            }
        }
        final String textAndTypes = pairs.stream().collect(Collectors.joining(", ", "[", "]"));
        return Map.of("elements", extracted.elements().size(), "types", Map.copyOf(types), "text_and_type_sha256",
                DocxOwnership.sha256(textAndTypes.getBytes(StandardCharsets.UTF_8)), "style_ranges", ranges,
                "outline_levels", Map.copyOf(outlines), "region_memberships", Map.copyOf(regions), "warnings",
                extracted.warnings()
                        .stream()
                        .map(w -> Map.of("code", w.code().name(), "message", w.message()))
                        .toList());
    }

    /** Metamorphic invariants compare the Java parser with itself; shared defects can go undetected. */
    static Map<String, Boolean> checks(final Map<String, Object> original, final Map<String, Object> derived,
            final DocxStyleProbe.Mode mode, final boolean hasText) {
        final var checks = new LinkedHashMap<String, Boolean>();
        checks.put("text_and_types_unchanged",
                original.get("text_and_type_sha256").equals(derived.get("text_and_type_sha256")));
        checks.put("no_probe_warnings", ((List<?>) derived.get("warnings")).isEmpty());
        final int ranges = ((Number) derived.get("style_ranges")).intValue();
        final var levels = ((Map<?, ?>) derived.get("outline_levels")).keySet();
        if (!hasText) {
            checks.put("empty_source_has_no_style_ranges", ranges == 0);
        }
        if (mode == DocxStyleProbe.Mode.ENABLED && hasText) {
            checks.put("conditional_outline_exercised", levels.stream().anyMatch(level -> !"9".equals(level)));
        } else if (mode == DocxStyleProbe.Mode.DISABLED) {
            checks.put("only_whole_table_outline", levels.stream().allMatch("9" ::equals));
        } else if (mode == DocxStyleProbe.Mode.DIRECT_OVERRIDE) {
            if (hasText) {
                checks.put("conditional_outline_survives_direct_body_override",
                        levels.stream().anyMatch(level -> !"9".equals(level)));
            }
        }
        return Map.copyOf(checks);
    }
}
