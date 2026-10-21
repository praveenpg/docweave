package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Optional pinned independent PDF diagnostic; default CI never downloads external inputs. */
class PdfReadingCorpusTest {
    /** Pinned fetch-only sources and expected text identities, separate from upstream Unstructured references. */
    private static final Path MANIFEST = Path.of("benchmarks/review/pdf-reading/manifest.json");

    @Test
    void optionalIndependentReadingComparisonPreservesNativeCharacters() throws Exception {
        final String cache = System.getProperty("docweave.pdf.reading.cache");
        final String output = System.getProperty("docweave.pdf.reading.report");
        if (cache == null && output == null) {
            return;
        }
        assertNotNull(cache, "Both PDF reading cache and report are required");
        assertNotNull(output, "Both PDF reading cache and report are required");
        final byte[] manifestBytes = Files.readAllBytes(MANIFEST);
        final var manifest = JsonMapper.builder().build().readTree(manifestBytes);
        final var inputs = new LinkedHashMap<String, byte[]>();
        final var expected = new LinkedHashMap<String, String>();
        final var pdfs = new ArrayList<String>();
        for (final var entry : manifest.get("inputs")) {
            final String name = entry.get("filename").asString();
            assertEquals(name, Path.of(name).getFileName().toString());
            final Path path = Path.of(cache).resolve(name);
            assertEquals(entry.get("bytes").asLong(), Files.size(path), name);
            final byte[] bytes = Files.readAllBytes(path);
            assertEquals(entry.get("sha256").asString(), DocxOwnership.sha256(bytes), name);
            assertEquals(entry.get("git_blob_sha1").asString(), blob(bytes), name);
            inputs.put(name, bytes);
            if (entry.get("role").asString().equals("pdf")) {
                pdfs.add(name);
            } else {
                expected.put(name, normalized(new String(bytes, StandardCharsets.UTF_8)));
            }
        }
        assertEquals(2, pdfs.size());
        assertEquals(2, expected.size());
        final var records = new ArrayList<Map<String, Object>>();
        boolean preserved = true;
        for (final String name : pdfs) {
            String baseline = null;
            for (final var mode : PdfOptions.TextLayout.values()) {
                final var processor = DocWeave.create(PartitionerRegistry.of(
                        List.of(PdfPartitioner.create(PdfOptions.defaults().withTextLayout(mode)))));
                final var result = processor.partition(DocumentSource.bytes(inputs.get(name), name));
                final String text =
                        normalized(String.join(" ", result.elements().stream().map(e -> e.text()).toList()));
                if (baseline == null) {
                    baseline = text;
                }
                final boolean inventory = bag(text).equals(bag(baseline));
                final boolean paragraphOrder = mode != PdfOptions.TextLayout.PARAGRAPHS || text.equals(baseline);
                preserved &= inventory && paragraphOrder;
                final var matches = new TreeMap<String, Object>();
                for (final var reference : expected.entrySet()) {
                    matches.put(reference.getKey(),
                            Map.of("order_equal", text.equals(reference.getValue()), "nfkc_order_equal",
                                    nfkc(text).equals(nfkc(reference.getValue())), "nfkc_inventory_equal",
                                    bag(nfkc(text)).equals(bag(nfkc(reference.getValue())))));
                }
                final var record = new LinkedHashMap<String, Object>();
                record.put("input", name)
                ;
                record.put("mode", mode.name())
                ;
                record.put("elements", result.elements().size())
                ;
                record.put("column_elements",
                        result.elements().stream().filter(e -> e.metadata().containsKey("pdf_column")).count())
                ;
                record.put("normalized_sha256", hash(text))
                ;
                record.put("codepoint_bag_sha256", hash(bag(text)))
                ;
                record.put("codepoints_without_whitespace_bom", text.codePointCount(0, text.length()))
                ;
                record.put("baseline_inventory_preserved", inventory)
                ;
                record.put("paragraph_baseline_order_preserved", paragraphOrder)
                ;
                record.put("reference_comparisons", matches)
                ;
                records.add(record);
            }
        }
        final var sources = new TreeMap<String, String>();
        try (final var paths = Files.walk(Path.of("docweave-pdf/src/main/java"))) {
            for (final var path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                sources.put(path.toString(), DocxOwnership.sha256(Files.readAllBytes(path)));
            }
        }
        sources.put("PdfReadingCorpusTest.java",
                DocxOwnership.sha256(Files.readAllBytes(Path.of(
                        "docweave-validation/src/test/java/dev/docweave/validation/PdfReadingCorpusTest.java"))));
        CorpusReview.write(Path.of(output),
                Map.of("scope", "diagnostic_only_not_human_or_upstream_PDF_parity", "manifest_sha256",
                        DocxOwnership.sha256(manifestBytes), "java_version", System.getProperty("java.version"),
                        "source_sha256", sources, "runtime_classpath", ValidationProvenance.classpath(),
                        "native_preservation_checks_pass", preserved, "records", records));
        assertTrue(preserved, "Layout changed native character inventory or paragraph-only order; see fresh report");
    }

    /** Removes Unicode whitespace and a leading-file BOM for text-free order/inventory comparison. */
    private static String normalized(final String text) {
        return text.codePoints()
                .filter(point -> !Character.isWhitespace(point) && !Character.isSpaceChar(point) && point != 0xfeff)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /** Sorts code points without claiming word ownership or correct order. */
    private static String bag(final String text) {
        return text.codePoints()
                .sorted()
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /** Expands compatibility glyphs only for diagnostics; production text is unchanged. */
    private static String nfkc(final String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC);
    }

    /** Hashes text without storing it in committed reports. */
    private static String hash(final String text) {
        return DocxOwnership.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Verifies downloaded bytes against the independent pinned Git tree object identity. */
    private static String blob(final byte[] bytes) throws NoSuchAlgorithmException {
        final var digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        return java.util.HexFormat.of().formatHex(digest.digest(bytes));
    }
}
