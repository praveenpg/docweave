package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
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
import java.util.regex.Pattern;

/** Numeric full-document caption eligibility audit; no publisher caption strings or selected source crops enter it. */
public final class AuditCaptionEligibility {
    /** Prevents standalone utility construction. */
    private AuditCaptionEligibility() {
    }

    /**
     * Audits unchanged native units against the actual committed label and adjacency predicates.
     * Reflection reuses private predicates without exposing them as public library APIs or changing their thresholds.
     * Every image relation on the same page remains in the report, including rejected and small occurrences.
     * @param args pinned scientific source manifest, original cache and NEW numeric report
     * @throws Exception if original identity, predicate access, source geometry or fresh output checks fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 3 && !Files.exists(Path.of(args[2])),
                "Expected original manifest, cache and NEW report");
        final var mapper = JsonMapper.builder().build();
        final var manifestBytes = Files.readAllBytes(Path.of(args[0]));
        final var manifest = mapper.readTree(manifestBytes);
        final var labelField = ImageAdjacentPdfFigureCaptions.class.getDeclaredField("LABEL");
        labelField.setAccessible(true);
        final var label = (Pattern) labelField.get(null);
        final var adjacency = ImageAdjacentPdfFigureCaptions.class.getDeclaredMethod(
                "adjacent", PdfLayoutItem.Bounds.class, PdfLayoutItem.Bounds.class);
        adjacency.setAccessible(true);
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final String filename = selected.get("pdf").asString();
            require(filename.matches("[a-z]+\\.pdf"), "Unsafe source filename");
            final var source = Path.of(args[1], filename);
            require(Files.size(source) <= 10 * 1024 * 1024, "Original exceeds audit budget");
            final var bytes = Files.readAllBytes(source);
            boolean pinned = false;
            for (final var input : selected.get("inputs")) {
                if (input.get("filename").asString().equals(filename)) {
                    require(bytes.length == input.get("bytes").asLong()
                                    && hash(bytes).equals(input.get("sha256").asString()),
                            "Original source changed");
                    pinned = true;
                }
            }
            require(pinned, "Missing original PDF identity");
            for (final String profile : List.of("NATIVE_LINES", "NATIVE_COLUMNS", "SOURCE_TAGGED_LINES")) {
                final var options = PdfOptions.defaults().withTextLayout(
                        profile.equals("NATIVE_COLUMNS") ? PdfOptions.TextLayout.COLUMN_PARAGRAPHS
                                                         : PdfOptions.TextLayout.LINES);
                final var partitioner = profile.equals("SOURCE_TAGGED_LINES")
                                                ? PdfPartitioner.createWithTaggedTables(options)
                                                : PdfPartitioner.create(options);
                final var cues = new ArrayList<Map<String, Object>>();
                String outcome = "SUCCESS";
                int imageCount = 0;
                try {
                    final var elements = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                                                 .partition(DocumentSource.bytes(bytes, filename))
                                                 .elements();
                    final var images =
                            elements.stream().filter(element -> element.type() == ElementType.Image).toList();
                    imageCount = images.size();
                    for (final var element : elements) {
                        if (element.type() != ElementType.UncategorizedText
                                || !(element.text().startsWith("Fig") || element.text().startsWith("FIG"))) {
                            continue;
                        }
                        final var caption = box(element);
                        final var relations = new ArrayList<Map<String, Object>>();
                        int eligible = 0;
                        for (final var image : images) {
                            if (!image.metadata().get("page_number").equals(element.metadata().get("page_number"))) {
                                continue;
                            }
                            final var bounds = box(image);
                            final double width = (double) bounds.right() - bounds.left();
                            final double height = (double) bounds.bottom() - bounds.top();
                            final boolean size = width >= 36 && height >= 18;
                            final boolean near = (boolean) adjacency.invoke(null, caption, bounds);
                            eligible += size && near ? 1 : 0;
                            relations.add(Map.of("image_bounds", coordinates(bounds), "size_eligible", size,
                                    "vertical_gap", (double) caption.top() - bounds.bottom(), "left_edge_difference",
                                    Math.abs((double) caption.left() - bounds.left()), "adjacency_predicate", near));
                        }
                        final var cue = new java.util.LinkedHashMap<String, Object>();
                        cue.put("page", element.metadata().get("page_number"));
                        cue.put("text_utf16_units", element.text().length());
                        cue.put("text_sha256", hash(element.text().getBytes(StandardCharsets.UTF_8)));
                        cue.put("bounds", coordinates(caption));
                        cue.put("rotation_metadata", element.metadata().containsKey("pdf_text_rotation"));
                        cue.put("cue_length_eligible", element.text().length() <= 1024);
                        cue.put("label_predicate", label.matcher(element.text()).find());
                        final int prefix =
                                element.text().startsWith("Figure") || element.text().startsWith("FIGURE") ? 6 : 3;
                        cue.put("codepoint_after_label_word",
                                element.text().length() > prefix ? element.text().codePointAt(prefix) : -1);
                        final int period = element.text().indexOf('.', prefix);
                        cue.put("codepoint_after_label_period",
                                period >= 0 && period <= prefix + 8 && element.text().length() > period + 1
                                        ? element.text().codePointAt(period + 1)
                                        : -1);
                        cue.put("eligible_images", eligible);
                        cue.put("same_page_image_relations", relations);
                        cues.add(cue);
                    }
                } catch (final PartitionException failure) {
                    outcome = failure.code().name();
                }
                results.add(Map.of("case", selected.get("id").asString(), "profile", profile, "outcome", outcome,
                        "source_images", imageCount, "label_cues", cues));
            }
        }
        require(results.size() == 9, "Incomplete original/profile audit");
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "All original pages and Fig/FIG-prefixed native units with actual committed label/adjacency predicates; no source reference captions, tuning or extraction changes",
                        "runtime", Runtime.version().toString(), "source_manifest_sha256", hash(manifestBytes),
                        "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Reads the unchanged displayed rectangular geometry from ordinary native output.
     * @param element source-native item or image occurrence
     * @return displayed-point rectangle
     */
    private static PdfLayoutItem.Bounds box(final Element element) {
        final var coordinates = (Map<?, ?>) element.metadata().get("coordinates");
        require("PDFUserSpace".equals(coordinates.get("system")), "Unexpected source coordinate system");
        final var points = (List<?>) coordinates.get("points");
        float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (final var point : points) {
            final var pair = (List<?>) point;
            final float x = ((Number) pair.get(0)).floatValue();
            final float y = ((Number) pair.get(1)).floatValue();
            require(Float.isFinite(x) && Float.isFinite(y), "Invalid native geometry");
            left = Math.min(left, x);
            top = Math.min(top, y);
            right = Math.max(right, x);
            bottom = Math.max(bottom, y);
        }
        return new PdfLayoutItem.Bounds(left, top, right, bottom);
    }

    /**
     * Copies numeric bounds without source text or page raster bytes.
     * @param box original rectangle
     * @return left/top/right/bottom
     */
    private static List<Double> coordinates(final PdfLayoutItem.Bounds box) {
        return List.of((double) box.left(), (double) box.top(), (double) box.right(), (double) box.bottom());
    }

    /**
     * Hashes complete source/protocol/unit bytes without exporting characters.
     * @param bytes exact input
     * @return lowercase SHA256
     * @throws Exception if hashing fails
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects source/protocol changes without omitting failed cases.
     * @param condition required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
