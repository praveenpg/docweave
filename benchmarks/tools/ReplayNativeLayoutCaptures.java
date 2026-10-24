package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfNativeLayoutOptions;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import io.docweave.partition.PdfTableLayoutProvider;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/** Diagnostic replay of hash-pinned model layouts through the real native-glyph extraction/scoring paths. */
public final class ReplayNativeLayoutCaptures {
    /**
     * One frozen model output; header evidence is unspecified because the legacy capture format omits it.
     * @param rasterHash exact encoded PNG identity of the model input
     * @param outcome OK or a captured typed resource failure
     * @param tables complete model tables, empty on captured failure
     */
    private record Snapshot(String rasterHash, String outcome, List<PdfTableLayoutProvider.Table> tables) {}

    /** Prevents utility construction. */
    private ReplayNativeLayoutCaptures() {
    }

    /**
     * Extracts each complete original once per frozen detector profile, then applies unchanged JVM row scoring.
     * Depends explicitly on compiled validation test classes; reflection does not expose private library APIs.
     * Requires a native-text page for every capture, sequential identical rasters and matching file/source pins.
     * @param args replay manifest, original source cache, structure capture root and fresh numeric output
     * @throws Exception if identity, capture decoding, extraction or unchanged scorer requirements fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 4, "Expected replay manifest, source cache, capture root and fresh report");
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var referencePath = Path.of(manifest.get("reference_manifest").asString());
        require(hash(referencePath).equals(manifest.get("reference_manifest_sha256").asString()),
                "Reference protocol changed");
        final var scorerSource =
                Path.of("docweave-validation/src/test/java/io/docweave/validation/PdfCurrentBusinessRowsTest.java");
        require(hash(scorerSource).equals(manifest.get("validation_source_sha256").asString()),
                "Scorer source changed");
        final var reference = mapper.readTree(Files.readAllBytes(referencePath));
        final var cache = Path.of(args[1]);
        for (final var input : reference.get("inputs")) {
            final var path = cache.resolve(input.get("filename").asString());
            require(Files.size(path) == input.get("bytes").asLong()
                            && hash(path).equals(input.get("sha256").asString()),
                    "Original source changed");
        }
        final var source =
                DocumentSource.bytes(Files.readAllBytes(cache.resolve(reference.get("source_pdf").asString())),
                        reference.get("source_pdf").asString());
        final var scorer = Class.forName("io.docweave.validation.PdfCurrentBusinessRowsTest");
        final var captureType = Class.forName(scorer.getName() + "$Capture");
        final var captureConstructor = captureType.getDeclaredConstructor(String.class, List.class, List.class);
        captureConstructor.setAccessible(true);
        final var rowReader = scorer.getDeclaredMethod("rows", org.jsoup.nodes.Element.class);
        rowReader.setAccessible(true);
        final var score = scorer.getDeclaredMethod(
                "multiplePages", JsonNode.class, List.class, captureType, String.class, int.class);
        score.setAccessible(true);
        final var results = new ArrayList<Object>();
        final var documents = new ArrayList<Map<String, Object>>();
        for (final var profile : manifest.get("profiles")) {
            final String name = profile.asString();
            final var snapshots = snapshots(manifest, Path.of(args[2]), name);
            final var calls = new AtomicInteger();
            final PdfTableLayoutProvider provider = image -> {
                final int index = calls.getAndIncrement();
                require(index < snapshots.size(), "Unexpected model replay call");
                final var snapshot = snapshots.get(index);
                try (final var output = new ByteArrayOutputStream()) {
                    require(ImageIO.write(image, "png", output), "No PNG writer");
                    require(hash(output.toByteArray()).equals(snapshot.rasterHash()),
                            "Replay raster identity mismatch");
                }
                if (!snapshot.outcome().equals("OK")) {
                    throw new PartitionException(
                            PartitionException.Code.valueOf(snapshot.outcome()), "Captured model failure");
                }
                return snapshot.tables();
            };
            final var nativeOptions = PdfOptions.defaults().withTableCellCoordinates(true).withTableDetection(
                    PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT);
            final var service = PdfPartitioner.createWithNativeTablesFirst(
                    nativeOptions, new PdfNativeLayoutOptions(150, 16_000_000, 1024), provider);
            final var processor = DocWeave.create(PartitionerRegistry.of(List.of(service)));
            String outcome = "SUCCESS";
            List<io.docweave.core.Element> elements = List.of();
            List<String> warnings = List.of();
            try {
                final var extracted = processor.partition(source);
                elements = extracted.elements();
                warnings = extracted.warnings().stream().map(warning -> warning.code().name()).toList();
                require(calls.get() == snapshots.size(), "Unconsumed original replay pages");
            } catch (final PartitionException failure) {
                outcome = failure.code().name();
            }
            documents.add(Map.of("profile", name, "outcome", outcome, "layout_calls", calls.get(), "document_elements",
                    elements.size(), "original_pages", snapshots.size()));
            final var captured = captureConstructor.newInstance(outcome, elements, warnings);
            final var html = Jsoup.parse(Files.readString(cache.resolve(reference.get("source_html").asString())));
            for (final var selected : reference.get("cases")) {
                final var decoded = (List<?>) rowReader.invoke(
                        null, html.select("table").get(selected.get("html_table_index").asInt()));
                require(decoded.size() == selected.get("reference_rows").asInt()
                                && hash(String.join("\n", decoded.stream().map(Object::toString).toList())
                                                .getBytes(StandardCharsets.UTF_8))
                                        .equals(selected.get("reference_rows_sha256").asString()),
                        "Reference rows changed");
                results.add(
                        score.invoke(null, selected, decoded, captured, name, reference.get("source_pages").asInt()));
            }
        }
        Files.writeString(Path.of(args[3]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime",
                        Runtime.version().toString(), "scope", manifest.get("scope").asString(),
                        "replay_manifest_sha256", hash(Path.of(args[0])), "documents", documents, "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Validates every original page/capture identity before constructing a borrowed model-output replay.
     * @param manifest frozen page/file identities
     * @param root trusted local numeric capture directory
     * @param profile exact frozen detector profile
     * @return immutable ordered original-page snapshots
     * @throws Exception if hashes or complete layout decoding fail
     */
    private static List<Snapshot> snapshots(final JsonNode manifest, final Path root, final String profile)
            throws Exception {
        final var snapshots = new ArrayList<Snapshot>();
        for (final var page : manifest.get("pages")) {
            require(page.get("source_page").asInt() == snapshots.size() + 1, "Original page order changed");
            final String id = page.get("capture_id").asString();
            require(id.matches("[A-Za-z][A-Za-z0-9_.-]{0,100}") && profile.matches("[A-Za-z][A-Za-z0-9_-]{0,50}"),
                    "Unsafe capture name");
            final var directory = root.resolve(id).resolve(profile);
            for (final String filename : List.of("tables.tsv", "anchors.tsv", "outcome.txt")) {
                final var file = directory.resolve(filename);
                require(Files.size(file) <= 8 * 1024 * 1024
                                && hash(file).equals(page.get(profile).get(filename).asString()),
                        "Capture identity changed");
            }
            final String outcome = Files.readString(directory.resolve("outcome.txt")).strip();
            final var tables = outcome.equals("OK") ? tables(directory) : List.<PdfTableLayoutProvider.Table>of();
            snapshots.add(new Snapshot(page.get("render_png_sha256").asString(), outcome, tables));
        }
        return List.copyOf(snapshots);
    }

    /**
     * Reconstructs exact numeric model tables without filling, merging or repairing any anchor.
     * @param directory hash-verified numeric structure files
     * @return model layouts with unspecified header evidence
     * @throws IOException if capture reading fails
     */
    private static List<PdfTableLayoutProvider.Table> tables(final Path directory) throws IOException {
        final var anchorLines = Files.readAllLines(directory.resolve("anchors.tsv"));
        require(!anchorLines.isEmpty()
                        && anchorLines.getFirst().equals(
                                "crop\ttable\trow\tcolumn\trow_span\tcolumn_span\tleft\ttop\tright\tbottom"),
                "Invalid anchor schema");
        final var anchors = new LinkedHashMap<String, List<PdfTableLayoutProvider.Anchor>>();
        for (final String line : anchorLines.subList(1, anchorLines.size())) {
            final var fields = line.split("\t", -1);
            require(fields.length == 10, "Invalid anchor row");
            anchors.computeIfAbsent(fields[0] + "/" + fields[1], unused -> new ArrayList<>())
                    .add(new PdfTableLayoutProvider.Anchor(Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                            Integer.parseInt(fields[4]), Integer.parseInt(fields[5]), box(fields, 6)));
        }
        final var lines = Files.readAllLines(directory.resolve("tables.tsv"));
        require(!lines.isEmpty() && lines.getFirst().equals("crop\ttable\trows\tcolumns\tleft\ttop\tright\tbottom"),
                "Invalid table schema");
        final var tables = new ArrayList<PdfTableLayoutProvider.Table>();
        final var used = new java.util.HashSet<String>();
        for (final String line : lines.subList(1, lines.size())) {
            final var fields = line.split("\t", -1);
            require(fields.length == 8, "Invalid table row");
            final String key = fields[0] + "/" + fields[1];
            require(used.add(key), "Duplicate captured table");
            tables.add(new PdfTableLayoutProvider.Table(Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                    box(fields, 4), List.copyOf(anchors.getOrDefault(key, List.of())),
                    "hash_pinned_model_structure_replay_v1"));
        }
        require(used.containsAll(anchors.keySet()), "Orphan captured anchors");
        return List.copyOf(tables);
    }

    /**
     * Reads one original-pixel rectangle without frame adjustment.
     * @param fields numeric TSV fields
     * @param offset first rectangle field
     * @return immutable provider bounds
     */
    private static PdfTableLayoutProvider.Box box(final String[] fields, final int offset) {
        return new PdfTableLayoutProvider.Box(Double.parseDouble(fields[offset]),
                Double.parseDouble(fields[offset + 1]), Double.parseDouble(fields[offset + 2]),
                Double.parseDouble(fields[offset + 3]));
    }

    /**
     * Hashes exact local evidence bytes.
     * @param path source or numeric capture
     * @return SHA256
     * @throws IOException if reading fails
     */
    private static String hash(final Path path) throws IOException {
        return hash(Files.readAllBytes(path));
    }

    /**
     * Hashes exact encoded bytes without exposing their content.
     * @param bytes source, PNG or frozen reference identity
     * @return SHA256
     */
    private static String hash(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Rejects replay mismatches without choosing another source/table or repairing input.
     * @param valid required invariant
     * @param message content-free failure reason
     */
    private static void require(final boolean valid, final String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
