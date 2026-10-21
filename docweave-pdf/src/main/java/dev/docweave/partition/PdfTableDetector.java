package dev.docweave.partition;

import java.io.IOException;
import java.util.List;

/** Internal strategy contract for recognizing source-backed table topology without owning text. */
interface PdfTableDetector {
    /**
     * Recognizes non-overlapping tables from stroked page geometry.
     * @param segments crop-relative path segments
     * @param work shared per-page geometry/ownership budget
     * @return validated tables, with rejected geometry left available as ordinary text
     * @throws IOException if cancelled or a table/work limit is exceeded
     */
    List<PdfTableGrid> detect(final List<PdfPageGraphics.Segment> segments, final PdfTableWork work) throws IOException;
}
