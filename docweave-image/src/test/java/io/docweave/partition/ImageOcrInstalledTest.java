package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.ocr.TesseractOcr;
import io.docweave.ocr.TesseractOptions;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #76 acceptance with an explicitly configured, locally installed Tesseract: real text, order, page attribution,
 * coordinates and orientation on the owned fixtures, and peak-heap measurements of a large image and multi-page TIFFs.
 * Skipped unless {@code docweave.tesseract.executable}, {@code docweave.tesseract.tessdata} and
 * {@code docweave.image.report} (a fresh JSON path) are set; nothing is discovered or downloaded.
 */
class ImageOcrInstalledTest {
    private static DocWeave engine(final ImageOptions options) {
        return engine(options, null);
    }

    private static DocWeave engine(final ImageOptions options, final List<Long> liveHeapPerPage) {
        final var policy = new TesseractOptions(Path.of(System.getProperty("docweave.tesseract.executable")),
                Path.of(System.getProperty("docweave.tesseract.tessdata")), List.of("eng"), Duration.ofSeconds(120), 1,
                16_000_000, 64L * 1024 * 1024, 4 * 1024 * 1024, 10_000, 1_000_000);
        final var registry = PartitionerRegistry.builder();
        final PdfOcrProvider tesseract = TesseractOcr.create(policy);
        final PdfOcrProvider ocr = liveHeapPerPage == null ? tesseract : image -> {
            // Live heap while a page is being recognized: earlier pages must already be collectable.
            System.gc();
            liveHeapPerPage.add(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            return tesseract.recognize(image);
        };
        ImagePartitioner.createAll(options, ocr).forEach(registry::register);
        return DocWeave.create(registry.build());
    }

    private static byte[] fixture(final String name) throws IOException {
        try (final var in = ImageOcrInstalledTest.class.getResourceAsStream("image/" + name)) {
            return in.readAllBytes();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<List<Double>> points(final Element element) {
        return (List<List<Double>>) ((Map<String, Object>) element.metadata().get("coordinates")).get("points");
    }

    private static boolean configured() {
        final String executable = System.getProperty("docweave.tesseract.executable");
        final String report = System.getProperty("docweave.image.report");
        if (executable == null && report == null) {
            return false;
        }
        assertNotNull(executable, "Real OCR requires executable, tessdata and report paths together");
        assertNotNull(System.getProperty("docweave.tesseract.tessdata"));
        assertNotNull(report);
        return true;
    }

    @Test
    void realOcrReadsOwnedImagesInOrderWithPagesCoordinatesAndOrientation() throws Exception {
        if (!configured()) {
            return;
        }
        final var report = new LinkedHashMap<String, Object>();
        final var engine = engine(ImageOptions.defaults());
        final var png = engine.partition(DocumentSource.bytes(fixture("text.png"), "text.png"));
        assertEquals(ImageFixtureGenerator.PAGE, png.elements().stream().map(Element::text).toList());
        double previousTop = -1;
        for (final var line : png.elements()) {
            final var box = points(line);
            assertTrue(box.get(0).get(1) > previousTop, "top-to-bottom reading order");
            previousTop = box.get(0).get(1);
            assertTrue(box.get(2).get(0) <= 1600 && box.get(2).get(1) <= 520, "inside the page");
        }
        report.put("png", summary(png));
        final var jpeg = engine.partition(DocumentSource.bytes(fixture("text.jpg"), "text.jpg"));
        assertEquals(ImageFixtureGenerator.PAGE, jpeg.elements().stream().map(Element::text).toList());
        report.put("jpeg", summary(jpeg));
        final var rotated = engine.partition(DocumentSource.bytes(fixture("text-exif6.jpg"), "text-exif6.jpg"));
        assertEquals(ImageFixtureGenerator.PAGE, rotated.elements().stream().map(Element::text).toList(),
                "EXIF orientation 6 is applied before OCR");
        for (int index = 0; index < png.elements().size(); index++) {
            final var upright = points(png.elements().get(index));
            final var turned = points(rotated.elements().get(index));
            assertEquals(6, rotated.elements().get(index).metadata().get("image_orientation"));
            assertTrue(Math.abs(upright.get(0).get(0) - turned.get(0).get(0)) <= 6
                               && Math.abs(upright.get(0).get(1) - turned.get(0).get(1)) <= 6,
                    "coordinates are in the upright page: " + upright + " vs " + turned);
        }
        report.put("jpeg_exif6", summary(rotated));
        final var tiff = engine.partition(DocumentSource.bytes(fixture("pages-g4.tif"), "pages-g4.tif"));
        assertEquals(ImageFixtureGenerator.TIFF_PAGES, tiff.elements().stream().map(Element::text).toList());
        assertEquals(List.of(1, 2, 3), tiff.elements().stream().map(e -> e.metadata().get("page_number")).toList());
        report.put("tiff_g4", summary(tiff));
        final var blank = engine.partition(DocumentSource.bytes(fixture("blank.png"), "blank.png"));
        assertTrue(blank.elements().isEmpty());
        assertEquals(List.of(PartitionResult.WarningCode.IMAGE_NO_TEXT),
                blank.warnings().stream().map(PartitionResult.Warning::code).toList());
        report.put("blank", summary(blank));
        if (System.getProperty("docweave.image.memory") != null) {
            report.put("memory", memory());
        }
        write(Path.of(System.getProperty("docweave.image.report")), report);
    }

    private static Map<String, Object> summary(final PartitionResult result) {
        final var summary = new LinkedHashMap<String, Object>();
        summary.put("texts", result.elements().stream().map(Element::text).toList());
        summary.put("pages", result.elements().stream().map(e -> e.metadata().get("page_number")).toList());
        summary.put("first_box", result.elements().isEmpty() ? null : points(result.elements().getFirst()));
        summary.put("warnings", result.warnings().stream().map(w -> w.code().name()).toList());
        return summary;
    }

    /**
     * Peak heap of one large page and of 1- and 20-page TIFFs of the same page, each after a full collection, so
     * that bounded page-at-a-time processing is visible as a peak that does not grow with the page count.
     */
    private static List<Map<String, Object>> memory() throws Exception {
        final var directory = Files.createTempDirectory("docweave-image-memory-");
        final var results = new ArrayList<Map<String, Object>>();
        try {
            final Path large = directory.resolve("large-4000x4000.png");
            final Path one = directory.resolve("a4-300dpi-1.tif");
            final Path twenty = directory.resolve("a4-300dpi-20.tif");
            writeInputs(large, one, twenty);
            results.add(measure("png 4000x4000 (16.0 MP)", large, 1));
            results.add(measure("tiff LZW 1 x 2480x3508 (8.7 MP each)", one, 1));
            results.add(measure("tiff LZW 20 x 2480x3508 (8.7 MP each)", twenty, 20));
        } finally {
            try (final var files = Files.list(directory)) {
                for (final Path file : files.toList()) {
                    Files.delete(file);
                }
            }
            Files.delete(directory);
        }
        return results;
    }

    /** Writes the measured inputs; no source raster stays reachable once this returns. */
    private static void writeInputs(final Path large, final Path one, final Path twenty) throws IOException {
        final BufferedImage tile = ImageIO.read(ImagePartitionTest.class.getResource("image/text.png"));
        ImageIO.write(tiled(tile, 4000, 4000), "png", large.toFile());
        final BufferedImage a4 = tiled(tile, 2480, 3508);
        writeTiff(one, a4, 1);
        writeTiff(twenty, a4, 20);
    }

    private static BufferedImage tiled(final BufferedImage tile, final int width, final int height) {
        final var page = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final var g = page.createGraphics();
        try {
            for (int y = 0; y < height; y += tile.getHeight()) {
                for (int x = 0; x < width; x += tile.getWidth()) {
                    g.drawImage(tile, x, y, null);
                }
            }
        } finally {
            g.dispose();
        }
        return page;
    }

    private static void writeTiff(final Path file, final BufferedImage page, final int pages) throws IOException {
        final var writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (final var stream = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(stream);
            final ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType("LZW");
            writer.prepareWriteSequence(null);
            for (int index = 0; index < pages; index++) {
                writer.writeToSequence(new IIOImage(page, null,
                                               writer.getDefaultImageMetadata(
                                                       ImageTypeSpecifier.createFromRenderedImage(page), param)),
                        param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static Map<String, Object> measure(final String name, final Path file, final int pages) throws Exception {
        final var pools = ManagementFactory.getMemoryPoolMXBeans()
                                  .stream()
                                  .filter(pool -> pool.getType() == MemoryType.HEAP)
                                  .toList();
        System.gc();
        final long baseline = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        pools.forEach(java.lang.management.MemoryPoolMXBean::resetPeakUsage);
        final long start = System.nanoTime();
        // The 20-page TIFF is larger than the default 10 MiB input limit; raise it explicitly for the measurement.
        final var live = new ArrayList<Long>();
        final var result = engine(ImageOptions.defaults(), live)
                                   .partition(DocumentSource.of(file),
                                           PartitionOptions.builder().maxInputBytes(256 * 1024 * 1024).build());
        final long elapsed = System.nanoTime() - start;
        final long peak = pools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
        assertEquals(pages, result.elements().stream().map(e -> e.metadata().get("page_number")).distinct().count());
        final var row = new LinkedHashMap<String, Object>();
        row.put("input", name);
        row.put("input_bytes", Files.size(file));
        row.put("pages", pages);
        row.put("elements", result.elements().size());
        row.put("options", "ImageOptions.defaults(); maxInputBytes 256 MiB; Tesseract eng, maxConcurrentProcesses 1; "
                                   + "one document at a time; -Xmx" + Runtime.getRuntime().maxMemory() / (1024 * 1024)
                                   + "m");
        row.put("elapsed_ms", elapsed / 1_000_000);
        row.put("heap_baseline_mib", baseline / (1024 * 1024));
        row.put("heap_peak_mib", peak / (1024 * 1024));
        row.put("live_heap_at_each_page_ocr_mib", live.stream().map(bytes -> bytes / (1024 * 1024)).toList());
        row.put("max_heap_mib", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        return row;
    }

    private static void write(final Path path, final Map<String, Object> report) throws IOException {
        Files.writeString(path, json(report), java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private static String json(final Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            final var parts = new ArrayList<String>();
            map.forEach((key, item) -> parts.add(json(String.valueOf(key)) + ": " + json(item)));
            return "{" + String.join(", ", parts) + "}";
        }
        if (value instanceof List<?> list) {
            return "[" + String.join(", ", list.stream().map(ImageOcrInstalledTest::json).toList()) + "]";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
