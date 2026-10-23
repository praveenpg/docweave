package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
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
import java.util.Objects;

/**
 * Checks that explicit row-tie paragraph fragments are a lossless re-representation of row-tie output.
 *
 * <p>For every pinned row-control source, text layout and inline-script choice, fragments are rejoined: the first
 * fragment's metadata without fragment keys, one more line, paragraph provenance, the union of both coordinate boxes
 * and the two texts joined by one space. The rejoined sequence must equal the {@code SMALLER_TYPE_BLOCKS_ROW_TIES}
 * sequence element for element (type, text and metadata; element ids are positional and excluded). The report holds
 * counts and hashes only.
 */
public final class CompareRowFragmentPolicy {
    /** Fragment metadata keys removed on rejoining. */
    private static final List<String> KEYS = List.of("pdf_reading_order_fragment_policy",
            "pdf_paragraph_fragment_group", "pdf_paragraph_fragment_index", "pdf_paragraph_fragment_count");

    /** Prevents construction. */
    private CompareRowFragmentPolicy() {
    }

    /**
     * Writes per-source fragment counts and reconstruction results.
     *
     * @param args pinned row-source TSV ({@code id, path, bytes, sha256}) and a fresh report
     * @throws Exception if an identity check or extraction fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("row-sources.tsv report");
        }
        final var rows = new ArrayList<Map<String, Object>>();
        int profiles = 0;
        int failures = 0;
        long fragmentGroups = 0;
        for (final var line : Files.readAllLines(Path.of(args[0]))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            final var fields = line.split("\t");
            final byte[] bytes = Files.readAllBytes(Path.of(fields[1]));
            if (bytes.length != Long.parseLong(fields[2]) || !hash(bytes).equals(fields[3])) {
                throw new IllegalStateException("Source pin changed");
            }
            for (final var layout : PdfOptions.TextLayout.values()) {
                for (final boolean inline : List.of(false, true)) {
                    var options = PdfOptions.defaults().withTextLayout(layout);
                    if (inline) {
                        options = options.withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
                    }
                    final var ties = extract(
                            bytes, options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES));
                    final var fragments = extract(
                            bytes, options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS));
                    final long groups =
                            fragments.stream()
                                    .filter(e -> Objects.equals(1, e.metadata().get("pdf_paragraph_fragment_index")))
                                    .count();
                    final boolean equal = normalized(projectElements(ties)).equals(normalized(rejoin(fragments)));
                    profiles++;
                    fragmentGroups += groups;
                    if (!equal) {
                        failures++;
                    }
                    if (groups > 0 || !equal) {
                        final var row = new LinkedHashMap<String, Object>();
                        row.put("id", fields[0]);
                        row.put("layout", layout.name());
                        row.put("inline", inline);
                        row.put("fragment_groups", groups);
                        row.put("pages_with_fragments",
                                fragments.stream()
                                        .filter(e
                                                -> Objects.equals(1, e.metadata().get("pdf_paragraph_fragment_index")))
                                        .map(e -> e.metadata().get("page_number"))
                                        .distinct()
                                        .toList());
                        row.put("rejoined_equals_row_ties", equal);
                        rows.add(row);
                    }
                }
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("source_tsv_sha256", hash(Files.readAllBytes(Path.of(args[0]))));
        report.put("java_version", System.getProperty("java.version"));
        report.put("source_profiles", profiles);
        report.put("reconstruction_failures", failures);
        report.put("fragment_groups", fragmentGroups);
        report.put("profiles_with_fragments_or_failures", rows);
        Files.writeString(Path.of(args[1]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("profiles=" + profiles + " failures=" + failures + " groups=" + fragmentGroups);
    }

    /**
     * Rejoins each fragment pair into one element value.
     *
     * @param elements fragment output
     * @return projected rejoined sequence, as type/text/metadata triples
     */
    private static List<List<Object>> rejoin(final List<Element> elements) {
        final var result = new ArrayList<List<Object>>();
        int pending = -1;
        for (final var element : elements) {
            final Object index = element.metadata().get("pdf_paragraph_fragment_index");
            if (Objects.equals(1, index)) {
                pending = result.size();
                result.add(List.of(element.type(), element.text(), element.metadata()));
            } else if (Objects.equals(2, index)) {
                final var first = result.get(pending);
                @SuppressWarnings("unchecked")
                final var metadata = new LinkedHashMap<>((Map<String, Object>) first.get(2));
                KEYS.forEach(metadata::remove);
                metadata.put("pdf_line_count", ((Number) metadata.getOrDefault("pdf_line_count", 1)).intValue() + 1);
                metadata.put("pdf_text_layout", "paragraphs_v1");
                metadata.put("coordinates",
                        union((Map<?, ?>) metadata.get("coordinates"), (Map<?, ?>) element.metadata().get("coordinates")));
                result.set(pending, List.of(first.get(0), first.get(1) + " " + element.text(), metadata));
            } else {
                result.add(List.of(element.type(), element.text(), element.metadata()));
            }
        }
        return result;
    }

    /**
     * Normalizes the metadata of type/text/metadata triples.
     *
     * @param elements triples
     * @return triples with normalized metadata
     */
    private static List<List<Object>> normalized(final List<List<Object>> elements) {
        return elements.stream().map(e -> List.<Object>of(e.get(0), e.get(1), normalize(e.get(2)))).toList();
    }

    /**
     * Projects whole elements to type/text/metadata triples; element ids are positional and excluded.
     *
     * @param elements elements
     * @return triples
     */
    private static List<List<Object>> projectElements(final List<Element> elements) {
        return elements.stream().map(e -> List.<Object>of(e.type(), e.text(), e.metadata())).toList();
    }

    /**
     * Normalizes metadata to a key-sorted map with numbers as doubles, so map implementations and boxed number
     * types do not affect equality.
     *
     * @param metadata metadata
     * @return normalized copy
     */
    private static Object normalize(final Object metadata) {
        if (metadata instanceof Map<?, ?> map) {
            final var sorted = new java.util.TreeMap<String, Object>();
            map.forEach((key, value) -> sorted.put(String.valueOf(key), normalize(value)));
            return sorted;
        }
        if (metadata instanceof List<?> list) {
            return list.stream().map(CompareRowFragmentPolicy::normalize).toList();
        }
        if (metadata instanceof Number number) {
            return number.doubleValue();
        }
        return metadata;
    }

    /**
     * Unions two coordinate maps with four corner points.
     *
     * @param a first coordinates
     * @param b second coordinates
     * @return coordinates of the enclosing box with a's other entries
     */
    private static Map<String, Object> union(final Map<?, ?> a, final Map<?, ?> b) {
        final double[] x = bounds(a);
        final double[] y = bounds(b);
        final double left = Math.min(x[0], y[0]);
        final double top = Math.min(x[1], y[1]);
        final double right = Math.max(x[2], y[2]);
        final double bottom = Math.max(x[3], y[3]);
        final var result = new LinkedHashMap<String, Object>();
        a.forEach((key, value) -> result.put(String.valueOf(key), value));
        final var points = (List<?>) a.get("points");
        result.put("points", List.of(List.of(left, top), corner(points, 1, left, top, right, bottom),
                                     List.of(right, bottom), corner(points, 3, left, top, right, bottom)));
        return result;
    }

    /**
     * Rebuilds one of the two side corners in the source's order.
     *
     * @param points source corner points
     * @param index corner index 1 or 3
     * @param left union left
     * @param top union top
     * @param right union right
     * @param bottom union bottom
     * @return corner
     */
    private static List<Double> corner(final List<?> points, final int index, final double left, final double top,
            final double right, final double bottom) {
        final var point = (List<?>) points.get(index);
        final var first = (List<?>) points.get(0);
        final boolean isLeft = ((Number) point.get(0)).doubleValue() == ((Number) first.get(0)).doubleValue();
        final boolean isTop = ((Number) point.get(1)).doubleValue() == ((Number) first.get(1)).doubleValue();
        return List.of(isLeft ? left : right, isTop ? top : bottom);
    }

    /**
     * Reads left, top, right and bottom from corner points 0 and 2.
     *
     * @param coordinates coordinates map
     * @return bounds
     */
    private static double[] bounds(final Map<?, ?> coordinates) {
        final var points = (List<?>) coordinates.get("points");
        final var first = (List<?>) points.get(0);
        final var third = (List<?>) points.get(2);
        return new double[] {((Number) first.get(0)).doubleValue(), ((Number) first.get(1)).doubleValue(),
                ((Number) third.get(0)).doubleValue(), ((Number) third.get(1)).doubleValue()};
    }

    /**
     * Extracts a whole source.
     *
     * @param bytes source
     * @param options options
     * @return elements
     * @throws Exception if extraction fails
     */
    private static List<Element> extract(final byte[] bytes, final PdfOptions options) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(bytes, "source.pdf"))
                .elements();
    }

    /**
     * Hashes bytes.
     *
     * @param bytes content
     * @return lowercase SHA-256
     * @throws Exception if unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
