package dev.docweave.partition;

import dev.docweave.DocWeave;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Regressions against versioned object-model evidence, not universal Word parity. */
class DocxWordNumberingEvidenceTest {
    @Test
    void pinnedDirectAndTableStyleSequencesMatchCollectedWordLabels() throws Exception {
        final var packet = Path.of("benchmarks/review/word-style-followups");
        final var observations = JsonMapper.builder().build().readTree(
                Files.readString(packet.resolve("word-observations.json")).replace("\ufeff", ""));
        final var selected = List.of("numbering-numpr-first.docx", "numbering-distinct-abstract.docx",
                "numbering-explicit-restart.docx", "direct-numbering-shared.docx");
        int checked = 0;
        final var engine = DocWeave.create();
        for (final var recorded : observations.get("cases")) {
            final String name = recorded.get("file").asString();
            if (!selected.contains(name)) {
                continue;
            }
            final var path = packet.resolve(name);
            assertEquals(recorded.get("sha256").asString(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            final var expected = new ArrayList<String>();
            for (final var cell : recorded.get("cells")) {
                expected.add(cell.get("list_string").asString());
            }
            final var table = engine.partition(DocumentSource.of(path)).elements().getFirst();
            final var items = (List<?>) table.metadata().get("docx_list_items");
            assertEquals(expected, items.stream().map(item -> ((Map<?, ?>) item).get("docx_list_label")).toList(), name);
            assertEquals(List.of("2", "2", "2", "1", "1", "1", "1", "1", "1"),
                    items.stream().map(item -> ((Map<?, ?>) item).get("docx_num_id")).toList(), name);
            checked++;
        }
        assertEquals(selected.size(), checked);
    }
}
