import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import org.apache.commons.csv.CSVFormat;
import org.jsoup.Jsoup;
import tools.jackson.databind.json.JsonMapper;

import java.io.StringReader;
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

/**
 * Scores the unchanged native procurement table against the original saved CSV (v1) and an additive, separately
 * pinned candidate reference (v2) that restores glyph-evidenced separators.
 *
 * <p>v2 must differ from v1 in exactly two positions, each only by one inserted U+0020 directly after an ampersand.
 * The extraction runs with default native options before any reference is read. Comparison collapses ASCII
 * whitespace only. The report carries counts, positions and hashes, never cell text.
 */
public final class VerifyProcurementReferenceVersions {
    /** Pinned original source. */
    private static final String SOURCE_SHA256 = "038126d57e3d9aadfee6c3ac44acc66d4fbdc13598dad8deadd7ec76a2a7d2ab";

    /** Pinned original saved reference. */
    private static final String REFERENCE_SHA256 = "d4ae792846efa5cb1a2829ec8fe767d2aae62c6e96de5e8d9538cba465a3032e";

    /** Prevents construction. */
    private VerifyProcurementReferenceVersions() {
    }

    /**
     * Extracts the complete original, then scores both reference versions.
     *
     * @param args original PDF, original CSV, candidate v2 CSV and a fresh numeric report
     * @throws Exception if identities, topology or the v2 difference contract fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 4 || Files.exists(Path.of(args[3]))) {
            throw new IllegalArgumentException("Expected pdf, v1 csv, v2 csv and a fresh report");
        }
        final byte[] pdf = Files.readAllBytes(Path.of(args[0]));
        final byte[] v1 = Files.readAllBytes(Path.of(args[1]));
        final byte[] v2 = Files.readAllBytes(Path.of(args[2]));
        require(sha(pdf).equals(SOURCE_SHA256) && sha(v1).equals(REFERENCE_SHA256), "Input changed");
        final var result = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                                                   PdfOptions.defaults().withTableCellCoordinates(true)))))
                                   .partition(DocumentSource.bytes(pdf, "procurement.pdf"));
        final var tables = result.elements()
                                   .stream()
                                   .filter(e
                                           -> e.type() == ElementType.Table
                                                      && ((Number) e.metadata().get("page_number")).intValue() == 1)
                                   .toList();
        require(tables.size() == 1, "Expected one selected-page table");
        final var grid = (Map<?, ?>) tables.getFirst().metadata().get("pdf_table_cells");
        final int rows = ((Number) grid.get("row_count")).intValue();
        final int columns = ((Number) grid.get("column_count")).intValue();
        require(rows == 14 && columns == 11, "Grid changed");
        final var cells = (List<?>) grid.get("cells");
        final var texts = Jsoup.parse((String) tables.getFirst().metadata().get("text_as_html")).select("td,th");
        require(cells.size() == texts.size(), "Cell population changed");
        final String[][] slots = new String[rows][columns];
        final String[][] raw = new String[rows][columns];
        for (int index = 0; index < cells.size(); index++) {
            final var cell = (Map<?, ?>) cells.get(index);
            final int row = num(cell, "row_index");
            final int column = num(cell, "column_index");
            for (int r = row; r < row + num(cell, "row_span"); r++) {
                for (int c = column; c < column + num(cell, "column_span"); c++) {
                    require(slots[r][c] == null, "Invalid anchor");
                    slots[r][c] = r == row && c == column ? normalize(texts.get(index).wholeText()) : "";
                    raw[r][c] = r == row && c == column ? texts.get(index).wholeText() : "";
                }
            }
        }
        final var first = records(v1, rows, columns);
        final var second = records(v2, rows, columns);
        final var changed = new ArrayList<List<Integer>>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                final String a = first.get(r).get(c);
                final String b = second.get(r).get(c);
                if (!a.equals(b)) {
                    require(b.length() == a.length() + 1 && insertedSpaceAfterAmpersand(a, b),
                            "v2 may only insert one space after an ampersand");
                    changed.add(List.of(r, c));
                }
            }
        }
        require(changed.size() == 2, "v2 must change exactly two positions");
        final var report = new LinkedHashMap<String, Object>();
        report.put("source_sha256", SOURCE_SHA256);
        report.put("reference_v1_sha256", REFERENCE_SHA256);
        report.put("reference_v2_sha256", sha(v2));
        report.put("reference_v2_status", "PROPOSED_ADDITIVE_PENDING_INDEPENDENT_REVIEW");
        report.put("reference_positions", rows * columns);
        report.put("v2_changed_positions", changed);
        // Whether the extracted cell keeps a source whitespace character on both sides of each ampersand.
        report.put("extracted_separators_on_both_sides",
                changed.stream()
                        .map(position -> raw[position.get(0)][position.get(1)].matches("(?s).*\\s&\\s.*"))
                        .toList());
        report.put("v1_matched_positions", matches(first, slots));
        report.put("v2_matched_positions", matches(second, slots));
        Files.writeString(Path.of(args[3]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println(report);
    }

    /**
     * Tests whether {@code b} equals {@code a} with one space inserted directly after an ampersand.
     *
     * @param a original cell text
     * @param b candidate cell text
     * @return whether the only difference is that insertion
     */
    private static boolean insertedSpaceAfterAmpersand(final String a, final String b) {
        for (int index = 0; index < a.length(); index++) {
            if (a.charAt(index) == '&' && b.equals(a.substring(0, index + 1) + " " + a.substring(index + 1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts reference positions equal to the projected extraction.
     *
     * @param records reference cells
     * @param slots projected extraction
     * @return matched positions
     */
    private static int matches(final List<List<String>> records, final String[][] slots) {
        int matched = 0;
        for (int r = 0; r < slots.length; r++) {
            for (int c = 0; c < slots[r].length; c++) {
                if (normalize(records.get(r).get(c)).equals(slots[r][c])) {
                    matched++;
                }
            }
        }
        return matched;
    }

    /**
     * Parses a complete reference of fixed dimensions.
     *
     * @param bytes UTF-8 CSV
     * @param rows expected rows
     * @param columns expected columns
     * @return cell texts
     * @throws Exception if parsing fails or dimensions differ
     */
    private static List<List<String>> records(final byte[] bytes, final int rows, final int columns) throws Exception {
        final var result = new ArrayList<List<String>>();
        try (final var parser = CSVFormat.DEFAULT.parse(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            for (final var record : parser) {
                require(record.size() == columns, "Reference width changed");
                result.add(record.toList());
            }
        }
        require(result.size() == rows, "Reference height changed");
        return result;
    }

    /**
     * Reads an integer anchor attribute.
     *
     * @param map cell metadata
     * @param key attribute
     * @return value
     */
    private static int num(final Map<?, ?> map, final String key) {
        return ((Number) map.get(key)).intValue();
    }

    /**
     * Collapses ASCII whitespace only, as the historical evaluator does.
     *
     * @param s text
     * @return comparison text
     */
    private static String normalize(final String s) {
        return s.replaceAll("[ \t\r\n\f\\x0B]+", " ").replaceAll("^ +| +$", "");
    }

    /**
     * Hashes bytes.
     *
     * @param bytes content
     * @return lowercase SHA-256
     * @throws Exception if unavailable
     */
    private static String sha(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Fails an invariant.
     *
     * @param condition condition
     * @param message explanation
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
