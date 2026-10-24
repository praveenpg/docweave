package io.docweave.partition;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.IOException;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Document-local resolver for explicit/inherited and declared default paragraph styles.
 * Caches named/document default selection and numbering definitions; owns no hierarchy/counter/emission state.
 * Direct paragraph levels win; otherwise the nearest style's effective pStyle association wins.
 * Without an association, existing inherited-ilvl/default-zero behavior is retained for Word-authored
 * files; this fallback is not a claim to implement every strict OOXML or Word layout rule.
 */
final class DocxParagraphPolicyResolver {
    /** Pattern matching Heading 1 through Heading 9 style identifiers or names. */
    private static final Pattern HEADING = Pattern.compile("(?i)^heading\\s*([1-9])$");

    /**
     * Resolved paragraph policy including heading depth, numbering, style provenance, and applied defaults.
     *
     * @param heading 1-based heading level ({@code 1..9}), or {@code 0} when not a heading
     * @param numId effective numbering instance ID, or {@code null}
     * @param listLevel 0-based list level ({@code 0..8})
     * @param style effective paragraph style ID, or {@code null}
     * @param implicitStyle {@code true} when the style was supplied by the declared default paragraph style
     * @param documentDefaults property names contributed by {@code docDefaults}
     * @param tableProperties property names contributed by table-style paragraph layers
     */
    record Policy(int heading, BigInteger numId, int listLevel, String style, boolean implicitStyle,
            java.util.List<String> documentDefaults, java.util.List<String> tableProperties) {}

    /** Parsed Word document. */
    private final XWPFDocument document;
    /** Document-local numbering definitions lookup. */
    private final DocxNumberingDefinitions definitions;
    /** Whether the default paragraph style ID has been resolved. */
    private boolean defaultResolved;
    /** Cached default paragraph style ID, or {@code null}. */
    private String defaultStyle;
    /** Whether document default paragraph properties have been resolved. */
    private boolean documentDefaultsResolved;
    /** Cached document default paragraph properties, or {@code null}. */
    private org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral documentDefaults;

    /**
     * Creates a paragraph policy resolver for the given Word document.
     *
     * @param document parsed Word document
     */
    DocxParagraphPolicyResolver(final XWPFDocument document) {
        this.document = document;
        this.definitions = new DocxNumberingDefinitions(document);
    }

    /**
     * Resolves paragraph heading, numbering, and style policy outside of table-style layers.
     *
     * @param paragraph paragraph to resolve
     * @return resolved paragraph policy
     * @throws IOException if the thread is interrupted
     */
    Policy resolve(final XWPFParagraph paragraph) throws IOException {
        return resolve(paragraph, java.util.List.of());
    }

    /**
     * Resolves paragraph/direct layers above whole-table layers, then document defaults.
     *
     * @param paragraph paragraph to resolve
     * @param tableLayers ordered table-style paragraph property layers
     * @return resolved paragraph policy
     * @throws IOException if the thread is interrupted
     */
    Policy resolve(final XWPFParagraph paragraph,
            final java.util.List<org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral> tableLayers)
            throws IOException {
        return resolve(paragraph, tableLayers, null);
    }

    /**
     * Resolves table cells with an independently selected positional outline. A direct
     * body outline (9) yields to a conditional heading as observed in the owned Word
     * controls. Direct headings, general-table precedence and numbering are unchanged.
     * The positional value must exclude wholeTable/general layers.
     *
     * @param paragraph paragraph to resolve
     * @param tableLayers ordered table-style paragraph property layers
     * @param conditionalOutline positional conditional table-style outline level, or {@code null}
     * @return resolved paragraph policy
     * @throws IOException if the thread is interrupted
     */
    Policy resolve(final XWPFParagraph paragraph,
            final java.util.List<org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral> tableLayers,
            final BigInteger conditionalOutline) throws IOException {
        ElementCollector.checkInterrupted();
        int heading = -1;
        BigInteger numId = paragraph.getNumID();
        BigInteger level = paragraph.getNumIlvl();
        final var direct = paragraph.getCTP().getPPr();
        if (direct != null && direct.isSetOutlineLvl()) {
            heading = outline(direct.getOutlineLvl().getVal());
        }
        String id = paragraph.getStyleID();
        boolean implicitStyle = id == null;
        if (implicitStyle) {
            id = defaultParagraphStyle();
        }
        implicitStyle = implicitStyle && id != null;
        final String styleId = id;
        final var visited = new java.util.LinkedHashSet<String>();
        // Walk the paragraph style inheritance chain (basedOn) without cycles to resolve heading depth and numbering.
        while (id != null && visited.add(id)) {
            ElementCollector.checkInterrupted();
            final var style = document.getStyles() == null ? null : document.getStyles().getStyle(id);
            var match = HEADING.matcher(id);
            if (style == null) {
                if (heading < 0 && match.matches()) {
                    heading = Integer.parseInt(match.group(1));
                }
                break;
            }
            final var properties = style.getCTStyle().getPPr();
            // Explicit/inherited outline levels take precedence over names on the same style.
            if (direct == null || !direct.isSetOutlineLvl()) {
                if (properties != null && properties.isSetOutlineLvl() && heading < 0) {
                    heading = outline(properties.getOutlineLvl().getVal());
                }
            }
            if (heading < 0 && match.matches()) {
                heading = Integer.parseInt(match.group(1));
            }
            if (heading < 0 && style.getName() != null) {
                match = HEADING.matcher(style.getName());
                if (match.matches()) {
                    heading = Integer.parseInt(match.group(1));
                } else if (style.getName().equalsIgnoreCase("Title")) {
                    heading = 1;
                }
            }
            if (heading < 0 && id.equalsIgnoreCase("Title")) {
                heading = 1;
            }
            if (properties != null && properties.isSetNumPr()) {
                final var numbering = properties.getNumPr();
                if (numId == null && numbering.isSetNumId()) {
                    numId = numbering.getNumId().getVal();
                }
                if (level == null && numbering.isSetIlvl()) {
                    level = numbering.getIlvl().getVal();
                }
            }
            id = style.getBasisStyleID();
        }
        if (heading < 0 && "Title".equalsIgnoreCase(styleId)) {
            heading = 1;
        }
        // Apply positional and base table-style paragraph layers where paragraph/style properties left values unset.
        final var appliedTable = new java.util.ArrayList<String>();
        if (direct != null && direct.isSetOutlineLvl() && BigInteger.valueOf(9).equals(direct.getOutlineLvl().getVal())
                && outline(conditionalOutline) > 0) {
            heading = outline(conditionalOutline);
            appliedTable.add("outline_level");
        }
        for (final var properties : tableLayers) {
            ElementCollector.checkInterrupted();
            if (heading < 0 && properties.isSetOutlineLvl()) {
                heading = outline(properties.getOutlineLvl().getVal());
                appliedTable.add("outline_level");
            }
            if (properties.isSetNumPr()) {
                final var numbering = properties.getNumPr();
                if (numId == null && numbering.isSetNumId()) {
                    numId = numbering.getNumId().getVal();
                    appliedTable.add("num_id");
                }
                if (level == null && numbering.isSetIlvl()) {
                    level = numbering.getIlvl().getVal();
                    appliedTable.add("list_level");
                }
            }
        }
        // Fall back to document-wide paragraph defaults (w:docDefaults/w:pPrDefault).
        final var appliedDefaults = new java.util.ArrayList<String>();
        final var defaults = documentParagraphDefaults();
        if (defaults != null) {
            if (heading < 0 && defaults.isSetOutlineLvl()) {
                heading = outline(defaults.getOutlineLvl().getVal());
                appliedDefaults.add("outline_level");
            }
            if (defaults.isSetNumPr()) {
                final var numbering = defaults.getNumPr();
                if (numId == null && numbering.isSetNumId()) {
                    numId = numbering.getNumId().getVal();
                    appliedDefaults.add("num_id");
                }
                if (level == null && numbering.isSetIlvl()) {
                    level = numbering.getIlvl().getVal();
                    appliedDefaults.add("list_level");
                }
            }
        }
        // When no direct ilvl is set on the paragraph, check if a numbering level is linked to one of the visited
        // styles via pStyle.
        if (numId != null && numId.signum() > 0 && paragraph.getNumIlvl() == null) {
            final var associated = definitions.associatedLevel(numId, java.util.List.copyOf(visited));
            if (associated != null) {
                level = BigInteger.valueOf(associated);
                appliedDefaults.remove("list_level");
                appliedTable.remove("list_level");
            }
        }
        if (level != null && (level.signum() < 0 || level.compareTo(BigInteger.valueOf(8)) > 0)) {
            throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, "Invalid DOCX list level");
        }
        final int listLevel = level == null ? 0 : level.intValue();
        if (listLevel < 0 || listLevel > 8) {
            throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, "Invalid DOCX list level");
        }
        return new Policy(Math.max(0, heading), numId, listLevel, styleId, implicitStyle,
                java.util.List.copyOf(appliedDefaults), java.util.List.copyOf(appliedTable));
    }

    /**
     * Finds the last declared default paragraph style in stored order, once per document view.
     * Only true/on/1 enable the flag. No default is synthesized by name or borrowed from another
     * style type. Multiple flagged defaults use a deterministic last-in-part recovery policy.
     *
     * @return declared default paragraph style ID, or {@code null}
     * @throws IOException if the thread is interrupted
     */
    private String defaultParagraphStyle() throws IOException {
        if (defaultResolved) {
            return defaultStyle;
        }
        final var styles = document.getStyles();
        String selected = null;
        if (styles != null) {
            for (final var style : styles.getStyles()) {
                ElementCollector.checkInterrupted();
                final var definition = style.getCTStyle();
                if (definition.isSetType()
                        && definition.getType()
                                   != org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType.PARAGRAPH) {
                    continue;
                }
                final String flag = definition.isSetDefault() ? String.valueOf(definition.getDefault()) : null;
                if (("true".equals(flag) || "on".equals(flag) || "1".equals(flag)) && style.getStyleId() != null
                        && !style.getStyleId().isBlank()) {
                    selected = style.getStyleId();
                }
            }
        }
        defaultStyle = selected;
        defaultResolved = true;
        return defaultStyle;
    }

    /**
     * Reads the lowest-precedence paragraph properties without calling POI accessors which create
     * missing defaults. Only outline and numbering properties affect this semantic extractor;
     * run properties, page layout, spacing and conditional table styles are not interpreted here.
     *
     * @return document default paragraph properties, or {@code null}
     * @throws IOException if the thread is interrupted
     */
    private org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral documentParagraphDefaults()
            throws IOException {
        ElementCollector.checkInterrupted();
        if (documentDefaultsResolved) {
            return documentDefaults;
        }
        final var styles = document.getStyles();
        if (styles != null && styles.getCtStyles() != null && styles.getCtStyles().isSetDocDefaults()) {
            final var defaults = styles.getCtStyles().getDocDefaults();
            if (defaults.isSetPPrDefault() && defaults.getPPrDefault().isSetPPr()) {
                documentDefaults = defaults.getPPrDefault().getPPr();
            }
        }
        documentDefaultsResolved = true;
        return documentDefaults;
    }

    /**
     * Converts a 0-based Word outline level ({@code 0..8}) to a 1-based heading depth ({@code 1..9}).
     *
     * @param value Word outline level value, or {@code null}
     * @return 1-based heading depth, or {@code 0} when outside {@code 0..8}
     */
    private int outline(final BigInteger value) {
        return value != null && value.signum() >= 0 && value.compareTo(BigInteger.valueOf(8)) <= 0
                ? value.intValue() + 1
                : 0;
    }
}
