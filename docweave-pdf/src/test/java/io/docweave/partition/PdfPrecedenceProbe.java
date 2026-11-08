package io.docweave.partition;

import io.docweave.DocWeave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Child-JVM probe for {@link PdfBudgetPrecedenceTest}: runs multi-budget scenarios through whole-document
 * partitioning at the common-pool parallelism the JVM was started with and prints one {@code name|outcome} line each.
 */
final class PdfPrecedenceProbe {
    /** Pages per scenario document. */
    static final int PAGES = 40;
    /** OCR lines on an ordinary page; with its image an ordinary page holds this plus one elements. */
    static final int LINES = 10;
    /** OCR lines on a dense page, one more than the buffered page-local cap. */
    static final int DENSE = 4097;

    /** Utility class. */
    private PdfPrecedenceProbe() {
    }

    /**
     * One scenario: a cumulative element crossing page, an image crossing page and a dense page, each optional.
     * @param name stable name
     * @param buffered whether table continuation buffers pages (cumulative check B), otherwise direct (check C)
     * @param elementPage cumulative crossing page, or 0
     * @param imagePage cumulative image crossing page, or 0
     * @param densePage page exceeding the page-local cap (check A), or 0
     */
    record Scenario(String name, boolean buffered, int elementPage, int imagePage, int densePage) {}

    /**
     * The measured Phase 0 scenarios.
     * @return scenarios
     */
    static List<Scenario> scenarios() {
        return List.of(new Scenario("c-e20-i40", false, 20, 40, 0), new Scenario("c-e40-i20", false, 40, 20, 0),
                new Scenario("c-e30-i30", false, 30, 30, 0), new Scenario("c-e30-i31", false, 30, 31, 0),
                new Scenario("c-e31-i30", false, 31, 30, 0), new Scenario("c-e20", false, 20, 0, 0),
                new Scenario("b-e30-i31", true, 30, 31, 0), new Scenario("a-a30-i31", true, 0, 31, 30),
                new Scenario("a-a31-i30", true, 0, 30, 31), new Scenario("a-a30-i30", true, 0, 30, 30),
                new Scenario("a-a20-i40", true, 0, 40, 20), new Scenario("a-a40-i20", true, 0, 20, 40),
                new Scenario("a-a30", true, 0, 0, 30), new Scenario("a-a40-i31-e30", true, 30, 31, 40),
                new Scenario("a-a30-e29", true, 29, 0, 30), new Scenario("a-a30-e30", true, 30, 0, 30));
    }

    /**
     * Runs every scenario and prints its outcome.
     * @param args unused
     * @throws Exception if authoring fails
     */
    public static void main(final String[] args) throws Exception {
        System.out.println("batch|" + (java.util.concurrent.ForkJoinPool.getCommonPoolParallelism() + 1));
        final long imageBytes = imageBytes();
        for (final var scenario : scenarios()) {
            System.out.println(scenario.name() + "|" + outcome(scenario, imageBytes));
        }
    }

    /**
     * Runs one scenario through the whole-document path.
     * @param scenario scenario
     * @param imageBytes encoded bytes of one page image
     * @return {@code OK n} or {@code FAIL code message}
     * @throws IOException if authoring fails
     */
    static String outcome(final Scenario scenario, final long imageBytes) throws IOException {
        final var counts = new ArrayList<Integer>();
        for (int page = 1; page <= PAGES; page++) {
            counts.add(page == scenario.densePage() ? DENSE : LINES);
        }
        final int maxElements =
                scenario.elementPage() == 0 ? 1_000_000 : (LINES + 1) * (scenario.elementPage() - 1) + (LINES + 1) / 2;
        final int maxImages = scenario.imagePage() == 0
                                      ? Integer.MAX_VALUE
                                      : (int) (imageBytes * (scenario.imagePage() - 1) + imageBytes / 2);
        try {
            final var result =
                    DocWeave.create(PartitionerRegistry.of(List.of(partitioner(scenario.buffered(), maxImages))))
                            .partition(DocumentSource.bytes(document(counts), "precedence.pdf"),
                                    PartitionOptions.builder().maxElements(maxElements).build());
            return "OK " + result.elements().size();
        } catch (final PartitionException failure) {
            return "FAIL " + failure.code() + " " + failure.getMessage();
        }
    }

    /**
     * Measures the encoded bytes of one page image.
     * @return bytes charged for one page
     * @throws IOException if partitioning fails
     */
    static long imageBytes() throws IOException {
        final var partitioner = partitioner(false, Integer.MAX_VALUE);
        final var range = partitioner.partitionRange(DocumentSource.bytes(document(List.of(LINES)), "one.pdf"),
                PartitionOptions.builder().build(), new PdfPageRange(1, 1), PdfBudget.unlimited());
        return range.pages().getFirst().usage().get(PdfBudget.Kind.IMAGE_BYTES);
    }

    /**
     * OCR-only strategy retaining image bytes.
     * @param buffered whether table continuation is on
     * @param maxImageBytes image budget
     * @return strategy
     */
    static PdfPartitioner partitioner(final boolean buffered, final int maxImageBytes) {
        final var base =
                buffered ? PdfOptions.defaults().withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER)
                         : PdfOptions.defaults();
        return PdfPartitioner.builder()
                .strategy(PdfStrategy.OCR_ONLY)
                .options(PdfRangeCorpus.withImages(base, maxImageBytes))
                .ocrOptions(new PdfOcrOptions(72, 10_000_000, 20_000, 1_000_000))
                .ocrProvider(PdfWholeDocumentGoldenTest.countingProvider())
                .build();
    }

    /**
     * Pages with one identical image each; the page width encodes the line count for the recognizer.
     * @param counts lines per page
     * @return PDF bytes
     * @throws IOException if authoring fails
     */
    static byte[] document(final List<Integer> counts) throws IOException {
        final var image = new BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 16; y++) {
                image.setRGB(x, y, (x * 10) << 16 | (y * 15) << 8 | 90);
            }
        }
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            for (final int count : counts) {
                final var page = new PDPage(new PDRectangle(600 + count, 800));
                document.addPage(page);
                try (final var content = new PDPageContentStream(document, page)) {
                    content.drawImage(LosslessFactory.createFromImage(document, image), 20, 20, 48, 32);
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
