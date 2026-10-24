package io.docweave.partition;

import java.io.IOException;
import java.util.List;

/** Page-confined native caption classification strategy; implementations never generate source text. */
interface PdfFigureCaptionDetection {
    /**
     * Chooses an explicit reusable stateless strategy.
     * @param policy validated native caption policy
     * @return strategy without parsing, rendering or provider discovery
     */
    static PdfFigureCaptionDetection select(final PdfOptions.FigureCaptions policy) {
        return switch (policy) {
            case NONE -> (items, work) -> items;
            case IMAGE_ADJACENT_LABELS -> ImageAdjacentPdfFigureCaptions.INSTANCE;
            case IMAGE_ADJACENT_HORIZONTAL_LABELS -> ImageAdjacentPdfFigureCaptions.HORIZONTAL_INSTANCE;
            case IMAGE_ADJACENT_NUMBERED_TITLES -> ImageAdjacentPdfFigureCaptions.TITLE_INSTANCE;
            case IMAGE_EDGE_ALIGNED_NUMBERED_TITLES -> ImageAdjacentPdfFigureCaptions.EDGE_TITLE_INSTANCE;
        };
    }

    /**
     * Classifies whole existing native units without moving, joining, dropping or duplicating output.
     * @param items complete page output in its selected native order
     * @param work shared page geometry/cancellation budget
     * @return original sequence or a same-size sequence with explicit caption types/provenance
     * @throws IOException if cancellation or page work limits fail
     */
    List<PdfLayoutItem> apply(List<PdfLayoutItem> items, PdfTableWork work) throws IOException;
}
