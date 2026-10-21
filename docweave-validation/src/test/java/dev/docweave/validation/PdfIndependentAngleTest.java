package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.json.ElementJson;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Fetch-only Mozilla fixture: independent native slanted text, not general logical reading-order evidence. */
class PdfIndependentAngleTest {
    @Test
    void optionalOriginalMozillaFixtureRetainsSlantedTextAndSourceCoordinates() throws Exception {
        final String input = System.getProperty("docweave.pdf.angle.pdf");
        final String report = System.getProperty("docweave.pdf.angle.report");
        if (input == null && report == null) {
            return;
        }
        assertNotNull(input, "Both source and fresh report are required");
        assertNotNull(report, "Both source and fresh report are required");
        final byte[] manifestBytes = Files.readAllBytes(Path.of("benchmarks/review/pdf-native-angle/manifest.json"));
        final var manifest = JsonMapper.builder().build().readTree(manifestBytes);
        final byte[] bytes = Files.readAllBytes(Path.of(input));
        assertEquals(manifest.get("bytes").asLong(), bytes.length);
        assertEquals(manifest.get("sha256").asString(), DocxOwnership.sha256(bytes));
        final var digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        assertEquals(manifest.get("git_blob_sha1").asString(), HexFormat.of().formatHex(digest.digest(bytes)));
        final var source = DocumentSource.bytes(bytes, "mozilla-rotated.pdf");
        final var defaults =
                DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults()))));
        assertEquals(PartitionException.Code.UNSUPPORTED_OPTION,
                assertThrows(PartitionException.class, () -> defaults.partition(source)).code());
        final var options = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.ORIENTED_LINES);
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
        final var elements = processor.partition(source).elements();
        final var angled = elements.stream()
                                   .filter(e -> "projected_baseline_v1".equals(e.metadata().get("pdf_text_geometry")))
                                   .toList();
        assertEquals(1, angled.size());
        final var line = angled.getFirst();
        assertEquals(manifest.get("angled_text").asString(), line.text());
        assertEquals(manifest.get("clockwise_displayed_degrees").asDouble(),
                ((Number) line.metadata().get("pdf_text_rotation")).doubleValue(), .01);
        final String text = String.join(" ", elements.stream().map(e -> e.text()).toList());
        assertEquals(inventory(manifest.get("visual_inventory").asString()), inventory(text));
        for (final var element : elements) {
            assertEquals(1, element.metadata().get("page_number"));
            final var coordinates = (Map<?, ?>) element.metadata().get("coordinates");
            assertEquals(612.0, coordinates.get("layout_width"));
            assertEquals(792.0, coordinates.get("layout_height"));
            for (final var point : (List<?>) coordinates.get("points")) {
                final var pair = (List<?>) point;
                final double x = ((Number) pair.get(0)).doubleValue();
                final double y = ((Number) pair.get(1)).doubleValue();
                assertTrue(Double.isFinite(x) && x >= 0 && x <= 612);
                assertTrue(Double.isFinite(y) && y >= 0 && y <= 792);
            }
        }
        final var json = ElementJson.create();
        assertEquals(elements, json.read(json.write(elements)));
        assertEquals(elements, processor.partition(source).elements());
        final var sources = new TreeMap<String, String>();
        try (final var paths = Files.walk(Path.of("docweave-pdf/src/main/java"))) {
            for (final var path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                sources.put(path.toString(), DocxOwnership.sha256(Files.readAllBytes(path)));
            }
        }
        sources.put("PdfIndependentAngleTest.java",
                DocxOwnership.sha256(Files.readAllBytes(Path.of(
                        "docweave-validation/src/test/java/dev/docweave/validation/PdfIndependentAngleTest.java"))));
        CorpusReview.write(Path.of(report),
                Map.of("scope", "one_original_mozilla_fixture_not_general_layout_accuracy", "manifest_sha256",
                        DocxOwnership.sha256(manifestBytes), "java_version", System.getProperty("java.version"),
                        "runtime_classpath", ValidationProvenance.classpath(), "source_sha256", sources, "result",
                        Map.of("elements", elements.size(), "angled_lines", angled.size(),
                                "clockwise_displayed_degrees", line.metadata().get("pdf_text_rotation"),
                                "visual_inventory_preserved", true, "bounds_finite_in_source_frame", true,
                                "json_round_trip", true, "repeated_extraction_equal", true,
                                "default_rejects_unsupported_angle", true)));
    }

    /** Counts case-sensitive scalar inventory without asserting logical order or repairing decoded text. */
    private static String inventory(final String text) {
        return text.codePoints()
                .filter(point -> !Character.isWhitespace(point) && !Character.isSpaceChar(point))
                .sorted()
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
