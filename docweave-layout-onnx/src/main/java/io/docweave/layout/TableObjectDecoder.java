package io.docweave.layout;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PdfTableLayoutProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Strict native tensor schema, query-budget and finite-output validation before model object retention. */
final class TableObjectDecoder {
    /** Utility class. */
    private TableObjectDecoder() {
    }

    /**
     * Decodes one fixed-label TATR output in original source pixels without modifying native buffers.
     * Background queries remain budgeted/finite-checked; only selected positive clipped objects survive.
     * @param result caller-owned native result, closed after copying values
     * @param pixels original/resized frame metadata
     * @param options raw query/class threshold policy
     * @param deadline shared call budget
     * @param classCount internally selected three-label detection or seven-label structure schema
     * @return immutable thresholded objects in model query order
     * @throws IOException if shape, numeric output, budgets or cancellation fail
     */
    static List<LayoutObject> decode(final OrtSession.Result result, final LayoutPixels pixels,
            final OnnxTableOptions options, final LayoutDeadline deadline, final int classCount) throws IOException {
        final var logitsValue = result.get("logits").orElse(null);
        final var boxesValue = result.get("pred_boxes").orElse(null);
        if (!(logitsValue instanceof OnnxTensor logits) || !(boxesValue instanceof OnnxTensor boxes)) {
            throw invalid();
        }
        final var logitShape = ((TensorInfo) logits.getInfo()).getShape();
        final var boxShape = ((TensorInfo) boxes.getInfo()).getShape();
        if (logitShape.length != 3 || boxShape.length != 3 || logitShape[0] != 1 || boxShape[0] != 1
                || logitShape[1] < 1 || logitShape[1] != boxShape[1] || logitShape[2] != classCount || boxShape[2] != 4
                || ((TensorInfo) logits.getInfo()).type != ai.onnxruntime.OnnxJavaType.FLOAT
                || ((TensorInfo) boxes.getInfo()).type != ai.onnxruntime.OnnxJavaType.FLOAT) {
            throw invalid();
        }
        if (logitShape[1] > options.maxQueries()) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "ONNX table output exceeds maxQueries");
        }
        final var scores = logits.getFloatBuffer();
        final var bounds = boxes.getFloatBuffer();
        final var resultObjects = new ArrayList<LayoutObject>();
        final var classes = new double[classCount];
        for (int query = 0; query < logitShape[1]; query++) {
            deadline.remaining();
            int label = 0;
            double maximum = -Double.MAX_VALUE;
            for (int index = 0; index < classes.length; index++) {
                final float value = scores.get();
                if (!Float.isFinite(value)) {
                    throw invalid();
                }
                classes[index] = value;
                if (value > maximum) {
                    maximum = value;
                    label = index;
                }
            }
            double denominator = 0;
            for (final double value : classes) {
                denominator += Math.exp(value - maximum);
            }
            final double score = 1 / denominator;
            final double x = bounds.get();
            final double y = bounds.get();
            final double width = bounds.get();
            final double height = bounds.get();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)) {
                throw invalid();
            }
            if (label == classCount - 1 || score < options.minimumScore()) {
                continue;
            }
            if (x < 0 || x > 1 || y < 0 || y > 1 || width < 0 || width > 1 || height < 0 || height > 1) {
                throw invalid();
            }
            final double left = Math.max(0, (x - width / 2) * pixels.sourceWidth());
            final double top = Math.max(0, (y - height / 2) * pixels.sourceHeight());
            final double right = Math.min(pixels.sourceWidth(), (x + width / 2) * pixels.sourceWidth());
            final double bottom = Math.min(pixels.sourceHeight(), (y + height / 2) * pixels.sourceHeight());
            if (right > left && bottom > top) {
                resultObjects.add(
                        new LayoutObject(label, score, new PdfTableLayoutProvider.Box(left, top, right, bottom)));
            }
        }
        return List.copyOf(resultObjects);
    }

    /**
     * Creates a content-free native output contract failure.
     * @return typed invalid model output failure
     */
    private static PartitionException invalid() {
        return new PartitionException(PartitionException.Code.OCR_FAILURE, "Invalid ONNX table output tensor contract");
    }
}
