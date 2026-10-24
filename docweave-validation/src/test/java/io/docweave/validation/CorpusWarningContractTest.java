package io.docweave.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Owned source clues and warning multiset controls prevent code-only diagnostic false positives. */
class CorpusWarningContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path temporary;

    @Test
    void independentSourceCluesDistinguishBareRectanglesAndUnresolvedDirectStyleIds() throws IOException {
        final Path source = temporary.resolve("source-only.docx");
        final String document =
                "<w:document xmlns:w='" + XmlOwnerReader.WORD
                + "' xmlns:v='urn:schemas-microsoft-com:vml' xmlns:o='urn:schemas-microsoft-com:office:office'>"
                + "<w:body><w:p><w:r><w:pict><v:rect o:hr='t'/></w:pict><w:pict><v:rect/></w:pict>"
                + "<w:pict><v:rect title='Description'/></w:pict><w:pict><v:rect><v:imagedata/></v:rect></w:pict>"
                + "</w:r></w:p><w:tbl><w:tblPr><w:tblStyle w:val='Missing'/></w:tblPr></w:tbl>"
                + "<w:tbl><w:tblPr><w:tblStyle w:val='Table'/></w:tblPr></w:tbl>"
                + "<w:tbl><w:tblPr><w:tblStyle w:val='Paragraph'/></w:tblPr></w:tbl></w:body></w:document>";
        final String styles =
                "<w:styles xmlns:w='" + XmlOwnerReader.WORD
                + "'><w:style w:type='table' w:styleId='Table'/><w:style w:type='paragraph' w:styleId='Paragraph'/></w:styles>";
        // Raw source parts test the independent reader only; this is not presented as a valid parser fixture.
        try (final var zip = new ZipOutputStream(Files.newOutputStream(source))) {
            for (final var part : Map.of("word/document.xml", document, "word/styles.xml", styles).entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        assertEquals(Map.of("empty_vml_rectangles", 2, "vml_rectangles_with_office_hr_t", 1,
                             "unresolved_direct_table_style_ids", List.of("Missing", "Paragraph")),
                CorpusOmissionSource.inspect(source));
    }

    @Test
    void warningMessagesCountsAndSourceEvidenceMustAllMatchTheDeclaredContract() throws IOException {
        final var manifest = JSON.readTree(Files.readString(CorpusReview.OWNED.resolve("manifest.json")));
        final var document = (ObjectNode) manifest.get("documents").get(1);
        final String message = "DOCX nested table text and links were retained in its containing cell";
        final var expected = document.putArray("expected_warnings");
        for (int index = 0; index < 3; index++) {
            expected.addObject().put("code", "NESTED_TABLE_FLATTENED").put("message", message);
        }
        final Path path = temporary.resolve("manifest.json");
        Files.writeString(path, JSON.writeValueAsString(manifest));
        assertEquals(true, CorpusReview.run(path, CorpusReview.OWNED).get("checks_passed"));
        ((ObjectNode) expected.get(0)).put("message", "Different warning cause");
        Files.writeString(path, JSON.writeValueAsString(manifest));
        final var failed = JSON.valueToTree(CorpusReview.run(path, CorpusReview.OWNED));
        final var mode = failed.get("documents").get(1).get("java").get("defaults");
        assertTrue(mode.get("warning_codes_match").asBoolean());
        assertFalse(mode.get("warning_details_match").asBoolean());
        assertFalse(failed.get("checks_passed").asBoolean());
        ((ObjectNode) expected.get(0)).put("message", message);
        expected.remove(0);
        Files.writeString(path, JSON.writeValueAsString(manifest));
        assertEquals(false, CorpusReview.run(path, CorpusReview.OWNED).get("checks_passed"));
        document.putObject("required_source_omissions").put("empty_vml_rectangles", 99);
        Files.writeString(path, JSON.writeValueAsString(manifest));
        assertThrows(IOException.class, () -> CorpusReview.run(path, CorpusReview.OWNED));
        document.remove("required_source_omissions");
        document.put("expected_warnings", "invalid array");
        Files.writeString(path, JSON.writeValueAsString(manifest));
        assertThrows(IOException.class, () -> CorpusReview.run(path, CorpusReview.OWNED));
    }
}
