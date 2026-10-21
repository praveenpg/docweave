package dev.docweave.layout;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import dev.docweave.partition.PdfWordOcrProvider;
import dev.docweave.partition.TableLimits;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DetectedOnnxTableLayoutTest {
    private static OnnxTableOptions options(final String fixture) throws Exception {
        final var path = Path.of(DetectedOnnxTableLayoutTest.class.getResource("/onnx/" + fixture).toURI());
        final var hash =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        return OnnxTableOptions.defaults(path, hash);
    }

    @Test
    void columnHeaderEvidenceSurvivesNativeInferenceAndBothCropTranslations(final @TempDir Path directory)
            throws Exception {
        final var structure = columnHeaderFixture(directory);
        final var image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        try (final var provider = OnnxTableLayout.create(options("fixed-detections.onnx"), structure)) {
            final var tables = provider.locate(image);
            assertEquals(2, tables.size());
            for (final var table : tables) {
                assertEquals(1, table.headerRows());
                assertEquals(3, table.rows());
                assertEquals(2, table.columns());
                assertEquals(6, table.anchors().size(), "Header roles must not merge ordinary anchors");
            }
            assertTrue(tables.getFirst().bounds().right() < tables.getLast().bounds().left());
        } finally {
            image.flush();
        }
    }

    /**
     * Converts the owned fixture's unique spanning-cell query to a column-header query.
     * Only its fixed logits change; tensor shapes/boxes and the native model schema stay unchanged.
     * This is synthetic plumbing evidence, not a trained-model quality measurement.
     * @param directory isolated test output
     * @return hash-pinned local fixture policy
     * @throws Exception if fixture identity/layout or fresh output creation fails
     */
    private static OnnxTableOptions columnHeaderFixture(final Path directory) throws Exception {
        final byte[] bytes = Files.readAllBytes(options("fixed-table.onnx").model());
        final var original = ByteBuffer.allocate(49 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final var replacement = ByteBuffer.allocate(49 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final int[] originalLabels = {0, 2, 2, 2, 1, 1, 5};
        for (int query = 0; query < originalLabels.length; query++) {
            for (int label = 0; label < 7; label++) {
                original.putFloat(label == originalLabels[query] ? 20 : -20);
                final int selected = query == 6 ? 3 : originalLabels[query];
                replacement.putFloat(label == selected ? 20 : -20);
            }
        }
        int found = -1;
        for (int index = 0; index <= bytes.length - original.capacity(); index++) {
            if (java.util.Arrays.equals(
                        bytes, index, index + original.capacity(), original.array(), 0, original.capacity())) {
                assertEquals(-1, found, "Owned logits tensor must have one unambiguous byte identity");
                found = index;
            }
        }
        assertTrue(found >= 0, "Owned ONNX fixture no longer contains the documented logits tensor");
        System.arraycopy(replacement.array(), 0, bytes, found, replacement.capacity());
        final var path = directory.resolve("column-header.onnx");
        Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
        return OnnxTableOptions.defaults(
                path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    void twoTablesTranslateOnceAndDuplicateRotatedBackgroundQueriesDoNotInventTables() throws Exception {
        final var detection = options("fixed-detections.onnx");
        final var structure = options("fixed-table.onnx");
        final var image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x40ff0000);
        final var provider = OnnxTableLayout.create(detection, structure);
        try (provider) {
            final var tables = provider.locate(image);
            assertEquals(2, tables.size());
            for (int index = 0; index < tables.size(); index++) {
                final var table = tables.get(index);
                assertEquals(3, table.rows());
                assertEquals(2, table.columns());
                assertEquals(5, table.anchors().size());
                assertEquals(2, table.anchors().getFirst().columnSpan());
                assertEquals(index * 290 + 310 * 20d / 300, table.bounds().left(), .001);
                assertEquals(190 * 10d / 180, table.bounds().top(), .001);
                assertTrue(table.provenance().contains(detection.sha256()));
                assertTrue(table.provenance().contains(structure.sha256()));
                assertTrue(table.provenance().length() <= 256);
            }
            assertTrue(tables.getFirst().bounds().right() < tables.getLast().bounds().left());
            assertEquals(0x40ff0000, image.getRGB(0, 0));
        }
        provider.close();
        assertThrows(PartitionException.class, () -> provider.locate(image));
    }

    @Test
    void nativeTwoStageGeometryIntegratesWithIndependentPdfWordOwnership() throws Exception {
        try (final var provider = OnnxTableLayout.create(options("fixed-detections.onnx"), options("fixed-table.onnx"));
                final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(new PDRectangle(600, 400)));
            document.save(output);
            final var calls = new AtomicInteger();
            final PdfWordOcrProvider words = (image, kind) -> {
                calls.incrementAndGet();
                assertEquals(PdfOcrProvider.InputKind.PAGE, kind);
                return List.of(word("First", 50, 20), word("A", 50, 45), word("10", 150, 45), word("B", 50, 70),
                        word("20", 150, 70), word("Second", 350, 20), word("C", 350, 45), word("30", 450, 45),
                        word("D", 350, 70), word("40", 450, 70), word("Outside", 100, 350));
            };
            final var ocr = new PdfOcrOptions(72, 300_000, 100, 1000)
                                    .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT);
            final var partitioner =
                    PdfPartitioner.create(PdfOptions.defaults().withTableCellCoordinates(true), ocr, words, provider);
            final var processor = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)));
            final var result = processor.partition(DocumentSource.bytes(output.toByteArray(), "owned-two-tables.pdf"));
            final var tables =
                    result.elements().stream().filter(element -> element.type() == ElementType.Table).toList();
            assertEquals(List.of("First A 10 B 20", "Second C 30 D 40"),
                    tables.stream().map(element -> element.text()).toList());
            assertEquals(List.of("Outside"), result.elements()
                                                     .stream()
                                                     .filter(element -> element.type() != ElementType.Table)
                                                     .map(element -> element.text())
                                                     .toList());
            assertEquals(1, calls.get());
            assertTrue(tables.getFirst().metadata().get("text_as_html").toString().contains("colspan=\"2\""));
            assertTrue(tables.getFirst()
                            .metadata()
                            .get("pdf_table_layout_provenance")
                            .toString()
                            .contains(options("fixed-detections.onnx").sha256()));
        }
    }

    private static PdfWordOcrProvider.WordLine word(final String text, final double x, final double y) {
        return new PdfWordOcrProvider.WordLine(new PdfOcrProvider.Line(text, x, y, x + 20, y + 5),
                List.of(new PdfWordOcrProvider.Word(text, x, y, x + 20, y + 5)));
    }

    @Test
    void aggregateProspectiveSlotsApplyAcrossCropsAndBothPolicies() throws Exception {
        final var detection = options("fixed-detections.onnx").withTableLimits(new TableLimits(3, 2, 10, 100));
        try (final var provider = OnnxTableLayout.create(detection, options("fixed-table.onnx"))) {
            final var failure = assertThrows(PartitionException.class,
                    () -> provider.locate(new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB)));
            assertEquals(PartitionException.Code.TABLE_LIMIT, failure.code());
        }
    }

    @Test
    void childNativeLoopUsesShorterSharedDeadlineAndCleanupRemainsSafe() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (final var provider = OnnxTableLayout.create(options("fixed-detections.onnx"),
                         options("finite-loop.onnx").withTimeout(Duration.ofMillis(500)))) {
                final var failure = assertThrows(PartitionException.class,
                        () -> provider.locate(new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB)));
                assertEquals(PartitionException.Code.OCR_TIMEOUT, failure.code());
            }
        });
    }

    @Test
    void graphRolesAreValidatedAndConcurrentVirtualPagesShareManagedSessions() throws Exception {
        final var detection = options("fixed-detections.onnx");
        final var structure = options("fixed-table.onnx");
        assertThrows(PartitionException.class, () -> OnnxTableLayout.create(structure, structure));
        assertThrows(PartitionException.class, () -> OnnxTableLayout.create(detection, detection));
        try (final var provider = OnnxTableLayout.create(detection.withConcurrency(2), structure);
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
            final var expected = provider.locate(image);
            final var futures =
                    IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> provider.locate(image))).toList();
            for (final var future : futures) {
                assertEquals(expected, future.get());
            }
        }
    }
}
