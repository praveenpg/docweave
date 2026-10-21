import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.TableLimits;

/** Checks the beta1 full constructor after compiling against the published beta1 API. */
public final class LegacyPdfOptionsConsumer {
    /** Prevents construction of this command-line compatibility probe. */
    private LegacyPdfOptionsConsumer() {
    }

    /**
     * Constructs the historical twelve-argument record and compares unchanged defaults.
     * @param args unused
     */
    public static void main(final String[] args) {
        final var legacy = new PdfOptions(1000, 250_000, 100_000, 16_000_000, 16 * 1024 * 1024, false,
                TableLimits.defaults(), PdfOptions.TableDetection.RULED_ONLY, PdfOptions.TextLayout.LINES, false,
                PdfOptions.TableContinuation.NONE, PdfOptions.UnicodeNormalization.RAW);
        if (!legacy.equals(PdfOptions.defaults())) {
            throw new AssertionError("Historical native PDF defaults changed");
        }
        System.out.println("Beta1 full PdfOptions constructor links and preserves defaults");
    }
}
