package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfMarginClassifier;
import io.docweave.partition.PdfMarginOptions;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit source-labelled native margins; retains misses under conservative historical defaults. */
class PdfIndependentMarginsTest {
    @Test
    void optionalOriginalRfcPagesPreserveSourceInventoryWithExplicitMarginPolicy() throws Exception {
        final String input = System.getProperty("docweave.pdf.margins.pdf");
        final String report = System.getProperty("docweave.pdf.margins.report");
        if (input == null && report == null) {
            return;
        }
        assertNotNull(input);
        assertNotNull(report);
        final var mapper = JsonMapper.builder().build();
        final var manifest =
                mapper.readTree(Files.readAllBytes(Path.of("benchmarks/review/pdf-margins/manifest.json")));
        final byte[] original = Files.readAllBytes(Path.of(input));
        assertEquals(manifest.get("bytes").asLong(), original.length);
        assertEquals(manifest.get("sha256").asString(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
        final byte[] selected;
        try (final var source = Loader.loadPDF(original); final var subset = new PDDocument();
                final var output = new ByteArrayOutputStream()) {
            assertEquals(manifest.get("source_pages").asInt(), source.getNumberOfPages());
            for (int index = 0; index < 12; index++) {
                final var page = subset.importPage(source.getPage(index));
                page.setResources(source.getPage(index).getResources());
            }
            subset.save(output);
            selected = output.toByteArray();
        }
        final var pdf = PdfPartitioner.create(PdfOptions.defaults());
        final var source = DocWeave.create(PartitionerRegistry.of(List.of(pdf)))
                                   .partition(DocumentSource.bytes(selected, "rfc9110-first12.pdf"))
                                   .elements();
        final var oracles = new HashMap<Integer, ElementType>();
        for (int index = 0; index < source.size(); index++) {
            final var element = source.get(index);
            final var role = oracle(element, manifest);
            if (role != null) {
                oracles.put(index, role);
            }
        }
        assertEquals(33, oracles.values().stream().filter(role -> role == ElementType.Header).count());
        assertEquals(24, oracles.values().stream().filter(role -> role == ElementType.Footer).count());
        final var profiles = List.of(PdfMarginOptions.defaults(), new PdfMarginOptions(.08, .16, false),
                new PdfMarginOptions(.08, .16, true));
        final var results = new ArrayList<Map<String, Object>>();
        for (final var options : profiles) {
            final var classifier = PdfMarginClassifier.create(options);
            final var tagged = classifier.classify(source, 12);
            assertEquals(source.size(), tagged.size());
            assertEquals(tagged, classifier.classify(tagged, 12));
            int headers = 0;
            int footers = 0;
            int extra = 0;
            for (int index = 0; index < source.size(); index++) {
                final var before = source.get(index);
                final var after = tagged.get(index);
                assertEquals(before.elementId(), after.elementId());
                assertEquals(before.text(), after.text());
                assertEquals(before.metadata().get("coordinates"), after.metadata().get("coordinates"));
                assertFalse(before.metadata().containsKey("pdf_margin_classification"));
                if (after.type() == ElementType.Header || after.type() == ElementType.Footer) {
                    if (oracles.get(index) != after.type()) {
                        extra++;
                    } else if (after.type() == ElementType.Header) {
                        headers++;
                    } else {
                        footers++;
                    }
                } else {
                    assertSame(before, after);
                }
            }
            assertEquals(0, extra);
            if (options.equals(PdfMarginOptions.defaults())) {
                assertEquals(0, headers + footers);
            }
            if (options.allowFirstPageBodyText()) {
                assertEquals(33, headers);
                assertEquals(24, footers);
            }
            final var entry = new LinkedHashMap<String, Object>();
            entry.put("header_band_fraction", options.headerBandFraction());
            entry.put("footer_band_fraction", options.footerBandFraction());
            entry.put("first_page_body_text_allowed", options.allowFirstPageBodyText());
            entry.put("matched_headers", headers);
            entry.put("matched_footers", footers);
            entry.put("missed_labelled_margins", oracles.size() - headers - footers);
            entry.put("extra_tags", extra);
            entry.put("source_inventory_identity_coordinates_preserved", true);
            results.add(entry);
        }
        Files.write(Path.of(report),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                        Map.of("runtime", Runtime.version().toString(), "source_sha256",
                                manifest.get("sha256").asString(), "source_pages", manifest.get("source_pages").asInt(),
                                "selected_pages", 12, "source_elements", source.size(), "labelled_headers", 33,
                                "labelled_footers", 24, "scope", manifest.get("scope").asString(), "results", results)),
                StandardOpenOption.CREATE_NEW);
    }

    /** Uses fixed source labels, physical pages and source margin bands, independently of classifier output. */
    private static ElementType oracle(final Element element, final JsonNode manifest) {
        final int page = ((Number) element.metadata().get("page_number")).intValue();
        final var coordinates = (Map<?, ?>) element.metadata().get("coordinates");
        if (coordinates == null) {
            return null;
        }
        final var points = (List<?>) coordinates.get("points");
        final double top = ((Number) ((List<?>) points.getFirst()).get(1)).doubleValue()
                           / ((Number) coordinates.get("layout_height")).doubleValue();
        final double bottom = ((Number) ((List<?>) points.get(2)).get(1)).doubleValue()
                              / ((Number) coordinates.get("layout_height")).doubleValue();
        final String text = element.text().strip().replaceAll("\\s+", " ");
        for (final var role : List.of(ElementType.Header, ElementType.Footer)) {
            if (role == ElementType.Header ? bottom > .08 : top < .84) {
                continue;
            }
            for (final var label : manifest.get(role == ElementType.Header ? "header_oracles" : "footer_oracles")) {
                if (!text.equals(label.get("text").asString())) {
                    continue;
                }
                for (final var physical : label.get("pages")) {
                    if (physical.asInt() == page) {
                        return role;
                    }
                }
            }
        }
        return null;
    }
}
