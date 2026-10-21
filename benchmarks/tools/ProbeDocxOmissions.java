import dev.docweave.DocWeave;
import dev.docweave.partition.DocumentSource;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlObject;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Java 21-compatible standalone source-file launcher. */
final class ProbeDocxOmissions {
    /** Prevents external construction of the launcher. */
    private ProbeDocxOmissions() {
    }

    /**
     * Runs the standalone tool using a document-confined launcher instance.
     * @param args command-line arguments
     * @throws Exception if processing or I/O fails
     */
    public static void main(final String[] args) throws Exception {
        new ProbeDocxOmissions().run(args);
    }

    /** Owned audit probes: reports actual text/diagnostics without claiming Word expectations. */
    private void run(final String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ProbeDocxOmissions.java new-output-directory");
        }
        final var root = Path.of(args[0]);
        Files.createDirectory(root);
        final var reports = new ArrayList<Map<String, Object>>();
        final String word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        final String rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        for (final String feature : List.of("symbol", "internal-altchunk")) {
            final var path = root.resolve(feature + ".docx");
            try (final var document = new XWPFDocument()) {
                final var before = document.createParagraph();
                before.createRun().setText("Before");
                XmlObject target;
                String content;
                if (feature.equals("symbol")) {
                    target = before.getCTP();
                    content = "<w:r><w:sym w:font='Wingdings' w:char='F0FC'/></w:r>";
                } else {
                    final var partName = PackagingURIHelper.createPartName("/word/afchunk.html");
                    final var part = document.getPackage().createPart(partName, "text/html");
                    try (final var output = part.getOutputStream()) {
                        output.write("<html><body><p>Imported content marker</p></body></html>".getBytes(
                                java.nio.charset.StandardCharsets.UTF_8));
                    }
                    final var relationship =
                            document.getPackagePart().addRelationship(partName, TargetMode.INTERNAL, rel + "/aFChunk");
                    target = document.getDocument().getBody();
                    content = "<w:altChunk r:id='" + relationship.getId() + "'/>";
                }
                final var xml = XmlObject.Factory.parse(
                        "<root xmlns:w='" + word + "' xmlns:r='" + rel + "'>" + content + "</root>");
                try (final var from = xml.newCursor(); final var to = target.newCursor()) {
                    from.toFirstChild();
                    to.toEndToken();
                    from.copyXmlContents(to);
                }
                document.createParagraph().createRun().setText("After");
                try (final var output = Files.newOutputStream(path)) {
                    document.write(output);
                }
            }
            final var result = DocWeave.create().partition(DocumentSource.of(path));
            reports.add(Map.of("feature", feature, "file", path.getFileName().toString(), "text",
                    result.elements().stream().map(element -> element.text()).toList(), "warning_codes",
                    result.warnings().stream().map(warning -> warning.code().name()).toList()));
        }
        Files.writeString(root.resolve("report.json"),
                JsonMapper.builder().build().writeValueAsString(Map.of(
                        "kind", "Actual local omission probes; not Word or upstream expectations", "cases", reports)));
    }
}
