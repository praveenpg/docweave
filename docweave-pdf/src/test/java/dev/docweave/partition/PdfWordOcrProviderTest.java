package dev.docweave.partition;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfWordOcrProviderTest {
    @Test
    void completeTokensAreImmutableAndProjectThroughExistingApi() throws Exception {
        final var line = new PdfOcrProvider.Line("A B", 1, 2, 20, 12);
        final var words = new ArrayList<>(List.of(
                new PdfWordOcrProvider.Word("A", 1, 2, 5, 12), new PdfWordOcrProvider.Word("B", 15, 2, 20, 12)));
        final var detailed = new PdfWordOcrProvider.WordLine(line, words);
        words.clear();
        assertEquals(2, detailed.words().size());
        assertThrows(UnsupportedOperationException.class, () -> detailed.words().clear());
        final var purposes = new ArrayList<PdfOcrProvider.InputKind>();
        final PdfWordOcrProvider provider = (image, kind) -> {
            purposes.add(kind);
            return List.of(detailed);
        };
        final var raster = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        assertEquals(List.of(line), provider.recognize(raster));
        assertEquals(List.of(line), provider.recognize(raster, PdfOcrProvider.InputKind.TABLE_CELL));
        assertEquals(List.of(PdfOcrProvider.InputKind.PAGE, PdfOcrProvider.InputKind.TABLE_CELL), purposes);
        assertThrows(NullPointerException.class, () -> provider.recognize(raster, null));
    }

    @Test
    void invalidOrIncompleteGeometryAndTextCannotBecomeOwnershipEvidence() {
        final var line = new PdfOcrProvider.Line("A", 1, 2, 20, 12);
        assertThrows(IllegalArgumentException.class, () -> new PdfWordOcrProvider.WordLine(line, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PdfWordOcrProvider.WordLine(line, List.of(new PdfWordOcrProvider.Word("B", 1, 2, 5, 12))));
        assertThrows(IllegalArgumentException.class,
                () -> new PdfWordOcrProvider.WordLine(line, List.of(new PdfWordOcrProvider.Word("A", 0, 2, 5, 12))));
        for (final double edge : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1, 5}) {
            assertThrows(IllegalArgumentException.class, () -> new PdfWordOcrProvider.Word("A", edge, 2, 5, 12));
        }
        assertThrows(IllegalArgumentException.class, () -> new PdfWordOcrProvider.Word(" ", 1, 2, 5, 12));
        assertThrows(NullPointerException.class, () -> new PdfWordOcrProvider.Word(null, 1, 2, 5, 12));
    }
}
