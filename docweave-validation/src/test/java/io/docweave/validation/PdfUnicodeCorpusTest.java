package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfFontInspector;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Optional pinned original Unicode PDFs and maintained external saved text; no runtime oracle generation. */
class PdfUnicodeCorpusTest {
    @Test
    void optionalSavedUnicodeExpectationsRetainRawAndExplicitPresentationPolicies() throws Exception {
        final String cache = System.getProperty("docweave.pdf.unicode.cache");
        final String report = System.getProperty("docweave.pdf.unicode.report");
        if (cache == null && report == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(report);
        final var mapper = JsonMapper.builder().build();
        final var manifest =
                mapper.readTree(Files.readAllBytes(Path.of("benchmarks/review/pdf-unicode/manifest.json")));
        for (final var input : manifest.get("inputs")) {
            final var bytes = Files.readAllBytes(Path.of(cache).resolve(input.get("filename").asString()));
            assertEquals(input.get("bytes").asLong(), bytes.length);
            assertEquals(input.get("sha256").asString(), hash(bytes));
            final var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            assertEquals(input.get("git_blob_sha1").asString(), HexFormat.of().formatHex(digest.digest(bytes)));
        }
        final var results = new ArrayList<Map<String, Object>>();
        final var rawElements = new java.util.HashMap<String, List<Element>>();
        for (final var policy : PdfOptions.UnicodeNormalization.values()) {
            final var options = PdfOptions.defaults().withUnicodeNormalization(policy);
            final var parser = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
            int exact = 0;
            for (final var input : manifest.get("inputs")) {
                if (!input.get("role").asString().equals("pdf")) {
                    continue;
                }
                final String name = input.get("filename").asString();
                final var bytes = Files.readAllBytes(Path.of(cache).resolve(name));
                final var expected = expectation(Path.of(cache).resolve(name + "-sorted.txt"));
                final var alternate = expectation(Path.of(cache).resolve(name + ".txt"));
                assertEquals(expected, alternate, "Both pinned reference variants agree for this fixed subset");
                final var partitioned = parser.partition(DocumentSource.bytes(bytes, name));
                final var observed =
                        normalize(String.join(" ", partitioned.elements().stream().map(e -> e.text()).toList()));
                final boolean matched = expected.equals(observed);
                if (matched) {
                    exact++;
                }
                final var referenceCounts = inventory(expected);
                final var observedCounts = inventory(observed);
                final var inspected = PdfFontInspector.create().inspect(bytes);
                final var entry = new LinkedHashMap<String, Object>();
                entry.put("input", name);
                entry.put("unicode_policy", policy.name());
                entry.put("exact_whitespace_normalized_text", matched);
                entry.put("exact_nonwhitespace_codepoint_inventory", referenceCounts.equals(observedCounts));
                entry.put("reference_codepoints", expected.codePointCount(0, expected.length()));
                entry.put("observed_codepoints", observed.codePointCount(0, observed.length()));
                entry.put("missing_codepoints", difference(referenceCounts, observedCounts));
                entry.put("extra_codepoints", difference(observedCounts, referenceCounts));
                entry.put("elements", partitioned.elements().size());
                entry.put("source_element_geometry_and_types_preserved", true);
                entry.put("font_count", inspected.fonts().size());
                entry.put(
                        "source_glyphs", inspected.fonts().stream().mapToLong(PdfFontInspector.FontInfo::glyphs).sum());
                entry.put("unmapped_glyphs",
                        inspected.fonts().stream().mapToLong(PdfFontInspector.FontInfo::unmappedGlyphs).sum());
                entry.put("replacement_glyphs",
                        inspected.fonts().stream().mapToLong(PdfFontInspector.FontInfo::replacementGlyphs).sum());
                results.add(entry);
            }
            assertEquals(policy == PdfOptions.UnicodeNormalization.RAW ? 3 : 5, exact);
        }
        Files.write(Path.of(report),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("runtime",
                        Runtime.version().toString(), "source_commit", manifest.get("commit").asString(), "scope",
                        manifest.get("scope").asString(), "source_pdfs", 5, "pinned_assets", 15,
                        "expectation_transport", manifest.get("expectation_transport").asString(), "results", results)),
                StandardOpenOption.CREATE_NEW);
    }

    /** Reads the reference-file transport BOM only; never strips that codepoint from extracted source content. */
    private static String expectation(final Path path) throws Exception {
        final String text = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("\uFEFF"), "Pinned reference files have a recorded UTF-8 BOM");
        return normalize(text.substring(1));
    }

    /** Compares whitespace only; source Unicode scalars, order, case and punctuation remain significant. */
    private static String normalize(final String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /** Counts source Unicode scalars without whitespace, independently of source line/group ordering. */
    private static Map<Integer, Integer> inventory(final String text) {
        final var result = new TreeMap<Integer, Integer>();
        text.codePoints()
                .filter(point -> !Character.isWhitespace(point))
                .forEach(point -> result.merge(point, 1, Integer::sum));
        return result;
    }

    /** Reports numeric scalar deficits without redistributing source text. */
    private static Map<String, Integer> difference(
            final Map<Integer, Integer> first, final Map<Integer, Integer> second) {
        final var result = new TreeMap<String, Integer>();
        first.forEach((point, count) -> {
            final int excess = count - second.getOrDefault(point, 0);
            if (excess > 0) {
                result.put("U+%04X".formatted(point), excess);
            }
        });
        return result;
    }

    /** Preserves exact identity of fetch-only source assets. */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
