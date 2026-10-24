package io.docweave.layout;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import io.docweave.partition.PartitionException;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit fixed exported YOLOX detector contract; native resources are owned by the shared session. */
final class YoloXTableModel implements TableModel<YoloXPixels> {
    /** Creates an immutable tensor/preprocessing strategy. */
    YoloXTableModel() {
    }

    @Override
    public void validate(final OrtSession session) throws IOException, OrtException {
        if (!session.getInputNames().equals(Set.of("images")) || !session.getOutputNames().equals(Set.of("output"))) {
            throw invalid();
        }
        tensor(session.getInputInfo().get("images").getInfo(), 1, 3, YoloXImageTransform.HEIGHT,
                YoloXImageTransform.WIDTH);
        tensor(session.getOutputInfo().get("output").getInfo(), 1, YoloXObjectDecoder.QUERIES, 16);
    }

    @Override
    public YoloXPixels prepare(final BufferedImage image, final OnnxTableOptions options, final LayoutDeadline deadline)
            throws IOException {
        return YoloXImageTransform.prepare(image, options, deadline);
    }

    @Override
    public List<LayoutObject> infer(final OrtEnvironment environment, final OrtSession session,
            final YoloXPixels pixels, final OnnxTableOptions options, final LayoutDeadline deadline,
            final LayoutWork work, final OrtSession.RunOptions run) throws IOException, OrtException {
        try (final var values = OnnxTensor.createTensor(environment, pixels.values(),
                     new long[] {1, 3, YoloXImageTransform.HEIGHT, YoloXImageTransform.WIDTH});
                final var result = session.run(Map.of("images", values), run)) {
            final var output = result.get("output").orElse(null);
            if (!(output instanceof OnnxTensor tensor)) {
                throw invalid();
            }
            tensor(tensor.getInfo(), 1, YoloXObjectDecoder.QUERIES, 16);
            deadline.remaining();
            return YoloXObjectDecoder.decode(tensor.getFloatBuffer(), pixels, options, deadline, work);
        }
    }

    /**
     * Requires exact fixed dimensions and primitive type, including after native execution.
     * @param value native tensor metadata
     * @param expected fixed exported dimensions
     * @throws IOException if metadata violates the supported graph contract
     */
    private static void tensor(final Object value, final long... expected) throws IOException {
        if (!(value instanceof TensorInfo info) || info.type != OnnxJavaType.FLOAT
                || !Arrays.equals(info.getShape(), expected)) {
            throw invalid();
        }
    }

    /**
     * Creates a content-free tensor contract failure.
     * @return typed recognition failure
     */
    private static PartitionException invalid() {
        return new PartitionException(PartitionException.Code.OCR_FAILURE, "Unsupported YOLOX tensor schema");
    }
}
