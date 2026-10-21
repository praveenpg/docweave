package dev.docweave.partition;

import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Page-confined native glyph ownership using validated complete provider geometry and bounded rendering. */
final class PdfNativeProviderTables {
    /**
     * Accepted tables and the unchanged remaining native source sequence.
     * @param tables immutable pending tables; no ordinary text duplication
     * @param remaining immutable unclaimed glyphs in original order
     */
    record Result(List<PdfLayoutItem> tables, List<PdfPageText.Glyph> remaining) {}

    /** Utility type. */
    private PdfNativeProviderTables() {
    }

    /**
     * Renders one native-text page, invokes the borrowed provider once, and assigns native glyphs.
     * Empty tables claim nothing; rejected/absent layouts leave ordinary native text intact.
     * @param document call-confined open source
     * @param index zero-based physical page
     * @param geometry shared displayed frame
     * @param glyphs original quarter-turn native positions
     * @param options native extraction/table policy
     * @param policy explicit render/anchor budgets
     * @param provider caller-owned complete geometry strategy
     * @return accepted tables and unclaimed source glyphs
     * @throws IOException if unsupported transforms, rendering, provider validation, budgets or cancellation fail
     */
    static Result extract(final PDDocument document, final int index, final PdfPageGeometry geometry,
            final List<PdfPageText.Glyph> glyphs, final PdfOptions options, final PdfNativeLayoutOptions policy,
            final PdfTableLayoutProvider provider) throws IOException {
        return extract(document, index, geometry, glyphs, options, policy, provider,
                new PdfPageLayout.NativeStage(List.of(), glyphs, 0), new PdfTableWork());
    }

    /**
     * Validates every raw provider candidate before rejecting overlap with accepted native tables.
     * Native ownership is supplied explicitly rather than reconstructed from table envelopes.
     * @param document call-confined source
     * @param index zero-based physical page
     * @param geometry original displayed frame
     * @param glyphs exactly unclaimed native positions
     * @param options native text/table limits
     * @param policy render and combined native/raw-provider anchor policy
     * @param provider caller-owned geometry strategy; failures remain visible even when native tables exist
     * @param preferred accepted native tables and anchor count
     * @param work shared native/provider page work budget
     * @return disjoint model tables and still-unclaimed original positions
     * @throws IOException if provider validation, combined budgets, cancellation or extraction fail
     */
    static Result extract(final PDDocument document, final int index, final PdfPageGeometry geometry,
            final List<PdfPageText.Glyph> glyphs, final PdfOptions options, final PdfNativeLayoutOptions policy,
            final PdfTableLayoutProvider provider, final PdfPageLayout.NativeStage preferred, final PdfTableWork work)
            throws IOException {
        if (preferred.anchors() > policy.maxTableAnchors()) {
            throw limit("Native tables exceed combined maxTableAnchors");
        }
        final double width = Math.ceil(geometry.width() * policy.dpi() / 72.0);
        final double height = Math.ceil(geometry.height() * policy.dpi() / 72.0);
        if (width < 1 || height < 1 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE
                || width * height > policy.maxRenderPixels()) {
            throw limit("Native PDF layout exceeds maxRenderPixels");
        }
        ElementCollector.checkInterrupted();
        final var image = new PDFRenderer(document).renderImageWithDPI(index, policy.dpi(), ImageType.RGB);
        try {
            if ((long) image.getWidth() * image.getHeight() > policy.maxRenderPixels()) {
                throw limit("Native PDF layout exceeds maxRenderPixels");
            }
            final var rawLayouts =
                    PdfTableLayouts.locate(provider, image, options.tableLimits(), policy.validation(), work);
            final double xScale = geometry.width() / (double) image.getWidth();
            final double yScale = geometry.height() / (double) image.getHeight();
            final var layouts =
                    policy.leadingRow() == PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_ROW
                            ? PdfTableLayouts.validate(PdfNativeLeadingRow.extend(rawLayouts, glyphs, geometry, xScale,
                                                               yScale, preferred.tables(), work),
                                      image, options.tableLimits(), policy.validation(), work)
                            : rawLayouts;
            long combinedAnchors = preferred.anchors();
            for (final var layout : layouts) {
                work.step();
                combinedAnchors += layout.anchors().size();
                if (combinedAnchors > policy.maxTableAnchors()) {
                    throw limit("Native and raw provider tables exceed combined maxTableAnchors");
                }
            }
            final var cellCoordinates =
                    options.includeTableCellCoordinates() ? new PdfTableCells(geometry, xScale, yScale) : null;
            final var continuationFrame = options.tableContinuation().providerHeaders()
                                                  ? new PdfTableCells(geometry, xScale, yScale,
                                                            options.tableContinuation().providerPrefixes(),
                                                            options.tableContinuation().providerLayouts())
                                                  : null;
            final var owned = new boolean[glyphs.size()];
            final var tables = new ArrayList<PdfLayoutItem>();
            for (int layoutIndex = 0; layoutIndex < layouts.size(); layoutIndex++) {
                final var layout = layouts.get(layoutIndex);
                boolean overlapsNative = false;
                final var envelope = layout.bounds();
                for (final var table : preferred.tables()) {
                    work.step();
                    final var box = table.box();
                    overlapsNative |= envelope.left() * xScale < box.right() && envelope.right() * xScale > box.left()
                                      && envelope.top() * yScale < box.bottom()
                                      && envelope.bottom() * yScale > box.top();
                }
                if (overlapsNative) {
                    continue;
                }
                final var anchors = layout.anchors()
                                            .stream()
                                            .sorted(Comparator.comparingInt(PdfTableLayoutProvider.Anchor::row)
                                                            .thenComparingInt(PdfTableLayoutProvider.Anchor::column))
                                            .toList();
                final var claims = new ArrayList<Integer>();
                final var values = new ArrayList<String>();
                final var descriptions = new ArrayList<Map<String, Object>>();
                int joinedDates = 0;
                int multilineCells = 0;
                int scriptGlyphs = 0;
                int retainedHyphenWraps = 0;
                for (final var anchor : anchors) {
                    final var box = anchor.bounds();
                    final var members = new ArrayList<PdfPageText.Glyph>();
                    for (int source = 0; source < glyphs.size(); source++) {
                        work.step();
                        final var glyph = glyphs.get(source);
                        // PDFRenderer already applies declared page rotation; map native centers into that same frame.
                        final var center = geometry.center(glyph);
                        final double x = center.x / xScale;
                        final double y = center.y / yScale;
                        if (x >= box.left() && x < box.right() && y >= box.top() && y < box.bottom()) {
                            if (owned[source]) {
                                throw new PartitionException(PartitionException.Code.OCR_FAILURE,
                                        "Native provider tables overlap source ownership");
                            }
                            members.add(glyph);
                            claims.add(source);
                        }
                    }
                    final var lines = PdfGlyphLines.displayed(members, geometry);
                    final String value = String.join(" ", lines.stream().map(PdfLayoutItem::text).toList());
                    if (value.codePointCount(0, value.length()) > options.tableLimits().maxCellCharacters()) {
                        throw limit("Native provider table exceeds maxCellCharacters");
                    }
                    final var scripts = policy.cellTextJoin() == PdfNativeLayoutOptions.CellTextJoin.INLINE_SCRIPT_RUNS
                                                ? PdfNativeCellText.inlineScriptRuns(members, geometry, lines, work)
                                                : null;
                    if (scripts != null) {
                        scriptGlyphs += scripts.assignedGlyphs();
                    }
                    final boolean sourceWords = policy.cellTextJoin()
                                                == PdfNativeLayoutOptions.CellTextJoin.SOURCE_WORD_WRAPS_AND_ISO_DATES;
                    final var compounds =
                            sourceWords ? PdfSourceDiscretionaryText.retainHyphens(lines, value, work,
                                                  PdfNativeCellText.compoundBoundaries(members, geometry, lines, work))
                                        : new PdfSourceDiscretionaryText.Result(value, 0);
                    final String joined = switch (policy.cellTextJoin()) {
                        case SPACE_SEPARATED -> value;
                        case WRAPPED_ISO_DATE, SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE ->
                            PdfNativeCellText.wrappedIsoDate(lines, value);
                        case PRESERVE_LINE_BREAKS -> PdfNativeCellText.preserveLineBreaks(lines, value);
                        case INLINE_SCRIPT_RUNS ->
                            String.join(" ", scripts.lines().stream().map(PdfLayoutItem::text).toList());
                        case SOURCE_WORD_WRAPS_AND_ISO_DATES -> {
                            final String date = PdfNativeCellText.wrappedIsoDate(lines, value);
                            yield date.equals(value) ? compounds.text() : date;
                        }
                    };
                    if (scripts != null
                            && joined.codePointCount(0, joined.length()) > options.tableLimits().maxCellCharacters()) {
                        throw limit("Native provider table exceeds maxCellCharacters");
                    }
                    if (!joined.equals(value)) {
                        if (policy.cellTextJoin() == PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS) {
                            multilineCells++;
                        } else if (sourceWords && compounds.retainedHyphenWraps() > 0
                                   && joined.equals(compounds.text())) {
                            retainedHyphenWraps += compounds.retainedHyphenWraps();
                        } else if (policy.cellTextJoin() != PdfNativeLayoutOptions.CellTextJoin.INLINE_SCRIPT_RUNS) {
                            joinedDates++;
                        }
                    }
                    values.add(joined);
                    if (cellCoordinates != null) {
                        descriptions.add(cellCoordinates.cell(anchor.row(), anchor.column(), anchor.rowSpan(),
                                anchor.columnSpan(), box.left(), box.top(), box.right(), box.bottom(), work));
                    }
                }
                if (values.stream().allMatch(String::isBlank)) {
                    continue;
                }
                final var html = new StringBuilder("<table><tbody>");
                int anchorIndex = 0;
                for (int row = 0; row < layout.rows(); row++) {
                    work.step();
                    html.append("<tr>");
                    while (anchorIndex < anchors.size() && anchors.get(anchorIndex).row() == row) {
                        final var anchor = anchors.get(anchorIndex);
                        html.append("<td");
                        if (anchor.rowSpan() > 1) {
                            html.append(" rowspan=\"").append(anchor.rowSpan()).append('"');
                        }
                        if (anchor.columnSpan() > 1) {
                            html.append(" colspan=\"").append(anchor.columnSpan()).append('"');
                        }
                        html.append('>')
                                .append(PdfNativeCellText.html(values.get(anchorIndex++), policy.cellTextJoin()))
                                .append("</td>");
                    }
                    html.append("</tr>");
                }
                html.append("</tbody></table>");
                final var metadata = new LinkedHashMap<String, Object>();
                metadata.put("text_as_html", html.toString());
                metadata.put("pdf_table_method", "provider_layout_native_glyphs_v1");
                metadata.put("pdf_text_method", "native");
                metadata.put("pdf_table_layout_provenance", layout.provenance());
                if (layout != rawLayouts.get(layoutIndex)) {
                    metadata.put("pdf_table_boundary_policy", "source_leading_numeric_row_v1");
                    metadata.put("pdf_table_added_leading_rows", 1);
                }
                if (layout.headerRows() > 0) {
                    metadata.put("pdf_table_header_rows", layout.headerRows());
                    metadata.put("pdf_table_header_policy", "provider_declared_prefix_v1");
                    // The format-neutral prefix is consumed by structural chunking; source HTML stays unchanged.
                    final int chunkHeaders = PdfTableLayouts.chunkHeaderRows(layout, work);
                    if (chunkHeaders > 0) {
                        metadata.put("table_leading_header_rows", chunkHeaders);
                    }
                }
                metadata.put("pdf_native_layout_dpi", policy.dpi());
                if (joinedDates > 0) {
                    metadata.put("pdf_table_cell_text_policy", "wrapped_iso_date_v1");
                    metadata.put("pdf_table_joined_iso_dates", joinedDates);
                }
                if (multilineCells > 0) {
                    metadata.put("pdf_table_cell_text_policy", "native_line_breaks_v1");
                    metadata.put("pdf_table_multiline_cells", multilineCells);
                }
                if (scriptGlyphs > 0) {
                    metadata.put("pdf_table_cell_text_policy", "native_inline_script_runs_v1");
                    metadata.put("pdf_table_inline_script_glyphs", scriptGlyphs);
                }
                if (sourceWordsPolicy(policy) && (joinedDates > 0 || retainedHyphenWraps > 0)) {
                    metadata.put("pdf_table_cell_text_policy", "source_word_wraps_and_iso_dates_v1");
                }
                if (retainedHyphenWraps > 0) {
                    metadata.put("pdf_table_retained_hyphen_wraps", retainedHyphenWraps);
                }
                if (cellCoordinates != null) {
                    metadata.put("pdf_table_cells", PdfTableCells.describe("provider_rectangles", layout.rows(),
                                                            layout.columns(), descriptions));
                }
                final var box = layout.bounds();
                tables.add(new PdfLayoutItem(ElementType.Table,
                        String.join(" ", values.stream().filter(value -> !value.isBlank()).toList()),
                        new PdfLayoutItem.Bounds((float) (box.left() * xScale), (float) (box.top() * yScale),
                                (float) (box.right() * xScale), (float) (box.bottom() * yScale)),
                        metadata,
                        continuationFrame == null ? null
                                                  : continuationFrame.providerSignature(
                                                            layout, anchors, values, "native_provider", work)));
                // Commit claims only after a nonblank table is completely staged.
                for (final int source : claims) {
                    work.step();
                    owned[source] = true;
                }
            }
            final var remaining = new ArrayList<PdfPageText.Glyph>();
            for (int source = 0; source < glyphs.size(); source++) {
                work.step();
                if (!owned[source]) {
                    remaining.add(glyphs.get(source));
                }
            }
            return new Result(List.copyOf(tables), List.copyOf(remaining));
        } finally {
            image.flush();
        }
    }

    /**
     * Creates a content-free structured resource diagnostic.
     * @param message exceeded policy
     * @return typed table-limit failure
     */
    private static PartitionException limit(final String message) {
        return new PartitionException(PartitionException.Code.TABLE_LIMIT, message);
    }

    /**
     * Identifies the explicitly composed native word/date policy without inferring source semantics.
     * @param policy immutable native provider configuration
     * @return whether source word/date composition is selected
     */
    private static boolean sourceWordsPolicy(final PdfNativeLayoutOptions policy) {
        return policy.cellTextJoin() == PdfNativeLayoutOptions.CellTextJoin.SOURCE_WORD_WRAPS_AND_ISO_DATES;
    }
}
