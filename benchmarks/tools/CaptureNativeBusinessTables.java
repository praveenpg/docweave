package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
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

/**
 * Captures existing native table strategies on pinned unchanged complete PDFs without OCR or models.
 * Only the predeclared source page is scored; local captures can contain text and must remain local.
 */
public final class CaptureNativeBusinessTables {
    /** Prevents launcher construction. */
    private CaptureNativeBusinessTables() {
    }

    /**
     * Runs historical native modes and optionally the new partial mode without labels, crops or repair.
     * @param args source cache, original four-column case TSV, NEW local capture directory and optional literal partial
     * @throws Exception if identity, schema, output or unexpected extraction requirements fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 3 && args.length != 4 || args.length == 4 && !args[3].equals("partial")) {
            throw new IllegalArgumentException(
                    "Expected source cache, case TSV, NEW output directory, optionally partial");
        }
        final var output = Files.createDirectory(Path.of(args[2]));
        write(output.resolve("runtime.json"), Map.of("java", System.getProperty("java.runtime.version")));
        final var profiles = new LinkedHashMap<String, PdfOptions.TableDetection>();
        profiles.put("default", PdfOptions.TableDetection.RULED_ONLY);
        profiles.put("aligned", PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT);
        profiles.put("multiline", PdfOptions.TableDetection.RULED_AND_MULTILINE_ALIGNED_TEXT);
        profiles.put("double-borders", PdfOptions.TableDetection.RULED_AND_DOUBLE_BORDERS);
        profiles.put("horizontal", PdfOptions.TableDetection.RULED_AND_HORIZONTAL_TEXT);
        if (args.length == 4) {
            profiles.put("partial", PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT);
        }
        for (final var line : Files.readAllLines(Path.of(args[1]))) {
            final var pin = line.split("\t");
            if (pin.length != 4 || !pin[0].matches("[a-z-]+") || !pin[1].matches("[A-Za-z0-9_.-]+\\.pdf")
                    || !pin[3].matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Unexpected pinned source identity");
            }
            final var bytes = Files.readAllBytes(Path.of(args[0]).resolve(pin[1]));
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(pin[3])) {
                throw new IllegalArgumentException("Source PDF hash mismatch");
            }
            final int selectedPage = Integer.parseInt(pin[2]);
            final var directory = Files.createDirectory(output.resolve(pin[0]));
            try (final var document = Loader.loadPDF(bytes)) {
                final var page = document.getPage(selectedPage - 1);
                final var box = page.getCropBox();
                if (page.getUserUnit() != 1 || box.getLowerLeftX() != 0 || box.getLowerLeftY() != 0) {
                    throw new IllegalArgumentException("Expected published unit, origin-zero crop frame");
                }
                write(directory.resolve("source.json"),
                        Map.of("source_page", selectedPage, "source_page_count", document.getNumberOfPages(),
                                "pdf_sha256", pin[3], "source_rotation", page.getRotation(), "page_width",
                                page.getRotation() % 180 == 0 ? box.getWidth() : box.getHeight(), "page_height",
                                page.getRotation() % 180 == 0 ? box.getHeight() : box.getWidth()));
            }
            for (final var profile : profiles.entrySet()) {
                final var options =
                        PdfOptions.defaults().withTableCellCoordinates(true).withTableDetection(profile.getValue());
                final var processor =
                        DocWeave.create(PartitionerRegistry.builder().register(PdfPartitioner.create(options)).build());
                final var tables = new ArrayList<Map<String, Object>>();
                final var report = new LinkedHashMap<String, Object>();
                try {
                    // Process the original complete document; reference-page filtering happens only afterward.
                    final var result = processor.partition(DocumentSource.bytes(bytes, pin[1]));
                    int selectedElements = 0;
                    for (final var element : result.elements()) {
                        if (element.metadata().get("page_number") instanceof final Number page
                                && page.intValue() == selectedPage) {
                            selectedElements++;
                            if (element.type() == ElementType.Table) {
                                tables.add(element.metadata());
                            }
                        }
                    }
                    report.put("outcome", "SUCCESS");
                    report.put("document_elements", result.elements().size());
                    report.put("selected_page_elements", selectedElements);
                    report.put(
                            "warning_codes", result.warnings().stream().map(warning -> warning.code().name()).toList());
                } catch (final PartitionException failure) {
                    // Keep failures in the reference denominator, including failures on other original pages.
                    report.put("outcome", failure.code().name());
                }
                report.put("tables", tables);
                report.put("native_table_policy", profile.getValue().name());
                report.put("page_calls", 0);
                report.put("cell_calls", 0);
                report.put("layout_calls", 0);
                report.put("layout_tables", 0);
                report.put("page_raster_sha256", List.of());
                write(directory.resolve(profile.getKey() + ".json"), report);
                System.out.println(
                        pin[0] + " " + profile.getKey() + ": " + report.get("outcome") + ", tables=" + tables.size());
            }
        }
    }

    /**
     * Writes fresh local capture JSON, potentially containing document text.
     * @param path new local output file
     * @param value JSON-compatible capture
     * @throws Exception if serialization or output fails
     */
    private static void write(final Path path, final Object value) throws Exception {
        Files.writeString(
                path, JsonMapper.builder().build().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
