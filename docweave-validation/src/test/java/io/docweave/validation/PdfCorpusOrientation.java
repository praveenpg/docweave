package io.docweave.validation;

import io.docweave.ocr.TesseractOrientation;
import io.docweave.ocr.TesseractOrientationOptions;
import io.docweave.partition.PdfOcrOptions;
import io.docweave.partition.PdfOcrProvider;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfOrientationEstimator;
import io.docweave.partition.PdfPartitioner;
import io.docweave.partition.PdfTableLayoutProvider;
import io.docweave.partition.PdfWordOcrProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit single-threaded corpus observations; never enables orientation in a default evaluation. */
final class PdfCorpusOrientation {
    /** Null retains historical factory behavior and report entries. */
    private final PdfOrientationEstimator estimator;
    /** One document's bounded page observations; corpus fixtures contain one page each. */
    private final List<PdfOrientationEstimator.Estimate> observations = new ArrayList<>();

    /** Reads only an explicit corpus flag and trusted already-installed engine/model configuration. */
    PdfCorpusOrientation(final String prefix) {
        if (!Boolean.getBoolean(prefix + ".orientation")) {
            estimator = null;
            return;
        }
        final String executable = System.getProperty("docweave.tesseract.executable");
        final String data = System.getProperty("docweave.tesseract.tessdata");
        assertNotNull(executable);
        assertNotNull(data);
        final var defaults = TesseractOrientationOptions.defaults();
        estimator = TesseractOrientation.create(new TesseractOrientationOptions(Path.of(executable), Path.of(data),
                defaults.timeout(), 1, defaults.maxImagePixels(), defaults.maxPngBytes(), defaults.maxOutputBytes(),
                defaults.minimumOrientationScore()));
    }

    /** Configures the same OCR/layout policy, optionally observing explicit orientation before recognition. */
    PdfPartitioner partitioner(final PdfOptions pdf, final PdfOcrOptions ocr, final PdfWordOcrProvider engine,
            final PdfTableLayoutProvider layout) {
        return partitioner(pdf, ocr, engine, layout, null);
    }

    /** Preserves historical factories unless a caller explicitly selects independent cell-line recognition. */
    PdfPartitioner partitioner(final PdfOptions pdf, final PdfOcrOptions ocr, final PdfWordOcrProvider engine,
            final PdfTableLayoutProvider layout, final PdfOcrProvider cells) {
        if (estimator == null) {
            if (cells != null) {
                return PdfPartitioner.createWithCellOcr(pdf, ocr, engine, layout, cells);
            }
            return layout == null ? PdfPartitioner.create(pdf, ocr, engine)
                                  : PdfPartitioner.create(pdf, ocr, engine, layout);
        }
        final PdfOrientationEstimator observed = image -> {
            final var estimate = estimator.estimate(image);
            observations.add(estimate);
            return estimate;
        };
        if (cells != null) {
            return PdfPartitioner.createOrientedWithCellOcr(pdf, ocr, engine, layout, cells, observed);
        }
        return layout == null ? PdfPartitioner.createOriented(pdf, ocr, engine, observed)
                              : PdfPartitioner.createOriented(pdf, ocr, engine, layout, observed);
    }

    /** Clears observations before each document, including documents that later fail a typed limit. */
    void begin() {
        observations.clear();
    }

    /** Adds raw decisions even when no OCR elements were emitted; absence never means accepted upright. */
    void describe(final Map<String, Object> entry) {
        if (estimator != null) {
            entry.put("orientation", observations.stream()
                                             .map(estimate
                                                     -> Map.of("correction", estimate.clockwiseDegrees(), "accepted",
                                                             estimate.accepted(), "raw_score", estimate.rawScore(),
                                                             "provenance", estimate.provenance()))
                                             .toList());
        }
    }
}
