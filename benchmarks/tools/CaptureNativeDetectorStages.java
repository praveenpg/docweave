package dev.docweave.layout;

import dev.docweave.partition.PdfTableLayoutProvider;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import tools.jackson.databind.json.JsonMapper;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Numeric-only original-PDF detector/crop/structure audit; never reads reference labels or page ROIs. */
public final class CaptureNativeDetectorStages {
    /** Prevents executable utility construction. */
    private CaptureNativeDetectorStages() {
    }

    /**
     * Audits every original page using the existing thresholded decoder and bounded crop/topology code.
     * Uses explicit trusted local graph identities,150DPI RGB, four CPU threads and30-second page deadlines.
     * This is a stage diagnostic, not a replacement production extraction transaction or OCR pipeline.
     * @param args PDF, expected PDF hash, detector/hash, structure/hash and fresh numeric report
     * @throws Exception if identities, inference, resource checks or fresh report writing fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException("Expected PDF/hash, detector/hash, structure/hash and fresh report");
        }
        final var input = Path.of(args[0]);
        if (Files.size(input) > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("Stage audit input exceeds10MiB");
        }
        final byte[] bytes = Files.readAllBytes(input);
        final String sourceHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!sourceHash.equals(args[1])) {
            throw new IllegalArgumentException("Original PDF identity mismatch");
        }
        final var detectionOptions = options(Path.of(args[2]), args[3]);
        final var structureOptions = options(Path.of(args[4]), args[5]);
        final var pages = new ArrayList<Map<String, Object>>();
        try (final var detector = new ManagedTatrSession(detectionOptions, 3);
                final var structure = new ManagedTatrSession(structureOptions, 7);
                final var document = Loader.loadPDF(bytes)) {
            if (document.getNumberOfPages() > 1000) {
                throw new IllegalArgumentException("Stage audit exceeds1000pages");
            }
            final var renderer = new PDFRenderer(document);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                final var page = document.getPage(index);
                final var cropBox = page.getCropBox();
                final long width = (long) Math.ceil(cropBox.getWidth() * 150.0 / 72);
                final long height = (long) Math.ceil(cropBox.getHeight() * 150.0 / 72);
                if (width < 1 || height < 1 || width > 16_000_000 / height) {
                    throw new IllegalArgumentException("Stage audit exceeds16M render pixels");
                }
                final var raster = renderer.renderImageWithDPI(index, 150, ImageType.RGB);
                try {
                    final var result = page(raster, detector, structure, detectionOptions, structureOptions);
                    result.put("source_page", index + 1);
                    result.put("source_crop_width", cropBox.getWidth());
                    result.put("source_crop_height", cropBox.getHeight());
                    result.put("source_rotation", page.getRotation());
                    pages.add(result);
                } finally {
                    raster.flush();
                }
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("runtime", Runtime.version().toString());
        report.put("pdf_sha256", sourceHash);
        report.put("detector_sha256", args[3]);
        report.put("structure_sha256", args[5]);
        report.put("scope",
                "Numeric stage audit of every original page. Thresholded detector objects before crop NMS; planned source-pixel crops; thresholded child structure objects in crop pixels; topology output before final cross-candidate rejection. No reference labels, OCR, source changes, text normalization or model tuning.");
        report.put("pages", pages);
        Files.writeString(Path.of(args[6]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Shares one deadline and crop/topology work counter across detector and child structure stages.
     * @param raster original RGB page
     * @param detector owned detector session
     * @param structure owned structure session
     * @param detectionOptions fixed detector limits
     * @param structureOptions fixed structure limits
     * @return mutable numeric page observation for caller source metadata
     * @throws Exception if inference or geometry checks fail
     */
    private static Map<String, Object> page(final BufferedImage raster, final ManagedTatrSession detector,
            final ManagedTatrSession structure, final OnnxTableOptions detectionOptions,
            final OnnxTableOptions structureOptions) throws Exception {
        final var deadline = new LayoutDeadline(detectionOptions.timeout());
        final var work = new LayoutWork(deadline);
        return detector.infer(raster, deadline, objects -> {
            final var crops = DetectionCrops.plan(objects, raster, structureOptions, work);
            final var children = new ArrayList<Map<String, Object>>();
            long slots = 0;
            for (final var crop : crops) {
                work.step();
                final var limits = structureOptions.tableLimits();
                final long remaining = Math.min(2_000_000, limits.maxCells()) - slots;
                if (remaining < 1) {
                    throw new IllegalArgumentException("Stage audit aggregate slots exceeded");
                }
                final var bounded = structureOptions.withTableLimits(new dev.docweave.partition.TableLimits(
                        limits.maxRows(), limits.maxColumns(), (int) remaining, limits.maxCellCharacters()));
                final var topology = new TableTopology(bounded, work);
                final var image = copy(raster, crop);
                try {
                    final var child = structure.infer(image, deadline, predictions -> {
                        final var tables = topology.tables(predictions);
                        final var result = new LinkedHashMap<String, Object>();
                        result.put("crop", List.of(crop.left(), crop.top(), crop.right(), crop.bottom()));
                        result.put("structure_objects",
                                predictions.stream().map(CaptureNativeDetectorStages::object).toList());
                        result.put("tables",
                                tables.stream()
                                        .map(table
                                                -> Map.of("rows", table.rows(), "columns", table.columns(), "bounds",
                                                        box(table.bounds()), "anchors", table.anchors().size()))
                                        .toList());
                        return result;
                    });
                    children.add(child);
                    slots += topology.consumedSlots();
                } finally {
                    image.flush();
                }
            }
            final var result = new LinkedHashMap<String, Object>();
            result.put("raster_width", raster.getWidth());
            result.put("raster_height", raster.getHeight());
            result.put("detector_objects", objects.stream().map(CaptureNativeDetectorStages::object).toList());
            result.put("crops", children);
            result.put("prospective_slots", slots);
            return result;
        });
    }

    /**
     * Keeps graph configuration identical to the fixed native-first model experiment.
     * @param model trusted local graph
     * @param hash expected graph hash
     * @return explicit four-thread, higher-score-merge policy
     */
    private static OnnxTableOptions options(final Path model, final String hash) {
        final var defaults = OnnxTableOptions.defaults(model, hash);
        return new OnnxTableOptions(model, hash, defaults.timeout(), defaults.maxConcurrentCalls(),
                defaults.maxModelBytes(), defaults.maxInputPixels(), defaults.maxQueries(), defaults.tableLimits(),
                defaults.minimumScore(), 4, OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
    }

    /**
     * Copies exactly the production crop rectangle over white without retaining source pixels.
     * @param source borrowed original RGB raster
     * @param crop bounded detector-driven rectangle
     * @return owned child raster, released after structure inference
     */
    private static BufferedImage copy(final BufferedImage source, final DetectionCrops.Crop crop) {
        final var image =
                new BufferedImage(crop.right() - crop.left(), crop.bottom() - crop.top(), BufferedImage.TYPE_INT_RGB);
        final var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(source, 0, 0, image.getWidth(), image.getHeight(), crop.left(), crop.top(), crop.right(),
                    crop.bottom(), null);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * Preserves numeric thresholded object identity before crop/structure processing.
     * @param object finite decoded source/crop pixel proposal
     * @return label, class score and pixel bounds, without publisher content
     */
    private static Map<String, Object> object(final LayoutObject object) {
        return Map.of("label", object.label(), "score", object.score(), "bounds", box(object.box()));
    }

    /**
     * Copies a finite pixel box without coordinate reinterpretation.
     * @param box source or crop pixel rectangle
     * @return left/top/right/bottom
     */
    private static List<Double> box(final PdfTableLayoutProvider.Box box) {
        return List.of(box.left(), box.top(), box.right(), box.bottom());
    }
}
