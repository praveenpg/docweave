import dev.docweave.DocWeave;
import dev.docweave.json.ElementJson;
import dev.docweave.partition.DocumentSource;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlObject;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Java 21-compatible standalone source-file launcher. */
final class ProbeNestedTables {
    /** Prevents external construction of the launcher. */
    private ProbeNestedTables() {
    }

    /**
     * Runs the standalone tool using a document-confined launcher instance.
     * @param args command-line arguments
     * @throws Exception if processing or I/O fails
     */
    public static void main(final String[] args) throws Exception {
        new ProbeNestedTables().run(args);
    }

    /** Owned nested-table baseline probes; records current output rather than a proposed schema. */
    private void run(final String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ProbeNestedTables.java new-output-directory");
        }
        final var root = Path.of(args[0]);
        Files.createDirectory(root);
        final String html =
                "<table><tr><td>😀 Before<table><caption>Child caption</caption>"
                + "<tr><th colspan='2'>Child heading</th></tr><tr><td><a href='https://example.com/item'>Repeated</a>"
                + "<table><tr><td>Grandchild</td></tr></table></td><td>Right</td></tr></table>"
                + "Between<table><tr><td>Repeated</td></tr></table>After</td><td>Outer right</td></tr></table>";
        final var htmlPath = root.resolve("nested.html");
        Files.writeString(htmlPath, html);
        final var docxPath = root.resolve("nested.docx");
        try (final var document = new XWPFDocument()) {
            final var table = document.createTable(1, 2);
            final var cell = table.getRow(0).getCell(0);
            cell.getParagraphs().getFirst().createRun().setText("😀 Before");
            table.getRow(0).getCell(1).getParagraphs().getFirst().createRun().setText("Outer right");
            final var relationship = document.getPackagePart().addExternalRelationship("https://example.com/item",
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink");
            final String xml =
                    "<root xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' "
                    + "xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'>"
                    + "<w:tbl><w:tblPr/><w:tr><w:tc><w:tcPr><w:gridSpan w:val='2'/></w:tcPr>"
                    + "<w:p><w:r><w:t>Child heading</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:p>"
                    + "<w:hyperlink r:id='" + relationship.getId() + "'><w:r><w:t>Repeated</w:t></w:r></w:hyperlink>"
                    + "</w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Grandchild</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                    + "</w:tc><w:tc><w:p><w:r><w:t>Right</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                    + "<w:p><w:r><w:t>Between</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Repeated</w:t>"
                    + "</w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>After</w:t></w:r></w:p></root>";
            final var source = XmlObject.Factory.parse(xml);
            try (final var from = source.newCursor(); final var to = cell.getCTTc().newCursor()) {
                from.toFirstChild();
                to.toEndToken();
                from.copyXmlContents(to);
            }
            try (final var output = Files.newOutputStream(docxPath)) {
                document.write(output);
            }
        }
        final var mapper = JsonMapper.builder().build();
        final var codec = ElementJson.create();
        final var reports = new ArrayList<Map<String, Object>>();
        for (final var path : List.of(htmlPath, docxPath)) {
            final var result = DocWeave.create().partition(DocumentSource.of(path));
            reports.add(Map.of("file", path.getFileName().toString(), "elements",
                    mapper.readValue(codec.write(result.elements()), List.class), "warning_codes",
                    result.warnings().stream().map(warning -> warning.code().name()).toList()));
        }
        Files.writeString(root.resolve("baseline.json"),
                mapper.writeValueAsString(Map.of("kind",
                        "Owned current-behavior probes; not Word expectations or proposed preservation output", "cases",
                        reports)));
    }
}
