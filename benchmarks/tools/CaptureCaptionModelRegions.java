package dev.docweave.layout;

import dev.docweave.partition.PartitionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Whole-original generic caption-class detector diagnostic; no source captions, crops, tables or OCR enter inference.
 */
public final class CaptureCaptionModelRegions {
    /** Prevents standalone utility construction. */
    private CaptureCaptionModelRegions() {
    }

    /**
     * Uses a separately compiled decoder copy selecting published model class0 after unchanged all-class NMS.
     * The loaded decoder bytecode hash must match the explicit capture protocol, preventing an accidental
     * table-class8 capture from being labelled as captions. Internal LayoutObject labels are not exported;
     * only copied numeric source-pixel regions are reported, with selected model class declared separately.
     * @param args original PDF/hash, local YOLOX graph/hash, expected decoder class SHA256 and NEW numeric report
     * @throws Exception if inputs, exact decoder identity, raster budget or fresh output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 6 && !Files.exists(Path.of(args[5])),
                "Expected original/model/decoder pins and NEW report");
        final var source = Path.of(args[0]);
        require(Files.size(source) <= 10 * 1024 * 1024, "Original exceeds10MiB");
        final var bytes = Files.readAllBytes(source);
        require(hash(bytes).equals(args[1]), "Original source changed");
        try (final var decoder = YoloXObjectDecoder.class.getResourceAsStream("YoloXObjectDecoder.class")) {
            require(decoder != null && hash(decoder.readAllBytes()).equals(args[4]),
                    "Caption decoder class identity changed");
        }
        final var options = OnnxTableOptions.yoloXDefaults(Path.of(args[2]), args[3]);
        final var results = new ArrayList<Map<String, Object>>();
        try (final var document = Loader.loadPDF(bytes);
                final var session = new ManagedTableSession<>(options, new YoloXTableModel())) {
            require(document.getNumberOfPages() > 0 && document.getNumberOfPages() <= 1000,
                    "Original page budget exceeded");
            final var renderer = new PDFRenderer(document);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                final var page = document.getPage(index);
                final var crop = page.getCropBox();
                final var row = new LinkedHashMap<String, Object>();
                row.put("page", index + 1);
                row.put("page_rotation", page.getRotation());
                row.put("source_user_unit", page.getUserUnit());
                row.put("source_crop_width", crop.getWidth());
                row.put("source_crop_height", crop.getHeight());
                final long width = (long) Math.ceil(crop.getWidth() * 150.0 / 72);
                final long height = (long) Math.ceil(crop.getHeight() * 150.0 / 72);
                require(width > 0 && height > 0 && width <= 16_000_000 / height, "Source render exceeds pixel budget");
                try {
                    final var raster = renderer.renderImageWithDPI(index, 150, ImageType.RGB);
                    try {
                        require((long) raster.getWidth() * raster.getHeight() <= 16_000_000,
                                "Rendered input exceeds pixel budget");
                        row.put("render_width", raster.getWidth());
                        row.put("render_height", raster.getHeight());
                        final var digest = MessageDigest.getInstance("SHA-256");
                        try (final var sink = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
                            require(ImageIO.write(raster, "PNG", sink), "No PNG writer");
                        }
                        row.put("render_png_sha256", HexFormat.of().formatHex(digest.digest()));
                        final var objects = session.infer(raster, new LayoutDeadline(options.timeout()), List::copyOf);
                        row.put("regions",
                                objects.stream()
                                        .map(object
                                                -> Map.of("score", object.score(), "bounds",
                                                        List.of(object.box().left(), object.box().top(),
                                                                object.box().right(), object.box().bottom())))
                                        .toList());
                        row.put("outcome", "SUCCESS");
                    } finally {
                        raster.flush();
                    }
                } catch (final java.io.IOException failure) {
                    row.put("outcome",
                            failure instanceof PartitionException typed ? typed.code().name() : "IO_FAILURE");
                    row.put("regions", List.of());
                }
                results.add(row);
                System.out.println("Caption-class capture page " + (index + 1) + "/" + document.getNumberOfPages() + " "
                                   + row.get("outcome"));
            }
        }
        Files.writeString(Path.of(args[5]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "All original pages, fixed class0 detector regions only; no caption text, source labels, source-selected crop, structure inference or production provider integration",
                        "runtime", Runtime.version().toString(), "source_sha256", args[1], "model_sha256", args[3],
                        "decoder_class_sha256", args[4], "selected_model_class", 0, "render_dpi", 150,
                        "minimum_score_strict", options.minimumScore(), "native_threads", options.nativeThreads(),
                        "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Hashes complete trusted source/decoder bytes without source content in diagnostics.
     * @param bytes exact local input
     * @return lowercase SHA256
     * @throws Exception if digest initialization fails
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects altered inputs or unsupported capture bounds without choosing another source.
     * @param condition required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
