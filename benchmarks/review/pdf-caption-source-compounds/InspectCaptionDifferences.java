package dev.docweave.validation;
import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.*;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Read-only exact-prefix localization; never relaxes a reference or extraction score. */
final class InspectCaptionDifferences {
    /** Prevents standalone utility construction. */
    private InspectCaptionDifferences() {
    }

    /**
     * Localizes a strict mismatch using one unique fixed source prefix without changing the extraction score.
     * @param args original cache, frozen raw baseline and NEW numeric diagnostic
     * @throws Exception if parsing, source identity or fresh-output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        final var root = Path.of(args[0]);
        final var mapper = JsonMapper.builder().build();
        final var baseline = mapper.readTree(Files.readAllBytes(Path.of(args[1]))).get("results").get(0);
        final var elements =
                DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults()))))
                        .partition(root.resolve("article.pdf"))
                        .elements();
        final var text = ScientificReadingEvaluation.normalize(String.join(
                "\n", elements.stream().filter(e -> e.type() != ElementType.Image).map(e -> e.text()).toList()));
        final var digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        if (!digest.equals(baseline.get("all_text_presence").get("candidate_sha256").asString()))
            throw new IllegalStateException("Baseline text changed");
        final var xml = ScientificPublisherXml.parse(Files.readAllBytes(root.resolve("article.xml")));
        final var figures =
                ((org.w3c.dom.Element) xml.getElementsByTagName("body").item(0)).getElementsByTagName("fig");
        final var serializer = ScorePublisherCaptions.class.getDeclaredMethod("captionText", org.w3c.dom.Element.class);
        serializer.setAccessible(true);
        final var rows = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < figures.getLength(); index++) {
            final var figure = (org.w3c.dom.Element) figures.item(index);
            final var reference = (String) serializer.invoke(
                    null, (org.w3c.dom.Element) figure.getElementsByTagName("caption").item(0));
            if (text.contains(reference)) {
                rows.add(Map.of("ordinal", index, "status", "EXACT"));
                continue;
            }
            final var prefix = reference.substring(0, Math.min(32, reference.length()));
            final int start = text.indexOf(prefix);
            if (start < 0 || text.indexOf(prefix, start + 1) >= 0) {
                rows.add(Map.of("ordinal", index, "status", "NO_UNIQUE_FIXED_32_PREFIX"));
                continue;
            }
            int delta = 0;
            while (delta < reference.length() && start + delta < text.length()
                    && reference.charAt(delta) == text.charAt(start + delta))
                delta++;
            final int expected = delta < reference.length() ? reference.codePointAt(delta) : -1;
            final int actual = start + delta < text.length() ? text.codePointAt(start + delta) : -1;
            rows.add(Map.of("ordinal", index, "status", "LOCALIZED_NOT_RESCORED", "first_different_utf16_offset", delta,
                    "expected_codepoint", expected, "actual_codepoint", actual));
        }
        Files.writeString(Path.of(args[2]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows),
                StandardOpenOption.CREATE_NEW);
    }
}
