package io.docweave.layout;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** Typed TATR facade retaining the internal diagnostic entrypoint over shared native-session ownership. */
final class ManagedTatrSession implements AutoCloseable {
    /** Owned managed session with the existing TATR tensor strategy. */
    private final ManagedTableSession<LayoutPixels> delegate;

    /**
     * Initializes the existing TATR detection or structure schema without network access.
     * @param options explicit local graph identity and resource policy
     * @param classes detection or structure label count including background
     * @throws IOException if graph identity, schema or native initialization fails
     */
    ManagedTatrSession(final OnnxTableOptions options, final int classes) throws IOException {
        delegate = new ManagedTableSession<>(options, new TatrTableModel(classes));
    }

    /**
     * Runs existing preprocessing and inference under shared managed ownership.
     * @param <T> call-confined result type
     * @param image borrowed original raster
     * @param deadline total pipeline deadline
     * @param finishing bounded postprocessing receiving copied source-frame objects
     * @return completed result
     * @throws IOException if inference, lifecycle or budgets fail
     */
    <T> T infer(final BufferedImage image, final LayoutDeadline deadline, final LayoutInference<T> finishing)
            throws IOException {
        return delegate.infer(image, deadline, finishing);
    }

    /**
     * Releases an initialized but unpublished sibling after transactional constructor failure.
     * @throws IOException if native cleanup fails
     */
    void discardUnpublished() throws IOException {
        delegate.discardUnpublished();
    }

    /**
     * Prevents further calls and closes after active calls leave native ownership.
     * @throws IOException if interrupted or native cleanup fails
     */
    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
