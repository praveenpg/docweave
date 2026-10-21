package dev.docweave.layout;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/**
 * Captures the corpus PDF embedding, rendered input and unchanged thresholded model objects.
 * Compile against reactor classes into a temporary directory; this is a diagnostic tool, not a service API.
 * Original images and generated derivatives stay in the caller's local cache, never in committed evidence.
 */
public final class CaptureTableReferenceInput {
    /** Prevents construction of the standalone diagnostic launcher. */
    private CaptureTableReferenceInput() {
    }

    /**
     * Embeds an entire PNG at150DPI, renders at300DPI and captures the existing structure-only decoder input.
     * No oracle crop, object deletion or source-image repair is performed.
     * @param args original PNG, new output directory, local structure graph and expected graph SHA256
     * @throws Exception if an input, model, resource limit or new-output requirement fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected PNG, new output directory, graph and graph SHA256");
        }
        final var directory = Files.createDirectory(Path.of(args[1]));
        Files.writeString(directory.resolve("java-runtime.txt"),
                System.getProperty("java.runtime.version") + "\n" + System.getProperty("java.vendor") + "\n",
                StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve("source.sha256"),
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(args[0])))),
                StandardOpenOption.CREATE_NEW);
        final var image = ImageIO.read(Path.of(args[0]).toFile());
        if (image == null) {
            throw new IllegalArgumentException("Input must be a readable image");
        }
        final byte[] encoded;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(image.getWidth() * 72f / 150, image.getHeight() * 72f / 150));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, page.getMediaBox().getWidth(),
                        page.getMediaBox().getHeight());
            }
            document.save(output);
            encoded = output.toByteArray();
        } finally {
            image.flush();
        }
        Files.write(directory.resolve("input.pdf"), encoded, StandardOpenOption.CREATE_NEW);
        final var options = OnnxTableOptions.defaults(Path.of(args[2]), args[3]);
        try (final var document = Loader.loadPDF(encoded); final var session = new ManagedTatrSession(options, 7)) {
            final var raster = new PDFRenderer(document).renderImageWithDPI(0, 300, ImageType.RGB);
            try {
                try (final var output =
                                Files.newOutputStream(directory.resolve("render.png"), StandardOpenOption.CREATE_NEW)) {
                    if (!ImageIO.write(raster, "png", output)) {
                        throw new IllegalStateException("PNG encoder unavailable");
                    }
                }
                final var deadline = new LayoutDeadline(options.timeout());
                session.infer(raster, deadline, objects -> {
                    final var rows = new ArrayList<String>();
                    rows.add("label\tscore\tleft\ttop\tright\tbottom");
                    for (final var object : objects) {
                        final var box = object.box();
                        rows.add(object.label() + "\t" + object.score() + "\t" + box.left() + "\t" + box.top() + "\t"
                                 + box.right() + "\t" + box.bottom());
                    }
                    Files.write(directory.resolve("objects.tsv"), rows, StandardOpenOption.CREATE_NEW);
                    final var tables = new TableTopology(options, deadline).tables(objects);
                    Files.writeString(directory.resolve("docweave-tables.txt"), Integer.toString(tables.size()),
                            StandardOpenOption.CREATE_NEW);
                    System.out.println("Captured " + objects.size() + " objects; DocWeave tables=" + tables.size());
                    return tables.size();
                });
            } finally {
                raster.flush();
            }
        }
    }
}
