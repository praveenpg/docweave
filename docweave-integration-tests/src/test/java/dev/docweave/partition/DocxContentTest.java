package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.batch.BatchProcessor;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlObject;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DocxContentTest {
    private static final String WORD = DocxBodyView.WORD;
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5xkAAAAASUVORK5CYII=");
    private final DocWeave processor = DocWeave.create();
    @FunctionalInterface
    interface Writer {
        void write(final XWPFDocument doc) throws Exception;
    }

    private byte[] document(final Writer writer) throws Exception {
        try (final var doc = new XWPFDocument(); final var output = new ByteArrayOutputStream()) {
            writer.write(doc);
            doc.write(output);
            return output.toByteArray();
        }
    }

    private PartitionResult parse(final byte[] bytes) throws Exception {
        return processor.partition(DocumentSource.bytes(bytes, "content.docx"));
    }

    private PartitionResult parse(final byte[] bytes, final DocxContentOptions options) throws Exception {
        return processor.partition(DocumentSource.bytes(bytes, "content.docx"),
                PartitionOptions.builder().docxOptions(DocxOptions.defaults().withContentOptions(options)).build());
    }

    private DocxContentOptions policy(
            final DocxContentOptions.Images images, final int max, final DocxContentOptions.Revisions revisions) {
        return new DocxContentOptions(true, true, true, images, max, revisions);
    }

    private List<String> texts(final PartitionResult result) {
        return result.elements().stream().map(Element::text).toList();
    }

    private void append(final XmlObject parent, final String content) throws Exception {
        final String xml =
                "<root xmlns:w='" + WORD + "' xmlns:r='" + DocxParagraphText.REL
                + "' xmlns:a='http://schemas.openxmlformats.org/drawingml/2006/main' xmlns:v='urn:schemas-microsoft-com:vml'>"
                + content + "</root>";
        final var source = XmlObject.Factory.parse(xml);
        try (final var from = source.newCursor(); final var to = parent.newCursor()) {
            from.toFirstChild();
            to.toEndToken();
            from.copyXmlContents(to);
        }
    }

    private XWPFPicture picture(final XWPFDocument doc, final String text) throws Exception {
        final var p = doc.createParagraph();
        p.createRun().setText(text);
        final var image = p.createRun().addPicture(new ByteArrayInputStream(PNG),
                org.apache.poi.common.usermodel.PictureType.PNG, "pixel.png", Units.toEMU(20), Units.toEMU(20));
        image.getCTPicture().getNvPicPr().getCNvPr().setDescr("Thread icon");
        p.getRuns().getLast().getCTR().getDrawingArray(0).getInlineArray(0).getDocPr().setDescr("Thread icon");
        return image;
    }

    @Test
    void orderedBodyAdapterKeepsParagraphText() throws Exception {
        try (final var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("body");
            final var elements =
                    new DocxBodyView(DocxContentOptions.defaults(), message -> fail(message)).elements(doc);
            assertEquals(1, elements.size());
            assertEquals("body", ((XWPFParagraph) elements.getFirst()).getText());
        }
    }

    @Test
    void notesAreEmittedOnceWithSourceReferenceIdsAndUnicodeOffsets() throws Exception {
        final var result = parse(document(doc -> {
            final var note = doc.createFootnote();
            note.createParagraph().createRun().setText("Footnote body");
            final var endnote = doc.createEndnote();
            endnote.createParagraph().createRun().setText("Endnote body");
            final var p = doc.createParagraph();
            p.createRun().setText("🧵 Body");
            p.addFootnoteReference(note);
            p.getCTP().addNewR().addNewEndnoteReference().setId(endnote.getId());
            final var again = doc.createParagraph();
            again.createRun().setText("Again");
            again.addFootnoteReference(note);
        }));
        assertEquals(List.of("🧵 Body", "Again", "Footnote body", "Endnote body"), texts(result));
        final var note = result.elements().get(2);
        assertEquals("footnote", note.metadata().get("docx_part"));
        assertEquals(result.elements().getFirst().elementId(), note.metadata().get("parent_id"));
        assertEquals(List.of(result.elements().getFirst().elementId(), result.elements().get(1).elementId()),
                note.metadata().get("docx_reference_element_ids"));
        final var refs = (List<?>) result.elements().getFirst().metadata().get("docx_references");
        assertEquals(6, ((Map<?, ?>) refs.getFirst()).get("start_index"));
    }

    @Test
    void separatorNotesAreNotDocumentTextAndNotesCanBeExcluded() throws Exception {
        final var bytes = document(doc -> {
            doc.createParagraph().createRun().setText("Body");
            final var separator = doc.createFootnote();
            separator.getCTFtnEdn().setType(STFtnEdn.SEPARATOR);
            separator.createParagraph().createRun().setText("Separator");
            doc.createFootnote().createParagraph().createRun().setText("Note");
        });
        assertEquals(List.of("Body", "Note"), texts(parse(bytes)));
        final var skip = new DocxContentOptions(
                false, false, false, DocxContentOptions.Images.SKIP, 100, DocxContentOptions.Revisions.ACCEPTED);
        assertEquals(List.of("Body"), texts(parse(bytes, skip)));
    }

    @Test
    void commentBodiesAndAuthorsLinkToCommentRanges() throws Exception {
        final var result = parse(document(doc -> {
            final var comment = doc.createComments().createComment(BigInteger.valueOf(7));
            comment.setAuthor("Reviewer");
            comment.createParagraph().createRun().setText("Review comment");
            final var p = doc.createParagraph();
            p.getCTP().addNewCommentRangeStart().setId(BigInteger.valueOf(7));
            p.createRun().setText("Reviewed text");
            p.getCTP().addNewCommentRangeEnd().setId(BigInteger.valueOf(7));
            p.getCTP().addNewR().addNewCommentReference().setId(BigInteger.valueOf(7));
        }));
        assertEquals(List.of("Reviewed text", "Review comment"), texts(result));
        final var comment = result.elements().getLast();
        assertEquals("comment", comment.metadata().get("docx_part"));
        assertEquals("Reviewer", comment.metadata().get("docx_comment_author"));
        assertEquals("7", comment.metadata().get("docx_comment_id"));
        assertEquals(List.of(result.elements().getFirst().elementId()),
                comment.metadata().get("docx_reference_element_ids"));
    }

    @Test
    void embeddedImagesDefaultToAnchoredReferencesAndCanIncludeBoundedBytes() throws Exception {
        final var bytes = document(doc -> picture(doc, "🧵 body "));
        final var result = parse(bytes);
        assertEquals(List.of("🧵 body", "Thread icon"), texts(result));
        final var image = result.elements().getLast();
        assertEquals(ElementType.Image, image.type());
        assertEquals(result.elements().getFirst().elementId(), image.metadata().get("parent_id"));
        assertEquals(6, image.metadata().get("docx_anchor_text_offset"));
        assertEquals("image/png", image.metadata().get("image_mime_type"));
        assertTrue(image.metadata().containsKey("docx_image_part"));
        assertFalse(image.metadata().containsKey("image_base64"));
        final var embedded =
                parse(bytes, policy(DocxContentOptions.Images.EMBED, 1024, DocxContentOptions.Revisions.ACCEPTED))
                        .elements()
                        .getLast();
        assertArrayEquals(PNG, Base64.getDecoder().decode((String) embedded.metadata().get("image_base64")));
        assertEquals(64, embedded.metadata().get("image_sha256").toString().length());
        assertEquals(List.of("🧵 body"),
                texts(parse(bytes, policy(DocxContentOptions.Images.SKIP, 1, DocxContentOptions.Revisions.ACCEPTED))));
        final var failure = assertThrows(PartitionException.class,
                () -> parse(bytes, policy(DocxContentOptions.Images.EMBED, 1, DocxContentOptions.Revisions.ACCEPTED)));
        assertEquals(PartitionException.Code.IMAGE_LIMIT, failure.code());
    }

    @Test
    void imageOnlyParagraphsEmitImagesAndExternalImagesAreNeverFetched() throws Exception {
        final var bytes = document(doc -> {
            final var picture = picture(doc, "");
            final var relation = doc.getPackagePart().addExternalRelationship(
                    "http://127.0.0.1:9/not-fetched.png", DocxParagraphText.REL + "/image");
            final var blip = picture.getCTPicture().getBlipFill().getBlip();
            blip.unsetEmbed();
            blip.setLink(relation.getId());
        });
        final var result =
                parse(bytes, policy(DocxContentOptions.Images.EMBED, 1, DocxContentOptions.Revisions.ACCEPTED));
        assertEquals(1, result.elements().size());
        final var image = result.elements().getFirst();
        assertEquals(ElementType.Image, image.type());
        assertEquals("http://127.0.0.1:9/not-fetched.png", image.metadata().get("image_url"));
        assertEquals(true, image.metadata().get("docx_image_external"));
        assertFalse(image.metadata().containsKey("image_base64"));
    }

    @Test
    void textBoxesPreserveParagraphTableOrderAndAnchorWithoutDuplicateText() throws Exception {
        final var bytes = document(doc -> {
            final var p = doc.createParagraph();
            p.createRun().setText("Main");
            append(p.getCTP().addNewR().addNewDrawing(),
                    "<w:txbxContent><w:p><w:r><w:t>Box first</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Box cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>Box last</w:t></w:r></w:p></w:txbxContent>");
        });
        final var result = parse(bytes);
        assertEquals(List.of("Main", "Box first", "Box cell", "Box last"), texts(result));
        assertEquals(ElementType.Table, result.elements().get(2).type());
        for (final var element : result.elements().subList(1, 4)) {
            assertEquals("textbox", element.metadata().get("docx_part"));
            assertEquals(result.elements().getFirst().elementId(), element.metadata().get("parent_id"));
        }
        final var disabled = new DocxContentOptions(
                true, true, false, DocxContentOptions.Images.SKIP, 100, DocxContentOptions.Revisions.ACCEPTED);
        assertEquals(List.of("Main"), texts(parse(bytes, disabled)));
    }

    @Test
    void contentControlsKeepBlockTableAndCellStructureInsteadOfFlattening() throws Exception {
        final var result = parse(document(doc -> {
            append(doc.getDocument().getBody(),
                    "<w:sdt><w:sdtContent><w:p><w:pPr><w:pStyle w:val='Heading1'/></w:pPr><w:r><w:t>Control heading</w:t></w:r></w:p><w:tbl><w:tr><w:sdt><w:sdtContent><w:tc><w:p><w:r><w:t>Structured cell</w:t></w:r></w:p></w:tc></w:sdtContent></w:sdt></w:tr></w:tbl></w:sdtContent></w:sdt>");
        }));
        assertEquals(List.of("Control heading", "Structured cell"), texts(result));
        assertEquals(ElementType.Title, result.elements().getFirst().type());
        assertEquals(ElementType.Table, result.elements().getLast().type());
        assertTrue(result.elements()
                        .getLast()
                        .metadata()
                        .get("text_as_html")
                        .toString()
                        .contains("<td>Structured cell</td>"));
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void gridBeforeAndAfterBecomeEmptyLogicalPositions() throws Exception {
        final var result = parse(document(doc -> {
            final var t = doc.createTable(1, 1);
            final var row = t.getRow(0);
            row.getCell(0).setText("Middle");
            final var pr = row.getCtRow().addNewTrPr();
            pr.addNewGridBefore().setVal(BigInteger.ONE);
            pr.addNewGridAfter().setVal(BigInteger.TWO);
        }));
        assertEquals(List.of("\tMiddle\t\t"), texts(result));
        assertEquals(4, result.elements().getFirst().metadata().get("table_column_count"));
        assertTrue(result.elements().getFirst().metadata().get("text_as_html").toString().contains("colspan=\"2\""));
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void nonemptyVerticalMergeContinuationsKeepAllTextAndLinksAtTheirOrigin() throws Exception {
        final var result = parse(document(doc -> {
            final var t = doc.createTable(2, 2);
            final var a = t.getRow(0).getCell(0);
            a.setText("Origin");
            a.getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.RESTART);
            t.getRow(0).getCell(1).setText("Top right");
            final var b = t.getRow(1).getCell(0);
            b.getParagraphs().getFirst().createHyperlinkRun("https://example.com/continued").setText("Continued");
            b.getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.CONTINUE);
            t.getRow(1).getCell(1).setText("Bottom right");
        }));
        assertEquals(List.of("Origin\nContinued\tTop right\n\tBottom right"), texts(result));
        final var metadata = result.elements().getFirst().metadata();
        assertTrue(metadata.get("text_as_html").toString().contains("rowspan=\"2\">Origin<br>Continued"));
        assertEquals(List.of(7), metadata.get("link_start_indexes"));
        assertEquals("append_to_origin", metadata.get("docx_merge_content_policy"));
    }

    @Test
    void legacyHorizontalMergeMarkersPreserveContinuationContent() throws Exception {
        final var result = parse(document(doc -> {
            final var t = doc.createTable(1, 3);
            t.getRow(0).getCell(0).setText("Left");
            t.getRow(0).getCell(0).getCTTc().addNewTcPr().addNewHMerge().setVal(STMerge.RESTART);
            t.getRow(0).getCell(1).setText("Continued");
            t.getRow(0).getCell(1).getCTTc().addNewTcPr().addNewHMerge().setVal(STMerge.CONTINUE);
            t.getRow(0).getCell(2).setText("Right");
        }));
        assertEquals(List.of("Left\nContinued\t\tRight"), texts(result));
        assertTrue(result.elements()
                        .getFirst()
                        .metadata()
                        .get("text_as_html")
                        .toString()
                        .contains("colspan=\"2\">Left<br>Continued"));
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void unmatchedMergeContinuationsFailRatherThanDropContent() throws Exception {
        for (final boolean horizontal : new boolean[] {false, true}) {
            final var bytes = document(doc -> {
                final var c = doc.createTable(1, 1).getRow(0).getCell(0);
                c.setText("preserve or fail");
                final var pr = c.getCTTc().addNewTcPr();
                if (horizontal) {
                    pr.addNewHMerge().setVal(STMerge.CONTINUE);
                } else {
                    pr.addNewVMerge().setVal(STMerge.CONTINUE);
                }
            });
            assertEquals(PartitionException.Code.MALFORMED_DOCUMENT,
                    assertThrows(PartitionException.class, () -> parse(bytes)).code());
        }
    }

    @Test
    void nestedTableLinksAndNoteReferencesRetainComposedOffsets() throws Exception {
        final var result = parse(document(doc -> {
            final var c = doc.createTable(1, 1).getRow(0).getCell(0);
            c.getParagraphs().getFirst().createRun().setText("🧵");
            final var relationship = doc.getPackagePart().addExternalRelationship(
                    "https://example.com/nested", DocxParagraphText.REL + "/hyperlink");
            append(c.getCTTc(),
                    "<w:tbl><w:tr><w:tc><w:p><w:hyperlink r:id='" + relationship.getId()
                            + "'><w:r><w:t>Nested link</w:t></w:r></w:hyperlink></w:p></w:tc></w:tr></w:tbl>");
        }));
        assertEquals(List.of("🧵\nNested link"), texts(result));
        assertEquals(List.of(2), result.elements().getFirst().metadata().get("link_start_indexes"));
        assertEquals(List.of("https://example.com/nested"), result.elements().getFirst().metadata().get("link_urls"));
    }

    @Test
    void revisionPoliciesSelectStoredAlternativesWithMetadata() throws Exception {
        final var bytes = document(doc -> {
            final var p = doc.createParagraph();
            p.createRun().setText("Base ");
            append(p.getCTP(),
                    "<w:del w:id='1' w:author='Editor'><w:r><w:delText>old </w:delText></w:r></w:del><w:ins w:id='2' w:author='Editor'><w:r><w:t>new </w:t></w:r></w:ins><w:r><w:t>end</w:t></w:r>");
        });
        assertEquals(List.of("Base new end"), texts(parse(bytes)));
        assertEquals(List.of("Base old end"), texts(parse(bytes, policy(DocxContentOptions.Images.SKIP, 100,
                                                                         DocxContentOptions.Revisions.ORIGINAL))));
        final var all = parse(bytes, policy(DocxContentOptions.Images.SKIP, 100, DocxContentOptions.Revisions.ALL));
        assertEquals(List.of("Base old new end"), texts(all));
        final var changes = (List<?>) all.elements().getFirst().metadata().get("docx_revisions");
        assertEquals(2, changes.size());
        assertEquals("Editor", ((Map<?, ?>) changes.getFirst()).get("author"));
    }

    @Test
    void blockRevisionsAndControlsPreservePolicyAndOrdering() throws Exception {
        final var bytes = document(doc
                -> append(doc.getDocument().getBody(),
                        "<w:del><w:p><w:r><w:delText>Removed paragraph</w:delText></w:r></w:p></w:del><w:ins><w:p><w:r><w:t>Inserted paragraph</w:t></w:r></w:p></w:ins>"));
        assertEquals(List.of("Inserted paragraph"), texts(parse(bytes)));
        assertEquals(List.of("Removed paragraph"), texts(parse(bytes, policy(DocxContentOptions.Images.SKIP, 100,
                                                                              DocxContentOptions.Revisions.ORIGINAL))));
    }

    @Test
    void fieldsExposeCachedResultsAndInstructionsWithoutExecution() throws Exception {
        final var result = parse(document(doc -> {
            final var p = doc.createParagraph();
            append(p.getCTP(),
                    "<w:fldSimple w:instr='DATE'><w:r><w:t>2026-10-14</w:t></w:r></w:fldSimple><w:r><w:t xml:space='preserve'> </w:t></w:r><w:r><w:fldChar w:fldCharType='begin'/></w:r><w:r><w:instrText>INCLUDETEXT http://127.0.0.1:9/not-fetched</w:instrText></w:r><w:r><w:fldChar w:fldCharType='separate'/></w:r><w:r><w:t>Cached</w:t></w:r><w:r><w:fldChar w:fldCharType='end'/></w:r>");
        }));
        assertEquals(List.of("2026-10-14 Cached"), texts(result));
        assertEquals(List.of("DATE", "INCLUDETEXT http://127.0.0.1:9/not-fetched"),
                result.elements().getFirst().metadata().get("docx_field_instructions"));
        assertEquals("cached_results", result.elements().getFirst().metadata().get("docx_field_policy"));
    }

    @Test
    void paragraphPartHyperlinksUseTheirOwningRelationshipPart() throws Exception {
        final var defaults = DocxOptions.defaults();
        final var bytes = document(doc -> {
            doc.createParagraph().createHyperlinkRun("https://example.com/body").setText("Body");
            final var note = doc.createFootnote();
            note.createParagraph().createHyperlinkRun("https://example.com/note").setText("Note");
        });
        final var result = parse(bytes);
        assertEquals(List.of("https://example.com/body"), result.elements().getFirst().metadata().get("link_urls"));
        assertEquals(List.of("https://example.com/note"), result.elements().getLast().metadata().get("link_urls"));
    }

    @Test
    void notesCommentsAndImagesRoundTripWithoutLosingPartOrProvenance() throws Exception {
        final var bytes = document(doc -> {
            picture(doc, "Body");
            doc.createFootnote().createParagraph().createRun().setText("Note");
        });
        final var result = parse(bytes);
        final var json = ElementJson.create();
        assertEquals(result.elements(), json.read(json.write(result.elements())));
        final var chunks = Chunker.create().basic(result.elements(), ChunkOptions.defaults());
        final var originals =
                chunks.stream()
                        .flatMap(c -> json.decodeOriginals((String) c.metadata().get("orig_elements")).stream())
                        .toList();
        assertEquals(result.elements().size(), originals.size());
        assertTrue(originals.stream().anyMatch(
                e -> e.type() == ElementType.Image && e.metadata().containsKey("docx_image_part")));
    }

    @Test
    void contentPolicyReuseIsSafeAcrossVirtualThreadBatches() throws Exception {
        final var bytes = document(doc -> {
            picture(doc, "Body");
            doc.createFootnote().createParagraph().createRun().setText("Note");
        });
        final var results =
                BatchProcessor.create().mapOrdered(IntStream.range(0, 20).boxed().toList(), 4, ignored -> parse(bytes));
        for (final var result : results) {
            assertEquals(List.of("Body", "Thread icon", "Note"), texts(result));
        }
    }

    private void numbering(
            final XWPFDocument doc, final String format, final String template, final boolean neverRestart) {
        final var abstractNum = CTAbstractNum.Factory.newInstance();
        abstractNum.setAbstractNumId(BigInteger.ZERO);
        for (int i = 0; i < 2; i++) {
            final var lvl = abstractNum.addNewLvl();
            lvl.setIlvl(BigInteger.valueOf(i));
            lvl.addNewStart().setVal(BigInteger.ONE);
            lvl.addNewNumFmt().setVal(STNumberFormat.Enum.forString(format));
            lvl.addNewLvlText().setVal(i == 0 ? template : "%1.%2");
            if (neverRestart && i == 1) {
                lvl.addNewLvlRestart().setVal(BigInteger.ZERO);
            }
        }
        final var numbering = doc.createNumbering();
        numbering.addAbstractNum(new XWPFAbstractNum(abstractNum));
        numbering.addNum(BigInteger.ZERO, BigInteger.ONE);
    }

    private XWPFParagraph numbered(final XWPFDocument doc, final String text, final int depth) {
        final var p = doc.createParagraph();
        p.createRun().setText(text);
        p.setNumID(BigInteger.ONE);
        p.getCTP().getPPr().getNumPr().addNewIlvl().setVal(BigInteger.valueOf(depth));
        return p;
    }

    @Test
    void listLabelsAndRestartCountersAreMetadataWithoutChangingSemanticText() throws Exception {
        final var result = parse(document(doc -> {
            numbering(doc, "decimal", "%1.", false);
            numbered(doc, "First", 0);
            numbered(doc, "Child", 1);
            numbered(doc, "Next", 0);
            numbered(doc, "New child", 1);
        }));
        assertEquals(List.of("First", "Child", "Next", "New child"), texts(result));
        assertEquals(List.of("1.", "1.1", "2.", "2.1"),
                result.elements().stream().map(e -> e.metadata().get("docx_list_label")).toList());
    }

    @Test
    void neverRestartAndStartOverridesAreRespected() throws Exception {
        final var result = parse(document(doc -> {
            numbering(doc, "decimal", "%1.", true);
            final var ct = doc.getNumbering().getNum(BigInteger.ONE).getCTNum();
            final var override = ct.addNewLvlOverride();
            override.setIlvl(BigInteger.ZERO);
            override.addNewStartOverride().setVal(BigInteger.valueOf(5));
            numbered(doc, "First", 0);
            numbered(doc, "Child", 1);
            numbered(doc, "Next", 0);
            numbered(doc, "Child continues", 1);
        }));
        assertEquals(List.of("5.", "5.1", "6.", "6.2"),
                result.elements().stream().map(e -> e.metadata().get("docx_list_label")).toList());
    }

    @Test
    void commonRomanAlphabeticAndPaddedNumberingLabelsRenderDeterministically() throws Exception {
        for (final var entry :
                Map.of("lowerRoman", "i)", "upperRoman", "I)", "lowerLetter", "a)", "upperLetter", "A)", "decimalZero",
                           "01)", "ordinal", "1st)")
                        .entrySet()) {
            final var result = parse(document(doc -> {
                numbering(doc, entry.getKey(), "%1)", false);
                numbered(doc, "Item", 0);
            }));
            assertEquals(entry.getValue(), result.elements().getFirst().metadata().get("docx_list_label"));
        }
    }

    @Test
    void countersContinueThroughListsInsideTableCells() throws Exception {
        final var result = parse(document(doc -> {
            numbering(doc, "decimal", "%1.", false);
            numbered(doc, "Before", 0);
            final var cell = doc.createTable(1, 1).getRow(0).getCell(0);
            final var p = cell.getParagraphs().getFirst();
            p.createRun().setText("In cell");
            p.setNumID(BigInteger.ONE);
            numbered(doc, "After", 0);
        }));
        assertEquals("1.", result.elements().getFirst().metadata().get("docx_list_label"));
        final var items = (List<?>) result.elements().get(1).metadata().get("docx_list_items");
        assertEquals("2.", ((Map<?, ?>) items.getFirst()).get("docx_list_label"));
        assertEquals("3.", result.elements().getLast().metadata().get("docx_list_label"));
    }

    @Test
    void hyperlinkFieldsExposeCachedLabelAndTargetWithoutFetching() throws Exception {
        final var result = parse(document(doc -> {
            final var p = doc.createParagraph();
            p.createRun().setText("🧵 ");
            append(p.getCTP(),
                    "<w:r><w:fldChar w:fldCharType='begin'/></w:r><w:r><w:instrText>HYPER</w:instrText></w:r><w:r><w:instrText>LINK &quot;https://example.com/field&quot;</w:instrText></w:r><w:r><w:fldChar w:fldCharType='separate'/></w:r><w:r><w:t>Field link</w:t></w:r><w:r><w:fldChar w:fldCharType='end'/></w:r>");
        }));
        assertEquals(List.of("🧵 Field link"), texts(result));
        assertEquals(List.of("https://example.com/field"), result.elements().getFirst().metadata().get("link_urls"));
        assertEquals(List.of(2), result.elements().getFirst().metadata().get("link_start_indexes"));
        assertEquals(List.of("HYPERLINK \"https://example.com/field\""),
                result.elements().getFirst().metadata().get("docx_field_instructions"));
    }

    @Test
    void tableChunkOffsetsDoNotCopyWholeTableReferenceAnnotations() throws Exception {
        final var bytes = document(doc -> {
            final var note = doc.createFootnote();
            note.createParagraph().createRun().setText("Note");
            final var t = doc.createTable(8, 1);
            for (final var row : t.getRows()) {
                final var p = row.getCell(0).getParagraphs().getFirst();
                p.createRun().setText("Content ".repeat(10));
                p.addFootnoteReference(note);
            }
        });
        final var table = parse(bytes).elements().getFirst();
        assertTrue(table.metadata().containsKey("docx_references"));
        final var chunks = Chunker.create().basic(List.of(table), ChunkOptions.builder().maxCharacters(100).build());
        for (final var chunk : chunks) {
            assertFalse(chunk.metadata().containsKey("docx_references"));
            assertTrue(ElementJson.create()
                            .decodeOriginals((String) chunk.metadata().get("orig_elements"))
                            .getFirst()
                            .metadata()
                            .containsKey("docx_references"));
        }
    }

    @Test
    void rowContentControlsPreserveTheirCells() throws Exception {
        final var result = parse(document(doc
                -> append(doc.getDocument().getBody(),
                        "<w:tbl><w:sdt><w:sdtContent><w:tr><w:tc><w:p><w:r><w:t>Row control</w:t></w:r></w:p></w:tc></w:tr></w:sdtContent></w:sdt></w:tbl>")));
        assertEquals(List.of("Row control"), texts(result));
        assertEquals(ElementType.Table, result.elements().getFirst().type());
    }

    @Test
    void rowRevisionMarkersRespectAcceptedAndOriginalSelections() throws Exception {
        final var bytes = document(doc
                -> append(doc.getDocument().getBody(),
                        "<w:tbl><w:tr><w:trPr><w:del w:id='1'/></w:trPr><w:tc><w:p><w:r><w:t>Old row</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:trPr><w:ins w:id='2'/></w:trPr><w:tc><w:p><w:r><w:t>New row</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"));
        assertEquals(List.of("New row"), texts(parse(bytes)));
        assertEquals(List.of("Old row"), texts(parse(bytes, policy(DocxContentOptions.Images.SKIP, 100,
                                                                    DocxContentOptions.Revisions.ORIGINAL))));
    }

    @Test
    void validatesContentPolicies() {
        assertThrows(NullPointerException.class,
                () -> new DocxContentOptions(true, true, true, null, 10, DocxContentOptions.Revisions.ACCEPTED));
        assertThrows(NullPointerException.class, () -> DocxOptions.defaults().withContentOptions(null));
        assertThrows(IllegalArgumentException.class,
                () -> policy(DocxContentOptions.Images.EMBED, 0, DocxContentOptions.Revisions.ACCEPTED));
    }
}
