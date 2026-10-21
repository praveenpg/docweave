package dev.docweave.validation;

import dev.docweave.layout.OnnxTableLayout;
import dev.docweave.layout.OnnxTableOptions;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfTableLayoutProvider;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit local graph selection shared by opt-in published-corpus evaluations. */
final class PdfLayoutModels {
    /** Prevents utility construction. */
    private PdfLayoutModels() {
    }

    /** Explicit corpus detector families; no fallback or automatic model-family inference. */
    private enum DetectorFamily {
        /** Historical three-label page detector. */ TATR,
        /** Fixed exported eleven-class detector before table selection. */ YOLOX
    }

    /**
     * Owns exactly the explicitly loaded model while exposing its selected geometry composition.
     * @param owner explicit managed session composition
     * @param selected caller-selected rule/model geometry strategy
     */
    record LocalLayout(OnnxTableLayout owner, PdfTableLayoutProvider selected)
            implements PdfTableLayoutProvider, AutoCloseable {
        @Override
        public List<Table> locate(final BufferedImage image) throws IOException {
            return selected.locate(image);
        }

        @Override
        public void close() throws IOException {
            owner.close();
        }
    }

    /**
     * Loads no graph unless the selected corpus mode explicitly requires one; never downloads weights.
     * A YOLOX family selection requires a detector graph/hash and provider-layout mode.
     * @param prefix explicit opt-in corpus property namespace
     * @param mode selected table extraction route
     * @return caller-owned local layout, or null for non-model modes
     * @throws IOException if graph identity, schema or native initialization fails
     * @throws IllegalArgumentException if detector family or selected mode is unsupported
     */
    static LocalLayout create(final String prefix, final PdfOcrOptions.TableDetection mode) throws IOException {
        final var family = DetectorFamily.valueOf(System.getProperty(prefix + ".detectionFamily", "TATR"));
        if (mode != PdfOcrOptions.TableDetection.PROVIDER_LAYOUT
                && mode != PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS) {
            if (family != DetectorFamily.TATR) {
                throw new IllegalArgumentException("YOLOX requires an explicit provider-layout mode");
            }
            return null;
        }
        final var model = System.getProperty(prefix + ".layoutModel");
        final var hash = System.getProperty(prefix + ".layoutSha256");
        assertNotNull(model, prefix + ".layoutModel");
        assertNotNull(hash, prefix + ".layoutSha256");
        final var structure = OnnxTableOptions.defaults(Path.of(model), hash)
                                      .withMergeConflicts(OnnxTableOptions.MergeConflicts.valueOf(
                                              System.getProperty(prefix + ".layoutMergeConflicts", "REJECT_TABLE")));
        final var detector = System.getProperty(prefix + ".detectionModel");
        final var detectorHash = System.getProperty(prefix + ".detectionSha256");
        assertEquals(detector == null, detectorHash == null, "Supply both detection graph and SHA256");
        if (family == DetectorFamily.YOLOX && detector == null) {
            throw new IllegalArgumentException("YOLOX requires an explicit detector graph and SHA256");
        }
        final var managed =
                detector == null ? OnnxTableLayout.create(structure)
                : family == DetectorFamily.YOLOX
                        ? OnnxTableLayout.createWithYoloX(
                                  OnnxTableOptions.yoloXDefaults(Path.of(detector), detectorHash), structure)
                        : OnnxTableLayout.create(OnnxTableOptions.defaults(Path.of(detector), detectorHash), structure);
        return new LocalLayout(managed,
                Boolean.getBoolean(prefix + ".preferRuled")
                        ? PdfTableLayoutProvider.preferRuled(managed, structure.tableLimits(), PdfOcrOptions.defaults())
                        : managed);
    }
}
