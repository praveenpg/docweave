import dev.docweave.layout.OnnxTableLayout;
import dev.docweave.layout.OnnxTableOptions;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PdfTableLayoutProvider;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

/** Standalone native inference and ownership smoke check using an owned constant-output graph. */
public final class OnnxConsumer {
    /** Prevents construction of this executable smoke check. */
    private OnnxConsumer() {
    }

    /**
     * Loads an explicitly supplied pinned synthetic graph through the published managed interface.
     * @param args path to the repository's owned fixed-table graph
     * @throws Exception if inference, geometry, identity rejection or closure checks fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected owned fixed-table graph path");
        }
        final var model = Path.of(args[0]);
        final var options =
                OnnxTableOptions.defaults(model, "13d88527dfc712e644b1f2251907d891a25d0b64e42326251e4745be67fee203");
        try {
            OnnxTableLayout.create(OnnxTableOptions.defaults(model, "0".repeat(64)));
            throw new AssertionError("Mismatched graph identity accepted");
        } catch (final PartitionException expected) {
            // The owned graph is intentionally supplied with an incorrect identity before native inference.
        }
        final var image = new BufferedImage(300, 180, BufferedImage.TYPE_INT_RGB);
        final var managed = OnnxTableLayout.create(options);
        final PdfTableLayoutProvider provider = managed;
        try (managed) {
            // FLOAT graph coordinates round during normalization; compare source pixels within a millipixel.
            final var tables = provider.locate(image);
            if (tables.size() != 1) {
                throw new AssertionError("Expected one synthetic table");
            }
            final var table = tables.getFirst();
            if (table.rows() != 3 || table.columns() != 2 || table.anchors().size() != 5
                    || table.anchors().getFirst().columnSpan() != 2 || Math.abs(table.bounds().left() - 20) > .001
                    || Math.abs(table.bounds().bottom() - 80) > .001
                    || !table.provenance().contains(options.sha256())) {
                throw new AssertionError("Synthetic geometry/provenance mismatch: " + table);
            }
            if (image.getRGB(0, 0) != 0xff000000) {
                throw new AssertionError("Borrowed raster modified");
            }
        } finally {
            image.flush();
        }
        try {
            provider.locate(image);
            throw new AssertionError("Closed provider accepted inference");
        } catch (final PartitionException expected) {
            // The consumer owns the managed session; use after close must fail through the public API.
        }
        System.out.println("ONNX native geometry, identity and managed lifecycle passed on " + Runtime.version());
    }
}
