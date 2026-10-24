package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.DocxOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Owned positive/negative controls for optional external-corpus feature checks; no download is needed. */
class CorpusFeatureChecksTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path temporary;

    @Test
    void deletedFragmentsExcludeAmbiguousShortAndTextboxTextAndDetectBodyLeaks() throws IOException {
        final String deleted = "A distinctive deleted fragment with 😀 Unicode.";
        final String accepted = "An equally lengthy phrase retained in accepted source.";
        final String xml =
                "<w:document xmlns:w='" + XmlOwnerReader.WORD + "'><w:body><w:p>"
                + "<w:del><w:r><w:delText>" + deleted + "</w:delText><w:delText>" + deleted
                + "</w:delText><w:delText>short</w:delText><w:delText>" + accepted + "</w:delText></w:r></w:del>"
                + "<w:r><w:delText>Unmarked text must not become a negative expectation.</w:delText>"
                + "<w:txbxContent><w:p><w:del><w:r><w:delText>Text box deleted text is outside this body-only probe.</w:delText>"
                + "</w:r></w:del></w:p></w:txbxContent></w:r></w:p></w:body></w:document>";
        final var owners = List.of(new DocxOwnership.Owner(
                "body/0", DocxOwnership.Story.BODY, null, DocxOwnership.Kind.PARAGRAPH, List.of(accepted)));
        final var fragments = CorpusFeatureChecks.deletedFragments(
                XmlOwnerReader.parse(xml.getBytes(StandardCharsets.UTF_8)), owners);
        assertEquals(List.of(deleted), fragments);
        final var body = new Element("body", ElementType.NarrativeText, accepted, Map.of("docx_part", "body"));
        assertEquals(true, CorpusFeatureChecks.deletedText(fragments, List.of(body), 1).get("checks_passed"));
        final var leak = new Element("leak", ElementType.NarrativeText, deleted, Map.of("docx_part", "body"));
        final var failed = CorpusFeatureChecks.deletedText(fragments, List.of(body, leak), 1);
        assertEquals(false, failed.get("checks_passed"));
        assertEquals(List.of(DocxOwnership.textHash(deleted)), failed.get("unexpected_fragment_sha256"));
        assertFalse(JSON.writeValueAsString(failed).contains(deleted));
        final var header = new Element("header", ElementType.Header, deleted, Map.of("docx_part", "header"));
        assertEquals(true, CorpusFeatureChecks.deletedText(fragments, List.of(body, header), 1).get("checks_passed"));
        assertEquals(false, CorpusFeatureChecks.deletedText(fragments, List.of(body), 2).get("checks_passed"));
    }

    @Test
    void preservationComparesActualRootObjectsAndRequiresReviewedDescriptorCounts() throws IOException {
        final var manifest = JSON.readTree(Files.readString(CorpusReview.OWNED.resolve("manifest.json")));
        final var input = CorpusReview.input(manifest.get("documents").get(1), CorpusReview.OWNED);
        final var baseline = DocWeave.create().partition(DocumentSource.bytes(input.bytes(), input.filename()));
        final var passed = CorpusFeatureChecks.preservation(input, DocxOptions.defaults(), baseline, 3);
        assertEquals(true, passed.get("checks_passed"));
        assertEquals(3, passed.get("nested_descriptors"));
        assertEquals(false,
                CorpusFeatureChecks.preservation(input, DocxOptions.defaults(), baseline, 4).get("checks_passed"));
    }

    @Test
    void manifestFeatureMismatchesAndInsufficientDeletionProbesNeverSilentlyPass() throws IOException {
        final var original = JSON.readTree(Files.readString(CorpusReview.OWNED.resolve("manifest.json")));
        final var document = (ObjectNode) original.get("documents").get(1);
        final Path manifest = temporary.resolve("manifest.json");
        document.putObject("required_source_features").put("nested_tables", 999);
        Files.writeString(manifest, JSON.writeValueAsString(original));
        assertThrows(IOException.class, () -> CorpusReview.run(manifest, CorpusReview.OWNED));
        document.putObject("required_source_features").put("nested_tables", "3");
        Files.writeString(manifest, JSON.writeValueAsString(original));
        assertThrows(IOException.class, () -> CorpusReview.run(manifest, CorpusReview.OWNED));
        document.remove("required_source_features");
        document.put("expected_nested_descriptors", 999);
        Files.writeString(manifest, JSON.writeValueAsString(original));
        assertEquals(false, CorpusReview.run(manifest, CorpusReview.OWNED).get("checks_passed"));
        document.remove("expected_nested_descriptors");
        document.put("minimum_deleted_fragments_checked", 1);
        Files.writeString(manifest, JSON.writeValueAsString(original));
        assertEquals(false, CorpusReview.run(manifest, CorpusReview.OWNED).get("checks_passed"));
        assertThrows(IOException.class,
                () -> CorpusReview.run(manifest, CorpusReview.OWNED, CorpusReview.Baseline.SUBSTRING, null));
    }
}
