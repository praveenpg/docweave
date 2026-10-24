import io.docweave.DocWeave;
import io.docweave.json.ElementJson;
import io.docweave.partition.DocumentSource;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Java 21-compatible standalone source-file launcher. */
final class CreateWordStyleReviewCases {
    /** Prevents external construction of the launcher. */
    private CreateWordStyleReviewCases() {
    }

    /**
     * Runs the standalone tool using a document-confined launcher instance.
     * @param args command-line arguments
     * @throws Exception if processing or I/O fails
     */
    public static void main(final String[] args) throws Exception {
        new CreateWordStyleReviewCases().run(args);
    }

    /** Generates owned semantic cases and candidate observations; never labels them Word ground truth. */
    record ReviewCase(
            String name, String question, String look, List<String> regions, boolean bandSize, boolean direct) {}

    String w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    String sha(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    void append(final XmlObject target, final String xml) throws Exception {
        final var source = XmlObject.Factory.parse("<root xmlns:w='" + w + "'>" + xml + "</root>");
        try (final var from = source.newCursor(); final var to = target.newCursor()) {
            from.toFirstChild();
            to.toEndToken();
            from.copyXmlContents(to);
        }
    }

    private void run(final String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: CreateWordStyleReviewCases.java output-directory");
        }
        final var directory = Path.of(args[0]);
        Files.createDirectories(directory);
        final var cases = List.of(
                new ReviewCase("unsized-bands", "Does an absent band size enable outline bands or leave body outline?",
                        "<w:tblLook w:noHBand='false' w:noVBand='true'/>", List.of("band1Horz", "band2Horz"), false,
                        false),
                new ReviewCase("edge-flag-without-branch",
                        "Does a first-row flag shift stripes when the style has no first-row branch?",
                        "<w:tblLook w:firstRow='true' w:noHBand='false' w:noVBand='true'/>",
                        List.of("band1Horz", "band2Horz"), true, false),
                new ReviewCase("row-column-overlap",
                        "Which outline wins where enabled first-row and first-column branches overlap?",
                        "<w:tblLook w:firstRow='true' w:firstColumn='true' w:noHBand='true' w:noVBand='true'/>",
                        List.of("firstRow", "firstCol"), false, false),
                new ReviewCase("whole-table-branch",
                        "Does Word apply the wholeTable paragraph outline or ignore the branch?",
                        "<w:tblLook w:noHBand='true' w:noVBand='true'/>", List.of("wholeTable"), false, false),
                new ReviewCase("table-style-numbering",
                        "Does Word apply numbering supplied only by table-style paragraph properties?",
                        "<w:tblLook w:firstRow='true' w:noHBand='true' w:noVBand='true'/>", List.of("firstRow"), false,
                        false),
                new ReviewCase("direct-override-control",
                        "Do direct body-outline and numId zero shield table-style properties?",
                        "<w:tblLook w:firstRow='true' w:noHBand='true' w:noVBand='true'/>",
                        List.of("wholeTable", "firstRow"), false, true));
        final var codec = ElementJson.create();
        final var mapper = JsonMapper.builder().build();
        final var manifest = new ArrayList<Map<String, Object>>();
        final var observations = new ArrayList<Map<String, Object>>();
        final var levels = Map.of("band1Horz", 0, "band2Horz", 1, "firstRow", 4, "firstCol", 5, "wholeTable", 6);
        for (final var item : cases) {
            final var path = directory.resolve(item.name() + ".docx");
            try (final var document = new XWPFDocument()) {
                document.createStyles();
                final var numbering = document.createNumbering();
                final var abstractNum = CTAbstractNum.Factory.newInstance();
                abstractNum.setAbstractNumId(BigInteger.ZERO);
                final var level = abstractNum.addNewLvl();
                level.setIlvl(BigInteger.ZERO);
                level.addNewStart().setVal(BigInteger.ONE);
                level.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
                level.addNewLvlText().setVal("%1.");
                numbering.addAbstractNum(new XWPFAbstractNum(abstractNum));
                numbering.addNum(BigInteger.ZERO, BigInteger.ONE);
                numbering.addNum(BigInteger.ZERO, BigInteger.TWO);
                final var style = CTStyle.Factory.newInstance();
                style.setStyleId("ReviewStyle");
                style.setType(STStyleType.TABLE);
                final var base = style.addNewPPr();
                base.addNewOutlineLvl().setVal(BigInteger.valueOf(9));
                final boolean numbered = item.name().equals("table-style-numbering") || item.direct();
                if (numbered) {
                    base.addNewNumPr().addNewNumId().setVal(BigInteger.ONE);
                }
                for (final var region : item.regions()) {
                    final String num = numbered ? "<w:numPr><w:numId w:val='2'/></w:numPr>" : "";
                    append(style, "<w:tblStylePr w:type='" + region + "'><w:pPr><w:outlineLvl w:val='"
                                          + levels.get(region) + "'/>" + num + "</w:pPr></w:tblStylePr>");
                }
                document.getStyles().addStyle(new XWPFStyle(style));
                final var table = document.createTable(3, 3);
                table.setStyleID("ReviewStyle");
                append(table.getCTTbl().getTblPr(), item.look());
                if (item.bandSize()) {
                    append(table.getCTTbl().getTblPr(), "<w:tblStyleRowBandSize w:val='1'/>");
                }
                for (int row = 0; row < 3; row++) {
                    for (int column = 0; column < 3; column++) {
                        final var paragraph = table.getRow(row).getCell(column).getParagraphs().getFirst();
                        paragraph.createRun().setText(item.name() + "-r" + row + "c" + column);
                        if (item.direct()) {
                            paragraph.setNumID(BigInteger.ZERO);
                            paragraph.getCTP().getPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(9));
                        }
                    }
                }
                try (final var output = Files.newOutputStream(path)) {
                    document.write(output);
                }
            }
            final var bytes = Files.readAllBytes(path);
            manifest.add(Map.of("file", path.getFileName().toString(), "sha256", sha(bytes), "rows", 3, "columns", 3,
                    "question", item.question(), "independent_review", "pending"));
            final var result = DocWeave.create().partition(DocumentSource.of(path));
            observations.add(Map.of("file", path.getFileName().toString(), "sha256", sha(bytes), "elements",
                    mapper.readValue(codec.write(result.elements()), List.class), "warnings",
                    result.warnings().stream().map(warning -> warning.code().name()).toList()));
        }
        Files.writeString(directory.resolve("manifest.json"),
                mapper.writeValueAsString(Map.of("scope",
                        "Owned generated review cases; candidate observations are not Word ground truth",
                        "independent_review", "pending", "cases", manifest)));
        Files.writeString(directory.resolve("candidate.json"),
                mapper.writeValueAsString(Map.of("kind",
                        "DocWeave observations only; not independent Word expectations", "cases", observations)));
    }
}
