package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfParagraphAssembler;
import io.docweave.partition.PdfPartitioner;
import io.docweave.partition.PdfTaggedParagraphOptions;
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
import java.util.stream.Collectors;

/**
 * Compares DocWeave element order and grouping on one page with actual pinned Unstructured {@code fast} elements.
 *
 * <p>Each non-image upstream element on the page is located in the page's DocWeave text, joined with one space
 * between elements, as one contiguous occurrence of its own text after ASCII whitespace runs collapse. No other
 * normalization is applied, so hyphen, NBSP and Unicode differences stay visible as unlocated elements. Located
 * elements yield pairwise order (preserved or reversed relative to upstream) and grouping (the number of DocWeave
 * elements each spans, and DocWeave elements that hold several upstream elements). An upstream text found more
 * than once is ambiguous and receives no invented position. The report carries indices and counts only.
 */
public final class CompareUpstreamPageOrder {
    /** Profile suffix selecting the explicit native small-glyph run policy on a native text layout. */
    private static final String INLINE_SCRIPTS = "+INLINE_SCRIPTS";

    /** Prevents construction of this command-line tool. */
    private CompareUpstreamPageOrder() {
    }

    /**
     * Writes one comparison per case and declared profile.
     *
     * @param args frozen upstream reading manifest, original source cache, raw capture directory, page number,
     *         comma-separated profile names, a fresh report and optionally {@code --upstream-whitespace}, which
     *         compares both sides in upstream's whitespace presentation; or {@code --self-test}
     * @throws Exception if an identity or control fails
     */
    public static void main(final String[] args) throws Exception {
        controls();
        if (args.length == 1 && args[0].equals("--self-test")) {
            System.out.println("Upstream page-order controls passed");
            return;
        }
        require(args.length == 6 || args.length == 7 && args[6].equals("--upstream-whitespace"),
                "Expected manifest, sources, captures, page, profiles, a fresh report and an optional presentation");
        final Presentation presentation = args.length == 7 ? Presentation.UPSTREAM_WHITESPACE : Presentation.EXACT;
        final var mapper = JsonMapper.builder().build();
        final var manifestPath = Path.of(args[0]);
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        final var captures = Path.of(args[2]);
        final var runtime = mapper.readTree(Files.readAllBytes(captures.resolve("runtime.json")));
        require(runtime.get("packages").get("unstructured").asString().equals("0.27.28"), "Runtime differs");
        final int page = Integer.parseInt(args[3]);
        final var profiles = List.of(args[4].split(","));
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            for (final var input : selected.get("inputs")) {
                final var path = Path.of(args[1]).resolve(input.get("filename").asString());
                require(Files.size(path) == input.get("bytes").asLong()
                                && hash(Files.readAllBytes(path)).equals(input.get("sha256").asString()),
                        "Original source differs");
            }
            final var capturePath = captures.resolve(id + ".json");
            final var capture = mapper.readTree(Files.readAllBytes(capturePath));
            require(capture.get("outcome").asString().equals("SUCCESS"), "Upstream capture is not successful");
            final var upstream = new ArrayList<String>();
            final var categories = new ArrayList<String>();
            for (final var element : capture.get("elements")) {
                if (element.get("metadata").get("page_number").asInt() == page
                        && !element.get("type").asString().equals("Image")) {
                    upstream.add(presentation.apply(element.get("text").asString()));
                    categories.add(element.get("type").asString());
                }
            }
            final byte[] bytes = Files.readAllBytes(Path.of(args[1]).resolve(selected.get("pdf").asString()));
            for (final var profile : profiles) {
                final var elements = extract(profile, bytes, selected.get("pdf").asString())
                                             .stream()
                                             .filter(element -> element.type() != ElementType.Image)
                                             .filter(element -> onPage(element, page))
                                             .map(element -> presentation.apply(element.text()))
                                             .toList();
                final var result = new LinkedHashMap<String, Object>(compare(upstream, elements));
                result.put("id", id);
                result.put("profile", profile);
                result.put("page", page);
                result.put("upstream_categories",
                        categories.stream().collect(Collectors.groupingBy(
                                category -> category, LinkedHashMap::new, Collectors.counting())));
                result.put("capture_sha256", hash(Files.readAllBytes(capturePath)));
                results.add(result);
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("manifest_sha256", hash(Files.readAllBytes(manifestPath)));
        report.put("runtime_sha256", hash(Files.readAllBytes(captures.resolve("runtime.json"))));
        report.put("java_version", System.getProperty("java.version"));
        report.put("presentation", presentation.name());
        report.put("results", results);
        Files.writeString(Path.of(args[5]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Locates every upstream element and derives order and grouping counts.
     *
     * @param upstream collapsed upstream element texts in upstream order
     * @param docweave collapsed DocWeave element texts in DocWeave order
     * @return counts and index lists
     */
    static Map<String, Object> compare(final List<String> upstream, final List<String> docweave) {
        final var joined = new StringBuilder();
        final var starts = new ArrayList<Integer>();
        for (final var text : docweave) {
            if (!joined.isEmpty()) {
                joined.append(' ');
            }
            starts.add(joined.length());
            joined.append(text);
        }
        final var positions = new ArrayList<Integer>();
        final var unlocated = new ArrayList<Integer>();
        final var ambiguous = new ArrayList<Integer>();
        final var spans = new ArrayList<Integer>();
        final var holders = new LinkedHashMap<Integer, Integer>();
        for (int index = 0; index < upstream.size(); index++) {
            final var text = upstream.get(index);
            final int first = text.isEmpty() ? -1 : joined.indexOf(text);
            if (first < 0) {
                unlocated.add(index);
                positions.add(-1);
                continue;
            }
            if (joined.indexOf(text, first + 1) >= 0) {
                ambiguous.add(index);
                positions.add(-1);
                continue;
            }
            positions.add(first);
            final int start = owner(starts, first);
            final int end = owner(starts, first + text.length() - 1);
            spans.add(end - start + 1);
            for (int element = start; element <= end; element++) {
                holders.merge(element, 1, Integer::sum);
            }
        }
        long preserved = 0;
        long reversed = 0;
        final var reversedPairs = new ArrayList<List<Integer>>();
        for (int left = 0; left < positions.size(); left++) {
            for (int right = left + 1; right < positions.size(); right++) {
                if (positions.get(left) < 0 || positions.get(right) < 0) {
                    continue;
                }
                if (positions.get(left) < positions.get(right)) {
                    preserved++;
                } else {
                    reversed++;
                    reversedPairs.add(List.of(left, right));
                }
            }
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("upstream_elements", upstream.size());
        result.put("docweave_elements", docweave.size());
        result.put("located", upstream.size() - unlocated.size() - ambiguous.size());
        result.put("unlocated", unlocated);
        result.put("ambiguous", ambiguous);
        result.put("preserved_pairs", preserved);
        result.put("reversed_pairs", reversed);
        result.put("reversed_pair_indices", reversedPairs);
        result.put("located_split_across_docweave_elements", spans.stream().filter(span -> span > 1).count());
        result.put("docweave_elements_holding_several_upstream",
                holders.values().stream().filter(count -> count > 1).count());
        return result;
    }

    /**
     * Returns the DocWeave element index owning a joined-text offset.
     *
     * @param starts start offsets of each element in the joined text
     * @param offset joined-text offset
     * @return owning element index
     */
    private static int owner(final List<Integer> starts, final int offset) {
        int owner = 0;
        while (owner + 1 < starts.size() && starts.get(owner + 1) <= offset) {
            owner++;
        }
        return owner;
    }

    /**
     * Extracts with one frozen scientific profile, using the same option construction as the reading test.
     *
     * @param profile frozen profile name; a native layout name followed by {@code +INLINE_SCRIPTS} selects
     *                {@link PdfOptions.InlineScripts#NEARBY_SMALL_GLYPHS}
     * @param bytes   original PDF bytes
     * @param name    original file name
     * @return extracted, and where declared assembled, elements
     * @throws Exception if extraction fails
     */
    private static List<Element> extract(final String profile, final byte[] bytes, final String name) throws Exception {
        if (profile.endsWith(INLINE_SCRIPTS)) {
            final var layout =
                    PdfOptions.TextLayout.valueOf(profile.substring(0, profile.length() - INLINE_SCRIPTS.length()));
            return DocWeave
                    .create(PartitionerRegistry.of(List.of(
                            PdfPartitioner.create(PdfOptions.defaults().withTextLayout(layout).withInlineScripts(
                                    PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS)))))
                    .partition(DocumentSource.bytes(bytes, name))
                    .elements();
        }
        final var base = PdfPartitioner.create(PdfOptions.defaults());
        final var strategy = switch (profile) {
            case "SOURCE_PARAGRAPHS_LINE_WRAPS_ASSEMBLED" ->
                base.withTaggedParagraphs(PdfTaggedParagraphOptions.defaults()
                                .withWordWrap(PdfTaggedParagraphOptions.WordWrap.SOURCE_LINE_WRAPS)
                                .withPageBoundary(PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS));
            case "SOURCE_PARAGRAPHS_INLINE_SCRIPTS_ASSEMBLED" ->
                base.withTaggedParagraphs(PdfTaggedParagraphOptions.defaults()
                                .withWordWrap(PdfTaggedParagraphOptions.WordWrap.SOURCE_LINE_WRAPS)
                                .withPageBoundary(PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS)
                                .withInlineScripts(PdfTaggedParagraphOptions.InlineScripts.NEARBY_SMALL_GLYPHS));
            default ->
                PdfPartitioner.create(PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.valueOf(profile)));
        };
        final var elements = DocWeave.create(PartitionerRegistry.of(List.of(strategy)))
                                     .partition(DocumentSource.bytes(bytes, name))
                                     .elements();
        return profile.endsWith("_ASSEMBLED") ? PdfParagraphAssembler.create().assemble(elements) : elements;
    }

    /**
     * Returns whether an element, or any fragment of an assembled element, lies on the page.
     *
     * @param element extracted element
     * @param page    one-based page number
     * @return whether it belongs to the compared page
     */
    private static boolean onPage(final Element element, final int page) {
        if (element.metadata().get("page_number") instanceof Number number) {
            return number.intValue() == page;
        }
        return element.metadata().get("page_numbers") instanceof List<?> pages
                && pages.stream().anyMatch(value -> value instanceof Number number && number.intValue() == page);
    }

    /** Symmetric text presentation applied to both sides before location; neither changes extraction. */
    enum Presentation {
        /** Collapses ASCII whitespace runs only, so Unicode spaces such as NBSP stay distinct. */
        EXACT,
        /**
         * Mirrors pinned Unstructured's presentation of non-table text: every run of characters for which Python's
         * {@code str.isspace()} is true becomes one ASCII space (unstructured/partition/pdf.py, 0.27.28).
         */
        UPSTREAM_WHITESPACE;

        /**
         * Applies this presentation and trims.
         *
         * @param text raw element text
         * @return presented text
         */
        String apply(final String text) {
            return this == EXACT ? collapse(text)
                                 : text.replaceAll("[\\t\\n\\u000B\\f\\r\\u001C-\\u001F \\u0085\\u00A0\\u1680"
                                                           + "\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+",
                                               " ")
                                           .strip();
        }
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

    /** Checks location, ambiguity, reversal, split and merge counting on owned sequences. */
    private static void controls() {
        final var result = compare(List.of("a b", "c d", "e", "x", "f g"), List.of("c d", "a", "b e f", "g e"));
        require(result.get("located").equals(3), "Located control failed");
        require(result.get("unlocated").equals(List.of(3)), "Unlocated control failed");
        require(result.get("ambiguous").equals(List.of(2)), "Ambiguous control failed");
        require(result.get("reversed_pairs").equals(1L) && result.get("preserved_pairs").equals(2L),
                "Pair control failed");
        require(result.get("located_split_across_docweave_elements").equals(2L), "Split control failed");
        require(result.get("docweave_elements_holding_several_upstream").equals(1L), "Merge control failed");
        require(compare(List.of("a\u00a0b"), List.of("a b")).get("located").equals(0), "Strict text control failed");
        require(Presentation.UPSTREAM_WHITESPACE.apply(" a\u202f\u2009b\u00a0 c\u200d ").equals("a b c\u200d")
                        && Presentation.EXACT.apply("a\u202fb").equals("a\u202fb"),
                "Presentation control failed");
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
     * @param message   failure description
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
