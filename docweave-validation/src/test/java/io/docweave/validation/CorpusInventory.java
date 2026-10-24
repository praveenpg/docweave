package io.docweave.validation;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Offline, hash-checked inventory runner. Source text is never included in serialized inventory reports. */
final class CorpusInventory {
    private CorpusInventory() {
    }

    static Map<String, Object> run(final Path manifestPath, final Path cache) throws IOException {
        final byte[] bytes = Files.readAllBytes(manifestPath);
        final var manifest = JsonMapper.builder().build().readTree(bytes);
        final var documents = new ArrayList<Map<String, Object>>();
        for (final var contract : DefaultWordEvidenceComparator.array(manifest, "documents")) {
            final var input = CorpusReview.input(contract, cache);
            final var snapshot = DocxCorpusSource.create().inspect(cache.resolve(input.filename()));
            if (!input.sha256().equals(DocxOwnership.sha256(Files.readAllBytes(cache.resolve(input.filename()))))) {
                throw new IOException("Corpus input changed during inventory");
            }
            final var entry = new LinkedHashMap<String, Object>(snapshot.inventory());
            entry.put("filename", input.filename());
            entry.put("sha256", input.sha256());
            documents.add(entry);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("scope",
                "Stored XML structure only; includes revision/fallback branches. No rendering/fidelity/human-review claim.");
        report.put("manifest_sha256", DocxOwnership.sha256(bytes));
        report.put("validation_sources", ValidationProvenance.sources());
        report.put("java_version", Runtime.version().toString());
        report.put("documents", documents);
        return report;
    }
}
