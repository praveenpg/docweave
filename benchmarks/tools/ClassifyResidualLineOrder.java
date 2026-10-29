package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
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
import java.util.TreeMap;

/**
 * Classifies the reversed line pairs that remain under an explicit side-stream policy and bounds what any whole-element
 * reordering could achieve.
 *
 * <p>The pair population and outcome function are the unchanged {@link CompareUpstreamLineOrder} ones. Every located
 * atom is assigned to the one measured output element whose span in the space-joined page text contains it. A reversed
 * pair is {@code INTRA_ELEMENT} when both atoms share an element, {@code INTERLEAVED} when the upstream expectations
 * between atoms of its two elements point both ways, and {@code ORDERABLE} otherwise. The lower bound adds the
 * intra-element reversals and, for every unordered element pair, the smaller of its two expected-direction counts: no
 * permutation of intact elements can reverse fewer pairs. The report carries indices, counts, element types and
 * policy tags only; it never contains document text.
 */
public final class ClassifyResidualLineOrder {
    /** Prevents construction of this command-line tool. */
    private ClassifyResidualLineOrder() {
    }

    /** Classes of a reversed pair. */
    enum Kind {
        /** Both atoms lie in one output element. */
        INTRA_ELEMENT,
        /** The two elements' atoms are expected in both relative directions. */
        INTERLEAVED,
        /** All atom pairs of the two elements expect one direction, opposite to the output. */
        ORDERABLE,
        /** An atom straddles an element boundary in the joined text. */
        CROSSING
    }

    /**
     * Writes the classification for every paper and requested profile on page one.
     *
     * @param args original source directory, pinned capture directory, comma-separated
     *         {@code manifest} paths, comma-separated profiles and a fresh report
     * @throws Exception if an identity check or extraction fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("sources captures manifests profiles report");
        }
        final var mapper = JsonMapper.builder().build();
        final var captures = Path.of(args[1]);
        final var runtime = mapper.readTree(Files.readAllBytes(captures.resolve("runtime.json")));
        require(runtime.get("packages").get("unstructured").asString().equals("0.27.28"), "Runtime differs");
        final var cases = new ArrayList<Map<String, Object>>();
        for (final var manifestPath : args[2].split(",")) {
            final var manifest = mapper.readTree(Files.readAllBytes(Path.of(manifestPath)));
            for (final var selected : manifest.get("cases")) {
                final String id = selected.get("id").asString();
                final var input = selected.get("inputs").get(0);
                final byte[] bytes = Files.readAllBytes(Path.of(args[0]).resolve(selected.get("pdf").asString()));
                require(hash(bytes).equals(input.get("sha256").asString()), "Original source differs");
                final byte[] captureBytes = Files.readAllBytes(captures.resolve(id + ".json"));
                final var capture = mapper.readTree(captureBytes);
                require(capture.get("outcome").asString().equals("SUCCESS"), "Upstream capture is not successful");
                final var upstream = new ArrayList<String>();
                for (final var element : capture.get("elements")) {
                    if (element.get("metadata").get("page_number").asInt() == 1
                            && !element.get("type").asString().equals("Image")) {
                        upstream.add(CompareUpstreamLineOrder.collapse(element.get("text").asString()));
                    }
                }
                final var profiles = new ArrayList<Map<String, Object>>();
                for (final var profile : args[3].split(",")) {
                    final boolean inline = profile.contains("+INLINE_SCRIPTS");
                    final var atoms = CompareUpstreamLineOrder.atoms(
                            texts(extract(inline ? "LINES+INLINE_SCRIPTS" : "LINES", bytes)), upstream);
                    profiles.add(classify(profile, atoms, extract(profile, bytes)));
                }
                final var entry = new LinkedHashMap<String, Object>();
                entry.put("id", id);
                entry.put("source_sha256", hash(bytes));
                entry.put("capture_sha256", hash(captureBytes));
                entry.put("profiles", profiles);
                cases.add(entry);
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put(
                "scope", "Page 1 residual reversed-pair classes and a lower bound over all whole-element permutations; "
                                 + "counts, indices, types and tags only.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("cases", cases);
        Files.writeString(Path.of(args[4]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Classifies one profile's reversed pairs and computes the whole-element lower bound.
     *
     * @param profile measured profile name
     * @param atoms fixed atom population
     * @param elements measured page-one elements, images excluded
     * @return numeric classification
     */
    static Map<String, Object> classify(
            final String profile, final List<CompareUpstreamLineOrder.Atom> atoms, final List<Element> elements) {
        final var texts = texts(elements);
        final var outcomes = CompareUpstreamLineOrder.outcomes(atoms, texts);
        final String joined = String.join(" ", texts);
        final var starts = new int[texts.size()];
        for (int index = 1; index < texts.size(); index++) {
            starts[index] = starts[index - 1] + texts.get(index - 1).length() + 1;
        }
        final var owners = new int[atoms.size()];
        for (int index = 0; index < atoms.size(); index++) {
            owners[index] = owner(joined, starts, texts, atoms.get(index).text());
        }
        // directions.get(key)[0] counts pairs expecting the smaller element index first, [1] the larger first.
        final var directions = new TreeMap<Long, long[]>();
        final var reversedCross = new TreeMap<Long, Long>();
        final var kinds = new TreeMap<String, Long>();
        long intra = 0;
        long reversed = 0;
        int pair = 0;
        for (int left = 0; left < atoms.size(); left++) {
            for (int right = left + 1; right < atoms.size(); right++) {
                final var a = atoms.get(left);
                final var b = atoms.get(right);
                if (!a.mapped() || !b.mapped()) {
                    continue;
                }
                final var outcome = outcomes.get(pair++);
                if (outcome == CompareUpstreamLineOrder.Outcome.UNMATCHED) {
                    continue;
                }
                final int x = owners[left];
                final int y = owners[right];
                final boolean expectedLeftFirst =
                        a.element() != b.element() ? a.element() < b.element() : a.offset() < b.offset();
                if (outcome == CompareUpstreamLineOrder.Outcome.REVERSED) {
                    reversed++;
                }
                if (x < 0 || y < 0) {
                    if (outcome == CompareUpstreamLineOrder.Outcome.REVERSED) {
                        kinds.merge(Kind.CROSSING.name(), 1L, Long::sum);
                    }
                    continue;
                }
                if (x == y) {
                    if (outcome == CompareUpstreamLineOrder.Outcome.REVERSED) {
                        intra++;
                        kinds.merge(Kind.INTRA_ELEMENT.name(), 1L, Long::sum);
                    }
                    continue;
                }
                final long key = key(x, y);
                final boolean smallerFirst = expectedLeftFirst == x < y;
                directions.computeIfAbsent(key, ignored -> new long[2])[smallerFirst ? 0 : 1]++;
                if (outcome == CompareUpstreamLineOrder.Outcome.REVERSED) {
                    reversedCross.merge(key, 1L, Long::sum);
                }
            }
        }
        long bound = intra;
        final var pairs = new ArrayList<Map<String, Object>>();
        for (final var entry : directions.entrySet()) {
            final long[] counts = entry.getValue();
            bound += Math.min(counts[0], counts[1]);
            final long current = reversedCross.getOrDefault(entry.getKey(), 0L);
            if (current == 0) {
                continue;
            }
            final var kind = counts[0] > 0 && counts[1] > 0 ? Kind.INTERLEAVED : Kind.ORDERABLE;
            kinds.merge(kind.name(), current, Long::sum);
            final int first = (int) (entry.getKey() >> 32);
            final int second = (int) (long) entry.getKey();
            final var row = new LinkedHashMap<String, Object>();
            row.put("elements", List.of(describe(first, elements.get(first)), describe(second, elements.get(second))));
            row.put("expect_first_before_second", counts[0]);
            row.put("expect_second_before_first", counts[1]);
            row.put("reversed", current);
            row.put("kind", kind.name());
            pairs.add(row);
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("profile", profile);
        result.put("elements", elements.size());
        result.put("reversed", reversed);
        result.put("reversed_by_kind", kinds);
        result.put("whole_element_lower_bound", bound);
        result.put("gap_above_bound", reversed - bound);
        result.put("reversed_element_pairs", pairs);
        return result;
    }

    /**
     * Finds the element whose joined-text span contains the uniquely located atom.
     *
     * @param joined space-joined output text
     * @param starts start offset of each element in {@code joined}
     * @param texts collapsed element texts
     * @param atom atom text
     * @return owning element index, or -1 when unlocated, repeated or straddling a boundary
     */
    private static int owner(final String joined, final int[] starts, final List<String> texts, final String atom) {
        final int first = atom.isEmpty() ? -1 : joined.indexOf(atom);
        if (first < 0 || joined.indexOf(atom, first + 1) >= 0) {
            return -1;
        }
        for (int index = starts.length - 1; index >= 0; index--) {
            if (starts[index] <= first) {
                return first + atom.length() <= starts[index] + texts.get(index).length() ? index : -1;
            }
        }
        return -1;
    }

    /**
     * Describes one element without its text.
     *
     * @param index element index among non-image page-one elements
     * @param element the element
     * @return index, type, reading-order tag and line count
     */
    private static Map<String, Object> describe(final int index, final Element element) {
        final var result = new LinkedHashMap<String, Object>();
        result.put("index", index);
        result.put("type", element.type().name());
        result.put("reading_order", String.valueOf(element.metadata().get("pdf_reading_order")));
        result.put("line_count", String.valueOf(element.metadata().get("pdf_line_count")));
        return result;
    }

    /**
     * Encodes an unordered element pair.
     *
     * @param x one element index
     * @param y another element index
     * @return key with the smaller index in the high word
     */
    private static long key(final int x, final int y) {
        return ((long) Math.min(x, y) << 32) | Math.max(x, y);
    }

    /**
     * Extracts page-one non-image elements with a native layout and optional {@code +} modifiers.
     *
     * @param profile layout followed by optional {@code +INLINE_SCRIPTS}, {@code +SIDE_STREAMS} or
     *         {@code +SIDE_STREAMS_ROW_TIES}, {@code +SIDE_STREAMS_ROW_FRAGMENTS} or {@code +SIDE_STREAMS_CONTIGUOUS}
     * @param bytes original bytes
     * @return page-one non-image elements in output order
     * @throws Exception if extraction fails
     */
    private static List<Element> extract(final String profile, final byte[] bytes) throws Exception {
        final var parts = profile.split("\\+");
        var options = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.valueOf(parts[0]));
        for (int index = 1; index < parts.length; index++) {
            options = switch (parts[index]) {
                case "INLINE_SCRIPTS" -> options.withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
                case "SIDE_STREAMS" -> options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS);
                case "SIDE_STREAMS_ROW_TIES" ->
                    options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES);
                case "SIDE_STREAMS_ROW_FRAGMENTS" ->
                    options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS);
                case "SIDE_STREAMS_CONTIGUOUS" ->
                    options.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_CONTIGUOUS);
                default -> throw new IllegalArgumentException("Unknown profile modifier");
            };
        }
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(bytes, "paper.pdf"))
                .elements()
                .stream()
                .filter(e -> e.type() != ElementType.Image)
                .filter(e -> e.metadata().get("page_number") instanceof Number n && n.intValue() == 1)
                .toList();
    }

    /**
     * Projects text exactly as the existing pair evaluator does.
     *
     * @param elements elements in measured order
     * @return collapsed texts
     */
    private static List<String> texts(final List<Element> elements) {
        return elements.stream().map(e -> CompareUpstreamLineOrder.collapse(e.text())).toList();
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

    /**
     * Fails with a descriptive message when a condition is false.
     *
     * @param condition checked condition
     * @param message failure description
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
