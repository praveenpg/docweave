package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import org.w3c.dom.Element;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Whole-unit caption boundary audit; partial caption units never receive complete-caption credit. */
public final class AuditCaptionUnitBoundaries {
    /** Prevents standalone utility construction. */
    private AuditCaptionUnitBoundaries() {
    }

    /**
     * Compares entire classified units against unchanged source labels plus complete publisher captions.
     * The already frozen baseline serializer is invoked reflectively rather than duplicated or amended;
     * each resulting caption identity must equal its earlier reference before candidate extraction.
     * @param args original manifest, original cache, frozen caption baseline and NEW numeric report
     * @throws Exception if originals, reference identities, fixed populations or fresh output checks fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 4 && !Files.exists(Path.of(args[3])), "Expected originals, baseline and NEW report");
        final var mapper = JsonMapper.builder().build();
        final var sourceManifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var baseline = mapper.readTree(Files.readAllBytes(Path.of(args[2])));
        final var serializer = ScorePublisherCaptions.class.getDeclaredMethod("captionText", Element.class);
        serializer.setAccessible(true);
        final var results = new ArrayList<Map<String, Object>>();
        int baselineIndex = 0;
        for (final var selected : sourceManifest.get("cases")) {
            for (final var input : selected.get("inputs")) {
                final String filename = input.get("filename").asString();
                require(filename.matches("[a-z]+\\.(pdf|xml)"), "Unsafe source filename");
                final var path = Path.of(args[1], filename);
                require(Files.size(path) <= 16 * 1024 * 1024 && Files.size(path) == input.get("bytes").asLong(),
                        "Original length changed");
                require(hash(Files.readAllBytes(path)).equals(input.get("sha256").asString()),
                        "Original source changed");
            }
            final var article =
                    ScientificPublisherXml.parse(Files.readAllBytes(Path.of(args[1], selected.get("jats").asString())))
                            .getDocumentElement();
            final var figures = direct(article, "body").getElementsByTagName("fig");
            final var expected = new ArrayList<String>();
            final var captions = new ArrayList<String>();
            for (int index = 0; index < figures.getLength(); index++) {
                final var figure = (Element) figures.item(index);
                final String caption = (String) serializer.invoke(null, direct(figure, "caption"));
                captions.add(caption);
                expected.add(ScientificReadingEvaluation.normalize(direct(figure, "label").getTextContent()) + " "
                             + caption);
            }
            for (final String profile : List.of("NATIVE_LINES", "NATIVE_COLUMNS", "SOURCE_TAGGED_LINES")) {
                final var control = baseline.get("results").get(baselineIndex++);
                require(control.get("case").asString().equals(selected.get("id").asString())
                                && control.get("profile").asString().equals(profile)
                                && control.get("reference_identity").get("paragraphs").size() == captions.size(),
                        "Frozen reference population changed");
                for (int index = 0; index < captions.size(); index++) {
                    require(hash(captions.get(index).getBytes(StandardCharsets.UTF_8))
                                    .equals(control.get("reference_identity")
                                                    .get("paragraphs")
                                                    .get(index)
                                                    .get("sha256")
                                                    .asString()),
                            "Frozen caption reference changed");
                }
                final var options = PdfOptions.defaults()
                                            .withFigureCaptions(PdfOptions.FigureCaptions.IMAGE_ADJACENT_LABELS)
                                            .withTextLayout(profile.equals("NATIVE_COLUMNS")
                                                                    ? PdfOptions.TextLayout.COLUMN_PARAGRAPHS
                                                                    : PdfOptions.TextLayout.LINES);
                final var partitioner = profile.equals("SOURCE_TAGGED_LINES")
                                                ? PdfPartitioner.createWithTaggedTables(options)
                                                : PdfPartitioner.create(options);
                final var row = new LinkedHashMap<String, Object>();
                row.put("case", selected.get("id").asString());
                row.put("profile", profile);
                List<String> units = List.of();
                try {
                    units = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                                    .partition(DocumentSource.bytes(
                                            Files.readAllBytes(Path.of(args[1], selected.get("pdf").asString())),
                                            selected.get("pdf").asString()))
                                    .elements()
                                    .stream()
                                    .filter(element -> element.type() == ElementType.FigureCaption)
                                    .map(element -> ScientificReadingEvaluation.normalize(element.text()))
                                    .toList();
                    row.put("outcome", "SUCCESS");
                } catch (final PartitionException failure) {
                    row.put("outcome", failure.code().name());
                }
                final var details = new ArrayList<Map<String, Object>>();
                int unique = 0;
                for (int index = 0; index < expected.size(); index++) {
                    final String reference = expected.get(index);
                    final long count = units.stream().filter(reference::equals).count();
                    unique += count == 1 ? 1 : 0;
                    details.add(Map.of("ordinal", index, "expected_unit_sha256",
                            hash(reference.getBytes(StandardCharsets.UTF_8)), "exact_complete_unit_matches", count));
                }
                row.put("reference_captions", expected.size());
                row.put("emitted_caption_units", units.size());
                row.put("uniquely_exact_complete_units", unique);
                row.put("nonexact_emitted_units", units.stream().filter(unit -> !expected.contains(unit)).count());
                row.put("references", details);
                results.add(row);
            }
        }
        require(results.size() == 9 && baselineIndex == baseline.get("results").size(),
                "Incomplete caption audit population");
        Files.writeString(Path.of(args[3]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "Exact entire classified unit equals original publisher label plus complete frozen caption; no extra prefix/suffix tolerance, geometry oracle or semantic precision claim",
                        "runtime", Runtime.version().toString(), "baseline_sha256",
                        hash(Files.readAllBytes(Path.of(args[2]))), "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Requires one direct source child rather than inheriting a nested caption or label.
     * @param parent original source element
     * @param name required direct child
     * @return unique original child
     */
    private static Element direct(final Element parent, final String name) {
        Element found = null;
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getTagName().equals(name)) {
                require(found == null, "Duplicate source child");
                found = element;
            }
        }
        require(found != null, "Missing source child");
        return found;
    }

    /**
     * Hashes exact evidence bytes without retaining transcriptions in reports.
     * @param bytes original protocol/reference bytes
     * @return lowercase SHA256
     * @throws Exception if hashing fails
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects changed references without selecting a successful subset.
     * @param condition required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
