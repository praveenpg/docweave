package dev.docweave.partition;

import dev.docweave.core.ElementType;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded complete-layout validation and exact source-word ownership independent of a model runtime. */
final class PdfProviderTables {
    /** Shared bounded validation/assignment work. */
    private final PdfTableWork work = new PdfTableWork();
    /** Optional source-pixel cell coordinate writer. */
    private final PdfTableCells coordinates;
    /** Explicit continuation frame, independent of serialized cell coordinates. */
    private final PdfTableCells continuationFrame;

    /**
     * One staged source-token ownership claim, committed only after a nonempty table is emitted.
     * @param line source line index
     * @param word actual word index within that line
     */
    private record WordClaim(int line, int word) {}

    /**
     * Captures the source-to-displayed frame without another crop/rotation transform.
     * @param geometry displayed page geometry
     * @param image rendered raster supplying all provider coordinates
     * @param includeCells whether to emit provider anchor bounds
     */
    PdfProviderTables(final PdfPageGeometry geometry, final BufferedImage image, final boolean includeCells) {
        this(geometry, image, includeCells, null);
    }

    /**
     * Selects independent serialized geometry and explicit provider-continuation evidence.
     * @param geometry displayed page frame
     * @param image borrowed provider raster
     * @param includeCells whether to serialize anchor coordinates
     * @param continuationFrame optional original-frame signature writer; null disables matching
     */
    PdfProviderTables(final PdfPageGeometry geometry, final BufferedImage image, final boolean includeCells,
            final PdfTableCells continuationFrame) {
        this.continuationFrame = continuationFrame;
        coordinates = includeCells ? new PdfTableCells(geometry, geometry.width() / (double) image.getWidth(),
                                             geometry.height() / (double) image.getHeight())
                                   : null;
    }

    /**
     * Validates complete provider layouts before assigning actual source words to half-open anchor rectangles.
     * Unclaimed words remain in original source order; explicit CELLS mode refines authoritative cell text.
     * @param image borrowed rendered/native-masked raster
     * @param provider explicit thread-safe layout strategy
     * @param detailed validated actual words from one PAGE call
     * @param limits structured-table policy
     * @param options raw layout anchor ceiling
     * @param budget shared source/inserted-text policy
     * @param regions optional mixed-page exclusions
     * @param cellProvider caller-owned line strategy for explicit per-anchor refinement
     * @return accepted nonempty tables followed by ordinary unowned words
     * @throws IOException if provider, geometry, topology, budgets or cancellation fail
     */
    List<PdfOcrElement> recognize(final BufferedImage image, final PdfTableLayoutProvider provider,
            final List<PdfWordOcrProvider.WordLine> detailed, final TableLimits limits, final PdfOcrOptions options,
            final PdfOcrBudget budget, final PdfOcrSelection regions, final PdfOcrProvider cellProvider)
            throws IOException {
        final var layouts = new ArrayList<PdfTableLayoutProvider.Table>();
        for (final var candidate : PdfTableLayouts.locate(provider, image, limits, options, work)) {
            work.step();
            if (regions == null || regions.owns(line(candidate.bounds()))) {
                layouts.add(candidate);
            }
        }
        final var refinement = options.tableDetection() == PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS
                                       ? new PdfLayoutCellOcr(layouts, cellProvider, budget, options, limits, work)
                                       : null;
        final var owned = detailed.stream().map(line -> new boolean[line.words().size()]).toList();
        final var output = new ArrayList<PdfOcrElement>();
        for (final var layout : layouts) {
            work.step();
            final var envelope = line(layout.bounds());
            final var anchors = layout.anchors()
                                        .stream()
                                        .sorted(Comparator.comparingInt(PdfTableLayoutProvider.Anchor::row)
                                                        .thenComparingInt(PdfTableLayoutProvider.Anchor::column))
                                        .toList();
            final var values = anchors.stream().map(anchor -> new StringBuilder()).toList();
            final var points = new long[anchors.size()];
            int assigned = 0;
            final var claimed = new ArrayList<WordClaim>();
            for (int source = 0; source < detailed.size(); source++) {
                for (int word = 0; word < detailed.get(source).words().size(); word++) {
                    final var token = detailed.get(source).words().get(word);
                    final double x = token.left() + (token.right() - token.left()) / 2;
                    final double y = token.top() + (token.bottom() - token.top()) / 2;
                    for (int anchor = 0; anchor < anchors.size(); anchor++) {
                        work.step();
                        final var box = anchors.get(anchor).bounds();
                        if (x >= box.left() && x < box.right() && y >= box.top() && y < box.bottom()) {
                            final var value = values.get(anchor);
                            points[anchor] +=
                                    token.text().codePointCount(0, token.text().length()) + (value.isEmpty() ? 0 : 1);
                            if (points[anchor] > limits.maxCellCharacters()) {
                                throw limit("Provider PDF table cell exceeds maxCellCharacters");
                            }
                            if (!value.isEmpty()) {
                                value.append(' ');
                            }
                            value.append(token.text());
                            claimed.add(new WordClaim(source, word));
                            assigned++;
                            break;
                        }
                    }
                }
            }
            final var texts = refinement == null ? values.stream().map(StringBuilder::toString).toList()
                                                 : refinement.recognize(image, anchors, layout.bounds());
            final var nonempty = texts.stream().filter(value -> !value.isEmpty()).toList();
            if (nonempty.isEmpty()) {
                continue;
            }
            budget.reserveCharacters(refinement == null ? assigned - 1 : nonempty.size() - 1);
            for (final var claim : claimed) {
                owned.get(claim.line())[claim.word()] = true;
            }
            final var html = new StringBuilder("<table><tbody>");
            final var descriptions = new ArrayList<java.util.Map<String, Object>>();
            int index = 0;
            for (int row = 0; row < layout.rows(); row++) {
                work.step();
                html.append("<tr>");
                while (index < anchors.size() && anchors.get(index).row() == row) {
                    work.step();
                    final var anchor = anchors.get(index);
                    html.append("<td");
                    if (anchor.rowSpan() > 1) {
                        html.append(" rowspan=\"").append(anchor.rowSpan()).append('"');
                    }
                    if (anchor.columnSpan() > 1) {
                        html.append(" colspan=\"").append(anchor.columnSpan()).append('"');
                    }
                    html.append('>').append(HtmlTextEscaper.escape(texts.get(index))).append("</td>");
                    if (coordinates != null) {
                        final var box = anchor.bounds();
                        descriptions.add(coordinates.cell(anchor.row(), anchor.column(), anchor.rowSpan(),
                                anchor.columnSpan(), box.left(), box.top(), box.right(), box.bottom(), work));
                    }
                    index++;
                }
                html.append("</tr>");
            }
            final var metadata = new LinkedHashMap<String, Object>();
            metadata.put("text_as_html", html.append("</tbody></table>").toString());
            metadata.put(
                    "pdf_table_method", refinement == null ? "provider_layout_words_v1" : "provider_layout_cells_v1");
            metadata.put("pdf_table_layout_provenance", layout.provenance());
            if (layout.headerRows() > 0) {
                metadata.put("pdf_table_header_rows", layout.headerRows());
                metadata.put("pdf_table_header_policy", "provider_declared_prefix_v1");
                // Carry the same declaration into format-neutral chunking without inferring new header roles.
                final int chunkHeaders = PdfTableLayouts.chunkHeaderRows(layout, work);
                if (chunkHeaders > 0) {
                    metadata.put("table_leading_header_rows", chunkHeaders);
                }
            }
            if (refinement != null && options.cellContextPixels() > 0) {
                metadata.put("pdf_cell_ocr_context_pixels", options.cellContextPixels());
                metadata.put("pdf_cell_ocr_context_policy", "table_clamped_input_pixels_v1");
            }
            if (coordinates != null) {
                metadata.put("pdf_table_cells",
                        PdfTableCells.describe("provider_rectangles", layout.rows(), layout.columns(), descriptions));
            }
            output.add(new PdfOcrElement(ElementType.Table, String.join(" ", nonempty), envelope, metadata,
                    continuationFrame == null ? null
                                              : continuationFrame.providerSignature(layout, anchors, texts,
                                                        refinement == null ? "word_provider" : "cell_provider", work)));
        }
        output.addAll(PdfOcrTokens.remainders(detailed, owned, regions, work));
        return List.copyOf(output);
    }

    /**
     * Adapts provider bounds to the existing pixel-to-page emission frame.
     * @param box positive provider rectangle
     * @return rectangle-only pending OCR line
     */
    private static PdfOcrProvider.Line line(final PdfTableLayoutProvider.Box box) {
        return new PdfOcrProvider.Line("", box.left(), box.top(), box.right(), box.bottom());
    }

    /**
     * Creates a stable structured-table budget failure.
     * @param message content-free policy diagnostic
     * @return typed table limit
     */
    private static PartitionException limit(final String message) {
        return new PartitionException(PartitionException.Code.TABLE_LIMIT, message);
    }
}
