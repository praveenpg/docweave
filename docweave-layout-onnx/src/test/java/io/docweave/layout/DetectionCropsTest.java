package io.docweave.layout;

import io.docweave.partition.PartitionException;
import io.docweave.partition.PdfTableLayoutProvider.Box;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DetectionCropsTest {
    private static OnnxTableOptions options() {
        return OnnxTableOptions.defaults(Path.of("unused"), "0".repeat(64));
    }

    private static LayoutWork work() {
        return new LayoutWork(new LayoutDeadline(Duration.ofSeconds(1)));
    }

    @Test
    void rotatedOnlyDetectionsRemainUnclaimedAndPartiallyOverlappingTablesAreAmbiguous() throws Exception {
        final var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        assertTrue(
                DetectionCrops.plan(List.of(new LayoutObject(1, .99, new Box(0, 0, 60, 60))), image, options(), work())
                        .isEmpty());
        assertTrue(DetectionCrops
                        .plan(List.of(new LayoutObject(0, .99, new Box(0, 0, 60, 60)),
                                      new LayoutObject(0, .98, new Box(40, 40, 100, 100))),
                                image, options(), work())
                        .isEmpty());
    }

    @Test
    void rawPositiveCapPrecedesDuplicateSuppressionAndChildPixelCeilingPrecedesCopy() {
        final var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        final var object = new LayoutObject(0, .99, new Box(0, 0, 100, 100));
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        () -> DetectionCrops.plan(Collections.nCopies(33, object), image, options(), work()))
                        .code());
        final var base = options();
        final var capped = new OnnxTableOptions(base.model(), base.sha256(), base.timeout(), 1, base.maxModelBytes(),
                100, base.maxQueries(), base.tableLimits(), .5, 1);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(
                        PartitionException.class, () -> DetectionCrops.plan(List.of(object), image, capped, work()))
                        .code());
    }
}
