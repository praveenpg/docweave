package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

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
 * Hashes complete native output of pinned sources under the default and the pre-existing explicit side-stream
 * policies, so two runtimes can be compared without retaining source text.
 *
 * <p>It uses only API that predates {@code SMALLER_TYPE_BLOCKS_ROW_TIES}, so the same source compiles against an
 * earlier runtime. Each hash covers every element's identifier, type, text and metadata in emission order, with map
 * entries serialized in key order.
 */
public final class HashPdfPolicyOutputs {
    /** Prevents construction of this command-line tool. */
    private HashPdfPolicyOutputs() {
    }

    /**
     * Writes the hash report.
     *
     * @param args pinned source TSV (id, path, byte length, SHA-256) and a fresh report
     * @throws Exception if a pin differs, extraction fails or the report exists
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("sources-tsv report");
        }
        // Immutable metadata maps iterate in a per-JVM order, so map entries are serialized by key.
        final var mapper = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        final var rows = new ArrayList<Map<String, Object>>();
        final byte[] table = Files.readAllBytes(Path.of(args[0]));
        for (final var row : new String(table, StandardCharsets.UTF_8).split("\n")) {
            if (row.isBlank()) {
                continue;
            }
            final var fields = row.split("\t");
            final byte[] bytes = Files.readAllBytes(Path.of(fields[1]));
            if (bytes.length != Long.parseLong(fields[2]) || !hash(bytes).equals(fields[3])) {
                throw new IllegalStateException("Source pin differs: " + fields[0]);
            }
            for (final boolean inline : List.of(false, true)) {
                for (final var layout : PdfOptions.TextLayout.values()) {
                    for (final var policy : PdfOptions.SideStreams.values()) {
                        var options = PdfOptions.defaults().withTextLayout(layout).withSideStreams(policy);
                        if (inline) {
                            options = options.withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
                        }
                        final var elements = extract(bytes, options);
                        final var entry = new LinkedHashMap<String, Object>();
                        entry.put("source", fields[0]);
                        entry.put("layout", layout.name());
                        entry.put("inline_scripts", inline);
                        entry.put("side_streams", policy.name());
                        entry.put("elements", elements.size());
                        entry.put("output_sha256", hash(mapper.writeValueAsBytes(elements)));
                        rows.add(entry);
                    }
                }
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("java_version", System.getProperty("java.version"));
        report.put("sources_tsv_sha256", hash(table));
        report.put("outputs", rows);
        Files.writeString(Path.of(args[1]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
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
