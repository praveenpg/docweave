package dev.docweave.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Independent owned XML oracles and optional exact inventory parity with historical external reports. */
class DocxCorpusSourceTest {
    @TempDir Path temporary;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DocxCorpusSource.Snapshot inspect(final String body, final String... parts) throws IOException {
        final Path path = temporary.resolve("owned-" + UUID.randomUUID() + ".docx");
        try (final var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document xmlns:w='" + XmlOwnerReader.WORD + "'><w:body>" + body + "</w:body></w:document>")
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (final String part : parts) {
                zip.putNextEntry(new ZipEntry(part));
                zip.write("<stored/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return DocxCorpusSource.create().inspect(path);
    }

    private static String p(final String text) {
        return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    @Test
    void nestedParagraphsAreInventoriedButRejectedByTheLegacySubstringOracle() throws IOException {
        final var snapshot = inspect("<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Outer</w:t><w:txbxContent>" + p("Box")
                                             + "</w:txbxContent></w:r></w:p><w:tbl><w:tr><w:tc>" + p("Child")
                                             + "</w:tc></w:tr></w:tbl></w:tc></w:tr></w:tbl>",
                "word/header1.xml");
        assertEquals(2, snapshot.maximumTableDepth());
        assertEquals(1, snapshot.body().get("nested_tables"));
        assertEquals(1, snapshot.body().get("nested_paragraphs"));
        assertEquals(1, snapshot.body().get("text_boxes"));
        assertEquals(Map.of("header", 1), snapshot.storedParts());
        assertThrows(IOException.class, snapshot::paragraphs);
    }

    @Test
    void ordinaryParagraphsRetainBoundariesAcceptedChildrenAndWhitespaceNormalization() throws IOException {
        final var snapshot = inspect("<w:p><w:r><w:t>Before</w:t><w:tab/><w:t>after</w:t></w:r>"
                                     + "<w:del>" + p("Deleted inline")
                                     + "</w:del><w:ins><w:r><w:br/><w:t>inserted</w:t></w:r></w:ins></w:p>");
        // Even a deleted nested paragraph is outside the old probe's structural assumptions.
        assertFalse(snapshot.substringSupported());
        final var ordinary = inspect("<w:p><w:r><w:t>Before</w:t><w:tab/><w:t>after</w:t></w:r>"
                                     + "<w:del><w:r><w:t>deleted</w:t></w:r></w:del>"
                                     + "<w:ins><w:r><w:br/><w:t>inserted</w:t></w:r></w:ins></w:p>");
        assertEquals(List.of("Before after inserted"), ordinary.paragraphs());
    }

    @Test
    void legacySubstringPresenceDoesNotClaimOrderMultiplicityOrOwnership() {
        final var metrics = DocxCorpusSource.metrics(
                List.of(new DocxCorpusSource.TextElement("NarrativeText", "Second.\tFirst. 😀")),
                List.of("First.", "Second.", "First.", "Missing."));
        assertEquals(4, metrics.get("body_paragraphs_checked"));
        assertEquals(1, metrics.get("body_paragraphs_unmatched"));
        assertEquals(List.of(DocxOwnership.textHash("Missing.")), metrics.get("unmatched_paragraph_sha256"));
        assertEquals(
                "Second.\tFirst. 😀".codePointCount(0, "Second.\tFirst. 😀".length()), metrics.get("text_code_points"));
    }

    @Test
    void inventoryCountsRevisionBranchesAndStoredFileNamesWithoutClaimingRenderedCounts() throws IOException {
        final var snapshot = inspect(
                "<w:sdt><w:sdtContent><w:ins>" + p("Inserted") + "</w:ins>"
                        + "<w:del>" + p("Deleted ancestor") + "</w:del><w:p><w:r><w:fldChar/><w:drawing/>"
                        + "</w:r><w:fldSimple><w:r><w:t>Cached</w:t></w:r></w:fldSimple><w:hyperlink/></w:p></w:sdtContent></w:sdt>",
                "word/header1.xml", "word/footer1.xml", "word/footnotes.xml", "other/header2.xml",
                "word/_rels/header1.xml.rels");
        assertEquals(Map.of("header", 1, "footer", 1, "footnotes", 1), snapshot.storedParts());
        for (final String key : List.of("insertions", "deletions", "content_controls", "field_markers", "drawings",
                     "simple_fields", "hyperlinks")) {
            assertEquals(1, snapshot.body().get(key));
        }
        assertTrue(snapshot.paragraphs().contains("Deleted ancestor"),
                "Preserve legacy semantics rather than silently upgrading to accepted-owner selection");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.body().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.storedParts().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.storedParagraphs().clear());
    }

    @Test
    void optionalExternalInventoryUsesHashPinnedLocalFilesAndFreshOutput() throws IOException {
        final String manifest = System.getProperty("docweave.inventory.manifest");
        final String cache = System.getProperty("docweave.inventory.cache");
        final String output = System.getProperty("docweave.inventory.report");
        if (manifest == null && cache == null && output == null) {
            assertNull(System.getProperty("docweave.inventory.expected"),
                    "Expected inventory requires manifest/cache/report");
            return;
        }
        assertNotNull(manifest);
        assertNotNull(cache);
        assertNotNull(output);
        assertFalse(Files.exists(Path.of(output)), "Use a fresh inventory report path");
        final var report = CorpusInventory.run(Path.of(manifest), Path.of(cache));
        CorpusReview.write(Path.of(output), report);
        final String expected = System.getProperty("docweave.inventory.expected");
        if (expected != null) {
            assertEquals(JSON.readTree(Files.readAllBytes(Path.of(expected))).get("documents"),
                    JSON.valueToTree(report).get("documents"));
        }
    }
}
