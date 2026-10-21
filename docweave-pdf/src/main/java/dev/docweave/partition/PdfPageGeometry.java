package dev.docweave.partition;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Map;

/** Immutable crop/rotation frame shared by native glyphs, painted paths and image placements. */
final class PdfPageGeometry {
    /** Absolute crop origin in source user space. */
    private final float left;
    /** Absolute crop bottom in source user space. */
    private final float bottom;
    /** Unrotated crop width. */
    private final float rawWidth;
    /** Unrotated crop height. */
    private final float rawHeight;
    /** Normalized clockwise page rotation, in quarter turns. */
    private final int rotation;
    /** Physical points per unscaled source user-space unit. */
    private final float userUnit;

    /**
     * Validates page geometry without rendering or altering the source page.
     * @param page source page, with inherited rotation/crop resolved by PDFBox
     * @throws PartitionException if dimensions, rotation or UserUnit are invalid/unsupported
     */
    PdfPageGeometry(final PDPage page) throws PartitionException {
        final var crop = page.getCropBox();
        left = crop.getLowerLeftX();
        bottom = crop.getLowerLeftY();
        rawWidth = crop.getWidth();
        rawHeight = crop.getHeight();
        final var rawRotation = PDPageTree.getInheritableAttribute(page.getCOSObject(), COSName.ROTATE);
        if (rawRotation != null
                && (!(rawRotation instanceof final COSNumber number) || number.longValue() != number.intValue()
                        || !Float.isFinite(number.floatValue()) || number.floatValue() % 1 != 0
                        || number.longValue() % 90 != 0)) {
            throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                    "PDF page rotation must be an integer multiple of 90 degrees");
        }
        rotation = page.getRotation();
        final var unit = page.getCOSObject().getDictionaryObject(COSName.USER_UNIT);
        if (unit != null
                && (!(unit instanceof final COSNumber number) || !Float.isFinite(number.floatValue())
                        || number.floatValue() <= 0)) {
            throw new PartitionException(
                    PartitionException.Code.MALFORMED_DOCUMENT, "PDF UserUnit must be finite and positive");
        }
        userUnit = page.getUserUnit();
        if (!Float.isFinite(left) || !Float.isFinite(bottom) || !Float.isFinite(rawWidth) || !Float.isFinite(rawHeight)
                || rawWidth <= 0 || rawHeight <= 0) {
            throw new PartitionException(
                    PartitionException.Code.MALFORMED_DOCUMENT, "PDF crop dimensions must be finite and positive");
        }
    }

    /**
     * Creates an internal already-displayed upright frame for OCR topology/coordinate serialization.
     * The source page is never changed; final emission restores the original displayed frame.
     * @param width positive upright displayed width
     * @param height positive upright displayed height
     * @param userUnit unchanged physical scale, applied only by the existing coordinate converter
     */
    private PdfPageGeometry(final float width, final float height, final float userUnit) {
        left = 0;
        bottom = 0;
        rawWidth = width;
        rawHeight = height;
        rotation = 0;
        this.userUnit = userUnit;
    }

    /**
     * Supplies upright dimensions for explicit raster quarter-turn topology grouping.
     * @param correction accepted clockwise quarter-turn correction
     * @return this original frame for0, otherwise internal upright dimensions only
     */
    PdfPageGeometry upright(final int correction) {
        return correction == 0 ? this
                               : new PdfPageGeometry(correction % 180 == 0 ? width() : height(),
                                         correction % 180 == 0 ? height() : width(), userUnit);
    }

    /**
     * Converts absolute bottom-left source coordinates to crop-relative displayed top-left coordinates.
     * @param x absolute source x
     * @param y absolute source y
     * @return displayed unscaled user-space point
     */
    Point2D.Float point(final float x, final float y) {
        return relative(x - left, y - bottom);
    }

    /**
     * Applies normalized clockwise page rotation to crop-relative bottom-left coordinates.
     * @param x crop-relative x
     * @param y crop-relative y
     * @return top-left displayed coordinates
     */
    private Point2D.Float relative(final float x, final float y) {
        return switch (rotation) {
            case 90 -> new Point2D.Float(y, x);
            case 180 -> new Point2D.Float(rawWidth - x, y);
            case 270 -> new Point2D.Float(rawHeight - y, rawWidth - x);
            default -> new Point2D.Float(x, rawHeight - y);
        };
    }

    /**
     * Maps PDFBox text-direction-adjusted coordinates into the displayed page frame.
     * @param x horizontal coordinate in the glyph's upright text frame
     * @param y top-left baseline coordinate in that frame
     * @param direction counterclockwise source text direction, ignoring page rotation
     * @return displayed unscaled point
     */
    private Point2D.Float textPoint(final float x, final float y, final int direction) {
        if (direction == rotation) {
            return new Point2D.Float(x, y);
        }
        return switch (direction) {
            case 90 -> relative(y, x);
            case 180 -> relative(rawWidth - x, y);
            case 270 -> relative(rawWidth - y, rawHeight - x);
            default -> relative(x, rawHeight - y);
        };
    }

    /**
     * Maps an upright text run's approximate box into a displayed axis-aligned box.
     * @param box text-direction-adjusted bounds
     * @param direction source text orientation
     * @return displayed enclosing box; no pixel clipping or font-outline rendering
     */
    PdfLayoutItem.Bounds textBounds(final PdfLayoutItem.Bounds box, final int direction) {
        if (direction == rotation) {
            return box;
        }
        final var corners = List.of(textPoint(box.left(), box.top(), direction),
                textPoint(box.right(), box.top(), direction), textPoint(box.right(), box.bottom(), direction),
                textPoint(box.left(), box.bottom(), direction));
        return new PdfLayoutItem.Bounds((float) corners.stream().mapToDouble(Point2D::getX).min().orElseThrow(),
                (float) corners.stream().mapToDouble(Point2D::getY).min().orElseThrow(),
                (float) corners.stream().mapToDouble(Point2D::getX).max().orElseThrow(),
                (float) corners.stream().mapToDouble(Point2D::getY).max().orElseThrow());
    }

    /**
     * Locates a glyph center for table ownership after page/text orientation normalization.
     * @param glyph upright-frame metrics
     * @return displayed center
     */
    Point2D.Float center(final PdfPageText.Glyph glyph) {
        return textPoint(glyph.x() + glyph.width() / 2, glyph.y() - glyph.height() / 2, glyph.direction());
    }

    /**
     * Maps PDFBox's already crop-relative bottom-left text-matrix coordinates into the displayed frame.
     * @param x crop-relative source baseline x
     * @param y crop-relative source baseline y
     * @return displayed top-left point without another crop subtraction
     */
    Point2D.Float relativeTextPoint(final float x, final float y) {
        return relative(x, y);
    }

    /**
     * Returns a continuous clockwise displayed angle for explicitly selected arbitrary native baselines.
     * @param direction source counterclockwise angle
     * @return clockwise angle from zero inclusive through360 exclusive
     */
    double textRotation(final double direction) {
        return ((rotation - direction) % 360 + 360) % 360;
    }

    /**
     * Returns the text's clockwise displayed orientation.
     * @param direction source counterclockwise text angle
     * @return 0,90,180 or270 degrees clockwise on the displayed page
     */
    int textRotation(final int direction) {
        return Math.floorMod(rotation - direction, 360);
    }

    /**
     * Returns displayed crop width in source user-space units.
     * @return positive width, swapped for quarter-turn pages
     */
    float width() {
        return rotation == 90 || rotation == 270 ? rawHeight : rawWidth;
    }

    /**
     * Returns displayed crop height in source user-space units.
     * @return positive height, swapped for quarter-turn pages
     */
    float height() {
        return rotation == 90 || rotation == 270 ? rawWidth : rawHeight;
    }

    /**
     * Adds explicit source transforms only when non-default, retaining historical ordinary-page metadata.
     * @param metadata mutable element properties
     */
    void describe(final Map<String, Object> metadata) {
        if (rotation != 0) {
            metadata.put("pdf_page_rotation", rotation);
        }
        if (userUnit != 1) {
            metadata.put("pdf_user_unit", (double) userUnit);
        }
        if (left != 0 || bottom != 0) {
            metadata.put("pdf_crop_box",
                    List.of((double) left, (double) bottom, (double) (left + rawWidth), (double) (bottom + rawHeight)));
        }
    }
}
