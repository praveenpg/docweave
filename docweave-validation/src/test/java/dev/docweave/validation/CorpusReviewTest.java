package dev.docweave.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real public-API extraction, saved-summary migration checks and input/output contract failures. */
class CorpusReviewTest {
    @TempDir Path temporary;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void ownedCorpusMatchesSavedOwnershipSummariesWithoutPython() throws IOException {
        final var report = CorpusReview.run(CorpusReview.OWNED.resolve("manifest.json"), CorpusReview.OWNED);
        final var output = CorpusReview.defaultOutput();
        CorpusReview.write(output, report);
        assertEquals(true, report.get("checks_passed"));
        final var actual = JSON.valueToTree(report);
        final var saved = JSON.readTree(Files.readString(CorpusReview.OWNED.resolve("review-body-scope.json")));
        for (int i = 0; i < saved.get("documents").size(); i++) {
            for (final String mode : List.of("defaults", "include_headers_footers")) {
                assertEquals(saved.get("documents").get(i).get("java").get(mode).get("ownership"),
                        actual.get("documents").get(i).get("java").get(mode).get("ownership"));
            }
        }
        assertFalse(actual.has("upstream_provenance"));
        assertFalse(actual.get("runtime_classpath").isEmpty());
    }

    @Test
    void externalCorpusIsOptInAndRequiresCompleteLocalConfiguration() throws IOException {
        final String manifest = System.getProperty("docweave.corpus.manifest");
        final String cache = System.getProperty("docweave.corpus.cache");
        final String output = System.getProperty("docweave.corpus.report");
        if (manifest == null && cache == null && output == null) {
            assertNull(
                    System.getProperty("docweave.corpus.reference"), "Corpus reference requires manifest/cache/report");
            assertNull(
                    System.getProperty("docweave.corpus.baseline"), "Corpus baseline requires manifest/cache/report");
            return; // No network or external fixture dependency in the default reactor.
        }
        assertNotNull(manifest, "Set docweave.corpus.manifest, cache and report together");
        assertNotNull(cache, "Set docweave.corpus.manifest, cache and report together");
        assertNotNull(output, "Set docweave.corpus.manifest, cache and report together");
        assertFalse(Files.exists(Path.of(output)), "External corpus report must be a fresh path");
        final String baseline = System.getProperty("docweave.corpus.baseline", "ownership-v1");
        assertTrue(List.of("ownership-v1", "substring").contains(baseline), "Unknown corpus baseline");
        final String reference = System.getProperty("docweave.corpus.reference");
        final var report = CorpusReview.run(Path.of(manifest), Path.of(cache),
                baseline.equals("substring") ? CorpusReview.Baseline.SUBSTRING : CorpusReview.Baseline.OWNERSHIP,
                reference == null ? null : Path.of(reference));
        CorpusReview.write(Path.of(output), report);
        assertEquals(true, report.get("checks_passed"), "See the text-free corpus report for diagnostic failures");
    }

    @Test
    void portableBasenameHashSizeAndByteOwnershipGuardsRejectChangedInputs() throws IOException {
        final var manifest = JSON.readTree(Files.readString(CorpusReview.OWNED.resolve("manifest.json")));
        final var source = manifest.get("documents").get(0);
        final var input = CorpusReview.input(source, CorpusReview.OWNED);
        final byte original = input.bytes()[0];
        final byte[] mutable = input.bytes();
        mutable[0]++;
        assertEquals(original, input.bytes()[0]);
        for (final String name :
                List.of("../outside.docx", "..\\outside.docx", "/outside.docx", ".docx", "wrong.txt")) {
            final var changed = JSON.createObjectNode();
            changed.put("filename", name);
            assertThrows(IOException.class, () -> CorpusReview.input(changed, temporary));
        }
        final Path copied = temporary.resolve(input.filename());
        Files.write(copied, input.bytes());
        final var wrongSize = JSON.createObjectNode();
        wrongSize.put("filename", input.filename());
        wrongSize.put("sha256", input.sha256());
        wrongSize.put("bytes", 1);
        assertThrows(IOException.class, () -> CorpusReview.input(wrongSize, temporary));
        Files.write(copied, new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> CorpusReview.input(source, temporary));
    }

    @Test
    void reportsNeverOverwriteExistingEvidence() throws IOException {
        final Path output = temporary.resolve("evidence.json");
        CorpusReview.write(output, Map.of("checks_passed", true));
        final String original = Files.readString(output);
        assertThrows(IOException.class, () -> CorpusReview.write(output, Map.of("checks_passed", false)));
        assertEquals(original, Files.readString(output));
    }

    @Test
    void missingAnchorsTablesStoredPartsAndWarningCountsFailTheRealJavaContract() throws IOException {
        final List<Consumer<ObjectNode>> changes = List.of(doc
                -> doc.putArray("required_text").add("Missing owned anchor"),
                doc
                -> doc.put("minimum_tables", 99),
                doc
                -> doc.putObject("expected_stored_parts").put("Header", 1),
                doc -> doc.putArray("expected_warning_codes").add("NESTED_TABLE_FLATTENED"));
        for (final var change : changes) {
            final var original = JSON.readTree(Files.readString(CorpusReview.OWNED.resolve("manifest.json")));
            final var doc = (ObjectNode) original.get("documents").get(0);
            change.accept(doc);
            final var manifest = JSON.createObjectNode();
            manifest.put("scope", "Owned contract failure probe");
            manifest.putArray("documents").add(doc);
            final var path = temporary.resolve("manifest.json");
            Files.writeString(path, JSON.writeValueAsString(manifest));
            assertEquals(false, CorpusReview.run(path, CorpusReview.OWNED).get("checks_passed"));
        }
    }
}
