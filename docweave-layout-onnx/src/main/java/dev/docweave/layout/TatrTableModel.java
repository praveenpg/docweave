package dev.docweave.layout;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import dev.docweave.partition.PartitionException;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Existing TATR tensor contract and preprocessing, separated from native lifetime management. */
final class TatrTableModel implements TableModel<LayoutPixels> {
    /** Fixed label count, including background. */
    private final int classes;

    /**
     * Selects the existing detection or structure tensor schema.
     * @param classes three detection or seven structure labels
     */
    TatrTableModel(final int classes) {
        this.classes = classes;
    }

    @Override
    public void validate(final OrtSession session) throws IOException, OrtException {
        if (!session.getInputNames().equals(Set.of("pixel_values", "pixel_mask"))
                || !session.getOutputNames().equals(Set.of("logits", "pred_boxes"))) {
            throw invalid("Unsupported ONNX tensor names");
        }
        final var inputs = session.getInputInfo();
        final var outputs = session.getOutputInfo();
        tensor(inputs.get("pixel_values").getInfo(), OnnxJavaType.FLOAT, 1, 3, -1, -1);
        tensor(inputs.get("pixel_mask").getInfo(), OnnxJavaType.INT64, 1, -1, -1);
        tensor(outputs.get("logits").getInfo(), OnnxJavaType.FLOAT, 1, -1, classes);
        tensor(outputs.get("pred_boxes").getInfo(), OnnxJavaType.FLOAT, 1, -1, 4);
    }

    @Override
    public LayoutPixels prepare(final BufferedImage image, final OnnxTableOptions options,
            final LayoutDeadline deadline) throws IOException {
        return new BilinearTableImageTransform().prepare(image, options, deadline);
    }

    @Override
    public List<LayoutObject> infer(final OrtEnvironment environment, final OrtSession session,
            final LayoutPixels pixels, final OnnxTableOptions options, final LayoutDeadline deadline,
            final LayoutWork work, final OrtSession.RunOptions run) throws IOException, OrtException {
        try (final var values = OnnxTensor.createTensor(
                     environment, pixels.values(), new long[] {1, 3, pixels.height(), pixels.width()});
                final var mask = OnnxTensor.createTensor(
                        environment, pixels.mask(), new long[] {1, pixels.height(), pixels.width()});
                final var result = session.run(Map.of("pixel_values", values, "pixel_mask", mask), run)) {
            deadline.remaining();
            return TableObjectDecoder.decode(result, pixels, options, deadline, classes);
        }
    }

    /**
     * Preserves existing fixed/dynamic TATR metadata validation.
     * @param value native tensor metadata
     * @param type required primitive representation
     * @param expected fixed dimensions or negative dynamic dimensions
     * @throws IOException if metadata is incompatible
     */
    private static void tensor(final Object value, final OnnxJavaType type, final long... expected) throws IOException {
        if (!(value instanceof TensorInfo info) || info.type != type || info.getShape().length != expected.length) {
            throw invalid("Unsupported ONNX tensor schema");
        }
        final var shape = info.getShape();
        for (int index = 0; index < shape.length; index++) {
            if (shape[index] == 0 || expected[index] >= 0 && shape[index] > 0 && shape[index] != expected[index]) {
                throw invalid("Unsupported ONNX tensor dimensions");
            }
        }
    }

    /**
     * Creates an unchanged content-free tensor contract failure.
     * @param message bounded model diagnostic
     * @return typed recognition failure
     */
    private static PartitionException invalid(final String message) {
        return new PartitionException(PartitionException.Code.OCR_FAILURE, message);
    }
}
