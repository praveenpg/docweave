package io.docweave.ocr;

/**
 * Explicit Tesseract5 internal image binarization, independent of page/cell segmentation.
 * These fixed trusted arguments do not mutate the borrowed image or change its coordinate frame.
 * Adaptive methods can remove meaningful strokes or amplify noise; none guarantees better recognition.
 * Executable/model availability, deadlines and resource limits remain controlled by TesseractOptions.
 */
public enum TesseractThresholding {
    /** Omits the override and preserves the configured engine/model default; historical factory behavior. */
    ENGINE_DEFAULT,
    /** Explicit global Otsu binarization, thresholding_method=0. */
    OTSU,
    /** Explicit Leptonica tiled adaptive Otsu binarization, thresholding_method=1. */
    ADAPTIVE_OTSU,
    /** Explicit local Sauvola binarization with the configured engine's window/factor defaults, method2. */
    SAUVOLA;

    /**
     * Supplies only a fixed supported engine parameter, never document-controlled command arguments.
     * @return numeric thresholding method, or null when the engine default must remain untouched
     */
    String argument() {
        return switch (this) {
            case ENGINE_DEFAULT -> null;
            case OTSU -> "0";
            case ADAPTIVE_OTSU -> "1";
            case SAUVOLA -> "2";
        };
    }
}
