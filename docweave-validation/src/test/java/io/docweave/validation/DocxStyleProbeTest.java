package io.docweave.validation;

import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentFormat;
import io.docweave.partition.PartitionResult;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Owned XML checks distinguish source inventory, controlled derivatives and actual public-API extraction. */
class DocxStyleProbeTest {
    private static final String W = XmlOwnerReader.WORD;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path temporary;

    /** Makes a structurally complete owned package; raw parts exercise independent DOM instrumentation. */
    private Path source(final String text) throws IOException {
        final Path base = temporary.resolve("base-" + UUID.randomUUID() + ".docx");
        try (final var document = new XWPFDocument(); final var output = Files.newOutputStream(base)) {
            document.createTable(1, 1).getRow(0).getCell(0).setText(text);
            document.createStyles();
            document.write(output);
        }
        return rewrite(base,
                Map.of("word/styles.xml",
                        "<w:styles xmlns:w='" + W + "'><w:style w:type='table' w:styleId='Source'>"
                                + "<w:tblStylePr w:type='firstRow'><w:pPr><w:outlineLvl w:val='1'/></w:pPr></w:tblStylePr>"
                                + "<w:tblStylePr w:type='lastRow'><w:rPr><w:b/></w:rPr></w:tblStylePr></w:style></w:styles>"));
    }

    /** Rewrites selected test-only package parts without mutating the original file. */
    private Path rewrite(final Path source, final Map<String, String> replacement) throws IOException {
        final Path output = temporary.resolve(UUID.randomUUID() + ".docx");
        final var parts = new LinkedHashMap<String, byte[]>();
        try (final var zip = new ZipFile(source.toFile())) {
            for (final var entry : zip.stream().toList()) {
                try (final var input = zip.getInputStream(entry)) {
                    parts.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        replacement.forEach((name, value) -> parts.put(name, value.getBytes(StandardCharsets.UTF_8)));
        try (final var zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (final var part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue());
                zip.closeEntry();
            }
        }
        return output;
    }

    @Test
    void inventorySeparatesVisualSemanticAndWhitespaceOnlyVisits() throws IOException {
        final var probe = DocxStyleProbe.create();
        final var inventory = probe.inspect(source("😀 corpus text"));
        assertEquals(1, inventory.get("tables"));
        assertEquals(Map.of("firstRow", 1, "lastRow", 1), inventory.get("conditional_definitions"));
        assertEquals(1, inventory.get("semantic_conditional_definitions"));
        assertEquals(1, inventory.get("stored_table_paragraph_visits_nonblank"));
        assertEquals(0, probe.inspect(source("   ")).get("stored_table_paragraph_visits_nonblank"));
        assertThrows(UnsupportedOperationException.class, () -> inventory.clear());
    }

    @Test
    void nestedTablesCountVisitsAndProducerIsOnlySelfReportedMetadata() throws IOException {
        final String paragraph = "<w:p><w:r><w:t>Nested text</w:t></w:r></w:p>";
        final Path source = rewrite(source("unused"),
                Map.of("word/document.xml",
                        "<w:document xmlns:w='" + W + "'><w:body><w:tbl><w:tr><w:tc><w:tbl><w:tr><w:tc>" + paragraph
                                + "</w:tc></w:tr></w:tbl></w:tc></w:tr></w:tbl></w:body></w:document>",
                        "docProps/app.xml",
                        "<Properties xmlns='urn:owned'><Application>Owned producer claim</Application></Properties>"));
        final var inventory = DocxStyleProbe.create().inspect(source);
        assertEquals(2, inventory.get("tables"));
        assertEquals(2, inventory.get("stored_table_paragraph_visits_nonblank"));
        assertEquals("Owned producer claim", inventory.get("producer_metadata"));
    }

    @Test
    void derivativesPreserveTextGeometryNamespaceBindingsAndUnmodifiedParts() throws IOException {
        final String body =
                "<w:document xmlns:w='" + W + "' xmlns:w14='http://schemas.microsoft.com/office/word/2010/wordml' "
                + "xmlns:mc='http://schemas.openxmlformats.org/markup-compatibility/2006' mc:Ignorable='w14'>"
                + "<w:body><w:tbl><w:tblPr/><w:tr><w:tblPrEx/><w:tc><w:tcPr><w:gridSpan w:val='2'/>"
                + "<w:vMerge w:val='restart'/><w:hMerge w:val='restart'/><w:cnfStyle w:firstRow='false'/></w:tcPr>"
                + "<w:p><w:pPr><w:pStyle w:val='Kept'/><w:numPr><w:numId w:val='7'/></w:numPr></w:pPr>"
                + "<w:r><w:t>😀 corpus text</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>";
        final Path source = rewrite(source("unused"), Map.of("word/document.xml", body));
        final byte[] original = Files.readAllBytes(source);
        final var probe = DocxStyleProbe.create();
        for (final var mode : DocxStyleProbe.Mode.values()) {
            final byte[] bytes = probe.derive(source, mode);
            assertArrayEquals(bytes, probe.derive(source, mode));
            final Path derived = temporary.resolve(mode + ".docx");
            Files.write(derived, bytes);
            final var document = XmlOwnerReader.document(derived);
            assertEquals("😀 corpus text", document.getElementsByTagNameNS(W, "t").item(0).getTextContent());
            final var root = document.getDocumentElement();
            assertEquals("http://schemas.microsoft.com/office/word/2010/wordml", root.lookupNamespaceURI("w14"));
            assertEquals("2", ((org.w3c.dom.Element) document.getElementsByTagNameNS(W, "gridSpan").item(0))
                                      .getAttributeNS(W, "val"));
            assertEquals(1, document.getElementsByTagNameNS(W, "vMerge").getLength());
            assertEquals(1, document.getElementsByTagNameNS(W, "hMerge").getLength());
            assertEquals(1, document.getElementsByTagNameNS(W, "pStyle").getLength());
            assertEquals(1, document.getElementsByTagNameNS(W, "numId").getLength());
            assertEquals(0, document.getElementsByTagNameNS(W, "cnfStyle").getLength());
            assertEquals(DefaultDocxStyleProbe.STYLE,
                    ((org.w3c.dom.Element) document.getElementsByTagNameNS(W, "tblStyle").item(0))
                            .getAttributeNS(W, "val"));
            for (int index = 0; index < document.getElementsByTagNameNS(W, "tblLook").getLength(); index++) {
                assertEquals(mode == DocxStyleProbe.Mode.DISABLED ? "0600" : "01E0",
                        ((org.w3c.dom.Element) document.getElementsByTagNameNS(W, "tblLook").item(index))
                                .getAttributeNS(W, "val"));
            }
            if (mode == DocxStyleProbe.Mode.DIRECT_OVERRIDE) {
                assertEquals("9", ((org.w3c.dom.Element) document.getElementsByTagNameNS(W, "outlineLvl").item(0))
                                          .getAttributeNS(W, "val"));
            }
            try (final var before = new ZipFile(source.toFile()); final var after = new ZipFile(derived.toFile())) {
                for (final var entry : before.stream().toList()) {
                    if (!List.of("word/document.xml", "word/styles.xml").contains(entry.getName())) {
                        assertArrayEquals(before.getInputStream(entry).readAllBytes(),
                                after.getInputStream(after.getEntry(entry.getName())).readAllBytes());
                    }
                }
            }
        }
        assertArrayEquals(original, Files.readAllBytes(source));
        assertEquals(1, probe.inspect(source).get("stored_cnf_flags"));
    }

    @Test
    void styleCollisionDtdAndOversizedXmlRejectBeforeInstrumentation() throws IOException {
        final var probe = DocxStyleProbe.create();
        final Path source = source("text");
        final Path collision =
                rewrite(source, Map.of("word/styles.xml", "<w:styles xmlns:w='" + W + "'><w:style w:styleId='"
                                                                  + DefaultDocxStyleProbe.STYLE + "'/></w:styles>"));
        assertThrows(IOException.class, () -> probe.derive(collision, DocxStyleProbe.Mode.ENABLED));
        for (final String xml : List.of("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///no-target'>]><x/>",
                     "<x>"
                             + " ".repeat(16 * 1024 * 1024) + "</x>")) {
            final Path invalid = rewrite(source, Map.of("word/document.xml", xml));
            assertThrows(IOException.class, () -> probe.inspect(invalid));
            assertThrows(IOException.class, () -> probe.derive(invalid, DocxStyleProbe.Mode.ENABLED));
        }
        assertThrows(NullPointerException.class, () -> probe.derive(source, null));
    }

    @Test
    void metamorphicChecksRejectLostConditionsOverridesChangedTextAndWarnings() {
        final var original = Map.<String, Object>of("text_and_type_sha256", "same");
        final var enabled = new LinkedHashMap<String, Object>(Map.of("text_and_type_sha256", "same", "warnings",
                List.of(), "outline_levels", Map.of("2", 1), "style_ranges", 1));
        assertTrue(StyleCorpusReview.checks(original, enabled, DocxStyleProbe.Mode.ENABLED, true)
                        .values()
                        .stream()
                        .allMatch(Boolean::booleanValue));
        assertFalse(StyleCorpusReview.checks(original, enabled, DocxStyleProbe.Mode.DISABLED, true)
                        .get("only_whole_table_outline"));
        assertTrue(StyleCorpusReview.checks(original, enabled, DocxStyleProbe.Mode.DIRECT_OVERRIDE, true)
                        .get("conditional_outline_survives_direct_body_override"));
        enabled.put("text_and_type_sha256", "changed");
        assertFalse(StyleCorpusReview.checks(original, enabled, DocxStyleProbe.Mode.ENABLED, true)
                        .get("text_and_types_unchanged"));
        enabled.put("warnings", List.of("test warning"));
        assertFalse(StyleCorpusReview.checks(original, enabled, DocxStyleProbe.Mode.ENABLED, true)
                        .get("no_probe_warnings"));
        enabled.put("style_ranges", 0);
        enabled.put("outline_levels", Map.of());
        assertFalse(StyleCorpusReview.checks(original, enabled, DocxStyleProbe.Mode.ENABLED, true)
                        .get("conditional_outline_exercised"));
        assertFalse(StyleCorpusReview.checks(original, enabled, DocxStyleProbe.Mode.DIRECT_OVERRIDE, true)
                        .get("conditional_outline_survives_direct_body_override"));
        final var empty = StyleCorpusReview.checks(original, enabled, DocxStyleProbe.Mode.ENABLED, false);
        assertFalse(empty.containsKey("conditional_outline_exercised"));
        assertTrue(empty.get("empty_source_has_no_style_ranges"));
    }

    @Test
    void summaryHasNoTextAndRetainsHistoricalUnicodeHashEncoding() {
        final var result = new PartitionResult(
                List.of(new Element("id", ElementType.Table, "😀 corpus text",
                        Map.of("docx_table_style_paragraphs",
                                List.of(Map.of("outline_level", 2, "regions", List.of("firstRow")))))),
                DocumentFormat.DOCX, "binary", 0, Duration.ZERO, List.of());
        final var summary = StyleCorpusReview.summarize(result);
        assertEquals(Map.of("2", 1), summary.get("outline_levels"));
        assertEquals(Map.of("firstRow", 1), summary.get("region_memberships"));
        assertEquals(DocxOwnership.sha256("[[\"Table\", \"😀 corpus text\"]]".getBytes(StandardCharsets.UTF_8)),
                summary.get("text_and_type_sha256"));
        assertFalse(JSON.writeValueAsString(summary).contains("corpus text"));
    }

    @Test
    void actualOwnedExtractionExercisesAllThreeModesAndManifestGuards() throws IOException {
        final Path source = source("😀 corpus text");
        final Path manifest = temporary.resolve("manifest.json");
        final var contract = new LinkedHashMap<String, Object>(Map.of("filename", source.getFileName().toString(),
                "sha256", DocxOwnership.sha256(Files.readAllBytes(source)), "bytes", Files.size(source)));
        Files.writeString(manifest, JSON.writeValueAsString(Map.of("documents", List.of(contract))));
        final var report = StyleCorpusReview.run(manifest, temporary);
        assertEquals(true, report.get("checks_passed"));
        assertFalse(JSON.writeValueAsString(report).contains("corpus text"));
        final Path output = Path.of("docweave-validation/target/docx-styles/owned-" + UUID.randomUUID() + ".json");
        CorpusReview.write(output, report);
        assertThrows(IOException.class, () -> CorpusReview.write(output, report));
        contract.put("sha256", "0".repeat(64));
        Files.writeString(manifest, JSON.writeValueAsString(Map.of("documents", List.of(contract))));
        assertThrows(IOException.class, () -> StyleCorpusReview.run(manifest, temporary));
        contract.put("sha256", DocxOwnership.sha256(Files.readAllBytes(source)));
        contract.put("bytes", Files.size(source) + 1);
        Files.writeString(manifest, JSON.writeValueAsString(Map.of("documents", List.of(contract))));
        assertThrows(IOException.class, () -> StyleCorpusReview.run(manifest, temporary));
        contract.put("filename", "../unsafe.docx");
        Files.writeString(manifest, JSON.writeValueAsString(Map.of("documents", List.of(contract))));
        assertThrows(IOException.class, () -> StyleCorpusReview.run(manifest, temporary));
    }

    @Test
    void optionalExternalStyleAuditRequiresPinnedLocalFilesAndFreshOutput() throws IOException {
        final String manifest = System.getProperty("docweave.styles.manifest");
        final String cache = System.getProperty("docweave.styles.cache");
        final String output = System.getProperty("docweave.styles.report");
        if (manifest == null && cache == null && output == null) {
            return;
        }
        assertNotNull(manifest);
        assertNotNull(cache);
        assertNotNull(output);
        assertFalse(Files.exists(Path.of(output)), "Use a fresh style report path");
        final var report = StyleCorpusReview.run(Path.of(manifest), Path.of(cache));
        CorpusReview.write(Path.of(output), report);
        assertEquals(true, report.get("checks_passed"));
    }
}
