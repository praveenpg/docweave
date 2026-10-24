package io.docweave.ocr;

import io.docweave.partition.PdfOrientationEstimator;

import java.util.Objects;

/**
 * Explicit managed installed Tesseract5 orientation-only estimator. Models are caller-installed;
 * no process starts during construction and no service discovery enables orientation silently.
 * A raw OSD score gate can abstain but does not establish calibrated confidence or language accuracy.
 */
public interface TesseractOrientation extends PdfOrientationEstimator {
    /**
     * Creates a hidden thread-safe estimator with its own bounded process admission.
     * @param options installed executable/model, resource and raw-score policy
     * @return reusable caller-owned strategy with no retained process between calls
     * @throws NullPointerException if options is null
     */
    static TesseractOrientation create(final TesseractOrientationOptions options) {
        return new DefaultTesseractOrientation(Objects.requireNonNull(options, "options"));
    }
}
