import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import org.apache.commons.csv.CSVFormat;
import org.jsoup.Jsoup;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Pinned whole-source partial-table header regression against unchanged saved CSV positions. */
public final class VerifyPartialHeaderReference {
    /** Prevents construction of this explicit source-reference evaluator. */
    private VerifyPartialHeaderReference() {
    }

    /**
     * Extracts the complete pinned six-page source before reading its unchanged reference CSV.
     * Projects actual anchors once; covered positions are blank and missing slots receive no credit.
     * The sole table on source page two must retain its 50-by-7 dimensions.
     * @param args original source/reference cache and fresh numeric JSON report
     * @throws Exception if identities, extraction, topology or reference population fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 2 || Files.exists(Path.of(args[1]))) {
            throw new IllegalArgumentException("Expected source cache and fresh report");
        }
        if (!normalize(" \tA\nB\r\f\u000b ").equals("A B") || !normalize("tin roof \u00a0").equals("tin roof \u00a0")) {
            throw new IllegalStateException("ASCII-only normalization control failed");
        }
        final var root = Path.of(args[0]);
        final var pdf = root.resolve("us-020.pdf");
        final var csv = root.resolve("us-020.csv");
        if (!sha(pdf).equals("d884d32bd2a1d620e0c7b5446e4d0b58ba67dab04e884839b2640b9a35749e99")
                || !sha(csv).equals("ff0ccd1d6ec4023e0c47a05d32f3dc8aa804f48cb28f704ab1886fedba80382b")) {
            throw new IllegalStateException("Input changed");
        }
        final var engine = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults()
                        .withTableDetection(PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT)
                        .withTableCellCoordinates(true)))));
        final var result = engine.partition(DocumentSource.bytes(Files.readAllBytes(pdf), "us-020.pdf"));
        final var tables = result.elements()
                                   .stream()
                                   .filter(e
                                           -> e.type() == ElementType.Table
                                                      && ((Number) e.metadata().get("page_number")).intValue() == 2)
                                   .toList();
        if (tables.size() != 1) {
            throw new IllegalStateException("Expected unchanged single selected table");
        }
        final var table = tables.getFirst();
        final var grid = (Map<?, ?>) table.metadata().get("pdf_table_cells");
        final var cells = (List<?>) grid.get("cells");
        final var texts = Jsoup.parse((String) table.metadata().get("text_as_html")).select("td,th");
        if (((Number) grid.get("row_count")).intValue() != 50 || ((Number) grid.get("column_count")).intValue() != 7
                || cells.size() != texts.size()) {
            throw new IllegalStateException("Grid changed");
        }
        final String[][] slots = new String[50][7];
        for (int i = 0; i < cells.size(); i++) {
            final var cell = (Map<?, ?>) cells.get(i);
            final int row = num(cell, "row_index");
            final int col = num(cell, "column_index");
            for (int r = row; r < row + num(cell, "row_span"); r++) {
                for (int c = col; c < col + num(cell, "column_span"); c++) {
                    if (r >= 50 || c >= 7 || slots[r][c] != null) {
                        throw new IllegalStateException("Invalid anchor");
                    }
                    slots[r][c] = r == row && c == col ? normalize(texts.get(i).wholeText()) : "";
                }
            }
        }
        int matches = 0, header = 0, body = 0, rows = 0;
        try (final var reader = Files.newBufferedReader(csv); final var parser = CSVFormat.DEFAULT.parse(reader)) {
            for (final var record : parser) {
                if (rows >= 50 || record.size() != 7) {
                    throw new IllegalStateException("Reference changed");
                }
                for (int c = 0; c < 7; c++) {
                    if (normalize(record.get(c)).equals(slots[rows][c])) {
                        matches++;
                        if (rows < 5) {
                            header++;
                        } else {
                            body++;
                        }
                    }
                }
                rows++;
            }
        }
        if (rows != 50) {
            throw new IllegalStateException("Missing reference rows");
        }
        final var report = Map.of("reference_positions", 350, "matched_positions", matches, "header_matches", header,
                "header_positions", 35, "body_matches", body, "body_positions", 315, "coordinates",
                table.metadata().get("coordinates"), "source_sha256", sha(pdf), "reference_sha256", sha(csv));
        Files.writeString(Path.of(args[1]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardOpenOption.CREATE_NEW);
        System.out.printf("matches=%d/350 headers=%d/35 body=%d/315%n", matches, header, body);
    }

    /**
     * Reads an original emitted integer anchor attribute.
     * @param map immutable cell metadata
     * @param key fixed anchor attribute
     * @return source topology value
     */
    private static int num(final Map<?, ?> map, final String key) {
        return ((Number) map.get(key)).intValue();
    }

    /**
     * Collapses ASCII whitespace only, preserving Unicode, punctuation and case.
     * @param s original source or reference text
     * @return unchanged historical comparison normalization
     */
    private static String normalize(final String s) {
        return s.replaceAll("[ \t\r\n\f\\x0B]+", " ").replaceAll("^ +| +$", "");
    }

    /**
     * Pins a small original source or reference file before use.
     * @param p local original file
     * @return exact SHA256 identity
     * @throws Exception if identity calculation fails
     */
    private static String sha(final Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }
}
