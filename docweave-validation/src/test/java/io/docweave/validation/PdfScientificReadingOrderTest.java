package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfParagraphAssembler;
import io.docweave.partition.PdfPartitioner;
import io.docweave.partition.PdfTaggedParagraphOptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** JVM-only original scientific paragraph-text/order diagnostics against frozen publisher XML selections. */
class PdfScientificReadingOrderTest {
    /** Creates independent source-selection and complete-denominator controls. */
    PdfScientificReadingOrderTest() {
    }

    /**
     * Reads independently authored abstract/first-section paragraphs without selecting later easier content.
     * @throws Exception if owned publisher decoding fails
     */
    @Test
    void sourceSelectionKeepsInlineContentAndOrdinalBoundaries() throws Exception {
        final String xml = """
                <!DOCTYPE article SYSTEM 'file:///unavailable-publisher.dtd'>
                <article><front><article-meta><abstract><p>Abstract <italic>inline</italic>.</p>
                <sec><title>Group</title><p>Second<break/>abstract.</p></sec></abstract>
                <abstract><p>Later abstract excluded.</p></abstract></article-meta></front>
                <body><sec><title>Introduction</title><p>First body <xref>reference</xref>.</p>
                <sec><p>Nested body excluded.</p></sec><fig><p>Figure excluded.</p></fig>
                <p>Second body.</p></sec><sec><p>Later section excluded.</p></sec></body>
                <sub-article><body><sec><p>Other article excluded.</p></sec></body></sub-article></article>
                """;
        final var references = ScientificReadingEvaluation.references(xml.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("Abstract inline.", "Second abstract.", "First body reference.", "Second body."),
                references.stream().map(ScientificReadingEvaluation.Paragraph::text).toList());
        assertEquals(List.of("abstract", "abstract", "first_body_section", "first_body_section"),
                references.stream().map(ScientificReadingEvaluation.Paragraph::section).toList());
        final var result = ScientificReadingEvaluation.score(references,
                String.join("\n", references.stream().map(ScientificReadingEvaluation.Paragraph::text).toList()));
        assertEquals(4, result.get("uniquely_matched_paragraphs"));
        assertEquals(6, result.get("preserved_order_pairs"));
        assertEquals(true, result.get("all_exact_in_source_order"));
        final String many = "<article><front><article-meta><abstract><p>Abstract.</p></abstract></article-meta></front>"
                            + "<body><sec>"
                            + java.util.stream.IntStream.range(0, 10)
                                      .mapToObj(index -> "<p>Body_" + index + "</p>")
                                      .collect(Collectors.joining())
                            + "</sec></body></article>";
        final var bounded = ScientificReadingEvaluation.references(many.getBytes(StandardCharsets.UTF_8));
        assertEquals(9, bounded.size());
        assertEquals("Body_7", bounded.getLast().text());
        assertThrows(IllegalArgumentException.class,
                () -> ScientificReadingEvaluation.references("<article/>".getBytes(StandardCharsets.UTF_8)));
    }

    /** Missing/duplicated references retain all paragraph and pair denominators; reversed text never passes. */
    @Test
    void missingDuplicateAndReversedTextCannotSatisfySourceOrder() {
        final var references = List.of(new ScientificReadingEvaluation.Paragraph("owned", "First paragraph."),
                new ScientificReadingEvaluation.Paragraph("owned", "Second paragraph."),
                new ScientificReadingEvaluation.Paragraph("owned", "Third paragraph."));
        final var missing = ScientificReadingEvaluation.score(references, "First paragraph. Third paragraph.");
        assertEquals(3, missing.get("reference_paragraphs"));
        assertEquals(3, missing.get("reference_order_pairs"));
        assertEquals(1, missing.get("missing_paragraphs"));
        assertEquals(1, missing.get("preserved_order_pairs"));
        assertEquals(2, missing.get("unresolved_order_pairs"));
        final var duplicate = ScientificReadingEvaluation.score(
                references, "First paragraph. Second paragraph. Second paragraph. Third paragraph.");
        assertEquals(1, duplicate.get("duplicated_paragraphs"));
        assertEquals(1, duplicate.get("preserved_order_pairs"));
        assertEquals(2, duplicate.get("unresolved_order_pairs"));
        final var reversed =
                ScientificReadingEvaluation.score(references, "Third paragraph. Second paragraph. First paragraph.");
        assertEquals(3, reversed.get("uniquely_matched_paragraphs"));
        assertEquals(0, reversed.get("preserved_order_pairs"));
        assertEquals(3, reversed.get("reversed_order_pairs"));
        assertEquals(false, reversed.get("all_exact_in_source_order"));
        final var failure = ScientificReadingEvaluation.score(references, "");
        assertEquals(3, failure.get("missing_paragraphs"));
        assertEquals(3, failure.get("unresolved_order_pairs"));
    }

    /**
     * Complete-body selection preserves nested/later prose without including table, figure or subarticle text.
     * @throws Exception if independent source selection fails
     */
    @Test
    void completeBodyKeepsEverySectionAndDirectParagraphInSourceOrder() throws Exception {
        final String xml = """
                <article><front><article-meta><abstract><p>Abstract one.</p><sec><p>Abstract two.</p></sec>
                </abstract></article-meta></front><body><p>Direct body.</p><sec><p>Before nested.</p>
                <fig><p>Figure excluded.</p></fig><table-wrap><p>Table excluded.</p></table-wrap>
                <sec><p>Nested <italic>inline</italic><break/>body.</p></sec><p>After nested.</p>
                <boxed-text><p>Box excluded.</p></boxed-text></sec><sec><p>Later section.</p></sec></body>
                <back><p>Back excluded.</p></back><sub-article><body><sec><p>Subarticle excluded.</p></sec>
                </body></sub-article></article>
                """;
        final byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        final var legacy = ScientificReadingEvaluation.references(bytes);
        assertEquals(List.of("Abstract one.", "Abstract two.", "Before nested.", "After nested."),
                legacy.stream().map(ScientificReadingEvaluation.Paragraph::text).toList());
        final var complete = ScientificReadingEvaluation.references(bytes, true);
        final var expected = List.of("Abstract one.", "Abstract two.", "Direct body.", "Before nested.",
                "Nested inline body.", "After nested.", "Later section.");
        assertEquals(expected, complete.stream().map(ScientificReadingEvaluation.Paragraph::text).toList());
        assertEquals(legacy, ScientificReadingEvaluation.references(bytes));
        final var success = ScientificReadingEvaluation.score(complete, String.join("\n", expected));
        assertEquals(7, success.get("uniquely_matched_paragraphs"));
        assertEquals(21, success.get("preserved_order_pairs"));
        assertEquals(true, success.get("all_exact_in_source_order"));
        final var reversed = ScientificReadingEvaluation.score(complete, String.join("\n", expected.reversed()));
        assertEquals(21, reversed.get("reversed_order_pairs"));
        assertEquals(0, reversed.get("preserved_order_pairs"));
        final var missing = ScientificReadingEvaluation.score(complete, String.join("\n", expected.subList(0, 6)));
        assertEquals(7, missing.get("reference_paragraphs"));
        assertEquals(21, missing.get("reference_order_pairs"));
        assertEquals(1, missing.get("missing_paragraphs"));
        assertThrows(UnsupportedOperationException.class, complete::clear);
    }

    /**
     * Oversized/deep complete selections fail without truncation; historical first-section bounds remain unchanged.
     * @throws Exception if bounded original publisher selection fails
     */
    @Test
    void completeBodyRejectsIncompleteOrExcessiveSelectionsInsteadOfTruncating() throws Exception {
        final String prefix = "<article><front><article-meta><abstract><p>Abstract.</p></abstract></article-meta>"
                              + "</front><body>";
        final String suffix = "</body></article>";
        final String paragraphs = java.util.stream.IntStream.range(0, 1023)
                                          .mapToObj(i -> "<p>Paragraph_" + i + ".</p>")
                                          .collect(Collectors.joining());
        final byte[] boundary = (prefix + "<sec>" + paragraphs + "</sec>" + suffix).getBytes(StandardCharsets.UTF_8);
        assertEquals(1024, ScientificReadingEvaluation.references(boundary, true).size());
        assertEquals(9, ScientificReadingEvaluation.references(boundary).size());
        for (final String invalid : List.of("<sec>" + paragraphs + "<p>Extra.</p></sec>",
                     "<sec>".repeat(33) + "<p>Too deep.</p>"
                             + "</sec>".repeat(33),
                     "<fig><p>Not a body paragraph.</p></fig>")) {
            assertThrows(IllegalArgumentException.class,
                    ()
                            -> ScientificReadingEvaluation.references(
                                    (prefix + invalid + suffix).getBytes(StandardCharsets.UTF_8), true));
        }
        assertEquals(2, ScientificReadingEvaluation
                                .references((prefix + "<sec>".repeat(32) + "<p>At depth bound.</p>"
                                                    + "</sec>".repeat(32) + suffix)
                                                    .getBytes(StandardCharsets.UTF_8),
                                        true)
                                .size());
    }

    /** Overlapping spans, case/hyphen differences and non-ASCII whitespace do not receive repaired matches. */
    @Test
    void overlappingAndNormalizedAwayDifferencesCannotImproveScores() {
        final var overlap =
                ScientificReadingEvaluation.score(List.of(new ScientificReadingEvaluation.Paragraph("owned", "A B"),
                                                          new ScientificReadingEvaluation.Paragraph("owned", "B C")),
                        "A B C");
        assertEquals(0, overlap.get("preserved_order_pairs"));
        assertEquals(1, overlap.get("unresolved_order_pairs"));
        assertEquals(false, overlap.get("all_exact_in_source_order"));
        assertEquals("A B", ScientificReadingEvaluation.normalize(" \tA\n B\r "));
        assertEquals("A\u00a0B", ScientificReadingEvaluation.normalize("A\u00a0B"));
        final var reference = List.of(new ScientificReadingEvaluation.Paragraph("owned", "Case-sensitive text."));
        for (final String different :
                List.of("case-sensitive text.", "Casesensitive text.", "Case-sensitive\u00a0text.")) {
            assertEquals(1, ScientificReadingEvaluation.score(reference, different).get("missing_paragraphs"));
        }
    }

    /**
     * Extracts whole original PDFs under every frozen profile; references are supplied only to scoring afterward.
     * @throws Exception if identities, frozen labels, extraction invariants or fresh report writing fail
     */
    @Test
    void optionalOriginalScientificParagraphOrder() throws Exception {
        final String cache = System.getProperty("docweave.pdf.scientific.reading.cache");
        final String report = System.getProperty("docweave.pdf.scientific.reading.report");
        if (cache == null && report == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(report);
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(
                Files.readAllBytes(Path.of(System.getProperty("docweave.pdf.scientific.reading.manifest",
                        "benchmarks/review/pdf-scientific-reading/manifest.json"))));
        final var results = new ArrayList<Map<String, Object>>();
        final boolean completeBody;
        if (manifest.has("reference_selection")) {
            assertEquals("ABSTRACT_AND_ALL_BODY_SECTIONS", manifest.get("reference_selection").asString());
            completeBody = true;
        } else {
            completeBody = false;
        }
        for (final var selected : manifest.get("cases")) {
            for (final var input : selected.get("inputs")) {
                verify(Path.of(cache), input);
            }
            final var references = ScientificReadingEvaluation.references(
                    Files.readAllBytes(Path.of(cache, selected.get("jats").asString())), completeBody);
            assertEquals(
                    selected.get("reference"), mapper.valueToTree(ScientificReadingEvaluation.identity(references)));
            final byte[] bytes = Files.readAllBytes(Path.of(cache, selected.get("pdf").asString()));
            for (final var profile : manifest.get("profiles")) {
                final String name = profile.asString();
                final var base = PdfPartitioner.create(PdfOptions.defaults());
                final var strategy = switch (name) {
                    case "PRESENTATION_FORMS_LINES" ->
                        PdfPartitioner.create(PdfOptions.defaults().withUnicodeNormalization(
                                PdfOptions.UnicodeNormalization.PRESENTATION_FORMS));
                    case "PRESENTATION_FORMS_SOURCE_PARAGRAPHS" ->
                        PdfPartitioner
                                .create(PdfOptions.defaults().withUnicodeNormalization(
                                        PdfOptions.UnicodeNormalization.PRESENTATION_FORMS))
                                .withTaggedParagraphs(PdfTaggedParagraphOptions.defaults()
                                                .withWordWrap(PdfTaggedParagraphOptions.WordWrap.SOURCE_SOFT_HYPHENS)
                                                .withPageBoundary(
                                                        PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS));
                    case "SOURCE_PARAGRAPHS_PAGE_FRAGMENTS", "SOURCE_PARAGRAPHS_ASSEMBLED" ->
                        base.withTaggedParagraphs(PdfTaggedParagraphOptions.defaults()
                                        .withWordWrap(PdfTaggedParagraphOptions.WordWrap.SOURCE_SOFT_HYPHENS)
                                        .withPageBoundary(PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS));
                    case "SOURCE_PARAGRAPHS_LINE_WRAPS", "SOURCE_PARAGRAPHS_LINE_WRAPS_ASSEMBLED" ->
                        base.withTaggedParagraphs(PdfTaggedParagraphOptions.defaults()
                                        .withWordWrap(PdfTaggedParagraphOptions.WordWrap.SOURCE_LINE_WRAPS)
                                        .withPageBoundary(PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS));
                    case "SOURCE_PARAGRAPHS_INLINE_SCRIPTS", "SOURCE_PARAGRAPHS_INLINE_SCRIPTS_ASSEMBLED" ->
                        base.withTaggedParagraphs(PdfTaggedParagraphOptions.defaults()
                                        .withWordWrap(PdfTaggedParagraphOptions.WordWrap.SOURCE_LINE_WRAPS)
                                        .withPageBoundary(PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS)
                                        .withInlineScripts(
                                                PdfTaggedParagraphOptions.InlineScripts.NEARBY_SMALL_GLYPHS));
                    case "SOURCE_PARAGRAPHS_PRESERVE" ->
                        base.withTaggedParagraphs(PdfTaggedParagraphOptions.defaults());
                    case "SOURCE_PARAGRAPHS_SOFT_HYPHENS" ->
                        base.withTaggedParagraphs(PdfTaggedParagraphOptions.defaults().withWordWrap(
                                PdfTaggedParagraphOptions.WordWrap.SOURCE_SOFT_HYPHENS));
                    default -> PdfPartitioner.create(nativeProfile(name));
                };
                final var processor = DocWeave.create(PartitionerRegistry.of(List.of(strategy)));
                String output = "";
                String outcome = "SUCCESS";
                try {
                    final var extracted =
                            processor.partition(DocumentSource.bytes(bytes, selected.get("pdf").asString())).elements();
                    final var elements = (name.equals("SOURCE_PARAGRAPHS_ASSEMBLED")
                                                 || name.equals("SOURCE_PARAGRAPHS_LINE_WRAPS_ASSEMBLED")
                                                 || name.equals("SOURCE_PARAGRAPHS_INLINE_SCRIPTS_ASSEMBLED"))
                                                 ? PdfParagraphAssembler.create().assemble(extracted)
                                                 : extracted;
                    output = elements.stream()
                                     .filter(element -> element.type() != ElementType.Image)
                                     .map(element -> element.text())
                                     .collect(Collectors.joining("\n"));
                } catch (final PartitionException failure) {
                    // Whole-document failures keep every independently selected paragraph and pair in the denominator.
                    outcome = failure.code().name();
                }
                final var result =
                        new LinkedHashMap<String, Object>(ScientificReadingEvaluation.score(references, output));
                result.put("id", selected.get("id").asString());
                result.put("profile", name);
                result.put("outcome", outcome);
                result.put("ordered_reference_sha256",
                        selected.get("reference").get("ordered_reference_sha256").asString());
                results.add(result);
                System.out.printf("Scientific reading complete: %s layout=%s outcome=%s%n",
                        selected.get("id").asString(), name, outcome);
            }
        }
        Files.writeString(Path.of(report),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("runtime", Runtime.version().toString(), "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Verifies source byte identities before parsing references or invoking DocWeave.
     * @param root explicitly supplied source cache
     * @param input original source identity
     * @throws Exception if source size/hash differs or the source cannot be read
     */
    private static void verify(final Path root, final JsonNode input) throws Exception {
        final var path = root.resolve(input.get("filename").asString());
        assertEquals(input.get("bytes").asLong(), Files.size(path));
        assertEquals(input.get("sha256").asString(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
    }

    /**
     * Builds a native layout profile: a text layout name followed by optional {@code +INLINE_SCRIPTS} and
     * {@code +SIDE_STREAMS} or {@code +SIDE_STREAMS_ROW_TIES} or {@code +SIDE_STREAMS_ROW_FRAGMENTS} modifiers, each
     * selecting its explicit non-default policy.
     * @param name profile name
     * @return extraction options
     */
    private static PdfOptions nativeProfile(final String name) {
        final var parts = name.split("\\+");
        var options = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.valueOf(parts[0]));
        for (int index = 1; index < parts.length; index++) {
            options = switch (parts[index]) {
                case "INLINE_SCRIPTS" -> options.withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
                case "SIDE_STREAMS" -> options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS);
                case "SIDE_STREAMS_ROW_TIES" ->
                    options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES);
                case "SIDE_STREAMS_ROW_FRAGMENTS" ->
                    options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS);
                default -> throw new IllegalArgumentException("Unknown profile modifier");
            };
        }
        return options;
    }
}
