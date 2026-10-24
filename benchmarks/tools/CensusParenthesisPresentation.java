package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.jsoup.Jsoup;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Counts how actual pinned Unstructured and DocWeave present parenthesized accounting numbers in one original.
 *
 * <p>The census separates upstream {@code fast} native text, upstream {@code hi_res} Table cells and non-table
 * text, and DocWeave default element text. It counts syntax only; it never rewrites text, assigns semantics or
 * compares against the issuer HTML, whose strict scores are unchanged. The report holds counts and hashes only.
 */
public final class CensusParenthesisPresentation {
    /** An unsigned number with optional three-digit comma groups and an optional decimal fraction. */
    private static final String NUMBER = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?";

    /** Presentation classes, each matched against ASCII spaces only. */
    enum Presentation {
        /** {@code (1,234)} with nothing inside the parentheses but the number. */
        TIGHT("\\(" + NUMBER + "\\)"),
        /** {@code (1,234 )}: one or more ASCII spaces before the closing parenthesis. */
        SPACE_BEFORE_CLOSE("\\(" + NUMBER + " +\\)"),
        /** {@code ( 1,234)}: one or more ASCII spaces after the opening parenthesis. */
        SPACE_AFTER_OPEN("\\( +" + NUMBER + " *\\)"),
        /** {@code (1,234} ending a text or cell without its closing parenthesis. */
        OPEN_AT_END("\\(" + NUMBER + "$"),
        /** A closing parenthesis that is the whole cell or text. */
        CLOSE_ALONE("^\\)$");

        /** Compiled syntax of this class. */
        private final Pattern pattern;

        /**
         * Compiles one class pattern.
         *
         * @param regex the class syntax
         */
        Presentation(final String regex) {
            pattern = Pattern.compile(regex);
        }

        /**
         * Counts non-overlapping occurrences in one text.
         *
         * @param text text to scan
         * @return occurrence count
         */
        long count(final String text) {
            return pattern.matcher(text).results().count();
        }
    }

    /** Prevents construction of this command-line tool. */
    private CensusParenthesisPresentation() {
    }

    /**
     * Writes the census for one original and its three upstream captures.
     *
     * @param args original PDF, its SHA-256, upstream fast capture, upstream hi_res default capture, upstream hi_res
     *         shared capture and a fresh report; or {@code --self-test}
     * @throws Exception if an identity or control fails
     */
    public static void main(final String[] args) throws Exception {
        controls();
        if (args.length == 1 && args[0].equals("--self-test")) {
            System.out.println("Parenthesis census controls passed");
            return;
        }
        if (args.length != 6) {
            throw new IllegalArgumentException("Expected source, sha256, fast, hi_res default, hi_res shared, report");
        }
        final var source = Path.of(args[0]);
        final byte[] bytes = Files.readAllBytes(source);
        if (!hex(bytes).equals(args[1])) {
            throw new IllegalStateException("Original source differs");
        }
        final var mapper = JsonMapper.builder().build();
        final var report = new LinkedHashMap<String, Object>();
        report.put("source_sha256", args[1]);
        report.put("java_version", System.getProperty("java.version"));
        final var fast = mapper.readTree(Files.readAllBytes(Path.of(args[2])));
        final var fastText = new ArrayList<String>();
        fast.get("elements").forEach(element -> fastText.add(element.get("text").asString()));
        report.put("upstream_fast", census(Path.of(args[2]), fast.get("outcome").asString(), fastText));
        for (final var profile :
                List.of(Map.entry("upstream_hi_res_default", args[3]), Map.entry("upstream_hi_res_shared", args[4]))) {
            final var capture = mapper.readTree(Files.readAllBytes(Path.of(profile.getValue())));
            final var cells = new ArrayList<String>();
            final var other = new ArrayList<String>();
            for (final var element : capture.get("elements")) {
                final var html = element.get("metadata").get("text_as_html");
                if (element.get("type").asString().equals("Table") && html != null && html.isString()) {
                    Jsoup.parseBodyFragment(html.asString()).select("td, th").forEach(cell -> cells.add(cell.text()));
                } else if (!element.get("type").asString().equals("Table")) {
                    other.add(element.get("text").asString());
                }
            }
            final var observed = new LinkedHashMap<String, Object>();
            observed.put("table_cells", census(Path.of(profile.getValue()), capture.get("outcome").asString(), cells));
            observed.put("non_table_text", counts(other));
            report.put(profile.getKey(), observed);
        }
        final var elements =
                DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults()))))
                        .partition(DocumentSource.bytes(bytes, source.getFileName().toString()))
                        .elements();
        final var docweave = new LinkedHashMap<String, Object>();
        docweave.put("profile", "PdfOptions.defaults()");
        docweave.put("elements", elements.size());
        docweave.put("presentation", counts(elements.stream().map(element -> element.text()).toList()));
        report.put("docweave_default", docweave);
        Files.writeString(Path.of(args[5]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Records one capture's identity, outcome, population and class counts.
     *
     * @param capture the raw capture file
     * @param outcome the capture outcome
     * @param texts   the texts to scan
     * @return the census entry
     * @throws Exception if the capture cannot be hashed
     */
    private static Map<String, Object> census(final Path capture, final String outcome, final List<String> texts)
            throws Exception {
        final var entry = new LinkedHashMap<String, Object>();
        entry.put("capture_sha256", hex(Files.readAllBytes(capture)));
        entry.put("outcome", outcome);
        entry.put("texts", texts.size());
        entry.put("presentation", counts(texts));
        return entry;
    }

    /**
     * Counts every class across the texts; cells and elements are scanned independently.
     *
     * @param texts texts to scan
     * @return counts by class name
     */
    static Map<String, Long> counts(final List<String> texts) {
        final var counts = new EnumMap<Presentation, Long>(Presentation.class);
        for (final var presentation : Presentation.values()) {
            counts.put(presentation, texts.stream().mapToLong(presentation::count).sum());
        }
        final var named = new LinkedHashMap<String, Long>();
        counts.forEach((presentation, count) -> named.put(presentation.name(), count));
        return named;
    }

    /** Checks each class and its negatives, including Unicode spaces and malformed grouping. */
    private static void controls() {
        final var observed = counts(List.of("(1,234) (5 ) ( 6) (7.50)", "(8,000", ")", "(1,23)", "(9\u00a0)", "a)"));
        final var expected = Map.of(
                "TIGHT", 2L, "SPACE_BEFORE_CLOSE", 1L, "SPACE_AFTER_OPEN", 1L, "OPEN_AT_END", 1L, "CLOSE_ALONE", 1L);
        if (!observed.equals(expected)) {
            throw new IllegalStateException("Presentation controls failed: " + observed);
        }
    }

    /**
     * Returns the lowercase SHA-256 of bytes.
     *
     * @param bytes bytes to hash
     * @return hexadecimal digest
     * @throws Exception if SHA-256 is unavailable
     */
    private static String hex(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
