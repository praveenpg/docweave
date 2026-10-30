package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import tools.jackson.databind.JsonNode;
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
import java.util.function.UnaryOperator;

/**
 * Measures page line order against actual pinned Unstructured {@code fast} element order on a fixed pair population.
 *
 * <p>Atoms are the page's native lines from DocWeave's geometric {@code LINES} layout, using the inline-script policy
 * of the profile being measured, so baseline and candidate profiles with the same policy share one population. An
 * atom maps to the one upstream element on the page whose text holds it exactly once; its expected position is that
 * element's index and then its offset inside the element. Every pair of mapped atoms has one expected order. In a
 * measured output, an atom is located only when its text occurs exactly once in the page text joined with single
 * spaces; a pair with an unlocated atom is unmatched, so deleting or duplicating text never improves the result.
 * Text comparison collapses ASCII whitespace runs only. The report carries indices, counts and hashes only.
 */
public final class CompareUpstreamLineOrder {
    /** Prevents construction of this command-line tool. */
    private CompareUpstreamLineOrder() {
    }

    /** Pair outcomes in a measured output. */
    enum Outcome {
        /** Both atoms located in the expected relative order. */
        CORRECT,
        /** Both atoms located in the opposite order. */
        REVERSED,
        /** At least one atom is missing or occurs more than once. */
        UNMATCHED
    }

    /**
     * One native line atom and its upstream mapping.
     *
     * @param text collapsed line text
     * @param element upstream element index on the page, or -1 when unmapped or ambiguous
     * @param offset offset of the text inside that element, or -1
     */
    record Atom(String text, int element, int offset) {
        /**
         * Returns whether the atom has one expected upstream position.
         *
         * @return whether mapped
         */
        boolean mapped() {
            return element >= 0;
        }
    }

    /**
     * Writes the fixed population and per-profile outcomes for every case, plus declared comparisons.
     *
     * @param args frozen upstream reading manifest, original source directory, raw capture directory, page number,
     *         comma-separated profiles, comma-separated {@code BASE>CANDIDATE} comparisons (or {@code -}), target
     *         spec {@code case:element:sha256} (or {@code -}) and a fresh report; or {@code --self-test}
     * @throws Exception if an identity, schema or control check fails
     */
    public static void main(final String[] args) throws Exception {
        controls();
        if (args.length == 1 && args[0].equals("--self-test")) {
            System.out.println("Upstream line-order controls passed");
            return;
        }
        require(args.length == 8, "Expected manifest, sources, captures, page, profiles, comparisons, target, report");
        final var mapper = JsonMapper.builder().build();
        final var manifestPath = Path.of(args[0]);
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        final var captures = Path.of(args[2]);
        final var runtime = mapper.readTree(Files.readAllBytes(captures.resolve("runtime.json")));
        require(runtime.get("packages").get("unstructured").asString().equals("0.27.28"), "Runtime differs");
        final int page = Integer.parseInt(args[3]);
        final var profiles = List.of(args[4].split(","));
        final var comparisons = args[5].equals("-") ? List.<String>of() : List.of(args[5].split(","));
        final var target = args[6].equals("-") ? null : args[6].split(":");
        final var cases = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            final var input = selected.get("inputs").get(0);
            final var source = Path.of(args[1]).resolve(selected.get("pdf").asString());
            final byte[] bytes = Files.readAllBytes(source);
            final var pin = pin(selected, selected.get("pdf").asString());
            require(bytes.length == pin.get("bytes").asLong() && hash(bytes).equals(pin.get("sha256").asString()),
                    "Original source differs");
            final var capturePath = captures.resolve(id + ".json");
            final var capture = mapper.readTree(Files.readAllBytes(capturePath));
            require(capture.get("outcome").asString().equals("SUCCESS"), "Upstream capture is not successful");
            final var upstream = new ArrayList<String>();
            for (final var element : capture.get("elements")) {
                if (element.get("metadata").get("page_number").asInt() == page
                        && !element.get("type").asString().equals("Image")) {
                    upstream.add(collapse(element.get("text").asString()));
                }
            }
            int targetElement = -1;
            if (target != null && target[0].equals(id)) {
                targetElement = Integer.parseInt(target[1]);
                require(hash(upstream.get(targetElement).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                .equals(target[2]),
                        "Frozen target element differs");
            }
            final var populations = new LinkedHashMap<String, List<Atom>>();
            final var outcomes = new LinkedHashMap<String, List<Outcome>>();
            final var rows = new ArrayList<Map<String, Object>>();
            for (final var profile : profiles) {
                final boolean inline = profile.contains("+INLINE_SCRIPTS");
                final String policy = inline ? "INLINE_SCRIPTS" : "PRESERVE";
                final var atoms = populations.computeIfAbsent(policy, key -> {
                    try {
                        return atoms(page(extract("LINES" + (inline ? "+INLINE_SCRIPTS" : ""), bytes, input), page),
                                upstream);
                    } catch (final Exception failure) {
                        throw new IllegalStateException("Atom extraction failed", failure);
                    }
                });
                final var output = page(extract(profile, bytes, input), page);
                final var result = outcomes(atoms, output);
                outcomes.put(profile, result);
                final var row = new LinkedHashMap<String, Object>();
                row.put("profile", profile);
                row.put("atom_policy", policy);
                row.put("atoms", atoms.size());
                row.put("mapped_atoms", atoms.stream().filter(Atom::mapped).count());
                row.put("pairs", result.size());
                for (final var outcome : Outcome.values()) {
                    row.put(outcome.name().toLowerCase(), result.stream().filter(outcome::equals).count());
                }
                if (targetElement >= 0) {
                    final var split = split(atoms, result, targetElement);
                    row.put("target", split.get(true));
                    row.put("control", split.get(false));
                }
                rows.add(row);
            }
            final var compared = new ArrayList<Map<String, Object>>();
            for (final var comparison : comparisons) {
                final var sides = comparison.split(">");
                final var before = outcomes.get(sides[0]);
                final var after = outcomes.get(sides[1]);
                require(before != null && after != null && before.size() == after.size(),
                        "Comparison profiles must share one atom population");
                final var policy = sides[0].contains("+INLINE_SCRIPTS") ? "INLINE_SCRIPTS" : "PRESERVE";
                compared.add(transitions(comparison, populations.get(policy), before, after, targetElement));
            }
            final var entry = new LinkedHashMap<String, Object>();
            entry.put("id", id);
            entry.put("page", page);
            entry.put("upstream_elements", upstream.size());
            entry.put("capture_sha256", hash(Files.readAllBytes(capturePath)));
            entry.put("target_element", targetElement);
            entry.put("profiles", rows);
            entry.put("comparisons", compared);
            cases.add(entry);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("manifest_sha256", hash(Files.readAllBytes(manifestPath)));
        report.put("runtime_sha256", hash(Files.readAllBytes(captures.resolve("runtime.json"))));
        report.put("java_version", System.getProperty("java.version"));
        report.put("cases", cases);
        Files.writeString(Path.of(args[7]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Maps native line texts to upstream elements; ambiguous or absent texts stay unmapped.
     *
     * @param lines collapsed native line texts in geometric order
     * @param upstream collapsed upstream element texts in upstream order
     * @return atoms in native line order
     */
    static List<Atom> atoms(final List<String> lines, final List<String> upstream) {
        final var result = new ArrayList<Atom>();
        for (final var line : lines) {
            int element = -1;
            int offset = -1;
            int holders = 0;
            for (int index = 0; index < upstream.size(); index++) {
                final int first = upstream.get(index).indexOf(line);
                if (first >= 0) {
                    holders += upstream.get(index).indexOf(line, first + 1) >= 0 ? 2 : 1;
                    element = index;
                    offset = first;
                }
            }
            result.add(holders == 1 && !line.isEmpty() ? new Atom(line, element, offset) : new Atom(line, -1, -1));
        }
        return List.copyOf(result);
    }

    /**
     * Scores every pair of mapped atoms in one output, in a fixed pair order.
     *
     * @param atoms fixed atom population
     * @param output collapsed output element texts in output order
     * @return outcomes for pairs (i, j), i &lt; j over mapped atoms
     */
    static List<Outcome> outcomes(final List<Atom> atoms, final List<String> output) {
        final String joined = String.join(" ", output);
        final var positions = new int[atoms.size()];
        for (int index = 0; index < atoms.size(); index++) {
            final var text = atoms.get(index).text();
            final int first = text.isEmpty() ? -1 : joined.indexOf(text);
            positions[index] = first >= 0 && joined.indexOf(text, first + 1) < 0 ? first : -1;
        }
        final var result = new ArrayList<Outcome>();
        for (int left = 0; left < atoms.size(); left++) {
            for (int right = left + 1; right < atoms.size(); right++) {
                final var a = atoms.get(left);
                final var b = atoms.get(right);
                if (!a.mapped() || !b.mapped()) {
                    continue;
                }
                if (positions[left] < 0 || positions[right] < 0) {
                    result.add(Outcome.UNMATCHED);
                    continue;
                }
                final boolean expectedLeftFirst =
                        a.element() != b.element() ? a.element() < b.element() : a.offset() < b.offset();
                result.add(
                        expectedLeftFirst == positions[left] < positions[right] ? Outcome.CORRECT : Outcome.REVERSED);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Counts outcomes separately for pairs touching the frozen target element and for all other pairs.
     *
     * @param atoms fixed atom population
     * @param outcomes outcomes in fixed pair order
     * @param target frozen target element index
     * @return counts keyed by whether the pair touches the target
     */
    private static Map<Boolean, Map<String, Long>> split(
            final List<Atom> atoms, final List<Outcome> outcomes, final int target) {
        final var result = new LinkedHashMap<Boolean, Map<String, Long>>();
        for (final boolean touched : new boolean[] {true, false}) {
            final var counts = new LinkedHashMap<String, Long>();
            for (final var outcome : Outcome.values()) {
                counts.put(outcome.name().toLowerCase(), 0L);
            }
            result.put(touched, counts);
        }
        int pair = 0;
        for (int left = 0; left < atoms.size(); left++) {
            for (int right = left + 1; right < atoms.size(); right++) {
                if (!atoms.get(left).mapped() || !atoms.get(right).mapped()) {
                    continue;
                }
                final boolean touched = atoms.get(left).element() == target || atoms.get(right).element() == target;
                result.get(touched).merge(outcomes.get(pair++).name().toLowerCase(), 1L, Long::sum);
            }
        }
        return result;
    }

    /**
     * Counts per-pair transitions between two outputs over one population, split by target membership.
     *
     * @param name comparison label
     * @param atoms fixed atom population
     * @param before baseline outcomes
     * @param after candidate outcomes
     * @param target frozen target element index, or -1
     * @return transition counts
     */
    private static Map<String, Object> transitions(final String name, final List<Atom> atoms,
            final List<Outcome> before, final List<Outcome> after, final int target) {
        final var counts = new LinkedHashMap<String, Long>();
        int pair = 0;
        for (int left = 0; left < atoms.size(); left++) {
            for (int right = left + 1; right < atoms.size(); right++) {
                if (!atoms.get(left).mapped() || !atoms.get(right).mapped()) {
                    continue;
                }
                final boolean touched =
                        target >= 0 && (atoms.get(left).element() == target || atoms.get(right).element() == target);
                final var from = before.get(pair);
                final var to = after.get(pair++);
                if (from != to) {
                    counts.merge((touched ? "target_" : "control_") + from.name().toLowerCase() + "_to_"
                                         + to.name().toLowerCase(),
                            1L, Long::sum);
                }
            }
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("comparison", name);
        result.put("changed_pairs", counts);
        return result;
    }

    /**
     * Returns collapsed non-image text of the elements on one page, in output order.
     *
     * @param elements extracted elements
     * @param page one-based page
     * @return collapsed texts
     */
    private static List<String> page(final List<Element> elements, final int page) {
        return elements.stream()
                .filter(element -> element.type() != ElementType.Image)
                .filter(element -> onPage(element, page))
                .map(element -> collapse(element.text()))
                .toList();
    }

    /**
     * Extracts with a native layout and optional modifiers separated by {@code +}.
     *
     * @param profile layout name followed by optional {@code +INLINE_SCRIPTS}
     * @param bytes original bytes
     * @param input original input pin
     * @return extracted elements
     * @throws Exception if extraction fails
     */
    private static List<Element> extract(final String profile, final byte[] bytes, final JsonNode input)
            throws Exception {
        final var parts = profile.split("\\+");
        UnaryOperator<PdfOptions> options = value -> value.withTextLayout(PdfOptions.TextLayout.valueOf(parts[0]));
        for (int index = 1; index < parts.length; index++) {
            final var modifier = MODIFIERS.get(parts[index]);
            require(modifier != null, "Unknown profile modifier");
            final var previous = options;
            options = value -> modifier.apply(previous.apply(value));
        }
        return DocWeave
                .create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options.apply(PdfOptions.defaults())))))
                .partition(DocumentSource.bytes(bytes, input.get("filename").asString()))
                .elements();
    }

    /** Supported explicit profile modifiers. */
    private static final Map<String, UnaryOperator<PdfOptions>> MODIFIERS = Map.of("INLINE_SCRIPTS",
            value
            -> value.withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS),
            "SIDE_STREAMS",
            value
            -> value.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS),
            "SIDE_STREAMS_ROW_TIES",
            value
            -> value.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES),
            "SIDE_STREAMS_ROW_FRAGMENTS",
            value -> value.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS),
            "SIDE_STREAMS_CONTIGUOUS",
            value -> value.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_CONTIGUOUS));

    /**
     * Returns whether an element lies on the page.
     *
     * @param element extracted element
     * @param page one-based page number
     * @return whether it belongs to the page
     */
    private static boolean onPage(final Element element, final int page) {
        return element.metadata().get("page_number") instanceof Number number && number.intValue() == page;
    }

    /**
     * Finds the frozen input pin for a file name.
     *
     * @param selected manifest case
     * @param filename input file name
     * @return pin node
     */
    private static JsonNode pin(final JsonNode selected, final String filename) {
        for (final var input : selected.get("inputs")) {
            if (input.get("filename").asString().equals(filename)) {
                return input;
            }
        }
        throw new IllegalStateException("Missing input pin");
    }

    /**
     * Collapses ASCII whitespace runs and trims; no other normalization is applied.
     *
     * @param text raw text
     * @return collapsed text
     */
    static String collapse(final String text) {
        return text.replaceAll("[ \\t\\n\\r\\f\\u000B]+", " ").strip();
    }

    /** Checks mapping ambiguity, ordering, reversal and the unmatched penalty for deleted or duplicated text. */
    private static void controls() {
        final var atoms = atoms(List.of("a1 a2", "b1", "c1", "x", "a3"), List.of("a1 a2 a3", "b1", "c1 x c1 x"));
        require(atoms.get(0).mapped() && atoms.get(1).mapped() && !atoms.get(2).mapped() && !atoms.get(3).mapped()
                        && atoms.get(4).mapped(),
                "Mapping control failed");
        require(outcomes(atoms, List.of("a1 a2 a3", "b1"))
                        .equals(List.of(Outcome.CORRECT, Outcome.CORRECT, Outcome.CORRECT)),
                "Order control failed");
        require(outcomes(atoms, List.of("a1 a2", "b1", "a3"))
                        .equals(List.of(Outcome.CORRECT, Outcome.CORRECT, Outcome.REVERSED)),
                "Interleave control failed");
        require(outcomes(atoms, List.of("a1 a2")).stream().filter(Outcome.UNMATCHED::equals).count() == 3,
                "Deletion control failed");
        require(outcomes(atoms, List.of("b1 b1 a1 a2 a3")).stream().filter(Outcome.UNMATCHED::equals).count() == 2,
                "Duplication control failed");
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
