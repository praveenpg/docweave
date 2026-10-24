package io.docweave.layout;

import io.docweave.partition.PdfTableLayoutProvider;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/** Single-image structure strategy owning one managed native session. */
final class DefaultOnnxTableLayout implements OnnxTableLayout {
    /** Immutable identity and resource policy. */
    private final OnnxTableOptions options;
    /** Owned seven-label structure session. */
    private final ManagedTatrSession session;

    /**
     * Verifies and loads the explicit local structure graph.
     * @param options caller-selected trusted graph identity and budgets
     * @throws IOException if native loading, identity or schema fails
     */
    DefaultOnnxTableLayout(final OnnxTableOptions options) throws IOException {
        this.options = options;
        session = new ManagedTatrSession(options, 7);
    }

    /**
     * Recognizes complete source-frame geometry for a borrowed table image.
     * @param image caller-owned raster
     * @return immutable nonambiguous layouts
     * @throws IOException if inference, geometry, lifecycle or resource checks fail
     */
    @Override
    public List<PdfTableLayoutProvider.Table> locate(final BufferedImage image) throws IOException {
        final var deadline = new LayoutDeadline(options.timeout());
        return session.infer(image, deadline, objects -> new TableTopology(options, deadline).tables(objects));
    }

    /**
     * Releases the owned session after active inference and postprocessing leave.
     * @throws IOException if interruption or native cleanup prevents closure
     */
    @Override
    public void close() throws IOException {
        session.close();
    }
}
