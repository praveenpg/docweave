package dev.docweave.layout;

import java.io.IOException;
import java.util.List;

/**
 * Call-confined checked postprocessing strategy executed within managed admission and native lifecycle ownership.
 * @param <T> immutable or call-confined result type
 */
@FunctionalInterface
interface LayoutInference<T> {
    /**
     * Refines copied model objects; no native tensor handle or borrowed image is retained here.
     * @param objects validated thresholded source-pixel predictions
     * @return completed result
     * @throws IOException if topology, child inference or shared budgets fail
     */
    T finish(List<LayoutObject> objects) throws IOException;
}
