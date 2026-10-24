package io.docweave.partition;

import java.io.IOException;
import java.util.List;

/** Internal recognition strategy for explicitly enabled native aligned-text tables. */
interface PdfBorderlessTableDetector {
    /**
     * Recognizes non-overlapping source-run groups without altering or copying source text.
     * @param runs ordinary text after ruled-table ownership
     * @param segments painted source line segments used as ambiguity barriers
     * @param images painted raster placements used as ambiguity barriers
     * @param work shared per-page recognition budget
     * @return accepted candidates; every rejected run remains available as ordinary text
     * @throws IOException if cancelled or a table/work budget fails
     */
    List<PdfAlignedTable> detect(final List<PdfTextRun> runs, final List<PdfPageGraphics.Segment> segments,
            final List<PdfPageGraphics.Image> images, final PdfTableWork work) throws IOException;
}
