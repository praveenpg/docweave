package io.docweave.layout;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Exports identical PDFBox-rendered detector inputs and native TATR region predictions.
 * Images are local derivatives, not redistributable evidence; no annotation is read.
 */
public final class CaptureScanDetectorInput {
    /** Prevents construction of the standalone diagnostic. */
    private CaptureScanDetectorInput() {
    }

    /**
     * Verifies pinned source bytes, embeds whole JPEGs at150DPI and records raw detections.
     * Input pixels must fit the existing16M policy before model inference or PNG export.
     * @param args cache, filename/SHA256 TSV, NEW output directory, detector graph, detector SHA256
     * @throws Exception if an identity, graph, output or model-call requirement fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected cache, pins TSV, NEW output, detector graph/hash");
        }
        final var output = Files.createDirectory(Path.of(args[2]));
        final var options = OnnxTableOptions.defaults(Path.of(args[3]), args[4]);
        try (final var detector = new ManagedTatrSession(options, 3)) {
            for (final var line : Files.readAllLines(Path.of(args[1]))) {
                final var fields = line.split("\t");
                if (fields.length != 2 || !fields[0].matches("cTDaR_[st][0-9]+\\.jpg")
                        || !fields[1].matches("[a-f0-9]{64}")) {
                    throw new IllegalArgumentException("Invalid pinned JPEG identity");
                }
                final var bytes = Files.readAllBytes(Path.of(args[0]).resolve(fields[0]));
                final var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!hash.equals(fields[1])) {
                    throw new IllegalArgumentException("JPEG identity mismatch");
                }
                final var directory = Files.createDirectory(output.resolve(fields[0].replace(".jpg", "")));
                final var source = ImageIO.read(new ByteArrayInputStream(bytes));
                if (source == null) {
                    throw new IllegalArgumentException("Unreadable JPEG");
                }
                final int width = source.getWidth();
                final int height = source.getHeight();
                source.flush();
                try (final var document = Loader.loadPDF(pdf(bytes, width, height))) {
                    final var raster = new PDFRenderer(document).renderImageWithDPI(0, 150, ImageType.RGB);
                    try {
                        Files.write(directory.resolve("source.tsv"),
                                List.of("sha256\twidth\theight\toriginal_width\toriginal_height\tpdf_width\tpdf_height\trender_dpi",
                                        hash + "\t" + raster.getWidth() + "\t" + raster.getHeight() + "\t" + width
                                                + "\t" + height + "\t" + document.getPage(0).getMediaBox().getWidth()
                                                + "\t" + document.getPage(0).getMediaBox().getHeight() + "\t150"),
                                StandardOpenOption.CREATE_NEW);
                        if ((long) raster.getWidth() * raster.getHeight() > options.maxInputPixels()) {
                            Files.writeString(
                                    directory.resolve("outcome.txt"), "OCR_LIMIT\n", StandardOpenOption.CREATE_NEW);
                            continue;
                        }
                        final var render = directory.resolve("render.png");
                        if (!ImageIO.write(raster, "PNG", render.toFile())) {
                            throw new IllegalStateException("No PNG encoder");
                        }
                        Files.writeString(directory.resolve("render.sha256"),
                                HexFormat.of().formatHex(
                                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(render)))
                                        + "\n",
                                StandardOpenOption.CREATE_NEW);
                        detector.infer(raster, new LayoutDeadline(options.timeout()), objects -> {
                            final var rows = new ArrayList<String>();
                            rows.add("label\tscore\tleft\ttop\tright\tbottom");
                            for (final var object : objects) {
                                final var box = object.box();
                                rows.add(object.label() + "\t" + object.score() + "\t" + box.left() + "\t" + box.top()
                                         + "\t" + box.right() + "\t" + box.bottom());
                            }
                            Files.write(directory.resolve("detections.tsv"), rows, StandardOpenOption.CREATE_NEW);
                            return objects.size();
                        });
                        Files.writeString(directory.resolve("outcome.txt"), "OK\n", StandardOpenOption.CREATE_NEW);
                    } finally {
                        raster.flush();
                    }
                }
                System.out.println("Captured detector input: " + fields[0]);
            }
        }
    }

    /**
     * Preserves original JPEG encoding in a whole-page150DPI physical frame.
     * @param bytes original JPEG bytes
     * @param width original pixel width
     * @param height original pixel height
     * @return owned one-page PDF encoding
     * @throws Exception if PDF construction or serialization fails
     */
    private static byte[] pdf(final byte[] bytes, final int width, final int height) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(width * 72f / 150, height * 72f / 150));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(JPEGFactory.createFromStream(document, new ByteArrayInputStream(bytes)), 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
