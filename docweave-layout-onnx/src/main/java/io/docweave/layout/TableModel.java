package io.docweave.layout;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/**
 * Internal tensor strategy, independent of managed admission and native-session lifetime.
 * @param <P> call-confined pixels; never retains the borrowed source image
 */
interface TableModel<P> {
    /**
     * Validates model metadata before the owning session is published.
     * @param session newly created trusted local model session
     * @throws IOException if the supported tensor contract is violated
     * @throws OrtException if native metadata cannot be read
     */
    void validate(OrtSession session) throws IOException, OrtException;

    /**
     * Prepares bounded pixels before native lifecycle admission.
     * @param image borrowed unchanged source raster
     * @param options caller-selected input/output limits
     * @param deadline existing total pipeline deadline
     * @return owned call-local prepared pixels
     * @throws IOException if input budgets, cancellation or preprocessing fail
     */
    P prepare(BufferedImage image, OnnxTableOptions options, LayoutDeadline deadline) throws IOException;

    /**
     * Releases all native tensors after copying validated source-frame objects.
     * @param environment borrowed process environment
     * @param session session held under the owner's lifecycle read lock
     * @param pixels call-confined prepared input
     * @param options caller-selected output limits
     * @param deadline shared total pipeline deadline
     * @param work shared detector/crop/topology comparison budget
     * @param run managed cooperative native cancellation handle
     * @return immutable copied objects without native handles
     * @throws IOException if output contracts, budgets or cancellation fail
     * @throws OrtException if tensor construction or execution fails
     */
    List<LayoutObject> infer(OrtEnvironment environment, OrtSession session, P pixels, OnnxTableOptions options,
            LayoutDeadline deadline, LayoutWork work, OrtSession.RunOptions run) throws IOException, OrtException;
}
