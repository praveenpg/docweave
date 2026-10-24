package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pins existing flattening behavior so future opt-in preservation cannot silently change defaults. */
class NestedTableBaselineTest {
    @Test
    void ownedHtmlAndDocxDefaultsRetainExactFlattenedElementsAndWarnings() throws Exception {
        final var root = Path.of("benchmarks/review/nested-tables");
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readString(root.resolve("manifest.json")));
        assertEquals(manifest.get("baseline_sha256").asString(), sha(root.resolve("baseline.json")));
        final var baseline = mapper.readTree(Files.readString(root.resolve("baseline.json")));
        final var codec = ElementJson.create();
        int checked = 0;
        for (final var fixture : manifest.get("cases")) {
            final String name = fixture.get("file").asString();
            assertEquals(fixture.get("sha256").asString(), sha(root.resolve(name)));
            final var recorded = baseline.get("cases").get(checked++);
            assertEquals(name, recorded.get("file").asString());
            final var expected = codec.read(mapper.writeValueAsString(recorded.get("elements")));
            final var actual = DocWeave.create().partition(DocumentSource.of(root.resolve(name)));
            assertEquals(expected, actual.elements());
            assertEquals(mapper.readValue(mapper.writeValueAsString(recorded.get("warning_codes")), List.class),
                    actual.warnings().stream().map(warning -> warning.code().name()).toList());
            final var table = actual.elements().getFirst();
            final var links = (List<?>) table.metadata().get("links");
            final var link = (Map<?, ?>) links.getFirst();
            final int offset = (Integer) link.get("start_index");
            final int start = table.text().offsetByCodePoints(0, offset);
            assertTrue(table.text().substring(start).startsWith((String) link.get("text")));
            assertFalse(((String) table.metadata().get("text_as_html")).contains("colspan"));
        }
        assertEquals(2, checked);
    }

    private String sha(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
