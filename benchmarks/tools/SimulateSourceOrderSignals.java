package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequence;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequenceWithProperties;
import org.apache.pdfbox.contentstream.operator.markedcontent.EndMarkedContentSequence;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulates source-supported page-order signals on whole DocWeave elements; evaluation only, no runtime change.
 *
 * <p>Signals are applied to page-one elements of the committed opt-in
 * {@code SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS} policy so accepted fragment gains remain the starting point.
 * {@code STRUCTURE} orders elements by the first logical-structure traversal position of the marked content that
 * owns their glyphs (tagged PDFs only). {@code CONTENT} orders elements by the first content-stream position of
 * their glyphs. Elements without a signal value follow their predecessor. Pair outcomes use the unchanged
 * {@link CompareUpstreamLineOrder} functions; reports contain counts and hashes only.
 */
public final class SimulateSourceOrderSignals {
    /** Prevents construction of this command-line tool. */
    private SimulateSourceOrderSignals() {
    }

    /**
     * Writes simulated pair transitions for every paper and column profile.
     *
     * @param args signal ({@code STRUCTURE} or {@code CONTENT}), original source directory, pinned capture directory
     *         and a fresh report
     * @throws Exception if an identity or extraction check fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 4 || !List.of("STRUCTURE", "CONTENT", "LOCAL_CONTENT").contains(args[0])) {
            throw new IllegalArgumentException("STRUCTURE|CONTENT|LOCAL_CONTENT sources captures report");
        }
        final String signal = args[0];
        final var mapper = JsonMapper.builder().build();
        final var runtime = mapper.readTree(Files.readAllBytes(Path.of(args[2], "runtime.json")));
        if (!runtime.get("packages").get("unstructured").asString().equals("0.27.28")) {
            throw new IllegalArgumentException("Reference runtime differs");
        }
        final var reports = new ArrayList<Map<String, Object>>();
        for (final var id : List.of("recent", "next", "holdout", "elife")) {
            final byte[] pdf = Files.readAllBytes(Path.of(args[1], id + ".pdf"));
            final byte[] captureBytes = Files.readAllBytes(Path.of(args[2], id + ".json"));
            final var upstream = new ArrayList<String>();
            for (final var element : mapper.readTree(captureBytes).get("elements")) {
                if (element.get("metadata").get("page_number").asInt() == 1
                        && !element.get("type").asString().equals("Image")) {
                    upstream.add(CompareUpstreamLineOrder.collapse(element.get("text").asString()));
                }
            }
            final List<Glyph> glyphs = glyphs(pdf, signal.equals("LOCAL_CONTENT") ? "CONTENT" : signal);
            for (final boolean inline : List.of(false, true)) {
                var options = PdfOptions.defaults();
                if (inline) {
                    options = options.withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
                }
                final var atoms = CompareUpstreamLineOrder.atoms(
                        texts(extract(pdf, options.withTextLayout(PdfOptions.TextLayout.LINES))), upstream);
                for (final var layout : List.of(PdfOptions.TextLayout.COLUMN_PARAGRAPHS,
                             PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS)) {
                    final var elements =
                            extract(pdf, options.withTextLayout(layout).withSideStreams(
                                                 PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS));
                    final int[] keyed = new int[1];
                    final var sorted = signal.equals("LOCAL_CONTENT") ? local(elements, glyphs, keyed)
                                                                      : order(elements, glyphs, keyed);
                    if (sorted.size() != elements.size() || !new java.util.HashSet<>(sorted).containsAll(elements)) {
                        throw new AssertionError("Whole-element invariant failed");
                    }
                    final var before = CompareUpstreamLineOrder.outcomes(atoms, texts(elements));
                    final var after = CompareUpstreamLineOrder.outcomes(atoms, texts(sorted));
                    final var counts = new LinkedHashMap<String, Long>();
                    for (int pair = 0; pair < before.size(); pair++) {
                        if (before.get(pair) != after.get(pair)) {
                            counts.merge(before.get(pair).name().toLowerCase() + "_to_"
                                                 + after.get(pair).name().toLowerCase(),
                                    1L, Long::sum);
                        }
                    }
                    final var row = new LinkedHashMap<String, Object>();
                    row.put("case", id);
                    row.put("layout", layout.name());
                    row.put("inline", inline);
                    row.put("source_sha256", hash(pdf));
                    row.put("capture_sha256", hash(captureBytes));
                    row.put("signal_glyphs", glyphs.size());
                    row.put("elements", elements.size());
                    row.put("elements_with_signal", keyed[0]);
                    row.put("order_changed", !sorted.equals(elements));
                    row.put("pairs", before.size());
                    for (final var outcome : CompareUpstreamLineOrder.Outcome.values()) {
                        row.put(outcome.name().toLowerCase() + "_before",
                                before.stream().filter(outcome::equals).count());
                        row.put(outcome.name().toLowerCase() + "_after",
                                after.stream().filter(outcome::equals).count());
                    }
                    row.put("changed_pairs", counts);
                    reports.add(row);
                    System.out.println(row);
                }
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("signal", signal);
        report.put("scope", "Source-supported order signal applied to whole row-fragment elements on page 1; "
                                    + "evaluation only, stable sorts, no runtime change.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("reports", reports);
        Files.writeString(Path.of(args[3]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * One page-one glyph with its displayed centre and order key.
     *
     * @param x displayed centre x
     * @param y displayed centre y
     * @param key structure traversal position or content-stream index
     */
    record Glyph(double x, double y, long key) {}

    /**
     * Reads page-one glyphs with their order keys.
     *
     * @param pdf source bytes
     * @param signal order signal
     * @return glyphs with a key; untagged glyphs are omitted for {@code STRUCTURE}
     * @throws IOException if parsing fails
     */
    static List<Glyph> glyphs(final byte[] pdf, final String signal) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            final PDPage page = document.getPage(0);
            final Map<Integer, Long> positions = new HashMap<>();
            if (signal.equals("STRUCTURE")) {
                final var root = document.getDocumentCatalog().getStructureTreeRoot();
                if (root == null) {
                    return List.of();
                }
                final long[] counter = {0};
                traverse(root, page, positions, counter, 0);
            }
            final var result = new ArrayList<Glyph>();
            final var stripper = new MarkedStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            stripper.setSortByPosition(false);
            stripper.getText(document);
            for (final var glyph : stripper.glyphs) {
                final TextPosition text = glyph.text();
                final double x = text.getXDirAdj() + text.getWidthDirAdj() / 2;
                final double y = text.getYDirAdj() - text.getHeightDir() / 2;
                if (signal.equals("CONTENT")) {
                    result.add(new Glyph(x, y, glyph.sequence()));
                } else if (glyph.mcid() >= 0 && positions.containsKey(glyph.mcid())) {
                    result.add(new Glyph(x, y, positions.get(glyph.mcid())));
                }
            }
            return result;
        }
    }

    /**
     * Assigns structure-traversal positions to page-one marked-content identifiers.
     *
     * @param node current structure node
     * @param page page one
     * @param positions destination
     * @param counter next position
     * @param depth recursion depth
     */
    private static void traverse(final PDStructureNode node, final PDPage page, final Map<Integer, Long> positions,
            final long[] counter, final int depth) {
        if (depth > 256) {
            return;
        }
        final PDPage owner = node instanceof PDStructureElement element ? element.getPage() : null;
        for (final Object kid : node.getKids()) {
            if (kid instanceof PDStructureNode child) {
                traverse(child, page, positions, counter, depth + 1);
            } else if (kid instanceof Integer mcid && (owner == null || owner.getCOSObject() == page.getCOSObject())) {
                positions.putIfAbsent(mcid, counter[0]++);
            } else if (kid instanceof PDMarkedContentReference reference
                       && (reference.getPage() == null ? owner : reference.getPage()) != null
                       && (reference.getPage() == null ? owner : reference.getPage()).getCOSObject()
                                  == page.getCOSObject()) {
                positions.putIfAbsent(reference.getMCID(), counter[0]++);
            }
        }
    }

    /**
     * One glyph with its innermost marked-content identifier and content-stream sequence.
     *
     * @param text glyph position
     * @param mcid innermost marked-content identifier, or -1
     * @param sequence content-stream glyph index
     */
    record MarkedGlyph(TextPosition text, int mcid, long sequence) {}

    /** Text stripper recording each glyph's innermost MCID and content-stream order. */
    private static final class MarkedStripper extends PDFTextStripper {
        /** Recorded glyphs in content-stream order. */
        private final List<MarkedGlyph> glyphs = new ArrayList<>();
        /** Open marked-content identifiers, innermost last. */
        private final ArrayDeque<Integer> open = new ArrayDeque<>();

        /**
         * Registers marked-content operators.
         *
         * @throws IOException if PDFBox initialization fails
         */
        MarkedStripper() throws IOException {
            addOperator(new BeginMarkedContentSequenceWithProperties(this));
            addOperator(new BeginMarkedContentSequence(this));
            addOperator(new EndMarkedContentSequence(this));
        }

        @Override
        public void beginMarkedContentSequence(final COSName tag, final COSDictionary properties) {
            int mcid = -1;
            if (properties != null && properties.getDictionaryObject(COSName.MCID) instanceof COSInteger value) {
                mcid = value.intValue();
            }
            open.addLast(mcid >= 0 ? mcid : open.isEmpty() ? -1 : open.peekLast());
        }

        @Override
        public void endMarkedContentSequence() {
            if (!open.isEmpty()) {
                open.removeLast();
            }
        }

        @Override
        protected void processTextPosition(final TextPosition text) {
            glyphs.add(new MarkedGlyph(text, open.isEmpty() ? -1 : open.peekLast(), glyphs.size()));
            super.processTextPosition(text);
        }
    }

    /**
     * Orders elements by their smallest glyph key; unkeyed elements follow their predecessor.
     *
     * @param elements page-one elements in output order
     * @param glyphs keyed glyphs
     * @param keyed receives the number of elements with a key
     * @return reordered elements
     */
    static List<Element> order(final List<Element> elements, final List<Glyph> glyphs, final int[] keyed) {
        final long[] keys = new long[elements.size()];
        long previous = Long.MIN_VALUE;
        for (int index = 0; index < elements.size(); index++) {
            final double[] b = bounds(elements.get(index));
            long best = Long.MAX_VALUE;
            for (final var glyph : glyphs) {
                if (glyph.x() >= b[0] - 0.5 && glyph.x() <= b[2] + 0.5 && glyph.y() >= b[1] - 0.5
                        && glyph.y() <= b[3] + 0.5) {
                    best = Math.min(best, glyph.key());
                }
            }
            if (best == Long.MAX_VALUE || elements.get(index).type() == ElementType.Image) {
                keys[index] = previous;
            } else {
                keys[index] = best;
                previous = best;
                keyed[0]++;
            }
        }
        final var indices = new ArrayList<Integer>();
        for (int index = 0; index < elements.size(); index++) {
            indices.add(index);
        }
        indices.sort(Comparator.comparingLong(index -> keys[index]));
        return indices.stream().map(elements::get).toList();
    }

    /**
     * Reorders only side-by-side runs by their first content-stream glyph; every other element keeps its position.
     *
     * @param elements page-one elements in output order
     * @param glyphs content-keyed glyphs
     * @param keyed receives the number of reordered runs' elements
     * @return reordered elements
     */
    static List<Element> local(final List<Element> elements, final List<Glyph> glyphs, final int[] keyed) {
        final long[] keys = new long[elements.size()];
        for (int index = 0; index < elements.size(); index++) {
            final double[] b = bounds(elements.get(index));
            long best = Long.MAX_VALUE;
            for (final var glyph : glyphs) {
                if (glyph.x() >= b[0] - 0.5 && glyph.x() <= b[2] + 0.5 && glyph.y() >= b[1] - 0.5
                        && glyph.y() <= b[3] + 0.5) {
                    best = Math.min(best, glyph.key());
                }
            }
            keys[index] = best;
        }
        final var result = new ArrayList<Element>(elements);
        int start = 0;
        while (start < elements.size()) {
            int end = start;
            while (end + 1 < elements.size() && sideBySide(elements.get(end), elements.get(end + 1))
                    && keys[end] != Long.MAX_VALUE && keys[end + 1] != Long.MAX_VALUE) {
                end++;
            }
            if (end > start) {
                final var run = new ArrayList<Integer>();
                for (int index = start; index <= end; index++) {
                    run.add(index);
                }
                run.sort(Comparator.comparingLong(index -> keys[index]));
                for (int offset = 0; offset < run.size(); offset++) {
                    result.set(start + offset, elements.get(run.get(offset)));
                }
                keyed[0] += run.size();
            }
            start = end + 1;
        }
        return result;
    }

    /**
     * Tests vertical overlap without horizontal overlap.
     *
     * @param first element
     * @param second element
     * @return whether the elements sit side by side
     */
    private static boolean sideBySide(final Element first, final Element second) {
        final double[] a = bounds(first);
        final double[] b = bounds(second);
        return Math.min(a[3], b[3]) > Math.max(a[1], b[1]) && (a[2] <= b[0] || b[2] <= a[0]);
    }

    /**
     * Reads displayed left, top, right and bottom.
     *
     * @param element an extracted element
     * @return bounds; elements without coordinates match no glyph
     */
    private static double[] bounds(final Element element) {
        if (!(element.metadata().get("coordinates") instanceof Map<?, ?> coordinates)
                || !(coordinates.get("points") instanceof List<?> points) || points.size() != 4
                || !(points.get(0) instanceof List<?> first) || !(points.get(2) instanceof List<?> last)) {
            return new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        }
        return new double[] {((Number) first.get(0)).doubleValue(), ((Number) first.get(1)).doubleValue(),
                ((Number) last.get(0)).doubleValue(), ((Number) last.get(1)).doubleValue()};
    }

    /**
     * Extracts page-one elements, images included.
     *
     * @param bytes source PDF
     * @param options native policy
     * @return page-one elements in output order
     * @throws Exception if extraction fails
     */
    private static List<Element> extract(final byte[] bytes, final PdfOptions options) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(bytes, "paper.pdf"))
                .elements()
                .stream()
                .filter(e -> e.metadata().get("page_number") instanceof Number n && n.intValue() == 1)
                .toList();
    }

    /**
     * Projects text exactly as the existing pair evaluator does.
     *
     * @param elements elements in measured order
     * @return collapsed non-image texts
     */
    private static List<String> texts(final List<Element> elements) {
        return elements.stream()
                .filter(e -> e.type() != ElementType.Image)
                .map(e -> CompareUpstreamLineOrder.collapse(e.text()))
                .toList();
    }

    /**
     * Returns the lowercase SHA-256 of bytes.
     *
     * @param bytes bytes to hash
     * @return hexadecimal digest
     * @throws Exception if SHA-256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
