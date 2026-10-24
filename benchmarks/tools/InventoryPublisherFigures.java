package io.docweave.validation;

import org.w3c.dom.Element;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Source-only publisher figure/caption identity inventory, without reading DocWeave output or copying caption text. */
public final class InventoryPublisherFigures {
    /** Prevents standalone utility construction. */
    private InventoryPublisherFigures() {
    }

    /**
     * Preserves every main-body figure from the same pinned three original publisher PDF/JATS pairs.
     * No caption is selected by extraction success; absent labels, captions or graphic references stay visible.
     * This is a reference discovery inventory, not physical PDF figure geometry or output accuracy scoring.
     * @param args original scientific-reading manifest, explicit original cache and NEW numeric report
     * @throws Exception if original identities, secure XML, source structure or fresh output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 3 && !Files.exists(Path.of(args[2])), "Expected pinned manifest, cache and NEW report");
        final var mapper = JsonMapper.builder().build();
        final byte[] protocol = Files.readAllBytes(Path.of(args[0]));
        final var manifest = mapper.readTree(protocol);
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            for (final var input : selected.get("inputs")) {
                final String filename = input.get("filename").asString();
                require(filename.matches("[a-z]+\\.(xml|pdf)"), "Unsafe original filename");
                final var path = Path.of(args[1], filename);
                require(Files.size(path) <= 16 * 1024 * 1024 && Files.size(path) == input.get("bytes").asLong(),
                        "Original length changed");
                require(hash(Files.readAllBytes(path)).equals(input.get("sha256").asString()),
                        "Original source changed");
            }
            final var source =
                    ScientificPublisherXml.parse(Files.readAllBytes(Path.of(args[1], selected.get("jats").asString())));
            final var article = source.getDocumentElement();
            require(article.getTagName().equals("article"), "Expected main publisher article");
            final var bodies = direct(article, "body");
            require(bodies.size() == 1, "Expected unique main article body");
            final var figures = bodies.getFirst().getElementsByTagName("fig");
            require(figures.getLength() <= 1024, "Publisher figure discovery exceeds budget");
            final var rows = new ArrayList<Map<String, Object>>();
            for (int index = 0; index < figures.getLength(); index++) {
                final var figure = (Element) figures.item(index);
                final var labels = direct(figure, "label");
                final var captions = direct(figure, "caption");
                rows.add(Map.of("ordinal", index, "source_id", figure.getAttribute("id"), "direct_labels",
                        labels.size(), "direct_captions", captions.size(), "graphic_descendants",
                        figure.getElementsByTagName("graphic").getLength(), "label_identities", identities(labels),
                        "caption_identities", identities(captions)));
            }
            results.add(Map.of("case", selected.get("id").asString(), "doi", selected.get("doi").asString(),
                    "main_body_figures", figures.getLength(), "figures", rows));
        }
        require(results.size() == 3, "Incomplete original publisher population");
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime",
                        Runtime.version().toString(), "source_manifest_sha256", hash(protocol), "scope",
                        "All source main-body fig nodes, exact DOM text identities only; no PDF geometry, extraction, semantic alignment or accuracy score",
                        "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Selects only immediate source children, avoiding accidental label/caption inheritance from nested figures.
     * @param parent original source node
     * @param name exact unnamespaced publisher element name
     * @return source-order direct children
     */
    private static List<Element> direct(final Element parent, final String name) {
        final var result = new ArrayList<Element>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getTagName().equals(name)) {
                result.add(element);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Records exact DOM character identities without redistributing publisher text or repairing whitespace/Unicode.
     * @param nodes original direct source labels/captions
     * @return exact character lengths and SHA256 values in source order
     * @throws Exception if hashing fails
     */
    private static List<Map<String, Object>> identities(final List<Element> nodes) throws Exception {
        final var result = new ArrayList<Map<String, Object>>();
        for (final var node : nodes) {
            final String text = node.getTextContent();
            result.add(Map.of("utf16_units", text.length(), "code_points", text.codePointCount(0, text.length()),
                    "utf8_sha256", hash(text.getBytes(StandardCharsets.UTF_8))));
        }
        return List.copyOf(result);
    }

    /**
     * Hashes exact original/protocol/reference bytes without content-bearing diagnostics.
     * @param bytes unchanged source bytes
     * @return lowercase SHA256
     * @throws Exception if digest initialization fails
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects altered originals and unsupported source selection rather than dropping cases.
     * @param condition required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
