package dev.docweave.validation;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Independent owned XML and deliberately incorrect outputs; no Python, POI or network. */
class DocxOwnershipTest {
    @TempDir Path directory;
    private final DocxOwnership validator = DocxOwnership.create();

    private List<DocxOwnership.Owner> owners(final String body) throws IOException {
        return source("<w:document xmlns:w='" + XmlOwnerReader.WORD
                      + "' xmlns:mc='http://schemas.openxmlformats.org/markup-compatibility/2006'><w:body>" + body
                      + "</w:body></w:document>");
    }

    private List<DocxOwnership.Owner> source(final String xml) throws IOException {
        final var path = directory.resolve("owned.docx");
        try (final var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return validator.read(path);
    }

    private static String p(final String text) {
        return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    private static String table(final String body) {
        return "<w:tbl><w:tr><w:tc>" + body + "</w:tc></w:tr></w:tbl>";
    }

    private static String box(final String host, final String content) {
        return "<w:p><w:r><w:t>" + host + "</w:t><w:pict><w:txbxContent>" + content
                + "</w:txbxContent></w:pict></w:r></w:p>";
    }

    private static Element body(final String text, final String id) {
        return new Element(id, ElementType.NarrativeText, text, Map.of("docx_part", "body"));
    }

    private static Element textbox(final String text, final String id, final String parent) {
        return new Element(id, ElementType.NarrativeText, text, Map.of("docx_part", "textbox", "parent_id", parent));
    }

    private static Element tableElement(final String text) {
        return new Element("table", ElementType.Table, text, Map.of("docx_part", "body"));
    }

    @Test
    void hostExcludesBoxAndBoxRequiresItsOwnParent() throws IOException {
        final var owners = owners("<w:p><w:r><w:t>Before </w:t><w:pict><w:txbxContent>" + p("Inside 😀")
                                  + "</w:txbxContent></w:pict><w:t>after</w:t></w:r></w:p>");
        assertEquals(List.of(List.of("Before after"), List.of("Inside 😀")),
                owners.stream().map(DocxOwnership.Owner::paragraphs).toList());
        assertEquals(owners.getFirst().key(), owners.get(1).parent());
        assertTrue(validator.check(List.of(body("Before after", "host"), textbox("Inside 😀", "box", "host")), owners)
                        .passed());
        assertFalse(validator.check(List.of(body("Before after", "host"), textbox("Inside 😀", "box", "wrong")), owners)
                        .passed());
    }

    @Test
    void boxTextCannotBeBorrowedFromBody() throws IOException {
        final var owners = owners(box("Host", p("Box")));
        final var report = validator.check(List.of(body("Host Box", "host")), owners);
        assertFalse(report.passed());
        assertEquals(1, report.unmatched().size());
    }

    @Test
    void nestedAndSiblingBoxesKeepHostMapping() throws IOException {
        final var owners = owners(box("Body", box("Box host", p("Grandchild")) + p("Sibling")));
        final var good = List.of(body("Body", "body"), textbox("Box host", "box", "body"),
                textbox("Grandchild", "grand", "box"), textbox("Sibling", "sibling", "body"));
        assertTrue(validator.check(good, owners).passed());
        assertFalse(validator
                        .check(List.of(good.getFirst(), good.get(1), textbox("Grandchild", "grand", "body"),
                                       good.getLast()),
                                owners)
                        .passed());
    }

    @Test
    void repeatedParagraphsRequireDistinctOccurrences() throws IOException {
        final var owners = owners(table(p("Repeated") + p("Repeated")));
        assertEquals(1, validator.check(List.of(tableElement("Repeated")), owners).unmatched().size());
        assertTrue(validator.check(List.of(tableElement("Repeated\tRepeated")), owners).passed());
    }

    @Test
    void nestedTableParagraphsShareRootWithoutRequiringMergeOrder() throws IOException {
        final var owners = owners(table(p("First") + table(p("Child")) + p("Last")));
        assertEquals(1, owners.size());
        assertEquals(List.of("First", "Child", "Last"), owners.getFirst().paragraphs());
        assertTrue(validator.check(List.of(tableElement("Last\nChild\tFirst")), owners).passed());
    }

    @Test
    void longestFirstAllocationRejectsReusedOverlappingSubstrings() throws IOException {
        final var owners = owners(table(p("a") + p("a b")));
        assertTrue(validator.check(List.of(tableElement("a b a")), owners).passed());
        assertFalse(validator.check(List.of(tableElement("a b")), owners).passed());
    }

    @Test
    void bodyOwnerOrderAndElementKindAreChecked() throws IOException {
        final var owners = owners(p("First") + p("Second"));
        assertFalse(validator.check(List.of(body("Second", "a"), body("First", "b")), owners).passed());
        assertFalse(validator.check(List.of(body("Table", "t")), owners(table(p("Table")))).passed());
    }

    @Test
    void acceptedRevisionsExcludeDeletedAncestorsPropertiesAndRows() throws IOException {
        final var owners =
                owners("<w:del>" + p("Deleted") + "</w:del><w:moveFrom>" + p("Moved") + "</w:moveFrom><w:sdt><w:sdtPr>"
                        + p("Property") + "</w:sdtPr><w:sdtContent><w:ins>" + p("Accepted")
                        + "</w:ins></w:sdtContent></w:sdt><w:tbl><w:tr><w:trPr><w:del/></w:trPr><w:tc>"
                        + p("Deleted row") + "</w:tc></w:tr><w:tr><w:tc>" + p("Kept") + "</w:tc></w:tr></w:tbl>");
        assertEquals(List.of(List.of("Accepted"), List.of("Kept")),
                owners.stream().map(DocxOwnership.Owner::paragraphs).toList());
    }

    @Test
    void unsupportedStorySelectionAndBlankHostsReject() {
        for (final String xml : List.of("<mc:AlternateContent>" + p("Choice") + "</mc:AlternateContent>",
                     "<w:unknown>" + p("Unknown") + "</w:unknown>", "<w:p>" + p("Invalid") + "</w:p>",
                     box("", p("Box")))) {
            assertThrows(IOException.class, () -> owners(xml));
        }
    }

    @Test
    void otherPartsAndImagesAreExcludedButExtraBodyOutputFails() throws IOException {
        final var owners = owners(p("Body"));
        final var good = List.of(body("Body", "p"), new Element("img", ElementType.Image, "Image", Map.of()),
                new Element("footer", ElementType.Footer, "Footer", Map.of("docx_part", "footer")));
        assertTrue(validator.check(good, owners).passed());
        assertEquals(
                1, validator.check(List.of(body("Body", "p"), body("Extra", "extra")), owners).unexpectedElements());
    }

    @Test
    void otherPartRootedBoxesAreExcludedButOrphansAndCyclesFail() throws IOException {
        final var owners = owners(p("Body"));
        final var header = new Element("header", ElementType.Header, "Header", Map.of("docx_part", "header"));
        final var good =
                List.of(body("Body", "p"), header, textbox("Box", "box", "header"), textbox("Nested", "nested", "box"));
        assertTrue(validator.check(good, owners).passed());
        assertEquals(1, validator.check(List.of(body("Body", "p"), textbox("Orphan", "orphan", "missing")), owners)
                                .unexpectedElements());
        assertEquals(1, validator.check(List.of(body("Body", "p"), textbox("Cycle", "cycle", "cycle")), owners)
                                .unexpectedElements());
    }

    @Test
    void unicodeWhitespaceAndCodePointLengthMatchTheSavedOracle() throws IOException {
        assertEquals("a b c d", DocxOwnership.normalize("\u00a0a\u0085b\u001cc\u202fd\u2003"));
        final var owners = owners(table(p("😀") + p("😀a")));
        assertTrue(validator.check(List.of(tableElement("😀a 😀")), owners).passed());
        assertFalse(validator.check(List.of(tableElement("😀a")), owners).passed());
    }

    @Test
    void maliciousXmlAndMissingDocumentBodyRejectWithoutExternalAccess() {
        assertThrows(IOException.class,
                ()
                        -> source("<!DOCTYPE w:document [<!ENTITY external SYSTEM 'file:///does-not-exist'>]>"
                                  + "<w:document xmlns:w='" + XmlOwnerReader.WORD + "'><w:body>" + p("&external;")
                                  + "</w:body></w:document>"));
        assertThrows(IOException.class, () -> source("<root/>"));
        assertThrows(IOException.class,
                () -> owners("<w:customXml>".repeat(260) + p("Too deep") + "</w:customXml>".repeat(260)));
    }

    @Test
    void sourceAndReportSnapshotsCannotBeMutatedAndDuplicateOutputIdsReject() throws IOException {
        final var owners = owners(p("Body"));
        assertThrows(UnsupportedOperationException.class, () -> owners.clear());
        assertThrows(UnsupportedOperationException.class, () -> owners.getFirst().paragraphs().clear());
        final var report = validator.check(List.of(body("Body", "p")), owners);
        assertThrows(UnsupportedOperationException.class, () -> report.owners().clear());
        assertThrows(IllegalArgumentException.class,
                () -> validator.check(List.of(body("A", "same"), body("B", "same")), owners));
    }
}
