package io.docweave.partition;

import java.io.IOException;
import java.util.List;

/** Internal strategy boundary for native tables with explicitly inferred row and header-span geometry. */
interface PdfNativeTableDetector {
    /**
     * Recognizes disjoint candidates without changing rejected source glyphs.
     * @param glyphs native glyphs remaining after closed-grid ownership
     * @param segments painted source rules
     * @param images raster ambiguity barriers
     * @param work shared page work and cancellation budget
     * @return immutable accepted candidates
     * @throws IOException if cancelled or table/work limits fail
     */
    List<PdfInferredTable> detect(List<PdfPageText.Glyph> glyphs, List<PdfPageGraphics.Segment> segments,
            List<PdfPageGraphics.Image> images, PdfTableWork work) throws IOException;
}
