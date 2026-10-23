package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.partition.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/** Whole-source representation comparison using unchanged frozen model outputs. */
public final class CompareNvidiaCellLines {
    /** Prevents utility construction. */
    private CompareNvidiaCellLines() {
    }

    /**
     * Extracts both policies from every original page and verifies geometry/ownership preservation.
     * @param args original source cache, frozen capture root and fresh numeric report
     * @throws Exception if source identity, native extraction or representation invariants fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 3);
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(
                Files.readAllBytes(Path.of("benchmarks/review/pdf-nvidia-proposal-structures/replay-manifest.json")));
        final var reference =
                mapper.readTree(Files.readAllBytes(Path.of(manifest.get("reference_manifest").asString())));
        require(hash(Files.readAllBytes(Path.of(manifest.get("reference_manifest").asString())))
                        .equals(manifest.get("reference_manifest_sha256").asString()));
        final var original = Path.of(args[0]).resolve(reference.get("source_pdf").asString());
        require(hash(Files.readAllBytes(original)).equals(reference.get("inputs").get(0).get("sha256").asString()));
        final var reports = new ArrayList<Map<String, Object>>();
        for (final var profile : manifest.get("profiles")) {
            final var before = extract(manifest, Path.of(args[1]), profile.asString(), original,
                    PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED);
            final var after = extract(manifest, Path.of(args[1]), profile.asString(), original,
                    PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS);
            require(before.size() == after.size());
            int changed = 0, tables = 0, retainedBreaks = 0;
            for (int index = 0; index < before.size(); index++) {
                final var first = before.get(index);
                final var second = after.get(index);
                require(first.type() == second.type());
                if (first.type() != ElementType.Table) {
                    require(first.equals(second));
                    continue;
                }
                tables++;
                require(first.text().equals(second.text().replace('\n', ' ')));
                final var oldMetadata = new LinkedHashMap<>(first.metadata());
                final var newMetadata = new LinkedHashMap<>(second.metadata());
                final var oldHtml = oldMetadata.remove("text_as_html");
                final var newHtml = newMetadata.remove("text_as_html").toString();
                require(oldHtml.equals(newHtml.replace("<br>", " ")));
                if (!first.text().equals(second.text())) {
                    changed++;
                    require("native_line_breaks_v1".equals(newMetadata.remove("pdf_table_cell_text_policy")));
                    require(((Number) newMetadata.remove("pdf_table_multiline_cells")).intValue() > 0);
                    retainedBreaks += second.text().chars().filter(c -> c == '\n').count();
                }
                require(oldMetadata.equals(newMetadata));
            }
            reports.add(Map.of("profile", profile.asString(), "original_pages", manifest.get("pages").size(),
                    "elements", before.size(), "tables", tables, "changed_tables", changed, "preserved_line_breaks",
                    retainedBreaks, "unchanged_text_after_lf_projection", true,
                    "unchanged_other_metadata_and_ordinary_elements", true));
        }
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime",
                        Runtime.version().toString(), "scope",
                        "Whole original source, fixed cached structures, representation/ownership comparison only; no row realignment or accuracy claim",
                        "results", reports))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Runs one complete source with exact sequential raster identity guards and model-produced layouts.
     * @param manifest frozen all-page captures
     * @param root local numeric capture directory
     * @param profile fixed detector profile
     * @param original original full PDF
     * @param policy explicit native cell representation
     * @return complete original element sequence
     * @throws Exception if source/capture validation or extraction fails
     */
    private static List<Element> extract(final JsonNode manifest, final Path root, final String profile,
            final Path original, final PdfNativeLayoutOptions.CellTextJoin policy) throws Exception {
        return extract(manifest, root, profile, original, policy, PdfNativeLayoutOptions.LeadingRow.PROVIDER_ONLY);
    }

    /**
     * Replays fixed original geometry with an explicitly selected source-boundary policy.
     * Every original page and raster identity remains required; no additional model call occurs.
     * @param manifest frozen all-page captures
     * @param root local numeric capture directory
     * @param profile fixed detector profile
     * @param original complete original PDF
     * @param policy native cell text representation
     * @param leadingRow explicit source-based leading row policy
     * @return complete original element sequence
     * @throws Exception if capture validation or extraction fails
     */
    static List<Element> extract(final JsonNode manifest, final Path root, final String profile, final Path original,
            final PdfNativeLayoutOptions.CellTextJoin policy, final PdfNativeLayoutOptions.LeadingRow leadingRow)
            throws Exception {
        return extract(manifest, root, profile, original, policy, leadingRow,
                PdfNativeLayoutOptions.TrailingRows.PROVIDER_ONLY);
    }

    /**
     * Replays fixed original geometry with explicitly selected leading and trailing source-boundary policies.
     * Every original page and raster identity remains required; no additional model call occurs.
     * @param manifest frozen all-page captures
     * @param root local numeric capture directory
     * @param profile fixed detector profile
     * @param original complete original PDF
     * @param policy native cell text representation
     * @param leadingRow explicit source-based leading row policy
     * @param trailingRows explicit source-based trailing row policy
     * @return complete original element sequence
     * @throws Exception if capture validation or extraction fails
     */
    static List<Element> extract(final JsonNode manifest, final Path root, final String profile, final Path original,
            final PdfNativeLayoutOptions.CellTextJoin policy, final PdfNativeLayoutOptions.LeadingRow leadingRow,
            final PdfNativeLayoutOptions.TrailingRows trailingRows) throws Exception {
        final var reader = ReplayNativeLayoutCaptures.class.getDeclaredMethod(
                "snapshots", JsonNode.class, Path.class, String.class);
        reader.setAccessible(true);
        final var snapshots = (List<?>) reader.invoke(null, manifest, root, profile);
        final var snapshotType = Class.forName("dev.docweave.validation.ReplayNativeLayoutCaptures$Snapshot");
        final var tables = snapshotType.getDeclaredMethod("tables");
        tables.setAccessible(true);
        final var raster = snapshotType.getDeclaredMethod("rasterHash");
        raster.setAccessible(true);
        final var outcome = snapshotType.getDeclaredMethod("outcome");
        outcome.setAccessible(true);
        final var calls = new AtomicInteger();
        final PdfTableLayoutProvider provider = image -> {
            final int index = calls.getAndIncrement();
            require(index < snapshots.size());
            final var snapshot = snapshots.get(index);
            try (final var bytes = new ByteArrayOutputStream()) {
                require(ImageIO.write(image, "png", bytes));
                require(hash(bytes.toByteArray()).equals(raster.invoke(snapshot)));
                require("OK".equals(outcome.invoke(snapshot)));
                @SuppressWarnings("unchecked")
                final var decoded = (List<PdfTableLayoutProvider.Table>) tables.invoke(snapshot);
                return decoded;
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        };
        final var partitioner = PdfPartitioner.createWithNativeTablesFirst(
                PdfOptions.defaults().withTableCellCoordinates(true).withTableDetection(
                        PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT),
                new PdfNativeLayoutOptions(150, 16000000, 1024, policy)
                        .withLeadingRow(leadingRow)
                        .withTrailingRows(trailingRows),
                provider);
        final var extracted = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                                      .partition(DocumentSource.bytes(
                                              Files.readAllBytes(original), original.getFileName().toString()));
        require(calls.get() == snapshots.size());
        return extracted.elements();
    }

    /**
     * Computes a local source/raster identity without exposing publisher content.
     * @param bytes complete original bytes
     * @return lowercase SHA-256 digest
     */
    private static String hash(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /**
     * Rejects discrepancies without repairing output or dropping cases.
     * @param valid required representation invariant
     */
    private static void require(final boolean valid) {
        if (!valid)
            throw new IllegalArgumentException("Cell line preservation comparison failed");
    }
}
