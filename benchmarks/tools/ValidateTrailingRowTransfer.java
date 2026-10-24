package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.layout.OnnxTableLayout;
import io.docweave.layout.OnnxTableOptions;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfNativeLayoutOptions;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import io.docweave.partition.PdfTableLayoutProvider;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/**
 * Explicit original-document transfer validation using one pinned inference capture per page and
 * identity-checked replay. Extraction receives neither source reference rows nor selected regions.
 * Captures contain source text and belong in local/private evidence only. No download is performed.
 */
public final class ValidateTrailingRowTransfer {
    /** Prevents launcher construction. */
    private ValidateTrailingRowTransfer() {
    }

    /**
     * One complete provider observation with a typed failure retained rather than silently discarded.
     * @param pngSha256 encoded raster identity
     * @param tables complete supplied geometry
     * @param failure typed failure name, or empty on success
     */
    private record Snapshot(String pngSha256, List<PdfTableLayoutProvider.Table> tables, String failure) {}

    /**
     * Complete extraction outcome; source elements are retained only in the private output.
     * @param outcome success or typed error
     * @param elements all emitted elements
     */
    private record Extraction(String outcome, List<Element> elements) {}

    /**
     * Runs the frozen source list once with a managed model, followed by geometry-identical policy replay.
     * @param args protocol properties and fresh private output directory
     * @throws Exception if identities, replay consumption, inference or output writing fails unexpectedly
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected protocol and fresh output directory");
        }
        final var protocol = new Properties();
        try (final var input = Files.newInputStream(Path.of(args[0]))) {
            protocol.load(input);
        }
        final var output = Files.createDirectory(Path.of(args[1]));
        final var mapper = JsonMapper.builder().build();
        final var detector = OnnxTableOptions.yoloXDefaults(
                Path.of(protocol.getProperty("detector.path")), protocol.getProperty("detector.sha256"));
        final var structure = OnnxTableOptions
                                      .defaults(Path.of(protocol.getProperty("structure.path")),
                                              protocol.getProperty("structure.sha256"))
                                      .withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
        final var reports = new ArrayList<Object>();
        try (final var provider = OnnxTableLayout.createWithYoloX(detector, structure)) {
            for (final String id : protocol.getProperty("sources").split(",")) {
                final var bytes = Files.readAllBytes(Path.of(protocol.getProperty(id + ".path")));
                require(hash(bytes).equals(protocol.getProperty(id + ".sha256")), "Source identity changed");
                final var snapshots = new ArrayList<Snapshot>();
                final PdfTableLayoutProvider recording = image -> {
                    final String png = pngHash(image);
                    try {
                        final var tables = provider.locate(image);
                        snapshots.add(new Snapshot(png, tables, ""));
                        return tables;
                    } catch (final PartitionException failure) {
                        snapshots.add(new Snapshot(png, List.of(), failure.code().name()));
                        throw failure;
                    }
                };
                final var baseline = extract(bytes, recording, false);
                final var calls = new AtomicInteger();
                final PdfTableLayoutProvider replay = image -> {
                    final int index = calls.getAndIncrement();
                    require(index < snapshots.size(), "Extra replay request");
                    final var capture = snapshots.get(index);
                    require(pngHash(image).equals(capture.pngSha256()), "Replay raster changed");
                    if (!capture.failure().isEmpty()) {
                        throw new PartitionException(
                                PartitionException.Code.valueOf(capture.failure()), "Captured typed provider failure");
                    }
                    return capture.tables();
                };
                final var candidate = extract(bytes, replay, true);
                require(calls.get() == snapshots.size(), "Unconsumed provider capture");
                final var before = payload(baseline.elements());
                final var after = payload(candidate.elements());
                final var report = new LinkedHashMap<String, Object>();
                report.put("source", id);
                report.put("source_sha256", hash(bytes));
                report.put("baseline_outcome", baseline.outcome());
                report.put("candidate_outcome", candidate.outcome());
                report.put("provider_calls", snapshots.size());
                report.put("provider_tables", snapshots.stream().mapToInt(s -> s.tables().size()).sum());
                report.put("baseline_tables",
                        baseline.elements().stream().filter(e -> e.type() == ElementType.Table).count());
                report.put("candidate_tables",
                        candidate.elements().stream().filter(e -> e.type() == ElementType.Table).count());
                report.put("added_rows", candidate.elements()
                                                 .stream()
                                                 .map(e -> e.metadata().get("pdf_table_added_trailing_rows"))
                                                 .filter(Number.class ::isInstance)
                                                 .map(Number.class ::cast)
                                                 .mapToInt(Number::intValue)
                                                 .sum());
                report.put("logical_payload_identical", before.equals(after));
                reports.add(report);
                Files.writeString(output.resolve(id + "-capture.json"), mapper.writeValueAsString(snapshots),
                        StandardOpenOption.CREATE_NEW);
                Files.writeString(output.resolve(id + "-baseline.json"), mapper.writeValueAsString(before),
                        StandardOpenOption.CREATE_NEW);
                Files.writeString(output.resolve(id + "-candidate.json"), mapper.writeValueAsString(after),
                        StandardOpenOption.CREATE_NEW);
                System.out.println(report);
            }
        }
        Files.writeString(output.resolve("summary.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("protocol_sha256", hash(Files.readAllBytes(Path.of(args[0]))), "java",
                                Runtime.version().toString(), "results", reports)),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Extracts a complete original under one uniform best-existing leading-row policy.
     * @param bytes complete original document
     * @param provider explicit capture or replay provider
     * @param trailing whether the new option is selected
     * @return complete outcome, including typed failures
     * @throws Exception if extraction encounters an unexpected error
     */
    private static Extraction extract(final byte[] bytes, final PdfTableLayoutProvider provider, final boolean trailing)
            throws Exception {
        final var policy =
                new PdfNativeLayoutOptions(150, 16_000_000, 1024)
                        .withLeadingRow(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW)
                        .withTrailingRows(trailing ? PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS
                                                   : PdfNativeLayoutOptions.TrailingRows.PROVIDER_ONLY);
        try {
            final var service = PdfPartitioner.createWithNativeLayout(
                    PdfOptions.defaults().withTableCellCoordinates(true), policy, provider);
            return new Extraction("SUCCESS", DocWeave.create(PartitionerRegistry.of(List.of(service)))
                                                     .partition(DocumentSource.bytes(bytes, "source.pdf"))
                                                     .elements());
        } catch (final PartitionException failure) {
            return new Extraction(failure.code().name(), List.of());
        }
    }

    /**
     * Retains ordered text/type/metadata independently of generated element identifiers.
     * @param elements extraction output
     * @return immutable logical payload
     */
    private static List<Map<String, Object>> payload(final List<Element> elements) {
        return elements.stream()
                .map(e -> Map.<String, Object>of("type", e.type().name(), "text", e.text(), "metadata", e.metadata()))
                .toList();
    }

    /**
     * Hashes the exact provider input without changing or retaining its pixels.
     * @param image borrowed provider raster
     * @return SHA-256 of encoded PNG
     * @throws java.io.IOException if the PNG writer fails
     */
    private static String pngHash(final BufferedImage image) throws java.io.IOException {
        try (final var bytes = new ByteArrayOutputStream()) {
            require(ImageIO.write(image, "png", bytes), "PNG writer unavailable");
            return hash(bytes.toByteArray());
        }
    }

    /**
     * Computes a stable byte identity.
     * @param bytes complete input
     * @return lowercase SHA-256
     */
    private static String hash(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Rejects a changed protocol rather than continuing with incomparable observations.
     * @param condition protocol assertion
     * @param message failure description
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
