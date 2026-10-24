package io.docweave.validation;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.xpath.XPathFactory;

/** Freezes one preselected publisher source without invoking extraction or exporting reference text. */
public final class FreezeScientificParagraphHoldout {
    /** Prevents utility construction. */
    private FreezeScientificParagraphHoldout() {
    }

    /**
     * Verifies publisher identity/date and freezes the unchanged ordinal paragraph selection.
     * This source-only step must finish before candidate extraction; unsupported references stop the run.
     * @param args committed selection JSON, source cache and fresh manifest path
     * @throws Exception if source bounds, identity, reference selection or fresh writing fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected selection, source cache and NEW manifest");
        }
        final var mapper = JsonMapper.builder().build();
        final var selectionPath = Path.of(args[0]);
        final var selected = mapper.readTree(Files.readAllBytes(selectionPath));
        final var cache = Path.of(args[1]);
        final String id = selected.get("id").asString();
        if (!id.matches("[a-z]+")) {
            throw new IllegalArgumentException("Invalid cache identity");
        }
        final byte[] pdf = bounded(cache.resolve(id + ".pdf"));
        final byte[] xml = bounded(cache.resolve(id + ".xml"));
        final var document = ScientificPublisherXml.parse(xml);
        final var xpath = XPathFactory.newInstance().newXPath();
        final String base = "/article/front/article-meta/";
        final String doi = xpath.evaluate(base + "article-id[@pub-id-type='doi']", document);
        final String date =
                xpath.evaluate(base + "pub-date[@pub-type='epub']/year", document) + "-"
                + String.format(java.util.Locale.ROOT, "%02d-%02d",
                        Integer.parseInt(xpath.evaluate(base + "pub-date[@pub-type='epub']/month", document)),
                        Integer.parseInt(xpath.evaluate(base + "pub-date[@pub-type='epub']/day", document)));
        if (!doi.equals(selected.get("doi").asString())
                || !date.equals(selected.get("provisional_publication_date").asString())) {
            throw new IllegalArgumentException("Publisher DOI or publication date differs from preselection");
        }
        final var source = new LinkedHashMap<String, Object>();
        source.put("id", id);
        source.put("doi", doi);
        source.put("publication_date", date);
        source.put("pdf", id + ".pdf");
        source.put("jats", id + ".xml");
        source.put("inputs", List.of(Map.of("filename", id + ".pdf", "bytes", pdf.length, "sha256", hash(pdf), "url",
                                             selected.get("pdf_url").asString()),
                                     Map.of("filename", id + ".xml", "bytes", xml.length, "sha256", hash(xml), "url",
                                             selected.get("jats_url").asString())));
        source.put("reference", ScientificReadingEvaluation.identity(ScientificReadingEvaluation.references(xml)));
        final var manifest = new LinkedHashMap<String, Object>();
        manifest.put("frozen_at", Instant.now().toString());
        manifest.put("production_commit", selected.get("production_commit").asString());
        manifest.put("selection_sha256", hash(Files.readAllBytes(selectionPath)));
        manifest.put("rule", selected.get("protocol").asString());
        manifest.put("profiles", List.of("LINES", "PARAGRAPHS", "COLUMN_PARAGRAPHS", "SOURCE_PARAGRAPHS_PRESERVE",
                                         "SOURCE_PARAGRAPHS_SOFT_HYPHENS"));
        manifest.put("cases", List.of(source));
        Files.writeString(Path.of(args[2]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Bounds original source size before allocating its byte array.
     * @param path selected PDF or publisher XML
     * @return unchanged original source bytes
     * @throws Exception if missing, empty, over16MiB or unreadable
     */
    private static byte[] bounded(final Path path) throws Exception {
        if (Files.size(path) < 1 || Files.size(path) > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Selected source exceeds the bounded protocol");
        }
        return Files.readAllBytes(path);
    }

    /**
     * Hashes original bytes without normalization.
     * @param bytes unchanged source or selection file
     * @return lowercase SHA256 identity
     * @throws Exception if SHA256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
