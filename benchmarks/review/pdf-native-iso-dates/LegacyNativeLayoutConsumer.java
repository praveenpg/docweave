import dev.docweave.partition.PdfNativeLayoutOptions;

/** Consumer compiled against the original three-component record, then run against the updated artifact. */
public final class LegacyNativeLayoutConsumer {
    /** Prevents construction. */
    private LegacyNativeLayoutConsumer() {
    }

    /**
     * Exercises retained binary constructor/accessor descriptors without referring to the new component.
     * @param args unused
     */
    public static void main(final String[] args) {
        final var policy = new PdfNativeLayoutOptions(72, 40_000, 16);
        if (policy.dpi() != 72 || policy.maxRenderPixels() != 40_000 || policy.maxTableAnchors() != 16
                || !policy.equals(new PdfNativeLayoutOptions(72, 40_000, 16))) {
            throw new IllegalStateException("Legacy native layout contract changed");
        }
    }
}
