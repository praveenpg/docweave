package dev.docweave.partition;

import dev.docweave.DocWeave;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Versioned direct-body and heading controls; no universal Word precedence claim. */
class DocxWordDirectOutlineEvidenceTest {
    @Test
    void directBodyAndNumberingSuppressionMatchOriginalWordControl() throws Exception {
        final var packet = Path.of("benchmarks/review/word-style-cases");
        final var report = JsonMapper.builder().build().readTree(
                Files.readString(packet.resolve("word-repeat-observations.json")).replace("\ufeff", ""));
        final var recorded = report.get("cases").get(5);
        assertEquals("direct-override-control.docx", recorded.get("file").asString());
        final var path = packet.resolve(recorded.get("file").asString());
        assertEquals(recorded.get("sha256").asString(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        final var table = DocWeave.create().partition(DocumentSource.of(path)).elements().getFirst();
        final var ranges = (List<?>) table.metadata().get("docx_table_style_paragraphs");
        assertEquals(3, ranges.size());
        for (int i = 0; i < ranges.size(); i++) { assertEquals(recorded.get("cells").get(i).get("outline_level").asInt(),
                    ((Map<?, ?>) ranges.get(i)).get("outline_level"));
        }
        assertFalse(table.metadata().containsKey("docx_list_items"));
    }

    @Test
    void directHeadingControlsKeepObservedEffectiveHeadings() throws Exception {
        checkControls("word-outline-controls",
                Set.of("plain-direct-outline-0.docx", "plain-direct-outline-3.docx",
                        "conditional-direct-outline-0.docx", "conditional-direct-outline-3.docx"));
    }

    @Test
    void plainAndConditionalBodyControlsKeepObservedEffectiveOutlines() throws Exception {
        checkControls("word-style-followups", Set.of("plain-direct-body.docx", "conditional-direct-body.docx"));
    }

    private void checkControls(final String directory, final Set<String> selected) throws Exception {
        final var packet = Path.of("benchmarks/review", directory);
        final var report = JsonMapper.builder().build().readTree(
                Files.readString(packet.resolve("word-observations.json")).replace("\ufeff", ""));
        int checked = 0;
        for (final var recorded : report.get("cases")) {
            final String name = recorded.get("file").asString();
            if (!selected.contains(name)) {
                continue;
            }
            final var path = packet.resolve(name);
            assertEquals(recorded.get("sha256").asString(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            try (final var input = Files.newInputStream(path); final var document = new XWPFDocument(input)) {
                final var table = document.getTables().getFirst();
                final var layers = new DocxTableParagraphStyles(document).resolve(table);
                final var policies = new DocxParagraphPolicyResolver(document);
                for (final var cell : recorded.get("cells")) {
                    final int row = cell.get("row").asInt(), column = cell.get("column").asInt();
                    final var paragraph = table.getRow(row).getCell(column).getParagraphs().getFirst();
                    // These owned controls have firstRow enabled and no other positional families.
                    final Set<String> regions = row == 0 ? Set.of("firstRow") : Set.of();
                    final var policy =
                            policies.resolve(paragraph, layers.forRegions(regions), layers.conditionalOutline(regions));
                    assertEquals(cell.get("outline_level").asInt(), policy.heading() == 0 ? 9 : policy.heading() - 1,
                            name + " at " + row + "," + column);
                }
            }
            checked++;
        }
        assertEquals(selected.size(), checked);
    }
}
