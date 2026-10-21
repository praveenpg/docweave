package dev.docweave.validation;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Shared bounded publisher-reference decoding; external declarations never trigger resource access. */
final class ScientificPublisherXml {
    /** Prevents utility instantiation. */
    private ScientificPublisherXml() {
    }

    /**
     * Allows inert external publisher DOCTYPE declarations while rejecting internal/unresolved entities.
     * @param bytes unchanged publisher XML, at most16MiB
     * @return original DOM without reference text repair
     * @throws Exception if XML, size or entity policy fails
     */
    static Document parse(final byte[] bytes) throws Exception {
        if (bytes.length > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Publisher XML exceeds16MiB");
        }
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        final var parsed = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        if (parsed.getDoctype() != null && parsed.getDoctype().getInternalSubset() != null) {
            throw new IllegalArgumentException("Internal publisher DTD declarations are unsupported");
        }
        final var pending = new ArrayDeque<Node>();
        pending.add(parsed);
        while (!pending.isEmpty()) {
            final var node = pending.removeLast();
            if (node.getNodeType() == Node.ENTITY_REFERENCE_NODE) {
                throw new IllegalArgumentException("Unresolved publisher entity reference");
            }
            for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                pending.add(child);
            }
        }
        return parsed;
    }
}
