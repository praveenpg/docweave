package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.json.ElementJson;
import dev.docweave.partition.DocumentSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

/** Collects genuine current-library output from verified byte snapshots of copied controlled inputs. */
final class DefaultWordCandidateCollector implements WordCandidateCollector {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public JsonNode collect(final Path packet, final JsonNode manifest) throws IOException {
        final var cases = new ArrayList<Map<String, Object>>();
        final var engine = DocWeave.create();
        final var codec = ElementJson.create();
        for (final var entry : DefaultWordEvidenceComparator.manifestCases(manifest).entrySet()) {
            final String name = entry.getKey();
            final byte[] bytes = Files.readAllBytes(packet.resolve(name));
            final String hash = DocxOwnership.sha256(bytes);
            DefaultWordEvidenceComparator.require(hash.equals(entry.getValue().get("sha256").asString()),
                    "Pinned input changed before candidate collection");
            final var result = engine.partition(DocumentSource.bytes(bytes, name));
            cases.add(Map.of("file", name, "sha256", hash, "elements", JSON.readTree(codec.write(result.elements())),
                    "warnings", result.warnings().stream().map(w -> w.code().name()).toList()));
        }
        return JSON.valueToTree(
                Map.of("kind", "DocWeave observations only; not independent Word expectations", "cases", cases));
    }
}
