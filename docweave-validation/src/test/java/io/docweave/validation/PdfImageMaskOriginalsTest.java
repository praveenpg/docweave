package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in complete original-image capture regression after normalized mask-extent budget changes. */
class PdfImageMaskOriginalsTest {
    /** Creates a stateless opt-in source-corpus test. */
    PdfImageMaskOriginalsTest() {
    }

    /**
     * Reuses the frozen three-source protocol and prior candidate captures without changing any PNG or reference.
     * Ordinary builds neither download sources nor read external caches; all properties must be explicit.
     * @throws Exception if source identity, raw-capture reading or fresh numeric report writing fails
     */
    @Test
    void optionalOriginalPayloadRegression() throws Exception {
        final String originals = System.getProperty("docweave.pdf.image.masks.originals");
        final String baseline = System.getProperty("docweave.pdf.image.masks.baseline");
        final String output = System.getProperty("docweave.pdf.image.masks.report");
        if (originals == null && baseline == null && output == null) {
            return;
        }
        assertNotNull(originals);
        assertNotNull(baseline);
        assertNotNull(output);
        final var mapper = JsonMapper.builder().build();
        final var root = Path.of("benchmarks/review/pdf-external-stencils");
        final byte[] manifestBytes = Files.readAllBytes(root.resolve("manifest.json"));
        final var manifest = mapper.readTree(manifestBytes);
        final var prior = mapper.readTree(Files.readAllBytes(root.resolve("java21.json")));
        assertEquals(3, manifest.get("cases").size());
        assertEquals(187, prior.get("source_reference_occurrences").asInt());
        assertEquals(1, prior.get("missing_reference_occurrences").asInt());
        final var runtime = mapper.readTree(Files.readAllBytes(Path.of(baseline, "runtime.json")));
        assertEquals(manifest.get("candidate_pdf_jar_sha256"), runtime.get("pdf_jar_sha256"));
        assertEquals(DocxOwnership.sha256(manifestBytes), runtime.get("manifest_sha256").asString());
        assertTrue(runtime.get("include_image_bytes").asBoolean());
        final var defaults = PdfOptions.defaults();
        final var options =
                new PdfOptions(defaults.maxPages(), defaults.maxGlyphsPerPage(), defaults.maxOperatorsPerPage(),
                        defaults.maxImagePixels(), defaults.maxImageBytes(), true, defaults.tableLimits(),
                        defaults.tableDetection(), defaults.textLayout(), defaults.includeTableCellCoordinates(),
                        defaults.tableContinuation(), defaults.unicodeNormalization(), defaults.textRendering());
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
        final var results = new ArrayList<Map<String, Object>>();
        boolean allEqual = true;
        int payloads = 0;
        for (final var selected : manifest.get("cases")) {
            final byte[] bytes = Files.readAllBytes(Path.of(originals, selected.get("pdf").asString()));
            assertEquals(selected.get("bytes").asLong(), bytes.length);
            final String sourceHash = DocxOwnership.sha256(bytes);
            assertEquals(selected.get("sha256").asString(), sourceHash);
            final var baselinePath = Path.of(baseline, selected.get("id").asString() + ".json");
            final byte[] priorBytes = Files.readAllBytes(baselinePath);
            final var expected = mapper.readTree(priorBytes);
            assertEquals("SUCCESS", expected.get("outcome").asString());
            assertEquals(selected.get("sha256"), expected.get("source_sha256"));
            final var actual = new LinkedHashMap<String, Object>();
            actual.put("source_sha256", sourceHash);
            int images = 0;
            try {
                final var result = processor.partition(DocumentSource.bytes(bytes, selected.get("pdf").asString()));
                actual.put("outcome", "SUCCESS");
                actual.put("elements", result.elements());
                actual.put("warnings", result.warnings().stream().map(w -> w.code().name()).toList());
                images = (int) result.elements().stream().filter(e -> e.type() == ElementType.Image).count();
            } catch (final PartitionException failure) {
                actual.put("outcome", failure.code().name());
                actual.put("elements", List.of());
                actual.put("warnings", List.of());
            }
            // Match the baseline's serialized wire-number representation, without omitting or normalizing fields.
            final byte[] currentBytes = mapper.writeValueAsBytes(actual);
            final boolean equal = mapper.readTree(currentBytes).equals(expected);
            allEqual &= equal;
            payloads += images;
            results.add(Map.of("case", selected.get("id").asString(), "outcome", actual.get("outcome"),
                    "original_pages", selected.get("pages").asInt(), "full_saved_public_capture_equal", equal,
                    "image_payloads", images, "baseline_capture_sha256", DocxOwnership.sha256(priorBytes),
                    "current_capture_sha256", DocxOwnership.sha256(currentBytes)));
        }
        final String implementation;
        try (final var resource = PdfPartitioner.class.getResourceAsStream("PdfImageBudget.class")) {
            assertNotNull(resource);
            implementation = DocxOwnership.sha256(resource.readAllBytes());
        }
        Files.writeString(Path.of(output),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime",
                        Runtime.version().toString(), "parent_manifest_sha256", DocxOwnership.sha256(manifestBytes),
                        "prior_inventory_report_sha256",
                        DocxOwnership.sha256(Files.readAllBytes(root.resolve("java21.json"))),
                        "loaded_image_budget_class_sha256", implementation, "scope",
                        "Complete saved element/warning captures equal prior candidate; no new Poppler run or excluded fields",
                        "source_reference_occurrences_retained", 187, "prior_unmatched_auxiliary_reference_retained", 1,
                        "image_payloads", payloads, "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
        assertTrue(allEqual, "At least one original complete image capture changed; inspect all recorded cases");
        assertEquals(186, payloads);
    }
}
