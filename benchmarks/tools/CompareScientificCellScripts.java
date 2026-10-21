package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfNativeLayoutOptions;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import dev.docweave.partition.PdfTaggedTableGeometry;
import org.jsoup.Jsoup;
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

/** Whole-original character/ownership regression diagnostic, without reference-text alignment or accuracy scoring. */
public final class CompareScientificCellScripts {
    /** Prevents construction of the standalone diagnostic. */
    private CompareScientificCellScripts() {
    }

    /**
     * Runs every pinned original under both fixed geometry profiles and both cell policies.
     * Input TSV rows contain a safe case identifier, complete PDF path, byte length and SHA256.
     * Typed extraction failures stay in the complete numeric report; successful pairs must retain
     * ordinary elements, all other metadata, each logical HTML cell's nonspacing character inventory
     * and its topology. This cannot establish mathematical meaning or correct character order.
     * @param args frozen input TSV and new numeric report path
     * @throws Exception if source identities, comparison contracts or fresh output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected pinned inputs TSV and NEW numeric report");
        }
        final var inputs = Path.of(args[0]);
        final var results = new ArrayList<Map<String, Object>>();
        for (final String row : Files.readAllLines(inputs)) {
            final var fields = row.split("\t", -1);
            if (fields.length != 4 || !fields[0].matches("[a-z0-9-]+") || !fields[3].matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid source pin");
            }
            final var path = Path.of(fields[1]);
            if (Files.size(path) > 10 * 1024 * 1024 || Files.size(path) != Long.parseLong(fields[2])) {
                throw new IllegalArgumentException("Source length changed or exceeds10MiB");
            }
            final var bytes = Files.readAllBytes(path);
            require(hash(bytes).equals(fields[3]), "Source identity changed");
            for (final var geometry : List.of(PdfTaggedTableGeometry.SUPPORTED_CELL_AXES,
                         PdfTaggedTableGeometry.LOGICAL_CELL_TEXT_ENVELOPES)) {
                final var report = new LinkedHashMap<String, Object>();
                report.put("case", fields[0]);
                report.put("source_sha256", fields[3]);
                report.put("geometry", geometry.name());
                try {
                    final var source = DocumentSource.bytes(bytes, fields[0] + ".pdf");
                    final var before = processor(PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED, geometry)
                                               .partition(source)
                                               .elements();
                    final var after = processor(PdfNativeLayoutOptions.CellTextJoin.INLINE_SCRIPT_RUNS, geometry)
                                              .partition(source)
                                              .elements();
                    require(before.size() == after.size(), "Element population changed");
                    int tables = 0;
                    int changed = 0;
                    int interpreted = 0;
                    int glyphs = 0;
                    for (int index = 0; index < before.size(); index++) {
                        final var original = before.get(index);
                        final var candidate = after.get(index);
                        if (original.type() != ElementType.Table) {
                            require(original.equals(candidate), "Ordinary element changed");
                            continue;
                        }
                        require(candidate.type() == ElementType.Table, "Table type changed");
                        tables++;
                        changed += original.text().equals(candidate.text()) ? 0 : 1;
                        final var oldMetadata = new LinkedHashMap<>(original.metadata());
                        final var newMetadata = new LinkedHashMap<>(candidate.metadata());
                        final var oldHtml = Jsoup.parseBodyFragment(oldMetadata.remove("text_as_html").toString());
                        final var newHtml = Jsoup.parseBodyFragment(newMetadata.remove("text_as_html").toString());
                        final var count = newMetadata.remove("pdf_table_inline_script_glyphs");
                        if (count != null) {
                            interpreted++;
                            glyphs += ((Number) count).intValue();
                            require("native_inline_script_runs_v1".equals(
                                            newMetadata.remove("pdf_table_cell_text_policy")),
                                    "Unexpected interpretation policy");
                        }
                        require(oldMetadata.equals(newMetadata), "Source geometry/metadata changed");
                        final var oldCells = oldHtml.select("td, th");
                        final var newCells = newHtml.select("td, th");
                        require(oldCells.size() == newCells.size(), "Cell population changed");
                        for (int cell = 0; cell < oldCells.size(); cell++) {
                            final var first = oldCells.get(cell);
                            final var second = newCells.get(cell);
                            require(first.tagName().equals(second.tagName())
                                            && first.attributes().equals(second.attributes()),
                                    "Cell topology changed");
                            require(characters(first.wholeText()).equals(characters(second.wholeText())),
                                    "Cell nonspacing characters changed");
                        }
                    }
                    report.put("outcome", "SUCCESS");
                    report.put("elements", before.size());
                    report.put("tables", tables);
                    report.put("changed_tables", changed);
                    report.put("interpreted_tables", interpreted);
                    report.put("assigned_script_glyphs", glyphs);
                    report.put("ordinary_elements_and_other_metadata_equal", true);
                    report.put("cell_topology_and_nonspacing_characters_equal", true);
                } catch (final PartitionException failure) {
                    report.put("outcome", failure.code().name());
                } catch (final IllegalStateException discrepancy) {
                    report.put("outcome", "REPRESENTATION_MISMATCH");
                    report.put("diagnostic", discrepancy.getMessage());
                }
                results.add(report);
            }
        }
        final var mapper = JsonMapper.builder().build();
        Files.writeString(Path.of(args[1]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime",
                        Runtime.version().toString(), "input_tsv_sha256", hash(Files.readAllBytes(inputs)), "scope",
                        "Whole-original representation regression; all inputs/profiles retained; no reference row scoring or source-specific selection",
                        "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
        require(results.stream().noneMatch(result -> "REPRESENTATION_MISMATCH".equals(result.get("outcome"))),
                "Representation discrepancy; inspect the complete numeric report");
    }

    /**
     * Selects source-first tables with unchanged closed-rule/ordinary fallback and no model or OCR provider.
     * @param join explicit cell text policy
     * @param geometry uniform source geometry interpretation
     * @return hidden public-service composition
     */
    private static DocWeave processor(
            final PdfNativeLayoutOptions.CellTextJoin join, final PdfTaggedTableGeometry geometry) {
        final var service =
                PdfPartitioner
                        .createWithTaggedTables(PdfOptions.defaults().withTableCellCoordinates(
                                                        geometry == PdfTaggedTableGeometry.SUPPORTED_CELL_AXES),
                                join)
                        .withTaggedTableGeometry(geometry);
        return DocWeave.create(PartitionerRegistry.of(List.of(service)));
    }

    /**
     * Counts exact characters except generated ASCII joining spaces, retaining NBSP and other Unicode spacing.
     * @param text source-derived cell text
     * @return code-point frequency map used only as a representation integrity check
     */
    private static Map<Integer, Integer> characters(final String text) {
        final var counts = new TreeMap<Integer, Integer>();
        text.codePoints().filter(code -> code != ' ').forEach(code -> counts.merge(code, 1, Integer::sum));
        return counts;
    }

    /**
     * Fingerprints complete encoded bytes.
     * @param bytes source or protocol bytes
     * @return lowercase SHA256
     * @throws Exception if SHA256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects representation/ownership discrepancies without including source text in errors.
     * @param condition required contract
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
