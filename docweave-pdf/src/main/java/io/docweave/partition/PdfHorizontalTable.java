package io.docweave.partition;

import java.util.List;

/**
 * Accepted native text cells with explicit glyph ownership and supporting rule envelope.
 * @param rows immutable row-major text cells with actual text bounds
 * @param members original glyph indices, each retained once
 * @param numericColumns columns whose complete body matches the numeric syntax
 * @param frame supporting rule envelope, used only for ambiguity rejection
 */
record PdfHorizontalTable(List<List<PdfLayoutItem>> rows, List<Integer> members, List<Integer> numericColumns,
        PdfLayoutItem.Bounds frame) {}
