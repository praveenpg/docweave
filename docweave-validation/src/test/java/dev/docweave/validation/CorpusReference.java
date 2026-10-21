package dev.docweave.validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads actual upstream-generated reference JSON without invoking or emulating the upstream implementation. */
record CorpusReference(String sha256, JsonNode provenance, Map<String, JsonNode> cases) {
    CorpusReference {
        cases = Map.copyOf(cases);
    }

    static CorpusReference read(final Path path) throws IOException {
        final byte[] bytes = Files.readAllBytes(path);
        final var reference = JsonMapper.builder().build().readTree(bytes);
        DefaultWordEvidenceComparator.require(reference.has("reference") && reference.get("reference").isObject(),
                "Upstream reference provenance required");
        final var cases = new LinkedHashMap<String, JsonNode>();
        for (final var entry : DefaultWordEvidenceComparator.array(reference, "cases")) {
            final String name = DefaultWordEvidenceComparator.filename(entry, "input", ".docx");
            DefaultWordEvidenceComparator.require(
                    cases.putIfAbsent(name, entry) == null, "Duplicate upstream reference input");
        }
        return new CorpusReference(DocxOwnership.sha256(bytes), reference.get("reference"), cases);
    }

    /** Reference mismatches reject, while upstream text differences remain diagnostics rather than Java checks. */
    Map<String, Object> metrics(final String filename, final String inputHash, final List<String> paragraphs)
            throws IOException {
        final var entry = cases.get(filename);
        DefaultWordEvidenceComparator.require(entry != null, "Missing upstream reference input");
        DefaultWordEvidenceComparator.require(
                inputHash.equals(DefaultWordEvidenceComparator.text(entry, "input_sha256")),
                "Upstream reference input hash differs from manifest");
        final var elements = new ArrayList<DocxCorpusSource.TextElement>();
        for (final var element : DefaultWordEvidenceComparator.array(entry, "upstream_output")) {
            elements.add(new DocxCorpusSource.TextElement(DefaultWordEvidenceComparator.text(element, "type"),
                    DefaultWordEvidenceComparator.text(element, "text")));
        }
        return DocxCorpusSource.metrics(elements, paragraphs);
    }
}
