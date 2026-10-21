import dev.docweave.partition.PdfTableLayoutProvider;

import java.util.List;

/** Consumer deliberately compiled against the former five-argument API for a binary-linkage smoke check. */
public final class LegacyTableConsumer {
    /** Prevents utility construction. */
    private LegacyTableConsumer() {
    }

    /**
     * Uses only former constructor/accessors at compile time, then checks the new default reflectively.
     * @param args unused command-line arguments
     * @throws Exception if binary linkage or header-default compatibility fails
     */
    public static void main(final String[] args) throws Exception {
        final var box = new PdfTableLayoutProvider.Box(0, 0, 100, 100);
        final var table = new PdfTableLayoutProvider.Table(
                1, 1, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "legacy");
        if (table.rows() != 1 || table.columns() != 1 || table.anchors().size() != 1
                || !table.provenance().equals("legacy")
                || !table.getClass().getMethod("headerRows").invoke(table).equals(0)) {
            throw new AssertionError("Legacy provider constructor/accessors changed");
        }
        System.out.println("Legacy table consumer passed on " + Runtime.version());
    }
}
