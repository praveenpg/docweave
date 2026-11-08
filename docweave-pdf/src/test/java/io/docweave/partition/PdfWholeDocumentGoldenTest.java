package io.docweave.partition;

import io.docweave.DocWeave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole-document output and budget failures compared with goldens captured from unchanged 0.1.0-beta.13
 * (source commit 54f47a8) before the page-range refactor. Set {@code -Ddocweave.writeRangeGoldens=<dir>} to
 * regenerate them; that only belongs to a deliberate, reviewed baseline change.
 */
class PdfWholeDocumentGoldenTest {
    /** Classpath folder holding the captured goldens. */
    private static final String GOLDENS = "range-goldens/";

    /** Creates the test. */
    PdfWholeDocumentGoldenTest() {
    }

    /**
     * Every corpus document, element by element, plus warnings and their order.
     * @throws Exception if partitioning or reading fails
     */
    @Test
    void wholeDocumentOutputMatchesBeta13() throws Exception {
        final var written = new LinkedHashMap<String, String>();
        for (final var testCase : PdfRangeCorpus.cases()) {
            written.put(testCase.name(), PdfRangeCorpus.canonical(testCase.partition()));
        }
        written.put("limits", limits());
        final String target = System.getProperty("docweave.writeRangeGoldens");
        if (target != null) {
            for (final var entry : written.entrySet()) {
                Files.writeString(Path.of(target, entry.getKey() + ".txt"), entry.getValue(), StandardCharsets.UTF_8);
            }
            return;
        }
        for (final var entry : written.entrySet()) {
            assertEquals(golden(entry.getKey()), entry.getValue(), entry.getKey());
        }
    }

    /**
     * Reads one captured golden.
     * @param name case name
     * @return captured text
     * @throws IOException if missing
     */
    private static String golden(final String name) throws IOException {
        try (final InputStream input = PdfWholeDocumentGoldenTest.class.getResourceAsStream(GOLDENS + name + ".txt")) {
            assertNotNull(input, "missing golden " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Budget outcomes: element count or failure code and message, one line per scenario.
     * @return scenario outcomes
     * @throws IOException if authoring fails
     */
    static String limits() throws IOException {
        final var out = new StringBuilder();
        for (final var scenario : limitScenarios()) {
            out.append(scenario.name()).append(' ').append(scenario.outcome()).append('\n');
        }
        return out.toString();
    }

    /**
     * Budget scenarios used by the golden capture and by the range tests.
     * @return scenarios
     * @throws IOException if authoring fails
     */
    static List<LimitScenario> limitScenarios() throws IOException {
        final var scenarios = new ArrayList<LimitScenario>();
        final var continuation =
                PdfOptions.defaults().withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER);
        for (final int count : List.of(4095, 4096, 4097)) {
            scenarios.add(new LimitScenario("geometric-cap-" + count, ocrPages(List.of(count)), PdfOptions.defaults(),
                    PdfOcrOptions.ReadingOrder.GEOMETRIC, 100_000));
            scenarios.add(new LimitScenario("continuation-cap-" + count, ocrPages(List.of(3, count)), continuation,
                    PdfOcrOptions.ReadingOrder.SOURCE_ORDER, 100_000));
        }
        for (final int count : List.of(49, 50, 51)) {
            scenarios.add(new LimitScenario("direct-cap-" + count, ocrPages(List.of(count)), PdfOptions.defaults(),
                    PdfOcrOptions.ReadingOrder.SOURCE_ORDER, 50));
            scenarios.add(new LimitScenario("geometric-small-cap-" + count, ocrPages(List.of(count)),
                    PdfOptions.defaults(), PdfOcrOptions.ReadingOrder.GEOMETRIC, 50));
        }
        for (final var pages : List.of(List.of(30, 20), List.of(30, 21), List.of(10, 10, 10, 10, 10, 1),
                     List.of(20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 31))) {
            final String suffix = String.join("-", pages.stream().map(String::valueOf).toList());
            scenarios.add(new LimitScenario("direct-cumulative-" + suffix, ocrPages(pages), PdfOptions.defaults(),
                    PdfOcrOptions.ReadingOrder.SOURCE_ORDER, 50));
            scenarios.add(new LimitScenario("buffered-cumulative-" + suffix, ocrPages(pages), PdfOptions.defaults(),
                    PdfOcrOptions.ReadingOrder.GEOMETRIC, 50));
            scenarios.add(new LimitScenario("continuation-cumulative-" + suffix, ocrPages(pages), continuation,
                    PdfOcrOptions.ReadingOrder.SOURCE_ORDER, 50));
        }
        final var images = PdfRangeCorpus.imageDocument();
        for (final int bytes : List.of(1, 400, 1_000, 10_000_000)) {
            scenarios.add(new LimitScenario(
                    "images-" + bytes, images, PdfRangeCorpus.withImages(PdfOptions.defaults(), bytes), null, 100_000));
        }
        return scenarios;
    }

    /**
     * One budget scenario.
     * @param name stable name
     * @param pdf document
     * @param options PDF options
     * @param order OCR reading order, or null for native-only extraction
     * @param maxElements document element budget
     */
    record LimitScenario(
            String name, byte[] pdf, PdfOptions options, PdfOcrOptions.ReadingOrder order, int maxElements) {
        /**
         * Creates the configured strategy.
         * @return strategy
         */
        PdfPartitioner partitioner() {
            if (order == null) {
                return PdfPartitioner.create(options);
            }
            return PdfPartitioner.builder()
                    .strategy(PdfStrategy.OCR_ONLY)
                    .options(options)
                    .ocrOptions(new PdfOcrOptions(72, 10_000_000, 20_000, 1_000_000).withReadingOrder(order))
                    .ocrProvider(countingProvider())
                    .build();
        }

        /**
         * Partition options carrying the element budget.
         * @return options
         */
        PartitionOptions partitionOptions() {
            return PartitionOptions.builder().maxElements(maxElements).build();
        }

        /**
         * Runs the whole document and describes the outcome.
         * @return {@code OK n} or {@code FAIL code message}
         */
        String outcome() {
            try {
                final var result = DocWeave.create(PartitionerRegistry.of(List.of(partitioner())))
                                           .partition(DocumentSource.bytes(pdf, "limits.pdf"), partitionOptions());
                return "OK " + result.elements().size();
            } catch (final PartitionException failure) {
                return "FAIL " + failure.code() + " " + failure.getMessage();
            } catch (final IOException failure) {
                return "ERROR " + failure;
            }
        }
    }

    /**
     * Recognizer whose line count is encoded in the rendered page width ({@code 600 + count}).
     * @return thread-safe provider
     */
    static PdfOcrProvider countingProvider() {
        return image -> {
            final int count = image.getWidth() - 600;
            final var lines = new ArrayList<PdfOcrProvider.Line>(count);
            for (int index = 0; index < count; index++) {
                final double top = 10 + (index * 7) % 700;
                final double left = 10 + (index * 13) % 400;
                lines.add(new PdfOcrProvider.Line("L" + index, left, top, left + 30, top + 6));
            }
            return lines;
        };
    }

    /**
     * Authors blank pages whose widths encode the recognizer's line count.
     * @param counts lines per page
     * @return PDF bytes
     * @throws IOException if authoring fails
     */
    static byte[] ocrPages(final List<Integer> counts) throws IOException {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            for (final int count : counts) {
                document.addPage(new PDPage(new PDRectangle(600 + count, 800)));
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
