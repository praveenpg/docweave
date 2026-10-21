package dev.docweave.validation;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Freezes publisher-only paragraph selections before any candidate extraction or scoring. */
public final class FreezeScientificReading {
    /** Prevents utility instantiation. */
    private FreezeScientificReading() {
    }

    /**
     * Verifies existing PDF/XML pins and freezes the same ordinal source rule for all three original papers.
     * Input cache files use recent/next/elife PDF/XML names; original bytes must match earlier source manifests.
     * This tool never invokes DocWeave, OCR or layout inference, and writes no reference transcription.
     * @param args project root, explicit source cache and output evidence directory
     * @throws Exception if source identities, reference contracts or fresh output creation fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected project root, verified cache and fresh evidence directory");
        }
        final var project = Path.of(args[0]);
        final var cache = Path.of(args[1]);
        final var output = Path.of(args[2]);
        final var mapper = JsonMapper.builder().build();
        final var definitions =
                List.of(List.of("recent", "benchmarks/review/pdf-recent-scientific-headers/manifest.json"),
                        List.of("next", "benchmarks/review/pdf-scientific-next-source/manifest.json"),
                        List.of("elife", "benchmarks/review/pdf-scientific-second-publisher/manifest.json"));
        final var cases = new ArrayList<Map<String, Object>>();
        for (final var definition : definitions) {
            final var manifestPath = project.resolve(definition.get(1));
            final var original = mapper.readTree(Files.readAllBytes(manifestPath));
            final var inputs = new ArrayList<Map<String, Object>>();
            for (final var source : original.get("inputs")) {
                final String originalName = source.get("filename").asString();
                if (!List.of("article.pdf", "article.xml").contains(originalName)) {
                    continue;
                }
                final String filename = definition.getFirst() + originalName.substring("article".length());
                final var path = cache.resolve(filename);
                if (Files.size(path) != source.get("bytes").asLong()
                        || !hash(path).equals(source.get("sha256").asString())) {
                    throw new IllegalArgumentException("Original publisher source identity differs");
                }
                inputs.add(Map.of("filename", filename, "bytes", Files.size(path), "sha256", hash(path), "url",
                        source.get("url").asString()));
            }
            if (inputs.size() != 2) {
                throw new IllegalArgumentException("Expected exactly one original PDF/XML pair");
            }
            final var selected = new LinkedHashMap<String, Object>();
            selected.put("id", definition.getFirst());
            selected.put("doi", original.get("doi").asString());
            selected.put("reference_manifest", definition.get(1));
            selected.put("reference_manifest_sha256", hash(manifestPath));
            selected.put("inputs", inputs);
            selected.put("pdf", definition.getFirst() + ".pdf");
            selected.put("jats", definition.getFirst() + ".xml");
            selected.put("reference", ScientificReadingEvaluation.identity(ScientificReadingEvaluation.references(
                                              Files.readAllBytes(cache.resolve(definition.getFirst() + ".xml")))));
            cases.add(selected);
        }
        final var manifest = new LinkedHashMap<String, Object>();
        manifest.put("frozen_at", Instant.now().toString());
        manifest.put("production_commit", "48345f5");
        manifest.put("rule",
                "First abstract paragraphs and up to eight direct paragraphs in first top-level body section, in publisher order. Same three original sources and all profiles. ASCII whitespace only; no case, hyphen, Unicode, punctuation, fuzzy or reference-order repair. Whole original PDFs; missing, duplicate, overlapping or failed spans retain all paragraph/pair denominators. Exact text spans are not visual paragraph segmentation or held-out semantic accuracy.");
        manifest.put("profiles", List.of("LINES", "PARAGRAPHS", "COLUMN_PARAGRAPHS"));
        manifest.put("cases", cases);
        final var helpers = new LinkedHashMap<String, String>();
        for (final String filename : List.of("ScientificPublisherXml.java", "ScientificReadingEvaluation.java",
                     "PdfScientificReadingOrderTest.java")) {
            final String relative = "docweave-validation/src/test/java/dev/docweave/validation/" + filename;
            helpers.put(relative, hash(project.resolve(relative)));
        }
        helpers.put("benchmarks/tools/FreezeScientificReading.java",
                hash(project.resolve("benchmarks/tools/FreezeScientificReading.java")));
        manifest.put("helper_sha256", helpers);
        Files.createDirectories(output);
        Files.writeString(output.resolve("manifest.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Hashes original bytes rather than parser-normalized source content.
     * @param path bounded source or owned helper
     * @return lowercase SHA256
     * @throws Exception if reading or the required digest fails
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
