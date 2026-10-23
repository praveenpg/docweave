package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares the explicit {@code SMALLER_TYPE_BLOCKS_ROW_TIES} policy with {@code SMALLER_TYPE_BLOCKS} on every page of
 * pinned sources, without upstream data.
 *
 * <p>For each source, every text layout and both inline-script choices, it checks per page that the new output
 * equals the frozen whole-element simulation ({@link EvaluateSidebarBlockMoves#move}) in column layouts,
 * and the existing output in other layouts;
 * that the non-side element sequence, the whole-element multiset and the non-whitespace source characters equal the
 * existing output; and that no row-association pair outcome ({@link CompareRowAssociation}) changes. Elements are
 * compared by type, text and metadata (including coordinates and line counts); element identifiers are excluded
 * because they encode sequence position. The report holds counts and hashes only.
 */
public final class CompareSidebarRowTiePolicy {
    /** Provenance recorded on side-stream elements. */
    private static final String SIDE = "side_stream_blocks_v1";

    /** Prevents construction of this command-line tool. */
    private CompareSidebarRowTiePolicy() {
    }

    /**
     * Writes the comparison report.
     *
     * @param args pinned source TSV (id, path, byte length, SHA-256) and a fresh report
     * @throws Exception if a pin differs, extraction fails or the report exists
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("sources-tsv report");
        }
        final var reports = new ArrayList<Map<String, Object>>();
        final byte[] table = Files.readAllBytes(Path.of(args[0]));
        for (final var row : new String(table, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            if (row.isBlank()) {
                continue;
            }
            final var fields = row.split("\t");
            final byte[] bytes = Files.readAllBytes(Path.of(fields[1]));
            if (bytes.length != Long.parseLong(fields[2]) || !hash(bytes).equals(fields[3])) {
                throw new IllegalStateException("Source pin differs: " + fields[0]);
            }
            for (final boolean inline : List.of(false, true)) {
                var base = PdfOptions.defaults();
                if (inline) {
                    base = base.withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
                }
                final var pairs =
                        CompareRowAssociation.pairs(extract(bytes, base.withTextLayout(PdfOptions.TextLayout.LINES)));
                for (final var layout : PdfOptions.TextLayout.values()) {
                    final var options = base.withTextLayout(layout);
                    final var blocks =
                            pages(extract(bytes, options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS)));
                    final var ties = pages(extract(
                            bytes, options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES)));
                    reports.add(compare(fields[0], fields[3], inline, layout, blocks, ties, pairs));
                }
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("java_version", System.getProperty("java.version"));
        report.put("sources_tsv_sha256", hash(table));
        report.put("reports", reports);
        Files.writeString(Path.of(args[1]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Compares one source profile page by page.
     *
     * @param id source identifier
     * @param sha256 source hash
     * @param inline whether inline scripts are selected
     * @param layout paragraph layout
     * @param blocks existing policy output by page
     * @param ties new policy output by page
     * @param pairs row-association pairs by page
     * @return counts and boolean checks
     */
    private static Map<String, Object> compare(final String id, final String sha256, final boolean inline,
            final PdfOptions.TextLayout layout, final Map<Integer, List<Element>> blocks,
            final Map<Integer, List<Element>> ties, final Map<Integer, List<String[]>> pairs) {
        int changedPages = 0;
        int swaps = 0;
        int changedPairs = 0;
        int sideElements = 0;
        int elements = 0;
        boolean equalsSimulation = blocks.keySet().equals(ties.keySet());
        boolean nonSideEqual = equalsSimulation;
        boolean multisetEqual = equalsSimulation;
        boolean charactersEqual = equalsSimulation;
        final var changed = new ArrayList<Integer>();
        for (final var entry : blocks.entrySet()) {
            final var before = entry.getValue();
            final var after = ties.getOrDefault(entry.getKey(), List.of());
            elements += before.size();
            sideElements += (int) before.stream().filter(CompareSidebarRowTiePolicy::side).count();
            final var expected = switch (layout) {
                case COLUMN_PARAGRAPHS, COLUMN_PARAGRAPHS_COMPOUND_WRAPS, ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS ->
                    EvaluateSidebarBlockMoves.move(before);
                default -> before;
            };
            equalsSimulation &= contents(expected).equals(contents(after));
            nonSideEqual &= contents(before.stream().filter(element -> !side(element)).toList())
                                    .equals(contents(after.stream().filter(element -> !side(element)).toList()));
            multisetEqual &= counts(before).equals(counts(after));
            charactersEqual &= characters(before).equals(characters(after));
            int moved = 0;
            for (int index = 0; index < before.size() && index < after.size(); index++) {
                if (side(before.get(index)) && !content(before.get(index)).equals(content(after.get(index)))) {
                    moved++;
                }
            }
            if (moved > 0) {
                changedPages++;
                changed.add(entry.getKey());
            }
            swaps += moved;
            final String beforeText = text(before);
            final String afterText = text(after);
            for (final var pair : pairs.getOrDefault(entry.getKey(), List.of())) {
                if (CompareRowAssociation.outcome(pair[0], pair[1], beforeText)
                        != CompareRowAssociation.outcome(pair[0], pair[1], afterText)) {
                    changedPairs++;
                }
            }
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("source", id);
        result.put("source_sha256", sha256);
        result.put("layout", layout.name());
        result.put("inline_scripts", inline);
        result.put("pages", blocks.size());
        result.put("elements", elements);
        result.put("side_elements", sideElements);
        result.put("moved_side_elements", swaps);
        result.put("changed_pages", changed);
        result.put("changed_page_count", changedPages);
        result.put("row_pairs", pairs.values().stream().mapToInt(List::size).sum());
        result.put("changed_row_pairs", changedPairs);
        result.put("equals_frozen_simulation", equalsSimulation);
        result.put("non_side_sequence_equal", nonSideEqual);
        result.put("whole_element_multiset_equal", multisetEqual);
        result.put("source_characters_equal", charactersEqual);
        return result;
    }

    /**
     * Tests for side-stream provenance.
     *
     * @param element element
     * @return whether the side-stream policy emitted it
     */
    private static boolean side(final Element element) {
        return SIDE.equals(element.metadata().get("pdf_reading_order"));
    }

    /**
     * Returns an element's position-independent content.
     *
     * @param element element
     * @return type, text and metadata
     */
    private static List<Object> content(final Element element) {
        return List.of(element.type(), element.text(), element.metadata());
    }

    /**
     * Returns the contents of a sequence.
     *
     * @param elements elements
     * @return contents in order
     */
    private static List<List<Object>> contents(final List<Element> elements) {
        return elements.stream().map(CompareSidebarRowTiePolicy::content).toList();
    }

    /**
     * Counts element contents.
     *
     * @param elements elements
     * @return content multiplicities
     */
    private static Map<List<Object>, Integer> counts(final List<Element> elements) {
        final var counts = new HashMap<List<Object>, Integer>();
        elements.forEach(element -> counts.merge(content(element), 1, Integer::sum));
        return counts;
    }

    /**
     * Counts non-whitespace code points.
     *
     * @param elements elements
     * @return code-point multiplicities
     */
    private static Map<Integer, Integer> characters(final List<Element> elements) {
        final var counts = new TreeMap<Integer, Integer>();
        elements.stream()
                .flatMapToInt(element -> element.text().codePoints())
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .forEach(codePoint -> counts.merge(codePoint, 1, Integer::sum));
        return counts;
    }

    /**
     * Groups elements by page.
     *
     * @param elements document elements
     * @return elements by page number
     */
    private static Map<Integer, List<Element>> pages(final List<Element> elements) {
        final var pages = new TreeMap<Integer, List<Element>>();
        for (final var element : elements) {
            if (element.metadata().get("page_number") instanceof Number number) {
                pages.computeIfAbsent(number.intValue(), unused -> new ArrayList<>()).add(element);
            }
        }
        return pages;
    }

    /**
     * Projects page text as the existing row metric does.
     *
     * @param elements page elements
     * @return joined collapsed non-image text
     */
    private static String text(final List<Element> elements) {
        return String.join(" ", elements.stream()
                                        .filter(element -> element.type() != ElementType.Image)
                                        .map(element -> CompareUpstreamLineOrder.collapse(element.text()))
                                        .toList());
    }

    /**
     * Extracts native elements without providers.
     *
     * @param bytes pinned PDF
     * @param options explicit native policy
     * @return extracted elements
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
     * @param bytes bytes
     * @return SHA-256
     * @throws Exception if SHA-256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
