package io.docweave.partition;

import io.docweave.DocWeave;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Owned unsupported-content fixtures: diagnostic fidelity without font mapping or import execution. */
class DocxOmissionDiagnosticsTest {
    private static final String SYMBOL = "<w:r><w:sym w:font='PRIVATE_FONT' w:char='F0FC'/></w:r>";

    @FunctionalInterface
    private interface Writer {
        void write(XWPFDocument document) throws Exception;
    }

    private byte[] document(final Writer writer) throws Exception {
        try (final var document = new XWPFDocument(); final var output = new ByteArrayOutputStream()) {
            writer.write(document);
            document.write(output);
            return output.toByteArray();
        }
    }

    private void append(final XmlObject target, final String content) throws Exception {
        final var xml = XmlObject.Factory.parse("<root xmlns:w='" + DocxBodyView.WORD + "' xmlns:r='"
                                                + DocxParagraphText.REL + "'>" + content + "</root>");
        try (final var from = xml.newCursor(); final var to = target.newCursor()) {
            from.toFirstChild();
            to.toEndToken();
            from.copyXmlContents(to);
        }
    }

    private PartitionResult parse(final byte[] data, final DocxContentOptions content) throws Exception {
        return DocWeave.create().partition(DocumentSource.bytes(data, "omissions.docx"),
                PartitionOptions.builder().docxOptions(DocxOptions.defaults().withContentOptions(content)).build());
    }

    private void warnings(final PartitionResult result, final int count) {
        assertEquals(count, result.warnings().size());
        assertTrue(result.warnings().stream().allMatch(
                warning -> warning.code() == PartitionResult.WarningCode.DOCX_CONTENT_OMITTED));
        assertTrue(result.warnings().stream().noneMatch(warning
                -> warning.message().contains("PRIVATE") || warning.message().contains("F0FC")
                           || warning.message().contains("docweave-test:")));
    }

    @Test
    void symbolsLeaveTextIdsAndUnicodeLinkOffsetsUnchanged() throws Exception {
        final Writer baseline = doc -> {
            final var paragraph = doc.createParagraph();
            append(paragraph.getCTP(), "<w:r><w:t>😀 Before </w:t></w:r><w:hyperlink w:anchor='section'>"
                                               + "<w:r><w:t>link</w:t></w:r></w:hyperlink>");
        };
        final var expected = parse(document(baseline), DocxContentOptions.defaults());
        final var result = parse(document(doc -> {
            baseline.write(doc);
            append(doc.getParagraphs().getFirst().getCTP(), SYMBOL + SYMBOL);
        }),
                DocxContentOptions.defaults());
        assertEquals(expected.elements(), result.elements());
        warnings(result, 2);
    }

    @Test
    void symbolOnlyParagraphStillReportsLossWithoutInventingText() throws Exception {
        final var result =
                parse(document(doc -> append(doc.createParagraph().getCTP(), SYMBOL)), DocxContentOptions.defaults());
        assertTrue(result.elements().isEmpty());
        warnings(result, 1);
    }

    /** Empty VML rules do not change text identities or become image/text elements. */
    @Test
    void visualOnlyVmlRulesRetainSurroundingTextAndReportTheirOmission() throws Exception {
        final Writer baseline = doc -> doc.createParagraph().createRun().setText("😀 Visible text.");
        final var expected = parse(document(baseline), DocxContentOptions.defaults());
        final var actual = parse(document(doc -> {
            baseline.write(doc);
            append(doc.getParagraphs().getFirst().getCTP(),
                    "<w:r><w:pict xmlns:v='urn:schemas-microsoft-com:vml' "
                            + "xmlns:o='urn:schemas-microsoft-com:office:office'><v:rect id='PRIVATE_SHAPE' o:hr='t'/>"
                            + "</w:pict></w:r>");
        }),
                DocxContentOptions.defaults());
        assertEquals(expected.elements(), actual.elements());
        warnings(actual, 1);
        assertEquals(
                "DOCX drawing has no extractable image reference or text box", actual.warnings().getFirst().message());
    }

    /** A rule-only paragraph remains a diagnosed visual omission without fabricated content or coordinates. */
    @Test
    void ruleOnlyParagraphDoesNotInventAPlaceholderImageOrPageBreak() throws Exception {
        final var actual = parse(
                document(doc
                        -> append(doc.createParagraph().getCTP(),
                                "<w:r><w:pict xmlns:v='urn:schemas-microsoft-com:vml'><v:rect/></w:pict>"
                                        + "<w:pict xmlns:v='urn:schemas-microsoft-com:vml'><v:rect/></w:pict></w:r>")),
                DocxContentOptions.defaults());
        assertTrue(actual.elements().isEmpty());
        warnings(actual, 2);
    }

    @Test
    void internalImportedPayloadIsNotExtractedAndSurroundingTextIsPreserved() throws Exception {
        final var result = parse(document(doc -> {
            doc.createParagraph().createRun().setText("Before");
            final var name = PackagingURIHelper.createPartName("/word/import.html");
            final var part = doc.getPackage().createPart(name, "text/html");
            try (final var output = part.getOutputStream()) {
                output.write("<p>PRIVATE imported content</p>".getBytes(StandardCharsets.UTF_8));
            }
            final var relation =
                    doc.getPackagePart().addRelationship(name, TargetMode.INTERNAL, DocxParagraphText.REL + "/aFChunk");
            append(doc.getDocument().getBody(), "<w:altChunk r:id='" + relation.getId() + "'/>");
            doc.createParagraph().createRun().setText("After");
        }),
                DocxContentOptions.defaults());
        assertEquals(List.of("Before", "After"), result.elements().stream().map(element -> element.text()).toList());
        warnings(result, 1);
    }

    @Test
    void externalAndMissingImportRelationshipsAreReportedWithoutResolution() throws Exception {
        final var result = parse(document(doc -> {
            final var relation = doc.getPackagePart().addExternalRelationship(
                    "docweave-test://PRIVATE-import", DocxParagraphText.REL + "/aFChunk");
            append(doc.getDocument().getBody(),
                    "<w:altChunk r:id='" + relation.getId() + "'/><w:altChunk r:id='missing'/><w:altChunk/>");
        }),
                DocxContentOptions.defaults());
        assertTrue(result.elements().isEmpty());
        warnings(result, 3);
    }

    @Test
    void tableCellsAndContentControlsUseSameDiagnosticSink() throws Exception {
        final var result = parse(document(doc -> {
            final var cell = doc.createTable(1, 1).getRow(0).getCell(0);
            cell.getParagraphs().getFirst().createRun().setText("Cell");
            append(cell.getParagraphs().getFirst().getCTP(), SYMBOL);
            append(cell.getCTTc(), "<w:sdt><w:sdtContent><w:altChunk r:id='missing'/></w:sdtContent></w:sdt>");
        }),
                DocxContentOptions.defaults());
        assertEquals("Cell", result.elements().getFirst().text());
        warnings(result, 2);
    }

    @Test
    void revisionSelectionControlsWhichOmissionsAreReported() throws Exception {
        final var data = document(doc -> {
            append(doc.createParagraph().getCTP(), "<w:ins w:id='1'>" + SYMBOL + "</w:ins>"
                                                           + "<w:del w:id='2'>" + SYMBOL + SYMBOL + "</w:del>");
            append(doc.getDocument().getBody(), "<w:ins w:id='3'><w:altChunk/></w:ins>"
                                                        + "<w:del w:id='4'><w:altChunk/><w:altChunk/></w:del>");
        });
        for (final var revision : DocxContentOptions.Revisions.values()) {
            final var options =
                    new DocxContentOptions(true, true, true, DocxContentOptions.Images.REFERENCE, 1024, revision);
            final var result = parse(data, options);
            warnings(result, switch (revision) {
                case ACCEPTED -> 2;
                case ORIGINAL -> 4;
                case ALL -> 6;
            });
        }
    }

    @Test
    void supplementalPartsReportOnlyWhenSelected() throws Exception {
        final var data = document(doc -> {
            final var body = doc.createParagraph();
            body.createRun().setText("Main");
            append(body.getCTP().addNewR().addNewDrawing(), "<w:txbxContent><w:p><w:r><w:t>Box</w:t>"
                                                                    + "</w:r>" + SYMBOL
                                                                    + "</w:p><w:altChunk/></w:txbxContent>");
            final var note = doc.createFootnote();
            append(note.createParagraph().getCTP(), SYMBOL);
            append(note.getCTFtnEdn(), "<w:altChunk/>");
            final var comment = doc.createComments().createComment(BigInteger.ONE);
            append(comment.createParagraph().getCTP(), SYMBOL);
            append(comment.getCtComment(), "<w:altChunk/>");
            final var header = doc.createHeader(HeaderFooterType.DEFAULT);
            append(header.createParagraph().getCTP(), SYMBOL);
            append(header._getHdrFtr(), "<w:altChunk/>");
        });
        warnings(parse(data, DocxContentOptions.defaults()), 6);
        final var disabled = new DocxContentOptions(
                false, false, false, DocxContentOptions.Images.SKIP, 1024, DocxContentOptions.Revisions.ACCEPTED);
        final var skipped = parse(data, disabled);
        warnings(skipped, 0);
        assertEquals(List.of("Main"), skipped.elements().stream().map(element -> element.text()).toList());
        final var defaults = DocxOptions.defaults();
        final var headers = new DocxOptions(defaults.maxArchiveEntries(), defaults.maxExpandedBytes(),
                defaults.maxEntryBytes(), defaults.maxXmlDepth(), defaults.maxXmlNodes(), true, defaults.tableLimits());
        final var included = DocWeave.create().partition(
                DocumentSource.bytes(data, "omissions.docx"), PartitionOptions.builder().docxOptions(headers).build());
        warnings(included, 8);
    }
}
