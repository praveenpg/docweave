package io.docweave.partition;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Table-continuation evidence for the first or last page of a page range: the page's single continuation candidate
 * table and the repeated-header signature that decides whether it continues, or is continued by, a table on the
 * adjacent page in another range. It is recorded only when table continuation is enabled and the page holds exactly
 * one table with an eligible header; otherwise no boundary exists for that page. The values are those the
 * whole-document path compares, so assembly links tables across ranges exactly as it would within one range.
 * @param pageNumber one-based physical page holding the candidate
 * @param elementId provisional ID of the candidate table in its range result
 * @param evidence the candidate's header and geometry signature
 * @param entryClear whether no ordinary text on the page lies above the table's top edge, so it may continue a
 *                   table from the previous page
 * @param exitClear whether no ordinary text on the page lies below the table's bottom edge, so a table on the next
 *                  page may continue it
 */
public record
        PdfTableBoundary(int pageNumber, String elementId, Evidence evidence, boolean entryClear, boolean exitClear) {
    /**
     * Validates the boundary.
     * @param pageNumber one-based physical page
     * @param elementId nonblank provisional table ID
     * @param evidence nonnull signature
     * @param entryClear upper-edge clearance
     * @param exitClear lower-edge clearance
     * @throws NullPointerException if a reference is null
     * @throws IllegalArgumentException if the page is not positive or the ID is blank
     */
    public PdfTableBoundary {
        Objects.requireNonNull(elementId, "elementId");
        Objects.requireNonNull(evidence, "evidence");
        if (pageNumber < 1 || elementId.isBlank()) {
            throw new IllegalArgumentException("A table boundary needs a positive page and a table ID");
        }
    }

    /**
     * Repeated-header signature of a table, in displayed unscaled PDF user space. Numbers are normalized on
     * construction (every number in {@code transforms} and {@code columns} becomes a {@link Double}), so evidence read
     * back from a store that changed number types, such as {@code Integer} to {@code Long} or {@code Double} to
     * {@code BigDecimal}, compares exactly as the original.
     * @param width displayed page width
     * @param height displayed page height
     * @param transforms non-default page transforms (rotation, user unit, crop box) as element metadata records them
     * @param source recognition family, including provider provenance
     * @param columns observed column boundaries, left to right
     * @param header normalized header labels
     * @param top displayed table top
     * @param bottom displayed table bottom
     */
    public record Evidence(double width, double height, Map<String, Object> transforms, String source,
            List<Double> columns, List<String> header, double top, double bottom) {
        /**
         * Copies the collections.
         * @param width displayed page width
         * @param height displayed page height
         * @param transforms nonnull page transforms; nested numbers become doubles
         * @param source nonnull recognition family
         * @param columns nonnull numeric column boundaries
         * @param header nonnull header labels
         * @param top table top
         * @param bottom table bottom
         * @throws NullPointerException if a reference or collection entry is null
         * @throws IllegalArgumentException if a column is not a number or a label is not a string
         */
        public Evidence {
            transforms = PdfTableSignature.numeric(transforms);
            Objects.requireNonNull(source, "source");
            // Read entries untyped: decoded lists may hold other types despite their declared element types.
            columns = ((List<?>) columns)
                              .stream()
                              .map(value -> {
                                  if (!(value instanceof final Number number)) {
                                      throw new IllegalArgumentException("Table evidence columns must be numbers");
                                  }
                                  return number.doubleValue();
                              })
                              .toList();
            header = ((List<?>) header)
                             .stream()
                             .map(value -> {
                                 if (!(value instanceof final String label)) {
                                     throw new IllegalArgumentException("Table evidence header labels must be strings");
                                 }
                                 return label;
                             })
                             .toList();
        }

        /**
         * Converts internal signature evidence.
         * @param signature validated signature
         * @return public evidence
         */
        static Evidence of(final PdfTableSignature signature) {
            return new Evidence(signature.width(), signature.height(), signature.transforms(), signature.source(),
                    signature.columns(), signature.header(), signature.top(), signature.bottom());
        }

        /**
         * Converts back to the internal signature used for comparison.
         * @return signature
         */
        PdfTableSignature signature() {
            return new PdfTableSignature(width, height, transforms, source, columns, header, top, bottom);
        }
    }
}
