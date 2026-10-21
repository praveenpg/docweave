package dev.docweave.partition;

import dev.docweave.DocWeave;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the pinned Word overlap observation without claiming general rendering parity. */
class DocxWordOverlapEvidenceTest {
    @Test
    void firstRowOutlineWinsAtFirstColumnOverlap() throws Exception {
        final var packet = Path.of("benchmarks/review/word-style-cases");
        final var report = JsonMapper.builder().build().readTree(
                Files.readString(packet.resolve("word-repeat-observations.json")).replace("\ufeff", ""));
        final var recorded = report.get("cases").get(2);
        assertEquals("row-column-overlap.docx", recorded.get("file").asString());
        final var path = packet.resolve(recorded.get("file").asString());
        assertEquals(recorded.get("sha256").asString(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        final var result = DocWeave.create().partition(DocumentSource.of(path));
        final var ranges = (List<?>) result.elements().getFirst().metadata().get("docx_table_style_paragraphs");
        assertEquals(9, ranges.size());
        for (int i = 0; i < ranges.size(); i++) { assertEquals(recorded.get("cells").get(i).get("outline_level").asInt(),
                    ((Map<?, ?>) ranges.get(i)).get("outline_level"));
        }
        assertEquals(List.of("firstRow", "firstCol", "nwCell"), ((Map<?, ?>) ranges.getFirst()).get("regions"));
        assertTrue(result.warnings().isEmpty());
    }
}
