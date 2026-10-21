import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Java 21-compatible standalone source-file launcher. */
final class CreateDocxContentFixture {
    /** Prevents external construction of the launcher. */
    private CreateDocxContentFixture() {
    }

    /**
     * Runs the standalone tool using a document-confined launcher instance.
     * @param args command-line arguments
     * @throws Exception if processing or I/O fails
     */
    public static void main(final String[] args) throws Exception {
        new CreateDocxContentFixture().run(args);
    }

    /** Generates a synthetic, project-owned DOCX for content-fidelity smoke checks. */
    private void run(final String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: CreateDocxContentFixture.java output.docx");
        }
        try (final var doc = new XWPFDocument()) {
            final var heading = doc.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Content report");
            final var note = doc.createFootnote();
            note.createParagraph().createRun().setText("Footnote explanation.");
            final var endnote = doc.createEndnote();
            endnote.createParagraph().createRun().setText("Endnote explanation.");
            final var comment = doc.createComments().createComment(BigInteger.valueOf(3));
            comment.setAuthor("Reviewer");
            comment.createParagraph().createRun().setText("Review comment.");
            var p = doc.createParagraph();
            p.getCTP().addNewCommentRangeStart().setId(BigInteger.valueOf(3));
            p.createRun().setText("🧵 Body with references.");
            p.addFootnoteReference(note);
            p.getCTP().addNewR().addNewEndnoteReference().setId(endnote.getId());
            p.getCTP().addNewCommentRangeEnd().setId(BigInteger.valueOf(3));
            p.getCTP().addNewR().addNewCommentReference().setId(BigInteger.valueOf(3));
            p = doc.createParagraph();
            p.createRun().setText("Selected revision: ");
            append(p.getCTP(), "<w:del w:id='1' w:author='Editor'><w:r><w:delText>old</w:delText></w:r></w:del><w:ins "
                                       + "w:id='2' w:author='Editor'><w:r><w:t>new</w:t></w:r></w:ins>");
            append(doc.getDocument().getBody(),
                    "<w:sdt><w:sdtContent><w:p><w:pPr><w:pStyle w:val='Heading2'/></w:pPr><w:r><w:t>Controlled "
                            + "section</w:t></w:r></w:p><w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w='2400'/><w:gridCol "
                            + "w:w='2400'/></w:tblGrid><w:tr><w:sdt><w:sdtContent><w:tc><w:p><w:r><w:t>Structured "
                            + "cell</w:t></w:r></w:p></w:tc></w:sdtContent></w:sdt><w:tc><w:p><w:r><w:t>Other "
                            + "cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:sdtContent></w:sdt>");
            final var table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Merged origin");
            table.getRow(0).getCell(0).getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.RESTART);
            table.getRow(0).getCell(1).setText("Top right");
            table.getRow(1).getCell(0).setText("Continuation text");
            table.getRow(1).getCell(0).getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.CONTINUE);
            table.getRow(1).getCell(1).setText("Bottom right");
            p = doc.createParagraph();
            p.createRun().setText("Illustration");
            final byte[] png = Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5xkAAAAASUVORK5CYII=");
            p.createRun().addPicture(
                    new ByteArrayInputStream(png), PictureType.PNG, "pixel.png", Units.toEMU(20), Units.toEMU(20));
            p.getRuns().getLast().getCTR().getDrawingArray(0).getInlineArray(0).getDocPr().setDescr("Thread icon");
            append(p.getCTP().addNewR(),
                    "<w:pict><v:rect style='width:120pt;height:40pt'><v:textbox><w:txbxContent><w:p><w:r><w:t>Text box "
                            + "paragraph.</w:t></w:r></w:p></w:txbxContent></v:textbox></v:rect></w:pict>");
            p = doc.createParagraph();
            append(p.getCTP(), "<w:fldSimple w:instr='DATE'><w:r><w:t>2026-10-14</w:t></w:r></w:fldSimple>");
            try (final var output = Files.newOutputStream(Path.of(args[0]))) {
                doc.write(output);
            }
        }
    }

    /** Inserts Word XML children while retaining the surrounding schema type. */
    private void append(final XmlObject parent, final String content) throws Exception {
        final var source =
                XmlObject.Factory.parse("<root xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' "
                                        + "xmlns:v='urn:schemas-microsoft-com:vml'>" + content + "</root>");
        try (final var from = source.newCursor(); final var to = parent.newCursor()) {
            from.toFirstChild();
            to.toEndToken();
            from.copyXmlContents(to);
        }
    }
}
