package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.json.ElementJson;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Optional fetch-only rotation regression evidence, independent of owned PDF generation and Unstructured goldens. */
class PdfRotationCorpusTest {
    @Test
    void optionalPinnedRotationCorpusPreservesTextAndJsonGeometry() throws Exception {
        final String cache = System.getProperty("docweave.pdf.rotation.cache");
        final String output = System.getProperty("docweave.pdf.rotation.report");
        if (cache == null && output == null) {
            return;
        }
        assertNotNull(cache, "Both rotation cache and fresh report path are required");
        assertNotNull(output, "Both rotation cache and fresh report path are required");
        final byte[] manifestBytes = Files.readAllBytes(Path.of("benchmarks/review/pdf-rotation/manifest.json"));
        final var manifest = JsonMapper.builder().build().readTree(manifestBytes);
        final var inputs = new LinkedHashMap<String, byte[]>();
        for (final var entry : manifest.get("inputs")) {
            final String name = entry.get("filename").asString();
            assertEquals(name, Path.of(name).getFileName().toString());
            final Path source = Path.of(cache).resolve(name);
            assertEquals(entry.get("bytes").asLong(), Files.size(source), name);
            final byte[] bytes = Files.readAllBytes(source);
            assertEquals(entry.get("sha256").asString(), DocxOwnership.sha256(bytes), name);
            final var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            assertEquals(entry.get("git_blob_sha1").asString(),
                    java.util.HexFormat.of().formatHex(digest.digest(bytes)), name);
            inputs.put(name, bytes);
        }
        final var records = new ArrayList<Map<String, Object>>();
        boolean checks = true;
        final var json = ElementJson.create();
        for (final var entry : manifest.get("inputs")) {
            if (!entry.get("role").asString().equals("pdf")) {
                continue;
            }
            final String name = entry.get("filename").asString();
            final String expected =
                    normalized(new String(inputs.get(entry.get("expectation").asString()), StandardCharsets.UTF_8));
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
                final boolean nativeInventory = bag(text).equals(bag(baseline));
                final boolean roundTrip = result.elements().equals(json.read(json.write(result.elements())));
                checks &= nativeInventory && roundTrip;
                final var pages = new TreeMap<Integer, Object>();
                for (final var element : result.elements()) {
                    final int page = ((Number) element.metadata().get("page_number")).intValue();
                    final var coordinates = (Map<?, ?>) element.metadata().get("coordinates");
                    pages.put(
                            page, Map.of("rotation", element.metadata().getOrDefault("pdf_page_rotation", 0), "width",
                                          coordinates.get("layout_width"), "height", coordinates.get("layout_height")));
                }
                final var record = new LinkedHashMap<String, Object>();
                record.put("input", name)
                ;
                record.put("mode", mode.name())
                ;
                record.put("elements", result.elements().size())
                ;
                record.put("pages_with_output", pages)
                ;
                record.put("normalized_text_sha256", hash(text))
                ;
                record.put("baseline_inventory_preserved", nativeInventory)
                ;
                record.put("json_round_trip", roundTrip)
                ;
                record.put("saved_order_equal", text.equals(expected))
                ;
                record.put("saved_nfkc_order_equal", nfkc(text).equals(nfkc(expected)))
                ;
                record.put("saved_nfkc_inventory_equal", bag(nfkc(text)).equals(bag(nfkc(expected))))
                ;
                record.put("sideways_elements",
                        result.elements().stream().filter(e -> e.metadata().containsKey("pdf_text_rotation")).count())
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
        sources.put("PdfRotationCorpusTest.java",
                DocxOwnership.sha256(Files.readAllBytes(Path.of(
                        "docweave-validation/src/test/java/io/docweave/validation/PdfRotationCorpusTest.java"))));
        CorpusReview.write(Path.of(output),
                Map.of("scope", "pinned_rotation_regression_not_general_layout_or_human_parity", "manifest_sha256",
                        DocxOwnership.sha256(manifestBytes), "source_sha256", sources, "runtime_classpath",
                        ValidationProvenance.classpath(), "java_version", System.getProperty("java.version"),
                        "preservation_checks_pass", checks, "records", records));
        assertTrue(checks, "Native inventory or JSON geometry changed; see fresh report");
    }

    /** Removes Unicode whitespace/BOM code points for diagnostic saved-text comparison. */
    private static String normalized(final String text) {
        return text.codePoints()
                .filter(point -> !Character.isWhitespace(point) && !Character.isSpaceChar(point) && point != 0xfeff)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /** Sorts code points without claiming correct reading order. */
    private static String bag(final String text) {
        return text.codePoints()
                .sorted()
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /** Uses compatibility normalization only for comparison, never production output. */
    private static String nfkc(final String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC);
    }

    /** Stores text-free output identities. */
    private static String hash(final String text) {
        return DocxOwnership.sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
