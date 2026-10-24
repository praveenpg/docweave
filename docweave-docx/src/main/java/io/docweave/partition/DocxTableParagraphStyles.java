package io.docweave.partition;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.xmlbeans.XmlException;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;
import org.w3c.dom.Node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Resolves semantic table-style paragraph layers independently of cell extraction and list counters.
 * Base layers are highest precedence first: derived-to-base wholeTable numbering, then
 * derived-to-base general properties. WholeTable outline is ignored to match the owned
 * Word observation; unmeasured wholeTable numbering retains the existing policy.
 * Active positional layers are prepended in reverse semantic precedence,
 * with derived-to-base inheritance within each region. Stored logical geometry selects membership.
 * Missing/wrong-type/cyclic/overlong chains yield no partial style layers. No public API is exposed.
 */
final class DocxTableParagraphStyles {
    /** Stable annotation order for supported table-style conditional region names. */
    static final List<String> ORDER = List.of("wholeTable", "band1Vert", "band2Vert", "band1Horz", "band2Horz",
            "firstRow", "lastRow", "firstCol", "lastCol", "nwCell", "neCell", "swCell", "seCell");
    /**
     * Application precedence, lowest first. Rows outrank columns as observed in the
     * pinned first-row/first-column Word case. ORDER remains the stable annotation order.
     * Last-edge symmetry is local policy; corners still outrank both edge families.
     */
    private static final List<String> PRECEDENCE = List.of("band1Vert", "band2Vert", "band1Horz", "band2Horz",
            "firstCol", "lastCol", "firstRow", "lastRow", "nwCell", "neCell", "swCell", "seCell");

    /**
     * Resolved table-style paragraph property layers, conditional branches, and diagnostic flags.
     *
     * @param style resolved table style ID, or {@code null}
     * @param implicit {@code true} when the style was selected from the declared default table style
     * @param properties base whole-table and general paragraph property layers
     * @param unresolved {@code true} when the table-style inheritance chain could not be resolved
     * @param omittedConditions {@code true} when unknown conditional branches with semantic properties were omitted
     * @param conditions conditional paragraph property layers keyed by region name
     * @param settings ordered {@code tblPr} DOM nodes from the table and its style chain
     */
    record Layers(String style, boolean implicit, List<CTPPrGeneral> properties, boolean unresolved,
            boolean omittedConditions, java.util.Map<String, List<CTPPrGeneral>> conditions, List<Node> settings) {
        /**
         * Returns an empty layer set when no table style is applied.
         *
         * @return empty table-style layers
         */
        static Layers none() {
            return new Layers(null, false, List.of(), false, false, java.util.Map.of(), List.of());
        }

        /**
         * Returns the ordered paragraph property layers applicable to the given active regions.
         *
         * @param regions active conditional region names for the cell
         * @return immutable list of paragraph property layers in precedence order
         */
        List<CTPPrGeneral> forRegions(final java.util.Set<String> regions) {
            final var result = new ArrayList<CTPPrGeneral>();
            // Prepend active conditional region layers from highest precedence (corners) to lowest (bands),
            // followed by wholeTable and general base layers.
            for (final String type : PRECEDENCE.reversed()) {
                if (regions.contains(type)) {
                    result.addAll(conditions.getOrDefault(type, List.of()));
                }
            }
            result.addAll(properties);
            return List.copyOf(result);
        }

        /**
         * Returns the highest-precedence explicit positional outline, excluding base layers.
         *
         * @param regions active conditional region names for the cell
         * @return highest-precedence positional outline level, or {@code null}
         */
        java.math.BigInteger conditionalOutline(final java.util.Set<String> regions) {
            return PRECEDENCE.reversed()
                    .stream()
                    .filter(regions::contains)
                    .flatMap(type -> conditions.getOrDefault(type, List.of()).stream())
                    .filter(CTPPrGeneral::isSetOutlineLvl)
                    .findFirst()
                    .map(properties -> properties.getOutlineLvl().getVal())
                    .orElse(null);
        }
    }

    /** Parsed Word document. */
    private final XWPFDocument document;

    /**
     * Creates a table-style paragraph resolver for the given Word document.
     *
     * @param document parsed Word document
     */
    DocxTableParagraphStyles(final XWPFDocument document) {
        this.document = document;
    }

    /**
     * Resolves the base and conditional paragraph property layers for a Word table.
     *
     * @param table Word table to inspect
     * @return resolved table-style layers
     * @throws IOException if the thread is interrupted
     */
    Layers resolve(final XWPFTable table) throws IOException {
        ElementCollector.checkInterrupted();
        String id = table.getStyleID();
        final boolean implicit = id == null;
        final var styles = document.getStyles();
        if (styles == null) {
            return id == null ? Layers.none()
                              : new Layers(id, false, List.of(), true, false, java.util.Map.of(), List.of());
        }
        // When the table omits tblStyle, fall back to the last declared default table style in styles.xml.
        if (implicit) {
            for (final var candidate : styles.getStyles()) {
                ElementCollector.checkInterrupted();
                final var definition = candidate.getCTStyle();
                final String flag = definition.isSetDefault() ? String.valueOf(definition.getDefault()) : null;
                if (definition.getType() == STStyleType.TABLE && candidate.getStyleId() != null
                        && !candidate.getStyleId().isBlank()
                        && ("true".equals(flag) || "on".equals(flag) || "1".equals(flag))) {
                    id = candidate.getStyleId();
                }
            }
        }
        if (id == null) {
            return Layers.none();
        }
        final String selected = id;
        final var visited = new HashSet<String>();
        final var whole = new ArrayList<CTPPrGeneral>();
        final var general = new ArrayList<CTPPrGeneral>();
        boolean omitted = false;
        final var conditions = new java.util.LinkedHashMap<String, List<CTPPrGeneral>>();
        final var settings = new ArrayList<Node>();
        if (table.getCTTbl().getTblPr() != null) {
            settings.add(table.getCTTbl().getTblPr().getDomNode());
        }
        // Walk the table style inheritance chain from derived to base, collecting general pPr, tblPr, and conditional
        // tblStylePr layers.
        for (int hops = 0; id != null; hops++) {
            ElementCollector.checkInterrupted();
            if (hops >= 128 || !visited.add(id)) {
                return new Layers(selected, implicit, List.of(), true, omitted, java.util.Map.of(), List.of());
            }
            final var style = styles.getStyle(id);
            if (style == null || style.getCTStyle().getType() != STStyleType.TABLE) {
                return new Layers(selected, implicit, List.of(), true, omitted, java.util.Map.of(), List.of());
            }
            final var definition = style.getCTStyle();
            if (definition.isSetPPr()) {
                general.add(definition.getPPr());
            }
            final Node styleSettings = DocxBodyView.child(definition.getDomNode(), "tblPr");
            if (styleSettings != null) {
                settings.add(styleSettings);
            }
            // POI's lite schemas omit the conditional style class; retain raw XML without adding a dependency.
            final Node root = definition.getDomNode();
            for (Node child = root.getLastChild(); child != null; child = child.getPreviousSibling()) {
                ElementCollector.checkInterrupted();
                if (!DocxBodyView.WORD.equals(child.getNamespaceURI()) || !"tblStylePr".equals(child.getLocalName())) {
                    continue;
                }
                final Node properties = DocxBodyView.child(child, "pPr");
                if (properties == null) {
                    continue;
                }
                final boolean semantic = DocxBodyView.child(properties, "numPr") != null
                                         || DocxBodyView.child(properties, "outlineLvl") != null;
                final String type = DocxBodyView.attribute(child, DocxBodyView.WORD, "type");
                if (type == null || !ORDER.contains(type)) {
                    omitted |= semantic;
                    continue;
                }
                try {
                    final var parsed = DocxBodyView.contents(properties, CTPPrGeneral.Factory.newInstance());
                    if (type.equals("wholeTable")) {
                        // Strip outlineLvl from wholeTable layers since Word ignores wholeTable outline levels.
                        if (parsed.isSetOutlineLvl()) {
                            parsed.unsetOutlineLvl();
                        }
                        whole.add(parsed);
                    } else {
                        conditions.computeIfAbsent(type, ignored -> new ArrayList<>()).add(parsed);
                    }
                } catch (final XmlException error) {
                    throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT,
                            "Invalid table-style paragraph properties", error);
                }
            }
            id = style.getBasisStyleID();
        }
        // Whole-table conditional numbering outranks general style pPr across the inheritance chain.
        whole.addAll(general);
        final var frozen = new java.util.LinkedHashMap<String, List<CTPPrGeneral>>();
        conditions.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        return new Layers(selected, implicit, List.copyOf(whole), false, omitted, java.util.Map.copyOf(frozen),
                List.copyOf(settings));
    }
}
