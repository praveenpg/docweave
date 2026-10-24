package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
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

/**
 * Complete publisher-caption text-presence baseline, separately reporting semantic caption output without label
 * repair.
 */
public final class ScorePublisherCaptions {
    /** Prevents standalone utility construction. */
    private ScorePublisherCaptions() {
    }

    /**
     * Scores every complete main-body caption under three uniform whole-original extraction profiles.
     * Reuses the existing strict ASCII-whitespace-only unique-presence/order scorer without fuzzy alignment.
     * Candidate extraction receives only source PDF bytes, never publisher XML, labels or selected regions.
     * @param args original scientific-reading manifest, explicit original cache and NEW numeric report
     * @throws Exception if source identities, complete references or fresh-output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        controls();
        require(args.length == 3 && !Files.exists(Path.of(args[2])), "Expected pinned manifest, cache and NEW report");
        final var mapper = JsonMapper.builder().build();
        final byte[] protocol = Files.readAllBytes(Path.of(args[0]));
        final var manifest = mapper.readTree(protocol);
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            for (final var input : selected.get("inputs")) {
                final String filename = input.get("filename").asString();
                require(filename.matches("[a-z]+\\.(pdf|xml)"), "Unsafe original filename");
                final var path = Path.of(args[1], filename);
                require(Files.size(path) <= 16 * 1024 * 1024 && Files.size(path) == input.get("bytes").asLong(),
                        "Original length changed");
                require(hash(Files.readAllBytes(path)).equals(input.get("sha256").asString()),
                        "Original source changed");
            }
            final var article =
                    ScientificPublisherXml.parse(Files.readAllBytes(Path.of(args[1], selected.get("jats").asString())))
                            .getDocumentElement();
            require(article.getTagName().equals("article"), "Expected main publisher article");
            final var bodies = direct(article, "body");
            require(bodies.size() == 1, "Expected unique main body");
            final var figures = bodies.getFirst().getElementsByTagName("fig");
            require(figures.getLength() > 0 && figures.getLength() <= 1024, "Invalid original figure population");
            final var references = new ArrayList<ScientificReadingEvaluation.Paragraph>();
            for (int index = 0; index < figures.getLength(); index++) {
                final var captions = direct((Element) figures.item(index), "caption");
                require(captions.size() == 1, "Source caption population changed");
                final String text = captionText(captions.getFirst());
                require(!text.isEmpty(), "Source caption reference empty");
                references.add(new ScientificReadingEvaluation.Paragraph("figure_" + index, text));
            }
            final var source =
                    DocumentSource.bytes(Files.readAllBytes(Path.of(args[1], selected.get("pdf").asString())),
                            selected.get("pdf").asString());
            for (final String profile : List.of("NATIVE_LINES", "NATIVE_COLUMNS", "SOURCE_TAGGED_LINES")) {
                final var options = PdfOptions.defaults().withTextLayout(
                        profile.equals("NATIVE_COLUMNS") ? PdfOptions.TextLayout.COLUMN_PARAGRAPHS
                                                         : PdfOptions.TextLayout.LINES);
                final var partitioner = profile.equals("SOURCE_TAGGED_LINES")
                                                ? PdfPartitioner.createWithTaggedTables(options)
                                                : PdfPartitioner.create(options);
                final var row = new LinkedHashMap<String, Object>();
                row.put("case", selected.get("id").asString());
                row.put("profile", profile);
                row.put("reference_identity", ScientificReadingEvaluation.identity(references));
                String allText = "";
                String captionText = "";
                long captions = 0;
                try {
                    final var elements =
                            DocWeave.create(PartitionerRegistry.of(List.of(partitioner))).partition(source).elements();
                    allText = String.join("\n", elements.stream()
                                                        .filter(element -> element.type() != ElementType.Image)
                                                        .map(element -> element.text())
                                                        .toList());
                    final var classified =
                            elements.stream().filter(element -> element.type() == ElementType.FigureCaption).toList();
                    captions = classified.size();
                    captionText = String.join("\n", classified.stream().map(element -> element.text()).toList());
                    row.put("outcome", "SUCCESS");
                } catch (final PartitionException failure) {
                    row.put("outcome", failure.code().name());
                }
                row.put("all_text_presence", ScientificReadingEvaluation.score(references, allText));
                row.put("classified_caption_presence", ScientificReadingEvaluation.score(references, captionText));
                row.put("classified_caption_elements", captions);
                results.add(row);
            }
        }
        require(results.size() == 9, "Incomplete source/profile population");
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime",
                        Runtime.version().toString(), "source_manifest_sha256", hash(protocol), "scope",
                        "Complete publisher main-body caption presence baseline; ASCII whitespace only, no case/Unicode/punctuation/hyphen repair, no physical caption-box oracle or image association accuracy claim",
                        "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Projects source caption blocks with structural line separators before ASCII-whitespace normalization.
     * Direct title/paragraph boundaries cannot concatenate unrelated end/start words. Inline content,
     * case, punctuation, Unicode and hyphens remain unchanged; no candidate output enters this rule.
     * @param caption original complete caption node
     * @return complete publisher text with title/paragraph boundaries represented by whitespace
     */
    private static String captionText(final Element caption) {
        final var text = new StringBuilder();
        for (var child = caption.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE && child.getNodeType() != Node.TEXT_NODE
                    && child.getNodeType() != Node.CDATA_SECTION_NODE) {
                continue;
            }
            final boolean block = child instanceof Element element
                                  && (element.getTagName().equals("title") || element.getTagName().equals("p"));
            if (block) {
                text.append('\n');
            }
            text.append(child.getTextContent());
            if (block) {
                text.append('\n');
            }
        }
        return ScientificReadingEvaluation.normalize(text.toString());
    }

    /**
     * Independently checks block separation, inline adjacency, preserved Unicode/NBSP and ignored XML commentary.
     * @throws Exception if secure owned XML parsing or reference projection fails
     */
    private static void controls() throws Exception {
        final var caption =
                ScientificPublisherXml
                        .parse(("<caption><!--not caption text--><title>Alpha "
                                + "<italic>β</italic>−12.</title><p>Gamma <sub>2</sub>H.\u00a0Z</p><?source ignored?></caption>")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .getDocumentElement();
        require(captionText(caption).equals("Alpha β−12. Gamma 2H.\u00a0Z"), "Owned source caption projection changed");
        require(direct(caption, "italic").isEmpty() && direct(caption, "p").size() == 1,
                "Nested source children incorrectly inherited");
    }

    /**
     * Selects direct source children without inheriting nested figure captions.
     * @param parent original DOM element
     * @param name exact direct child name
     * @return source-order matching children
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
     * Hashes complete original/protocol bytes without reporting source content.
     * @param bytes unchanged local input
     * @return lowercase SHA256
     * @throws Exception if hashing fails
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects changed originals or incomplete source populations.
     * @param condition required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
