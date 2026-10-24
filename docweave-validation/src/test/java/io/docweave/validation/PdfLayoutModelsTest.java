package io.docweave.validation;

import io.docweave.partition.PartitionException;
import io.docweave.partition.PdfOcrOptions;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit model-family selection controls; no downloaded graph or external engine is used. */
class PdfLayoutModelsTest {
    /** Isolated property namespace, separate from all real corpus invocations. */
    private static final String PREFIX = "docweave.test.layout.selection";

    /** Creates isolated model selection controls. */
    PdfLayoutModelsTest() {
    }

    /**
     * Historical default selection still executes the owned TATR pipeline; YOLOX dispatch rejects that schema.
     * @throws Exception if owned fixture loading or inference fails
     */
    @Test
    void defaultTatrSelectionAndExplicitYoloXDispatchRemainDistinct() throws Exception {
        final var source = properties();
        configured(source, () -> {
            try (final var layout = PdfLayoutModels.create(PREFIX, PdfOcrOptions.TableDetection.PROVIDER_LAYOUT)) {
                final var image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
                try {
                    assertEquals(2, layout.locate(image).size());
                } finally {
                    image.flush();
                }
            }
        });
        final var alternative = new HashMap<>(source);
        alternative.put("detectionFamily", "YOLOX");
        configured(alternative, () -> {
            final var failure = assertThrows(PartitionException.class,
                    () -> PdfLayoutModels.create(PREFIX, PdfOcrOptions.TableDetection.PROVIDER_LAYOUT));
            assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
            assertEquals("Unsupported YOLOX tensor schema", failure.getMessage());
        });
    }

    /**
     * Invalid family/mode combinations and absent detector identities cannot silently select another strategy.
     * @throws Exception if property restoration or fixture hashing fails
     */
    @Test
    void unsupportedOrIncompleteSelectionsFailBeforeEvaluation() throws Exception {
        configured(Map.of("detectionFamily", "yolox"),
                ()
                        -> assertThrows(IllegalArgumentException.class,
                                () -> PdfLayoutModels.create(PREFIX, PdfOcrOptions.TableDetection.PROVIDER_LAYOUT)));
        configured(Map.of("detectionFamily", "YOLOX"),
                ()
                        -> assertThrows(IllegalArgumentException.class,
                                () -> PdfLayoutModels.create(PREFIX, PdfOcrOptions.TableDetection.RULED)));
        final var incomplete = new HashMap<>(properties());
        incomplete.put("detectionFamily", "YOLOX");
        incomplete.remove("detectionModel");
        incomplete.remove("detectionSha256");
        configured(incomplete,
                ()
                        -> assertThrows(IllegalArgumentException.class,
                                () -> PdfLayoutModels.create(PREFIX, PdfOcrOptions.TableDetection.PROVIDER_LAYOUT)));
        configured(Map.of(), () -> assertNull(PdfLayoutModels.create(PREFIX, PdfOcrOptions.TableDetection.RULED)));
    }

    /**
     * Supplies independently owned small graph fixtures from the sibling module.
     * @return exact local graph identities
     * @throws Exception if fixture hashing fails
     */
    private static Map<String, String> properties() throws Exception {
        final var root = Path.of("docweave-layout-onnx/src/test/resources/onnx");
        final var structure = root.resolve("fixed-table.onnx");
        final var detector = root.resolve("fixed-detections.onnx");
        return Map.of("layoutModel", structure.toString(), "layoutSha256", hash(structure), "detectionModel",
                detector.toString(), "detectionSha256", hash(detector));
    }

    /**
     * Hashes complete owned fixture bytes.
     * @param path local fixture
     * @return exact SHA256
     * @throws Exception if reading or digest creation fails
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Restores every touched namespaced property, including originally absent values.
     * @param properties explicit suffix/value selection for this invocation
     * @param action isolated checked assertion
     * @throws Exception if the assertion fails
     */
    private static void configured(final Map<String, String> properties, final Action action) throws Exception {
        final var previous = new HashMap<String, String>();
        for (final String suffix : List.of("layoutModel", "layoutSha256", "detectionModel", "detectionSha256",
                     "detectionFamily", "layoutMergeConflicts", "preferRuled")) {
            final String key = PREFIX + "." + suffix;
            previous.put(key, System.getProperty(key));
            if (properties.containsKey(suffix)) {
                System.setProperty(key, properties.get(suffix));
            } else {
                System.clearProperty(key);
            }
        }
        try {
            action.run();
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    /** Checked test action scoped to temporary local properties. */
    @FunctionalInterface
    private interface Action {
        /**
         * Performs isolated model configuration assertions.
         * @throws Exception if fixture access or assertions fail
         */
        void run() throws Exception;
    }
}
