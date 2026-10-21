import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import dev.docweave.partition.TableLimits;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.imageio.ImageIO;

/** Numeric public-API probe usable unchanged with pre-fix and candidate packaged PDF JARs. */
public final class StencilPayloadProbe {
    /** Prevents utility construction. */
    private StencilPayloadProbe() {
    }

    /**
     * Builds one owned input or captures exact source pixels against analytic authored expectations.
     * A capture records misses rather than accepting or repairing the old implementation.
     * @param args --fixture and fresh PDF path; or source PDF/hash and fresh properties output
     * @throws Exception if source identity, fixed geometry, decoding or fresh output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--fixture")) {
            fixture(Path.of(args[1]));
            return;
        }
        require(args.length == 3 && Runtime.version().feature() == 21);
        final byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        require(hash(bytes).equals(args[1]));
        final var options = new PdfOptions(10, 10_000, 10_000, 1000, 10_000, true, TableLimits.defaults());
        final var result = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                                   .partition(DocumentSource.bytes(bytes, "owned-stencils.pdf"));
        final var images = result.elements().stream().filter(e -> e.type() == ElementType.Image).toList();
        require(images.size() == 2);
        final var expected = Map.of(20.0, Color.RED.getRGB(), 60.0, Color.BLUE.getRGB());
        final var seen = new HashSet<Double>();
        int paintMatches = 0;
        int transparentMatches = 0;
        final var report = new Properties();
        for (final var image : images) {
            final var coordinates = (Map<?, ?>) image.metadata().get("coordinates");
            require(coordinates.get("system").equals("PDFUserSpace"));
            final var first = (List<?>) ((List<?>) coordinates.get("points")).getFirst();
            final double x = ((Number) first.getFirst()).doubleValue();
            require(expected.containsKey(x) && seen.add(x) && ((Number) first.get(1)).doubleValue() == 70);
            final byte[] png = Base64.getDecoder().decode(image.metadata().get("image_base64").toString());
            final var pixels = ImageIO.read(new ByteArrayInputStream(png));
            require(pixels != null && pixels.getWidth() == 2 && pixels.getHeight() == 1);
            try {
                if (pixels.getRGB(0, 0) == expected.get(x)) {
                    paintMatches++;
                }
                if (pixels.getRGB(1, 0) >>> 24 == 0) {
                    transparentMatches++;
                }
                report.setProperty("placement." + (int) x + ".paint_argb", Integer.toHexString(pixels.getRGB(0, 0)));
                report.setProperty(
                        "placement." + (int) x + ".transparent_sample_argb", Integer.toHexString(pixels.getRGB(1, 0)));
                report.setProperty("placement." + (int) x + ".pixel_policy",
                        image.metadata().getOrDefault("image_pixel_policy", "absent").toString());
            } finally {
                pixels.flush();
            }
        }
        require(seen.equals(expected.keySet()));
        report.setProperty("runtime", Runtime.version().toString());
        report.setProperty("input_pdf_sha256", hash(bytes));
        report.setProperty("source_image_placements", "2");
        report.setProperty("paint_rgb_and_alpha_matches", Integer.toString(paintMatches));
        report.setProperty("transparent_alpha_matches", Integer.toString(transparentMatches));
        final var library = Path.of(PdfPartitioner.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        require(Files.isRegularFile(library) && library.getFileName().toString().endsWith(".jar"));
        report.setProperty("pdf_jar_sha256", hash(Files.readAllBytes(library)));
        try (final var writer = Files.newBufferedWriter(Path.of(args[2]), StandardOpenOption.CREATE_NEW)) {
            report.store(
                    writer, "Owned stencil pixels; source geometry association only; not external figure accuracy");
        }
    }

    /**
     * Creates two differently painted occurrences of one two-sample stencil, without pixel labels from a renderer.
     * @param output fresh owned PDF path
     * @throws Exception if fixture construction or new-file output fails
     */
    private static void fixture(final Path output) throws Exception {
        require(!Files.exists(output));
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(100, 100));
            document.addPage(page);
            final var image = new PDImageXObject(document);
            image.setWidth(2);
            image.setHeight(1);
            image.setBitsPerComponent(1);
            image.setStencil(true);
            try (final var samples = image.getCOSObject().createOutputStream()) {
                samples.write(0x40);
            }
            try (final var content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(Color.RED);
                content.drawImage(image, 20, 20, 20, 10);
                content.setNonStrokingColor(Color.BLUE);
                content.drawImage(image, 60, 20, 20, 10);
            }
            // Fixed IDs make this owned fixture reproducible; no publisher document is rewritten.
            final var ids = new COSArray();
            ids.add(new COSString("DocWeaveStencil1".getBytes(StandardCharsets.US_ASCII)));
            ids.add(new COSString("DocWeaveStencil1".getBytes(StandardCharsets.US_ASCII)));
            document.getDocument().getTrailer().setItem(COSName.ID, ids);
            document.save(output.toFile());
        }
    }

    /**
     * Computes original bytes and packaged-library identities without publishing source pixels or local paths.
     * @param bytes original input or JAR bytes
     * @return lowercase SHA256
     * @throws Exception if SHA256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects a changed source/placement frame without adjusting expected pixel values.
     * @param valid required invariant
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Owned stencil probe protocol mismatch");
        }
    }
}
