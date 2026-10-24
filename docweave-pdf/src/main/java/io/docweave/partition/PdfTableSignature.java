package io.docweave.partition;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Capped source evidence, never exposed as raw metadata or retained beyond the next page.
 * @param width displayed page width in unscaled PDFUserSpace
 * @param height displayed page height
 * @param transforms source crop/rotation/UserUnit identity
 * @param source recognition family, including provider provenance for explicit provider evidence
 * @param columns observed displayed column boundaries
 * @param header whitespace-collapsed, NFC, case-sensitive repeated labels
 * @param top displayed table top
 * @param bottom displayed table bottom
 */
record PdfTableSignature(double width, double height, Map<String, Object> transforms, String source,
        List<Double> columns, List<String> header, double top, double bottom) {
    /** Unicode spacing normalization, without OCR correction or case folding. */
    private static final Pattern SPACE = Pattern.compile("[\\p{javaWhitespace}\\p{Zs}]+");

    /**
     * Admits only an unmerged first row of two through32 visible columns and at least one body row.
     * The scan is capped to32 cells, even when caller table limits are much larger.
     * @param grid validated topology
     * @return whether a simple repeated header can be represented
     */
    static boolean supports(final PdfTableGrid grid) {
        final int count = grid.xs().size() - 1;
        if (count < 2 || count > 32 || grid.ys().size() < 3 || grid.cells().size() <= count) {
            return false;
        }
        for (int index = 0; index < count; index++) {
            final var cell = grid.cells().get(index);
            if (cell.row() != 0 || cell.column() != index || cell.rowSpan() != 1 || cell.columnSpan() != 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates a tiny header/geometry signature. Oversized/unsupported evidence means no link, not extraction failure.
     * @param width displayed page width
     * @param height displayed page height
     * @param transforms immutable source-frame properties
     * @param source recognition family
     * @param columns two through32 observed column intervals
     * @param labels nonempty source text for each first-row anchor
     * @param top table top
     * @param bottom table bottom
     * @return immutable signature, or null for an ineligible header/frame
     */
    static PdfTableSignature create(final double width, final double height, final Map<String, Object> transforms,
            final String source, final List<Double> columns, final List<String> labels, final double top,
            final double bottom) {
        if (labels.size() < 2 || labels.size() > 32 || columns.size() != labels.size() + 1 || top < 0 || bottom > height
                || top >= bottom || columns.getFirst() < 0 || columns.getLast() > width) {
            return null;
        }
        final var header = new ArrayList<String>();
        int characters = 0;
        int normalizedCharacters = 0;
        for (final String label : labels) {
            if (label.length() > 512 || (characters += label.length()) > 4096) {
                return null;
            }
            final String normalized =
                    Normalizer.normalize(SPACE.matcher(label).replaceAll(" ").strip(), Normalizer.Form.NFC);
            if (normalized.isEmpty() || normalized.length() > 512
                    || (normalizedCharacters += normalized.length()) > 4096
                    || normalized.codePoints().noneMatch(Character::isLetter)) {
                return null;
            }
            header.add(normalized);
        }
        if (header.stream().distinct().count() < 2) {
            return null;
        }
        return new PdfTableSignature(
                width, height, Map.copyOf(transforms), source, List.copyOf(columns), List.copyOf(header), top, bottom);
    }

    /**
     * Preserves exact normalized row/cell boundaries of a capped unmerged provider prefix.
     * Columns need nonempty combined alphabetic evidence; individual year cells may be numeric.
     * Existing single-row validation supplies unchanged frame, text and distinct-label limits.
     * @param width displayed page width
     * @param height displayed page height
     * @param transforms immutable original frame properties
     * @param source recognition family and provider provenance
     * @param columns observed column boundaries
     * @param rows two through four complete unmerged header rows, at most32 cells
     * @param top original table top
     * @param bottom original table bottom
     * @return row-aware signature or null for unsupported evidence
     */
    static PdfTableSignature createPrefix(final double width, final double height, final Map<String, Object> transforms,
            final String source, final List<Double> columns, final List<List<String>> rows, final double top,
            final double bottom) {
        final int count = columns.size() - 1;
        if (count < 2 || rows.size() < 2 || rows.size() > 4 || (long) rows.size() * count > 32
                || rows.stream().anyMatch(row -> row.size() != count)) {
            return null;
        }
        final var combined = new ArrayList<String>();
        for (int column = 0; column < count; column++) {
            final var parts = new ArrayList<String>();
            for (final var row : rows) {
                final String value = row.get(column);
                if (value.length() > 512
                        || Normalizer.normalize(SPACE.matcher(value).replaceAll(" ").strip(), Normalizer.Form.NFC)
                                .isEmpty()) {
                    return null;
                }
                parts.add(value);
            }
            combined.add(String.join("\n", parts));
        }
        final var validated = create(width, height, transforms, source, columns, combined, top, bottom);
        if (validated == null) {
            return null;
        }
        final var canonical = rows.stream()
                                      .flatMap(List::stream)
                                      .map(label
                                              -> Normalizer.normalize(SPACE.matcher(label).replaceAll(" ").strip(),
                                                      Normalizer.Form.NFC))
                                      .toList();
        return new PdfTableSignature(width, height, validated.transforms(),
                source + ":header_prefix_rows=" + rows.size(), validated.columns(), canonical, top, bottom);
    }

    /**
     * Compares exact source frames/families/normalized labels and all observed column boundaries.
     * @param previous preceding-page evidence
     * @return whether a bottom20% to top20% continuation is geometrically plausible within two source units
     */
    boolean follows(final PdfTableSignature previous) {
        if (width != previous.width || height != previous.height || !transforms.equals(previous.transforms)
                || !source.equals(previous.source) || !header.equals(previous.header)
                || columns.size() != previous.columns.size() || previous.bottom < height * 0.8 || top > height * 0.2) {
            return false;
        }
        for (int index = 0; index < columns.size(); index++) {
            if (Math.abs(columns.get(index) - previous.columns.get(index)) > 2) {
                return false;
            }
        }
        return true;
    }
}
