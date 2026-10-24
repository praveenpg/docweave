package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.layout.OnnxTableLayout;
import io.docweave.layout.OnnxTableOptions;
import org.apache.pdfbox.Loader;
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
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit whole-original-document native/model capture; no OCR or reference labels are supplied to extraction. */
public final class CaptureNativeModelTables {
    /** Prevents utility construction. */
    private CaptureNativeModelTables() {
    }

    /**
     * Runs one complete original document uniformly before selecting fixed report pages.
     * The explicit docweave.pdf.native.iso.dates system property selects wrapped ISO-date joining for every input.
     * The tagged.first and segmented.rows native properties select source precedence and segmented-row inference;
     * both require the native-first argument and apply uniformly to the complete fixed input set.
     * The source.soft.hyphens native property selects source discretionary joining plus ISO dates;
     * it requires tagged.first and is recorded explicitly in capture runtime metadata.
     * @param args cache, cases TSV, NEW output, detector path/hash and structure path/hash;
     *             optional native-first selects uniform partial-rule native precedence
     * @throws Exception if source/model identity, extraction or new-output checks fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 7 && (args.length != 8 || !args[7].equals("native-first"))) {
            throw new IllegalArgumentException("Expected cache, cases TSV, NEW output, detector/hash, structure/hash");
        }
        final boolean nativeFirst = args.length == 8;
        final boolean taggedFirst = Boolean.getBoolean("docweave.pdf.native.tagged.first");
        final boolean segmentedRows = Boolean.getBoolean("docweave.pdf.native.segmented.rows");
        if ((taggedFirst || segmentedRows) && !nativeFirst) {
            throw new IllegalArgumentException("Source-tagged/segmented policies require native-first mode");
        }
        final boolean sourceSoftHyphens = Boolean.getBoolean("docweave.pdf.native.source.soft.hyphens");
        if (sourceSoftHyphens && !taggedFirst) {
            throw new IllegalArgumentException("Source discretionary joining requires source-tagged precedence");
        }
        final var cellTextJoin = sourceSoftHyphens
                                         ? PdfNativeLayoutOptions.CellTextJoin.SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE
                                 : Boolean.getBoolean("docweave.pdf.native.iso.dates")
                                         ? PdfNativeLayoutOptions.CellTextJoin.WRAPPED_ISO_DATE
                                         : PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED;
        final var output = Files.createDirectory(Path.of(args[2]));
        final var cases = Files.readAllLines(Path.of(args[1])).stream().map(line -> line.split("\\t", -1)).toList();
        for (final var pin : cases) {
            if (pin.length != 4 || !pin[0].matches("[a-z-]+") || !pin[1].matches("[A-Za-z0-9_.-]+\\.pdf")
                    || !pin[3].matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid frozen case identity");
            }
        }
        final var mapper = JsonMapper.builder().build();
        try (final var model = OnnxTableLayout.create(
                     options(Path.of(args[3]), args[4], false), options(Path.of(args[5]), args[6], true))) {
            final var documents = new ArrayList<Map<String, Object>>();
            for (final String filename : cases.stream().map(pin -> pin[1]).distinct().toList()) {
                final var input = Path.of(args[0], filename);
                if (Files.size(input) > 16 * 1024 * 1024) {
                    throw new IllegalArgumentException("Capture PDF exceeds16MiB");
                }
                final var bytes = Files.readAllBytes(input);
                final String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (cases.stream().filter(pin -> pin[1].equals(filename)).anyMatch(pin -> !pin[3].equals(hash))) {
                    throw new IllegalArgumentException("Source identity mismatch");
                }
                final var calls = new AtomicInteger();
                final var tables = new AtomicInteger();
                final var rasters = new ArrayList<List<Integer>>();
                final PdfTableLayoutProvider provider = image -> {
                    calls.incrementAndGet();
                    rasters.add(List.of(image.getWidth(), image.getHeight()));
                    final var located = model.locate(image);
                    tables.addAndGet(located.size());
                    return located;
                };
                final var pdfOptions =
                        nativeFirst ? PdfOptions.defaults().withTableCellCoordinates(true).withTableDetection(
                                              segmentedRows ? PdfOptions.TableDetection.RULED_AND_SEGMENTED_ROWS
                                                            : PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT)
                                    : PdfOptions.defaults().withTableCellCoordinates(true);
                final var policy = new PdfNativeLayoutOptions(150, 16_000_000, 1024).withCellTextJoin(cellTextJoin);
                final var partitioner =
                        taggedFirst   ? PdfPartitioner.createWithTaggedTablesFirst(pdfOptions, policy, provider)
                        : nativeFirst ? PdfPartitioner.createWithNativeTablesFirst(pdfOptions, policy, provider)
                                      : PdfPartitioner.createWithNativeLayout(pdfOptions, policy, provider);
                final var processor = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)));
                String outcome = "SUCCESS";
                List<io.docweave.core.Element> elements = List.of();
                try {
                    elements = processor.partition(DocumentSource.bytes(bytes, filename)).elements();
                } catch (final PartitionException failure) {
                    // Keep both selected cases and their reference denominators on a whole-document failure.
                    outcome = failure.code().name();
                }
                documents.add(
                        Map.of("filename", filename, "pdf_sha256", hash, "outcome", outcome, "document_layout_calls",
                                calls.get(), "document_layout_tables", tables.get(), "rasters", rasters));
                try (final var pdf = Loader.loadPDF(bytes)) {
                    for (final var pin : cases) {
                        if (!pin[1].equals(filename)) {
                            continue;
                        }
                        final int pageNumber = Integer.parseInt(pin[2]);
                        final var page = pdf.getPage(pageNumber - 1);
                        final var geometry = new PdfPageGeometry(page);
                        final var selected =
                                elements.stream()
                                        .filter(e
                                                -> ((Number) e.metadata().get("page_number")).intValue() == pageNumber)
                                        .toList();
                        final var report = new LinkedHashMap<String, Object>();
                        report.put("outcome", outcome);
                        report.put("layout_strategy", taggedFirst   ? "tagged_native_first_disjoint_v1"
                                                      : nativeFirst ? "native_first_disjoint_v1"
                                                                    : "provider_layout_native_glyphs_v1");
                        report.put("tables", selected.stream()
                                                     .filter(e -> e.type() == ElementType.Table)
                                                     .map(e -> e.metadata())
                                                     .toList());
                        report.put("selected_page_elements", selected.size());
                        report.put("document_elements", elements.size());
                        report.put("page_calls", 0);
                        report.put("page_raster_sha256", List.of());
                        report.put("cell_calls", 0);
                        report.put("layout_calls", calls.get());
                        report.put("layout_tables", tables.get());
                        final var directory = Files.createDirectory(output.resolve(pin[0]));
                        Files.write(directory.resolve(nativeFirst ? "native-first.json" : "native-live.json"),
                                mapper.writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
                        Files.write(directory.resolve("source.json"),
                                mapper.writeValueAsBytes(Map.of("source_page", pageNumber, "pdf_sha256", hash,
                                        "source_rotation", page.getRotation(), "page_width", geometry.width(),
                                        "page_height", geometry.height(), "dpi", 150)),
                                StandardOpenOption.CREATE_NEW);
                    }
                }
            }
            Files.write(output.resolve("runtime.json"),
                    mapper.writeValueAsBytes(
                            Map.of("java", Runtime.version().toString(), "native_first", nativeFirst, "tagged_first",
                                    taggedFirst, "segmented_rows", segmentedRows, "cell_text_join", cellTextJoin.name(),
                                    "detector_sha256", args[4], "structure_sha256", args[6], "documents", documents)),
                    StandardOpenOption.CREATE_NEW);
        }
    }

    /**
     * Reuses historical defaults with four native threads and explicit higher-score structure merges.
     * @param path explicit trusted local graph
     * @param hash expected SHA256
     * @param structure whether structure merge selection applies
     * @return bounded immutable graph policy
     */
    private static OnnxTableOptions options(final Path path, final String hash, final boolean structure) {
        final var defaults = OnnxTableOptions.defaults(path, hash);
        return new OnnxTableOptions(path, hash, defaults.timeout(), defaults.maxConcurrentCalls(),
                defaults.maxModelBytes(), defaults.maxInputPixels(), defaults.maxQueries(), defaults.tableLimits(),
                defaults.minimumScore(), 4,
                structure ? OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE
                          : OnnxTableOptions.MergeConflicts.REJECT_TABLE);
    }
}
