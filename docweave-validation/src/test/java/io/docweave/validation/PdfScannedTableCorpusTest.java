package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.ocr.TesseractOcr;
import io.docweave.ocr.TesseractOptions;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOcrOptions;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Fetch-only published table expectations; ordinary CI neither downloads inputs nor executes an OCR engine. */
class PdfScannedTableCorpusTest {
    @Test
    void optionalPublishedCellsAndUnsupportedGridFallback() throws Exception {
        final String cache = System.getProperty("docweave.pdf.tables.cache");
        final String report = System.getProperty("docweave.pdf.tables.report");
        if (cache == null && report == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(report);
        final String executable = System.getProperty("docweave.tesseract.executable");
        final String data = System.getProperty("docweave.tesseract.tessdata");
        assertNotNull(executable);
        assertNotNull(data);
        final var mapper = JsonMapper.builder().build();
        final var manifest =
                mapper.readTree(Files.readAllBytes(Path.of("benchmarks/review/pdf-table-corpus/manifest.json")));
        for (final var input : manifest.get("inputs")) {
            final byte[] bytes = Files.readAllBytes(Path.of(cache).resolve(input.get("filename").asString()));
            assertEquals(input.get("bytes").asLong(), bytes.length);
            assertEquals(input.get("sha256").asString(), sha256(bytes));
            final var blob = MessageDigest.getInstance("SHA-1");
            blob.update(("blob " + bytes.length + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(input.get("git_blob_sha1").asString(), HexFormat.of().formatHex(blob.digest(bytes)));
        }
        final var base = TesseractOptions.defaults();
        final var engine = TesseractOcr.create(new TesseractOptions(Path.of(executable), Path.of(data), List.of("eng"),
                base.timeout(), 1, base.maxImagePixels(), base.maxPngBytes(), base.maxOutputBytes(), base.maxLines(),
                base.maxCharacters())
                        .withCellSegmentation(TesseractOptions.CellSegmentation.valueOf(
                                System.getProperty("docweave.pdf.tables.segmentation", "AUTO"))));
        final int dpi = Integer.getInteger("docweave.pdf.tables.dpi", 200);
        assertTrue(dpi == 200 || dpi == 300, "Reviewed crop permits only 200 or 300 DPI");
        final var policy = PdfOcrOptions.defaults();
        final var ocr = new PdfOcrOptions(dpi, policy.maxRenderPixels(), policy.maxLines(), policy.maxCharacters(),
                policy.selection(), policy.maxRegions(), PdfOcrOptions.TableDetection.RULED, policy.maxTableCells());
        final var processor = DocWeave.create(PartitionerRegistry.of(
                List.of(PdfPartitioner.create(PdfOptions.defaults().withTableCellCoordinates(true), ocr, engine))));
        final var rows = mapper.readTree(Files.readAllBytes(Path.of(cache).resolve("schools.json"))).get("data");
        final var expected = new ArrayList<String>();
        for (int row = 0; row < 6; row++) {
            // Published oracle has a synthetic empty first column outside the visible ruled grid.
            for (int column = 1; column < rows.get(row).size(); column++) {
                expected.add(normalize(rows.get(row).get(column).get("text").asString()));
            }
        }
        final byte[] schools = rasterPdf(Path.of(cache).resolve("schools.pdf"), true, dpi);
        final var result = processor.partition(DocumentSource.bytes(schools, "schools-rendered-crop.pdf"));
        final var tables = result.elements().stream().filter(e -> e.type() == ElementType.Table).toList();
        final var actual = new ArrayList<String>();
        final var rowWidths = new ArrayList<Integer>();
        for (final var table : tables) {
            final var rowMatcher = Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL)
                                           .matcher(table.metadata().get("text_as_html").toString());
            while (rowMatcher.find()) {
                rowWidths.add((int) Pattern.compile("<td>").matcher(rowMatcher.group(1)).results().count());
            }
            final var matcher = Pattern.compile("<td>(.*?)</td>", Pattern.DOTALL)
                                        .matcher(table.metadata().get("text_as_html").toString());
            while (matcher.find()) {
                actual.add(normalize(matcher.group(1)
                                .replace("&amp;", "&")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .replace("&quot;", "\"")
                                .replace("&#39;", "'")));
            }
        }
        final var differences = new ArrayList<Integer>();
        for (int cell = 0; cell < expected.size(); cell++) {
            if (cell >= actual.size() || !expected.get(cell).equals(actual.get(cell))) {
                differences.add(cell);
            }
        }
        final var fallback = processor.partition(DocumentSource.bytes(
                rasterPdf(Path.of(cache).resolve("AnimalSounds.pdf"), false, dpi), "animal-rendered.pdf"));
        final long fallbackTables = fallback.elements().stream().filter(e -> e.type() == ElementType.Table).count();
        final var evidence = new LinkedHashMap<String, Object>();
        final var sources = new LinkedHashMap<String, String>();
        for (final String source :
                List.of("docweave-validation/src/test/java/io/docweave/validation/PdfScannedTableCorpusTest.java",
                        "docweave-pdf/src/main/java/io/docweave/partition/RasterPdfTableDetector.java",
                        "docweave-pdf/src/main/java/io/docweave/partition/PdfScannedTables.java",
                        "docweave-pdf/src/main/java/io/docweave/partition/PdfTableCells.java",
                        "docweave-pdf/src/main/java/io/docweave/partition/PdfPageOcr.java",
                        "docweave-ocr-tesseract/src/main/java/io/docweave/ocr/DefaultTesseractOcr.java")) {
            sources.put(source, sha256(Files.readAllBytes(Path.of(source))));
        }
        evidence.put("source_sha256", sources);
        evidence.put("checked_at", Instant.now().toString());
        evidence.put("dpi", dpi);
        evidence.put("cell_segmentation", System.getProperty("docweave.pdf.tables.segmentation", "AUTO"));
        evidence.put("java", System.getProperty("java.runtime.version"));
        evidence.put("scope",
                "Schools page 1 top six rows at configured DPI; independent published Tabula cells, not human scan accuracy");
        evidence.put("manifest_sha256",
                sha256(Files.readAllBytes(Path.of("benchmarks/review/pdf-table-corpus/manifest.json"))));
        evidence.put("executable_sha256", sha256(Files.readAllBytes(Path.of(executable))));
        evidence.put("model_sha256", sha256(Files.readAllBytes(Path.of(data).resolve("eng.traineddata"))));
        evidence.put("schools",
                Map.of("tables", tables.size(), "expected_cells", expected.size(), "actual_cells", actual.size(),
                        "mismatching_cell_indexes", differences, "expected_normalized_sha256",
                        sha256(String.join("\n", expected).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "actual_normalized_sha256",
                        sha256(String.join("\n", actual).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        evidence.put("row_widths", rowWidths);
        final var cellGeometry = new ArrayList<Map<String, Object>>();
        for (final var table : tables) {
            final var extension = (Map<?, ?>) table.metadata().get("pdf_table_cells");
            assertEquals("rule_centerlines", extension.get("geometry"));
            assertEquals(6, extension.get("row_count"));
            assertEquals(10, extension.get("column_count"));
            final var anchors = (List<?>) extension.get("cells");
            assertEquals(60, anchors.size());
            for (final var anchor : anchors) {
                final var cell = (Map<?, ?>) anchor;
                assertEquals(1, cell.get("row_span"));
                assertEquals(1, cell.get("column_span"));
                assertEquals("PDFUserSpace", ((Map<?, ?>) cell.get("coordinates")).get("system"));
            }
            cellGeometry.add(
                    Map.of("rows", 6, "columns", 10, "anchors", anchors.size(), "coordinates", "PDFUserSpace"));
        }
        evidence.put("cell_geometry", cellGeometry);
        evidence.put("animal",
                Map.of("tables", fallbackTables, "ordinary_ocr_elements",
                        fallback.elements().stream().filter(e -> e.metadata().containsKey("pdf_text_method")).count()));
        Files.writeString(Path.of(report), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n",
                StandardOpenOption.CREATE_NEW);
        assertEquals(0, fallbackTables, "Open grid must retain ordinary OCR fallback");
        assertTrue(fallback.elements().stream().anyMatch(e -> e.text().contains("Cat")));
        // Recognition differences are diagnostic, never automatically promoted to approved golden text.
        assertEquals(1, tables.size(), "Published complete grid should be detected");
        assertEquals(expected.size(), actual.size(), "Cell positions must remain intact");
        assertEquals(List.of(10, 10, 10, 10, 10, 10), rowWidths);
        assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
    }

    /**
     * Renders the source before embedding pixels only; the crop is fixed independently of extracted candidate output.
     */
    private static byte[] rasterPdf(final Path source, final boolean crop, final int dpi) throws Exception {
        try (final var original = Loader.loadPDF(source.toFile())) {
            final var rendered = new PDFRenderer(original).renderImageWithDPI(0, dpi, ImageType.RGB);
            final double scale = dpi / 72.0;
            final BufferedImage raster = crop ? rendered.getSubimage((int) (16 * scale), (int) (53 * scale),
                                                        (int) (748 * scale), (int) (65 * scale))
                                              : rendered;
            try (final var pdf = new PDDocument(); final var output = new ByteArrayOutputStream()) {
                final var page = new PDPage(
                        new PDRectangle((float) (raster.getWidth() / scale), (float) (raster.getHeight() / scale)));
                pdf.addPage(page);
                try (final var content = new PDPageContentStream(pdf, page)) {
                    content.drawImage(LosslessFactory.createFromImage(pdf, raster), 0, 0, page.getMediaBox().getWidth(),
                            page.getMediaBox().getHeight());
                }
                pdf.save(output);
                return output.toByteArray();
            } finally {
                raster.flush();
                rendered.flush();
            }
        }
    }

    /** Collapses whitespace only, preserving punctuation, case, leading zeros and empty cell positions. */
    private static String normalize(final String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    /** Returns a byte-exact diagnostic identity without retaining third-party document text in reports. */
    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
