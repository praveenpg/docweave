package io.docweave.ocr;

import io.docweave.partition.PartitionException;
import io.docweave.partition.PdfOrientationEstimator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Set;

/** Strict bounded single-page orientation protocol parsing without retaining script or document text. */
final class TesseractOsd {
    /** Accepted keys from one Tesseract5 OSD page; unknown/duplicate/missing fields fail explicitly. */
    private static final Set<String> KEYS = Set.of(
            "Page number", "Orientation in degrees", "Rotate", "Orientation confidence", "Script", "Script confidence");

    /** Utility class. */
    private TesseractOsd() {
    }

    /**
     * Parses one orientation result and applies an explicit raw-score gate.
     * @param bytes bounded exclusively owned UTF-8 OSD stdout
     * @param options configured score/resource policy
     * @param deadline shared whole-estimate budget
     * @return accepted correction or explicit unchanged-page low-score observation
     * @throws IOException if encoding, protocol, numeric values, deadline or interruption is invalid
     */
    static PdfOrientationEstimator.Estimate parse(final byte[] bytes, final TesseractOrientationOptions options,
            final OcrDeadline deadline) throws IOException {
        deadline.remaining();
        final var text = StandardCharsets.UTF_8.newDecoder()
                                 .onMalformedInput(CodingErrorAction.REPORT)
                                 .onUnmappableCharacter(CodingErrorAction.REPORT)
                                 .decode(ByteBuffer.wrap(bytes))
                                 .toString();
        final var values = new HashMap<String, String>();
        for (final var line : text.lines().toList()) {
            deadline.remaining();
            if (line.isBlank()) {
                continue;
            }
            final int colon = line.indexOf(':');
            if (colon < 0 || values.size() >= KEYS.size()) {
                throw invalid();
            }
            final var key = line.substring(0, colon).strip();
            final var value = line.substring(colon + 1).strip();
            if (!KEYS.contains(key) || value.isEmpty() || values.putIfAbsent(key, value) != null) {
                throw invalid();
            }
        }
        if (!values.keySet().equals(KEYS)) {
            throw invalid();
        }
        try {
            final int page = Integer.parseInt(values.get("Page number"));
            final int orientation = Integer.parseInt(values.get("Orientation in degrees"));
            final int correction = Integer.parseInt(values.get("Rotate"));
            final double score = Double.parseDouble(values.get("Orientation confidence"));
            final double scriptScore = Double.parseDouble(values.get("Script confidence"));
            if (page != 0 || orientation < 0 || orientation > 270 || orientation % 90 != 0 || correction < 0
                    || correction > 270 || correction % 90 != 0 || (orientation + correction) % 360 != 0
                    || !Double.isFinite(score) || score < 0 || !Double.isFinite(scriptScore) || scriptScore < 0) {
                throw invalid();
            }
            final boolean accepted = score >= options.minimumOrientationScore();
            deadline.remaining();
            return new PdfOrientationEstimator.Estimate(
                    accepted ? correction : 0, accepted, score, provenance(options));
        } catch (final NumberFormatException failure) {
            throw invalid();
        }
    }

    /**
     * Describes the fixed OSD protocol/decision policy without script names or source content.
     * @param options immutable raw-score gate
     * @return bounded provenance, not a model identity or calibrated probability
     */
    static String provenance(final TesseractOrientationOptions options) {
        return "tesseract_osd_score_gate_v1;minimum=" + options.minimumOrientationScore();
    }

    /**
     * Constructs an output-contract failure without exposing native stdout or stderr.
     * @return typed malformed orientation output error
     */
    private static PartitionException invalid() {
        return new PartitionException(PartitionException.Code.OCR_FAILURE, "Invalid Tesseract orientation output");
    }
}
