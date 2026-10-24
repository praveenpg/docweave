package io.docweave.validation;

import io.docweave.partition.PdfFontInspector;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Optional pinned native-font inventory; published cell labels are not font correctness labels. */
class PdfFontCorpusTest {
    @Test
    void explicitIndependentFontInventoryHasNoCandidateGeneratedAccuracyOracle() throws Exception {
        final String cache = System.getProperty("docweave.pdf.fonts.cache");
        final String report = System.getProperty("docweave.pdf.fonts.report");
        if (cache == null && report == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(report);
        final var mapper = JsonMapper.builder().build();
        final var manifest =
                mapper.readTree(Files.readAllBytes(Path.of("benchmarks/review/pdf-independent-cells/manifest.json")));
        final var inventory = new ArrayList<Map<String, Object>>();
        final var inspector = PdfFontInspector.create();
        for (final var input : manifest.get("inputs")) {
            final String filename = input.get("filename").asString();
            if (!filename.endsWith(".pdf")) {
                continue;
            }
            final byte[] bytes = Files.readAllBytes(Path.of(cache).resolve(filename));
            assertEquals(input.get("bytes").asLong(), bytes.length);
            final String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertEquals(input.get("sha256").asString(), sha);
            final var inspected = inspector.inspect(bytes);
            assertTrue(inspected.pageCount() > 0);
            assertFalse(inspected.fonts().isEmpty());
            assertTrue(inspected.fonts().stream().mapToLong(PdfFontInspector.FontInfo::glyphs).sum() > 0);
            final var fonts = new ArrayList<Map<String, Object>>();
            for (final var font : inspected.fonts()) {
                final var entry = new LinkedHashMap<String, Object>();
                entry.put("id", font.id());
                entry.put("name", font.name());
                entry.put("subtype", font.subtype());
                entry.put("embedded", font.embedded());
                entry.put("damaged", font.damaged());
                entry.put("vertical", font.vertical());
                entry.put("standard14", font.standard14());
                entry.put("to_unicode_declared", font.toUnicodeDeclared());
                entry.put("glyphs", font.glyphs());
                entry.put("unmapped_glyphs", font.unmappedGlyphs());
                entry.put("replacement_glyphs", font.replacementGlyphs());
                entry.put("pages", font.pages());
                fonts.add(entry);
            }
            inventory.add(
                    Map.of("input", filename, "sha256", sha, "physical_pages", inspected.pageCount(), "fonts", fonts));
        }
        assertEquals(3, inventory.size());
        Files.write(Path.of(report),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("runtime",
                        Runtime.version().toString(), "source_commit", manifest.get("commit").asString(), "scope",
                        "Three pinned original native PDFs; observed used-font/mapping inventory only, no font or semantic accuracy oracle",
                        "results", inventory)),
                StandardOpenOption.CREATE_NEW);
    }
}
