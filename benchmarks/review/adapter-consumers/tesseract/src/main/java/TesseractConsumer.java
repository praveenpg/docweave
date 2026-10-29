import io.docweave.ocr.TesseractOcr;
import io.docweave.ocr.TesseractOptions;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PdfOcrProvider;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Standalone optional-adapter consumer; external OCR runs only with explicit paths. */
public final class TesseractConsumer {
    /** Prevents construction of this executable smoke check. */
    private TesseractConsumer() {
    }

    /**
     * Checks public construction and missing-engine diagnostics, optionally recognizing owned text.
     * @param args either empty or an explicit executable and installed tessdata directory
     * @throws Exception if diagnostics or explicit recognition fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 0 && args.length != 2) {
            throw new IllegalArgumentException("Expected [executable tessdata-directory]");
        }
        final var missing = Files.createTempDirectory("docweave-consumer-missing-");
        final var image = new BufferedImage(700, 120, BufferedImage.TYPE_INT_RGB);
        final var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 56));
            graphics.drawString("DOCWEAVE", 30, 80);
        } finally {
            graphics.dispose();
        }
        try {
            final var original = options(missing.resolve("absent"), null);
            final var column = original.withPageSegmentation(TesseractOptions.PageSegmentation.SINGLE_COLUMN);
            if (original.pageSegmentation() != TesseractOptions.PageSegmentation.SINGLE_BLOCK
                    || column.pageSegmentation() != TesseractOptions.PageSegmentation.SINGLE_COLUMN) {
                throw new AssertionError("Single-column configuration changed the original policy");
            }
            final PdfOcrProvider provider = TesseractOcr.create(original);
            try {
                provider.recognize(image);
                throw new AssertionError("Missing executable unexpectedly succeeded");
            } catch (final PartitionException failure) {
                if (failure.code() != PartitionException.Code.OCR_FAILURE) {
                    throw new AssertionError("Missing executable lost typed diagnostic", failure);
                }
            }
            System.out.println("Public OCR interface and missing-engine diagnostic passed");
            if (args.length == 2) {
                for (final var mode : List.of(TesseractOptions.PageSegmentation.SINGLE_BLOCK,
                             TesseractOptions.PageSegmentation.SINGLE_COLUMN)) {
                    final PdfOcrProvider installed =
                            TesseractOcr.create(options(Path.of(args[0]), Path.of(args[1])).withPageSegmentation(mode));
                    final var lines = installed.recognize(image);
                    if (lines.stream().noneMatch(line -> line.text().contains("DOCWEAVE"))) {
                        throw new AssertionError("Owned OCR smoke text not recognized: " + mode);
                    }
                }
                System.out.println("Explicit installed-engine recognition passed");
            }
        } finally {
            Files.delete(missing);
            image.flush();
        }
        System.out.println("Tesseract consumer passed on " + Runtime.version());
    }

    /**
     * Builds a bounded policy with explicit trusted resources and the original single-block page assumption.
     * @param executable caller-selected executable or deliberately absent test path
     * @param tessdata optional installed model directory
     * @return immutable configuration; construction executes nothing
     */
    private static TesseractOptions options(final Path executable, final Path tessdata) {
        return new TesseractOptions(executable, tessdata, List.of("eng"), Duration.ofSeconds(15), 1, 1_000_000,
                4_000_000, 1_000_000, 100, 10_000)
                .withPageSegmentation(TesseractOptions.PageSegmentation.SINGLE_BLOCK);
    }
}
