package dev.docweave.partition;

import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Page-confined rendering, provider invocation and validated pixel-to-user-space conversion. */
final class PdfPageOcr {
    /** Utility class. */
    private PdfPageOcr() {
    }

    /**
     * Recognizes a page and emits provider-ordered lines. The raster is always released after use.
     * @param document open caller-owned document
     * @param index zero-based page index
     * @param geometry displayed crop frame
     * @param context bounded emission sink
     * @param options rendering/output policy
     * @param provider caller-owned recognition strategy
     * @param tableLimits structured-table geometry and field limits
     * @param includeCellCoordinates whether to include displayed anchor-cell geometry
     * @param tableLayout optional complete source-pixel table geometry strategy
     * @param orientation optional caller-owned bounded quarter-turn estimator
     * @param cellProvider caller-owned TABLE_CELL recognizer, sharing the page's aggregate output budgets
     * @param regions optional mixed-page ownership/masking policy; null retains whole-page OCR
     * @throws IOException if rendering, recognition, limits, cancellation or emission fails
     */
    static void emit(final PDDocument document, final int index, final PdfPageGeometry geometry,
            final PdfPageOutput context, final PdfOcrOptions options, final PdfOcrProvider provider,
            final TableLimits tableLimits, final boolean includeCellCoordinates,
            final PdfTableLayoutProvider tableLayout, final PdfOrientationEstimator orientation,
            final PdfOcrProvider cellProvider, final PdfOcrRegions regions) throws IOException {
        ElementCollector.checkInterrupted();
        if (regions != null && regions.isEmpty()) {
            context.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "No in-crop image region was available for mixed-page OCR");
            return;
        }
        final double scale = options.dpi() / 72.0;
        final double width = Math.ceil(geometry.width() * scale);
        final double height = Math.ceil(geometry.height() * scale);
        if (width < 1 || height < 1 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE
                || width * height > options.maxRenderPixels()) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "PDF OCR exceeds maxRenderPixels");
        }
        final var image = new PDFRenderer(document).renderImageWithDPI(index, options.dpi(), ImageType.RGB);
        java.awt.image.BufferedImage masked = null;
        try {
            if ((long) image.getWidth() * image.getHeight() > options.maxRenderPixels()) {
                throw new PartitionException(PartitionException.Code.OCR_LIMIT, "PDF OCR exceeds maxRenderPixels");
            }
            ElementCollector.checkInterrupted();
            if (regions != null) {
                masked = regions.mask(image);
            }
            final boolean layoutMode =
                    options.tableDetection() == PdfOcrOptions.TableDetection.PROVIDER_LAYOUT
                    || options.tableDetection() == PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS;
            if ((options.tableDetection() == PdfOcrOptions.TableDetection.ALIGNED_WORDS || layoutMode)
                    && !(provider instanceof PdfWordOcrProvider)) {
                throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                        "Word-driven OCR tables require PdfWordOcrProvider");
            }
            if (layoutMode && tableLayout == null) {
                throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                        "Provider-layout OCR tables require PdfTableLayoutProvider");
            }
            try (final var frame = new PdfOcrFrame(masked == null ? image : masked, orientation)) {
                final var input = frame.image();
                final var upright = geometry.upright(frame.correction());
                final var selection = frame.selection(regions);
                final var budget = new PdfOcrBudget(options);
                final var cellMetadata =
                        includeCellCoordinates || context.continuations()
                                ? new PdfTableCells(upright, upright.width() / (double) input.getWidth(),
                                          upright.height() / (double) input.getHeight(),
                                          context.providerHeaderPrefixes(), context.providerHeaderLayouts())
                                : null;
                final var tables =
                        options.tableDetection() == PdfOcrOptions.TableDetection.RULED
                                ? new PdfScannedTables(includeCellCoordinates ? cellMetadata : null,
                                          context.continuations() && frame.correction() == 0 ? cellMetadata : null)
                                : null;
                if (tables != null) {
                    for (final var table : tables.recognize(input, provider, budget, options, tableLimits, selection)) {
                        emitElement(table, context, index, geometry, options, image.getWidth(), image.getHeight(),
                                regions, frame);
                    }
                }
                if (options.tableDetection() == PdfOcrOptions.TableDetection.ALIGNED_WORDS || layoutMode) {
                    final var words = (PdfWordOcrProvider) provider;
                    final var detailed = budget.recognizeWords(words, input);
                    final List<PdfOcrElement> elements;
                    if (layoutMode) {
                        elements = new PdfProviderTables(upright, input, includeCellCoordinates,
                                context.providerContinuations() && frame.correction() == 0 ? cellMetadata : null)
                                           .recognize(input, tableLayout, detailed, tableLimits, options, budget,
                                                   selection, cellProvider);
                    } else {
                        elements = new PdfScannedWordTables(upright, input, includeCellCoordinates)
                                           .detect(input, detailed, tableLimits, options, budget, selection);
                    }
                    for (final var element : elements) {
                        emitElement(element, context, index, geometry, options, image.getWidth(), image.getHeight(),
                                regions, frame);
                    }
                } else {
                    final var lines = budget.recognize(provider, input);
                    for (final var line : lines) {
                        ElementCollector.checkInterrupted();
                        if (line.text().isBlank() || (tables != null && !tables.retain(line))
                                || (selection != null && !selection.retain(line))) {
                            continue;
                        }
                        emitElement(new PdfOcrElement(ElementType.UncategorizedText, line.text(), line, Map.of()),
                                context, index, geometry, options, image.getWidth(), image.getHeight(), regions, frame);
                    }
                }
            }
            if (regions != null) {
                regions.finish(context);
            }
        } finally {
            if (masked != null) {
                masked.flush();
            }
            image.flush();
        }
    }

    /**
     * Converts pending pixel bounds into the shared displayed crop coordinate frame.
     * @param element pending ordinary or table output
     * @param context bounded sink
     * @param index zero-based page
     * @param geometry displayed page geometry
     * @param options OCR metadata policy
     * @param width actual source raster width
     * @param height actual source raster height
     * @param regions optional original-frame mixed-page selection metadata
     * @param frame input orientation and inverse coordinate policy
     * @throws IOException if emission or cancellation fails
     */
    private static void emitElement(final PdfOcrElement element, final PdfPageOutput context, final int index,
            final PdfPageGeometry geometry, final PdfOcrOptions options, final int width, final int height,
            final PdfOcrRegions regions, final PdfOcrFrame frame) throws IOException {
        final var metadata = new LinkedHashMap<String, Object>(element.metadata());
        metadata.put("page_number", index + 1);
        metadata.put("pdf_text_method", "ocr");
        metadata.put("pdf_ocr_dpi", options.dpi());
        geometry.describe(metadata);
        if (regions != null) {
            regions.describe(metadata);
        } else if (options.selection() == PdfOcrOptions.Selection.ALL_PAGES) {
            metadata.put("pdf_ocr_selection", "all_pages_v1");
        }
        frame.describe(metadata, geometry);
        final var box = frame.toSource(element.box());
        final double xScale = geometry.width() / (double) width;
        final double yScale = geometry.height() / (double) height;
        final double left = box.left() * xScale;
        final double right = box.right() * xScale;
        final double top = box.top() * yScale;
        final double bottom = box.bottom() * yScale;
        metadata.put("coordinates", Map.of("system", "PDFUserSpace", "layout_width", (double) geometry.width(),
                                            "layout_height", (double) geometry.height(), "points",
                                            List.of(List.of(left, top), List.of(right, top), List.of(right, bottom),
                                                    List.of(left, bottom))));
        context.emit(element.type(), element.text(), metadata, element.signature());
    }
}
