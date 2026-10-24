package io.docweave.validation;

import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * JDK-only source oracle. XML paths count elements, not whitespace/comments, matching saved evidence.
 * Reads at most 16 MiB of document.xml, rejects DTD/external entities, and caps XML depth at 256.
 * Package validity and extraction limits are still independently checked by the production parser.
 */
final class XmlOwnerReader {
    static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final int MAX_XML_BYTES = 16 * 1024 * 1024;
    private static final Set<String> EXCLUDED = Set.of("del", "moveFrom", "pPr", "rPr", "sdtPr", "sdtEndPr");
    private static final Set<String> WRAPPERS = Set.of("sdt", "sdtContent", "customXml", "ins", "moveTo");
    private final List<DocxOwnership.Owner> owners = new ArrayList<>();

    /** Parses a single, unambiguous document.xml entry; no relationship or field target is resolved. */
    List<DocxOwnership.Owner> read(final Path path) throws IOException {
        final var root = document(path).getDocumentElement();
        final var body =
                children(root)
                        .stream()
                        .map(Child::node)
                        .filter(node -> word(node, "body"))
                        .findFirst()
                        .orElseThrow(() -> new IOException("Ownership baseline requires a Word document body"));
        blocks(body, "body", DocxOwnership.Story.BODY, null);
        return List.copyOf(owners);
    }

    /** Shared secure XML reader; callers implement independent ownership/inventory traversal policies. */
    static org.w3c.dom.Document document(final Path path) throws IOException {
        return document(path, "word/document.xml");
    }

    /** Reads one bounded package XML part with the same secure settings as the ownership oracle. */
    static org.w3c.dom.Document document(final Path path, final String part) throws IOException {
        try (final var zip = new ZipFile(path.toFile())) {
            if (zip.stream().filter(entry -> entry.getName().equals(part)).count() != 1) {
                throw new IOException("Diagnostic requires exactly one " + part + " entry");
            }
            try (final var input = zip.getInputStream(zip.getEntry(part))) {
                return parse(input.readNBytes(MAX_XML_BYTES + 1));
            }
        }
    }

    /** Parses verified in-memory XML bytes with the same budgets and external-access rejection. */
    static org.w3c.dom.Document parse(final byte[] xml) throws IOException {
        if (xml.length > MAX_XML_BYTES) {
            throw new IOException("Ownership XML exceeds 16 MiB diagnostic limit");
        }
        try {
            final var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("jdk.xml.maxElementDepth", 256);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            final var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void fatalError(final org.xml.sax.SAXParseException error) throws SAXException {
                    throw error;
                }
            });
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (final ParserConfigurationException | SAXException error) {
            throw new IOException("Invalid ownership source XML", error);
        }
    }

    private void blocks(final Node node, final String path, final DocxOwnership.Story story, final String parent)
            throws IOException {
        for (final var child : accepted(node, path)) {
            if (word(child.node(), "p") || word(child.node(), "tbl")) {
                block(child.node(), child.path(), story, parent);
            } else if (WORD.equals(child.node().getNamespaceURI()) && WRAPPERS.contains(child.node().getLocalName())) {
                blocks(child.node(), child.path(), story, parent);
            } else if (containsBlock(child.node())) {
                throw new IOException("Ownership baseline encountered an unsupported block wrapper");
            }
        }
    }

    private void block(final Node node, final String path, final DocxOwnership.Story story, final String parent)
            throws IOException {
        final var paragraphs = new ArrayList<String>();
        final var boxes = new ArrayList<Child>();
        collect(node, path, false, paragraphs, boxes);
        if (paragraphs.isEmpty() && word(node, "p")) {
            if (!boxes.isEmpty()) {
                throw new IOException("Ownership baseline requires nonblank text-box host paragraphs");
            }
            return;
        }
        owners.add(new DocxOwnership.Owner(path, story, parent,
                word(node, "tbl") ? DocxOwnership.Kind.TABLE : DocxOwnership.Kind.PARAGRAPH, paragraphs));
        for (final var box : boxes) {
            blocks(box.node(), box.path(), DocxOwnership.Story.TEXTBOX, path);
        }
    }

    private void collect(final Node node, final String path, final boolean inParagraph, final List<String> paragraphs,
            final List<Child> boxes) throws IOException {
        if (word(node, "txbxContent")) {
            boxes.add(new Child(node, path));
            return;
        }
        if (word(node, "p")) {
            if (inParagraph) {
                throw new IOException("Ownership baseline only supports nested paragraphs inside text boxes");
            }
            final var text = new StringBuilder();
            for (final var child : children(node)) {
                ownText(child.node(), text);
            }
            final String normalized = DocxOwnership.normalize(text.toString());
            if (!normalized.isEmpty()) {
                paragraphs.add(normalized);
            }
        }
        for (final var child : accepted(node, path)) {
            collect(child.node(), child.path(), inParagraph || word(node, "p"), paragraphs, boxes);
        }
    }

    private static void ownText(final Node node, final StringBuilder text) {
        if (excluded(node) || word(node, "txbxContent") || word(node, "p")) {
            return;
        }
        if (word(node, "t")) {
            text.append(node.getTextContent());
        } else if (word(node, "tab") || word(node, "br") || word(node, "cr")) {
            text.append(' ');
        } else {
            for (final var child : children(node)) {
                ownText(child.node(), text);
            }
        }
    }

    private static List<Child> accepted(final Node node, final String path) throws IOException {
        final var result = new ArrayList<Child>();
        for (final var child : children(node)) {
            final var value = child.node();
            if (excluded(value)) {
                continue;
            }
            if (MC.equals(value.getNamespaceURI()) && "AlternateContent".equals(value.getLocalName())) {
                throw new IOException("Ownership baseline does not select AlternateContent branches");
            }
            if (word(value, "tr")
                    && children(value)
                            .stream()
                            .map(Child::node)
                            .filter(n -> word(n, "trPr"))
                            .flatMap(n -> children(n).stream())
                            .anyMatch(n -> word(n.node(), "del"))) {
                continue;
            }
            result.add(new Child(value, path + "/" + child.path()));
        }
        return result;
    }

    private static boolean containsBlock(final Node node) {
        return word(node, "p") || word(node, "tbl")
                || children(node).stream().anyMatch(child -> containsBlock(child.node()));
    }

    private static boolean excluded(final Node node) {
        return WORD.equals(node.getNamespaceURI()) && EXCLUDED.contains(node.getLocalName());
    }

    private static boolean word(final Node node, final String local) {
        return WORD.equals(node.getNamespaceURI()) && local.equals(node.getLocalName());
    }

    private static List<Child> children(final Node node) {
        final var children = new ArrayList<Child>();
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                children.add(new Child(child, Integer.toString(children.size())));
            }
        }
        return children;
    }

    private record Child(Node node, String path) {}
}
