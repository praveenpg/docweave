package dev.docweave.partition;

import dev.docweave.core.Element;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stateless physical-unit conversion; recognition and source frames remain untouched. */
final class DefaultPdfCoordinateConverter implements PdfCoordinateConverter {
    /** Creates the stateless implementation used by the public converter factory. */
    DefaultPdfCoordinateConverter() {
    }

    @Override
    public Element toPoints(final Element element) {
        return convert(element, "PDFPoints", 1);
    }

    @Override
    public Element toPixels(final Element element, final double dpi) {
        positive(dpi, "dpi");
        return convert(element, "PDFPixels", dpi);
    }

    @Override
    public Element toMillimeters(final Element element) {
        return convert(element, "PDFMillimeters", 1);
    }

    @Override
    public Element toUserSpace(final Element element) {
        return convert(element, "PDFUserSpace", 1);
    }

    /**
     * Converts only documented geometry locations, including cell-only chunk metadata.
     * @param element immutable source
     * @param target supported target system
     * @param dpi requested pixel density, unused for other targets
     * @return new immutable element, or original when no geometry is present
     */
    private Element convert(final Element element, final String target, final double dpi) {
        Objects.requireNonNull(element, "element");
        final var metadata = new LinkedHashMap<>(element.metadata());
        final double unit =
                metadata.containsKey("pdf_user_unit") ? positive(metadata.get("pdf_user_unit"), "pdf_user_unit") : 1;
        boolean changed = false;
        if (metadata.containsKey("coordinates")) {
            metadata.put("coordinates", coordinates(metadata.get("coordinates"), target, dpi, unit));
            changed = true;
        }
        if (metadata.containsKey("pdf_image_coordinates")) {
            metadata.put(
                    "pdf_image_coordinates", coordinates(metadata.get("pdf_image_coordinates"), target, dpi, unit));
            changed = true;
        }
        if (metadata.containsKey("pdf_image_clip_bounds")) {
            final var clip = mapping(metadata.get("pdf_image_clip_bounds"));
            if (clip.containsKey("bounds")) {
                clip.put("bounds", coordinates(clip.get("bounds"), target, dpi, unit));
            }
            metadata.put("pdf_image_clip_bounds", clip);
            changed = true;
        }
        if (metadata.containsKey("pdf_table_cells")) {
            final var grid = mapping(metadata.get("pdf_table_cells"));
            if (!(grid.get("cells") instanceof List<?> cells)) {
                throw new IllegalArgumentException("pdf_table_cells.cells must be a list");
            }
            grid.put("cells", cells.stream()
                                      .map(value -> {
                                          final var cell = mapping(value);
                                          if (cell.containsKey("coordinates")) {
                                              cell.put("coordinates",
                                                      coordinates(cell.get("coordinates"), target, dpi, unit));
                                          }
                                          return cell;
                                      })
                                      .toList());
            metadata.put("pdf_table_cells", grid);
            changed = true;
        }
        return changed ? new Element(element.elementId(), element.type(), element.text(), metadata) : element;
    }

    /**
     * Validates four ordered corners and converts each scalar using its own source frame.
     * Negative/off-page positions remain legal; all results must be finite.
     * @param value source coordinate map
     * @param target target system
     * @param dpi output pixel resolution
     * @param unit original PDF UserUnit
     * @return converted map preserving other coordinate attributes
     */
    private Map<String, Object> coordinates(
            final Object value, final String target, final double dpi, final double unit) {
        final var source = mapping(value);
        if (!(source.get("system") instanceof String system)) {
            throw new IllegalArgumentException("coordinate system must be a string");
        }
        final double sourceScale = switch (system) {
            case "PDFUserSpace" -> unit;
            case "PDFPoints" -> 1;
            case "PDFMillimeters" -> 72 / 25.4;
            case "PDFPixels" -> 72 / positive(source.get("dpi"), "source dpi");
            default -> throw new IllegalArgumentException("Unsupported PDF coordinate system: " + system);
        };
        final double targetScale = switch (target) {
            case "PDFUserSpace" -> 1 / unit;
            case "PDFMillimeters" -> 25.4 / 72;
            case "PDFPixels" -> dpi / 72;
            default -> 1;
        };
        final boolean sameFrame = system.equals(target)
                                  && (!target.equals("PDFPixels") || positive(source.get("dpi"), "source dpi") == dpi);
        final double factor = sameFrame ? 1 : positive(sourceScale * targetScale, "conversion factor");
        final double width = positive(source.get("layout_width"), "layout_width");
        final double height = positive(source.get("layout_height"), "layout_height");
        if (!(source.get("points") instanceof List<?> points) || points.size() != 4) {
            throw new IllegalArgumentException("coordinates require four points");
        }
        source.put("points", points.stream()
                                     .map(point -> {
                                         if (!(point instanceof List<?> pair) || pair.size() != 2) {
                                             throw new IllegalArgumentException(
                                                     "coordinate point requires two numbers");
                                         }
                                         return List.of(scaled(pair.get(0), factor), scaled(pair.get(1), factor));
                                     })
                                     .toList());
        source.put("layout_width", positive(scaled(width, factor), "converted width"));
        source.put("layout_height", positive(scaled(height, factor), "converted height"));
        source.put("system", target);
        source.remove("dpi");
        if (target.equals("PDFPixels")) {
            source.put("dpi", dpi);
        }
        return source;
    }

    /**
     * Copies a JSON object while rejecting non-string keys.
     * @param value object to copy
     * @return mutable local copy
     */
    private static Map<String, Object> mapping(final Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("geometry must be an object");
        }
        final var result = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("geometry keys must be strings");
            }
            result.put(name, item);
        });
        return result;
    }

    /**
     * Multiplies finite geometry and rejects overflow rather than serializing invalid JSON numbers.
     * @param value numeric position or dimension
     * @param factor positive scale
     * @return finite converted value
     */
    private static double scaled(final Object value, final double factor) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("coordinate must be a finite number");
        }
        final double result = number.doubleValue() * factor;
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("coordinate conversion overflow");
        }
        return result;
    }

    /**
     * Validates dimensions, PDF UserUnit and DPI before arithmetic.
     * @param value candidate number
     * @param name diagnostic attribute
     * @return positive finite value
     */
    private static double positive(final Object value, final String name) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() <= 0) {
            throw new IllegalArgumentException(name + " must be positive and finite");
        }
        return number.doubleValue();
    }
}
