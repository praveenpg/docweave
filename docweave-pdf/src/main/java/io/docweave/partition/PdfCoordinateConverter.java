package io.docweave.partition;

import io.docweave.core.Element;

/**
 * Converts displayed PDF element, table-cell and ordered source-image coordinates without changing identity/content.
 * Coordinates stay crop-relative with a top-left origin and the page rotation already applied.
 * Source crop boxes and embedded image pixel dimensions remain source attributes. Instances are
 * stateless and safe to share across threads. Unsupported or malformed coordinate maps are rejected.
 * The image's {@code pdf_image_coordinates} map retains its original corner order during conversion;
 * mirroring and shear are not replaced by an axis-aligned rectangle. An optional {@code pdf_image_clip_bounds}
 * map's nested {@code bounds} converts in the same frame; its EMPTY/UNBOUNDED classifications carry no
 * coordinates and are returned unchanged.
 */
public interface PdfCoordinateConverter {
    /**
     * Creates a stateless converter without invoking rendering or OCR.
     * @return coordinate conversion service
     */
    static PdfCoordinateConverter create() {
        return new DefaultPdfCoordinateConverter();
    }

    /**
     * Converts geometry to physical points (one seventy-second of an inch), applying PDF UserUnit.
     * @param element source element, unchanged
     * @return element with PDFPoints coordinates; identity and content preserved
     * @throws IllegalArgumentException for malformed or unsupported geometry
     */
    Element toPoints(Element element);

    /**
     * Converts geometry to a virtual page-pixel frame at the requested physical resolution. This is
     * independent of embedded image dimensions and actual OCR render resolution. Each coordinate map
     * stores its own dpi so subsequent conversions are reversible without consulting OCR metadata.
     * @param element source element, unchanged
     * @param dpi positive finite dots per physical inch
     * @return element with PDFPixels coordinates
     * @throws IllegalArgumentException for invalid DPI, geometry or nonfinite converted values
     */
    Element toPixels(Element element, double dpi);

    /**
     * Converts geometry to physical millimeters, applying PDF UserUnit.
     * @param element source element, unchanged
     * @return element with PDFMillimeters coordinates
     * @throws IllegalArgumentException for malformed or unsupported geometry
     */
    Element toMillimeters(Element element);

    /**
     * Restores displayed unscaled PDF user-space geometry; crop and rotation are not applied again.
     * The source pdf_user_unit attribute is retained through every conversion (absent means one).
     * @param element source element, unchanged
     * @return element with PDFUserSpace coordinates
     * @throws IllegalArgumentException for malformed or unsupported geometry
     */
    Element toUserSpace(Element element);
}
