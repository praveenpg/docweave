package dev.docweave.partition;

import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlException;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.w3c.dom.Node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Ordered Word body adapter retaining content-control children and explicit revision selection. */
final class DocxBodyView {
    /** WordprocessingML XML namespace URI. */
    static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    /** DOCX content extraction options. */
    private final DocxContentOptions options;
    /** Diagnostic consumer for reporting omitted content. */
    private final Consumer<String> reportOmission;

    /**
     * Uses the document's diagnostic sink without resolving imported-content relationships.
     *
     * @param context active partition context
     */
    DocxBodyView(final PartitionContext context) {
        this(context.options().docxOptions().contentOptions(),
                message -> context.warn(PartitionResult.WarningCode.DOCX_CONTENT_OMITTED, message));
    }

    /**
     * Separates ordered source traversal from diagnostic storage for adapter callers.
     *
     * @param options DOCX content extraction options
     * @param reportOmission diagnostic sink for omitted content messages
     */
    DocxBodyView(final DocxContentOptions options, final Consumer<String> reportOmission) {
        this.options = Objects.requireNonNull(options, "options");
        this.reportOmission = Objects.requireNonNull(reportOmission, "reportOmission");
    }

    /** Cached original DOM roots indexed by package part name and body key. */
    private final java.util.Map<String, java.util.Map<String, Node>> originalParts = new java.util.HashMap<>();

    /**
     * Returns the ordered body elements of the given Word body part.
     *
     * @param body Word body part
     * @return immutable list of ordered body elements
     * @throws IOException if reading the source part fails or is interrupted
     */
    List<IBodyElement> elements(final IBody body) throws IOException {
        return elements(originalRoot(body), body);
    }

    /**
     * Resolves the original unmutated DOM root for a Word body part when backed by a package stream.
     *
     * @param body Word body part
     * @return DOM node representing the body root
     * @throws IOException if parsing the underlying package part fails or is interrupted
     */
    private Node originalRoot(final IBody body) throws IOException {
        if (body instanceof XWPFTableCell) {
            return root(body);
        }
        final var part = body.getPart().getPackagePart();
        if (part.getSize() == 0) {
            return root(body);
        } // Caller-owned unsaved POI objects used by adapters/tests.
        final String name = part.getPartName().getName();
        var index = originalParts.get(name);
        if (index == null) {
            // Parse the raw package stream once per part and index body, header, footer, note, and comment roots by ID.
            index = new java.util.HashMap<>();
            try (final var stream = part.getInputStream()) {
                final Node source = org.apache.xmlbeans.XmlObject.Factory.parse(stream).getDomNode();
                final var pending = new java.util.ArrayDeque<Node>();
                pending.push(source);
                while (!pending.isEmpty()) {
                    ElementCollector.checkInterrupted();
                    Node node = pending.pop();
                    if (WORD.equals(node.getNamespaceURI())
                            && java.util.Set.of("body", "hdr", "ftr", "footnote", "endnote", "comment")
                                    .contains(node.getLocalName())) {
                        final String id = attribute(node, WORD, "id");
                        index.putIfAbsent(node.getLocalName() + ":" + (id == null ? "" : id), node);
                    }
                    for (Node child = node.getLastChild(); child != null; child = child.getPreviousSibling()) {
                        pending.push(child);
                    }
                }
            } catch (final XmlException error) {
                throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, "Invalid Word source part");
            }
            originalParts.put(name, index);
        }
        final String key = switch (body) {
            case final XWPFDocument ignored -> "body:";
            case final XWPFHeader ignored -> "hdr:";
            case final XWPFFooter ignored -> "ftr:";
            case final XWPFFootnote note -> "footnote:" + note.getId();
            case final XWPFEndnote note -> "endnote:" + note.getId();
            case final XWPFComment comment -> "comment:" + comment.getId();
            default -> "";
        };
        return index.getOrDefault(key, root(body));
    }

    /**
     * Collects ordered body elements under a DOM root node for the specified owner body.
     *
     * @param root DOM root node to traverse
     * @param owner owning Word body part
     * @return immutable list of ordered body elements
     * @throws IOException if traversal fails or is interrupted
     */
    List<IBodyElement> elements(final Node root, final IBody owner) throws IOException {
        final var result = new ArrayList<IBodyElement>();
        collect(root, owner, result);
        return List.copyOf(result);
    }

    /**
     * Recursively collects paragraphs and tables from a DOM container into the output list.
     *
     * @param root DOM container node
     * @param owner owning Word body part
     * @param output mutable destination list of body elements
     * @throws IOException if traversal fails or is interrupted
     */
    private void collect(final Node root, final IBody owner, final List<IBodyElement> output) throws IOException {
        // Walk direct children in document order, unwrapping content controls (w:sdt) and revision containers.
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            ElementCollector.checkInterrupted();
            if (!WORD.equals(child.getNamespaceURI())) {
                continue;
            }
            final String local = child.getLocalName();
            if (!include(local, options.revisions())) {
                continue;
            }
            try {
                switch (local) {
                case "p" -> output.add(new XWPFParagraph(contents(child, CTP.Factory.newInstance()), owner));
                case "tbl" -> output.add(new XWPFTable(contents(child, CTTbl.Factory.newInstance()), owner, false));
                case "altChunk" ->
                    reportOmission.accept(
                            "DOCX imported content is not extracted; import relationships are not resolved");
                case "sdt" -> {
                    final Node content = child(child, "sdtContent");
                    if (content != null) {
                        collect(content, owner, output);
                    }
                }
                case "ins", "del", "moveTo", "moveFrom", "customXml" -> collect(child, owner, output);
                default -> {
                }
                }
            } catch (final XmlException error) {
                throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, "Invalid Word body structure");
            }
        }
    }

    /**
     * Returns the ordered rows of a Word table, unwrapping content controls and revisions.
     *
     * @param table Word table to inspect
     * @return immutable list of table rows
     * @throws IOException if traversal fails or is interrupted
     */
    List<XWPFTableRow> rows(final XWPFTable table) throws IOException {
        final var result = new ArrayList<XWPFTableRow>();
        collectRows(table.getCTTbl().getDomNode(), table, result);
        return List.copyOf(result);
    }

    /**
     * Recursively collects table rows from a DOM container into the output list.
     *
     * @param root DOM container node
     * @param table owning Word table
     * @param output mutable destination list of table rows
     * @throws IOException if traversal fails or is interrupted
     */
    private void collectRows(final Node root, final XWPFTable table, final List<XWPFTableRow> output)
            throws IOException {
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            ElementCollector.checkInterrupted();
            if (!WORD.equals(node.getNamespaceURI()) || !include(node.getLocalName(), options.revisions())) {
                continue;
            }
            try {
                switch (node.getLocalName()) {
                case "tr" -> {
                    // Skip rows marked deleted/inserted in w:trPr when excluded by the active revision policy.
                    final Node properties = child(node, "trPr");
                    if (properties != null
                            && (child(properties, "del") != null
                                            && options.revisions() == DocxContentOptions.Revisions.ACCEPTED
                                    || child(properties, "ins") != null
                                               && options.revisions() == DocxContentOptions.Revisions.ORIGINAL)) {
                        continue;
                    }
                    output.add(new XWPFTableRow(contents(node, CTRow.Factory.newInstance()), table));
                }
                case "sdt" -> {
                    final Node content = child(node, "sdtContent");
                    if (content != null) {
                        collectRows(content, table, output);
                    }
                }
                case "ins", "del", "moveTo", "moveFrom", "customXml" -> collectRows(node, table, output);
                default -> {
                }
                }
            } catch (final XmlException error) {
                throw new PartitionException(
                        PartitionException.Code.MALFORMED_DOCUMENT, "Invalid structured Word table row");
            }
        }
    }

    /**
     * Returns the ordered cells of a Word table row, unwrapping content controls and revisions.
     *
     * @param row Word table row to inspect
     * @return immutable list of table cells
     * @throws IOException if traversal fails or is interrupted
     */
    List<XWPFTableCell> cells(final XWPFTableRow row) throws IOException {
        final var result = new ArrayList<XWPFTableCell>();
        collectCells(row.getCtRow().getDomNode(), row, result);
        return List.copyOf(result);
    }

    /**
     * Recursively collects table cells from a DOM container into the output list.
     *
     * @param root DOM container node
     * @param row owning Word table row
     * @param output mutable destination list of table cells
     * @throws IOException if traversal fails or is interrupted
     */
    private void collectCells(final Node root, final XWPFTableRow row, final List<XWPFTableCell> output)
            throws IOException {
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            ElementCollector.checkInterrupted();
            if (!WORD.equals(child.getNamespaceURI()) || !include(child.getLocalName(), options.revisions())) {
                continue;
            }
            try {
                switch (child.getLocalName()) {
                case "tc" ->
                    output.add(new XWPFTableCell(
                            contents(child, CTTc.Factory.newInstance()), row, row.getTable().getBody()));
                case "sdt" -> {
                    final Node content = child(child, "sdtContent");
                    if (content != null) {
                        collectCells(content, row, output);
                    }
                }
                case "ins", "del", "moveTo", "moveFrom", "customXml" -> collectCells(child, row, output);
                default -> {
                }
                }
            } catch (final XmlException error) {
                throw new PartitionException(
                        PartitionException.Code.MALFORMED_DOCUMENT, "Invalid structured Word table cell");
            }
        }
    }

    /**
     * Copies the XML contents of a DOM node into a typed XMLBeans object.
     *
     * @param <T> target XMLBeans type
     * @param node source DOM node
     * @param target destination XMLBeans instance
     * @return populated target XMLBeans instance
     * @throws XmlException if parsing the DOM node fails
     */
    static <T extends org.apache.xmlbeans.XmlObject> T contents(Node node, final T target) throws XmlException {
        final var source = org.apache.xmlbeans.XmlObject.Factory.parse(node);
        try (final var from = source.newCursor(); final var to = target.newCursor()) {
            from.toFirstChild();
            to.toEndDoc();
            from.copyXmlContents(to);
        }
        return target;
    }

    /**
     * Checks whether an element local name should be traversed under the configured revision policy.
     *
     * @param local element local name
     * @param policy active revision selection policy
     * @return {@code true} if the element should be included
     */
    static boolean include(final String local, final DocxContentOptions.Revisions policy) {
        return switch (local) {
            case "ins", "moveTo" -> policy != DocxContentOptions.Revisions.ORIGINAL;
            case "del", "moveFrom" -> policy != DocxContentOptions.Revisions.ACCEPTED;
            default -> true;
        };
    }

    /**
     * Extracts the underlying XMLBeans DOM node from a POI {@link IBody} instance.
     *
     * @param body POI body instance
     * @return underlying DOM root node
     */
    static Node root(final IBody body) {
        return switch (body) {
            case final XWPFDocument document -> document.getDocument().getBody().getDomNode();
            case final XWPFTableCell cell -> cell.getCTTc().getDomNode();
            case final XWPFHeaderFooter part -> part._getHdrFtr().getDomNode();
            case final XWPFAbstractFootnoteEndnote note -> note.getCTFtnEdn().getDomNode();
            case final XWPFComment comment -> comment.getCtComment().getDomNode();
            default -> throw new IllegalArgumentException("Unsupported Word body");
        };
    }

    /**
     * Concatenates all text and CDATA node values under a DOM subtree.
     *
     * @param root DOM subtree root
     * @return concatenated text content
     * @throws IOException if the thread is interrupted during traversal
     */
    static String text(final Node root) throws IOException {
        final var value = new StringBuilder();
        final var pending = new java.util.ArrayDeque<Node>();
        pending.push(root);
        while (!pending.isEmpty()) {
            ElementCollector.checkInterrupted();
            Node node = pending.pop();
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                value.append(node.getNodeValue());
            } else {
                for (Node child = node.getLastChild(); child != null; child = child.getPreviousSibling()) {
                    pending.push(child);
                }
            }
        }
        return value.toString();
    }

    /**
     * Finds the first direct WordprocessingML child element with the given local name.
     *
     * @param root parent DOM node, or {@code null}
     * @param name local element name to match
     * @return matching child node, or {@code null} if not found
     */
    static Node child(final Node root, final String name) {
        if (root == null) {
            return null;
        }
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (WORD.equals(child.getNamespaceURI()) && name.equals(child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    /**
     * Reads a namespaced or unqualified attribute value from a DOM node.
     *
     * @param node DOM node to inspect
     * @param namespace attribute namespace URI, or {@code null}
     * @param name attribute local name
     * @return attribute value, or {@code null} if absent
     */
    static String attribute(Node node, final String namespace, final String name) {
        final Node value = node.getAttributes() == null ? null : node.getAttributes().getNamedItemNS(namespace, name);
        return value == null ? null : value.getNodeValue();
    }
}
