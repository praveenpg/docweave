package io.docweave.partition;

import io.docweave.core.ElementType;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Per-document streaming state; parent text is flushed before children to preserve source order. */
final class XmlStreamExtractor {
    /**
     * Open XML element frame on the document traversal stack.
     * @param name expanded element tag name
     * @param path ancestor-to-self expanded tag path
     * @param attributes captured attribute map when attribute retention is enabled
     * @param preserve effective {@code xml:space} preservation state for this subtree
     */
    private record Frame(String name, List<String> path, Map<String, Object> attributes, boolean preserve) {}

    /** Active document partition context receiving emitted elements. */
    private final PartitionContext context;
    /** Configured XML depth, node-count and attribute/whitespace options. */
    private final XmlOptions options;
    /** Open ancestor element frames from root to current element. */
    private final ArrayDeque<Frame> stack = new ArrayDeque<>();
    /** Buffered character content for the currently active element frame. */
    private final StringBuilder text = new StringBuilder();
    /** Cumulative count of visited XML start-element nodes. */
    private int nodes;

    /**
     * Creates a document-local streaming XML extractor.
     * @param context active partition context
     */
    XmlStreamExtractor(final PartitionContext context) {
        this.context = context;
        options = context.options().xmlOptions();
    }

    /**
     * Streams the XML document and emits text elements in document order.
     * @throws IOException if malformed/unsafe XML, resource limits or interruption occurs
     */
    void extract() throws IOException {
        final var factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External XML resolution is forbidden");
        });
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new StringReader(context.text()));
            // Stream StAX events in document order, flushing buffered parent text before descending into child elements
            // or closing the current element.
            while (reader.hasNext()) {
                ElementCollector.checkInterrupted();
                switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> start(reader);
                case XMLStreamConstants.END_ELEMENT -> {
                    flush();
                    stack.removeLast();
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if (!stack.isEmpty()) {
                        text.append(reader.getText());
                    }
                }
                case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
                    throw new PartitionException(
                            PartitionException.Code.UNSAFE_XML, "DTD and custom entities are forbidden");
                default -> { /* Comments and processing instructions do not split text. */
                }
                }
            }
        } catch (final XMLStreamException error) {
            // The reader may include source text in its diagnostic; expose only a sanitized message.
            throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, "XML is not well formed");
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (final XMLStreamException ignored) { /* StringReader owns no external resource. */
                }
            }
        }
    }

    /**
     * Flushes pending parent text, enforces depth/node budgets and pushes a new element frame.
     * @param reader active StAX stream positioned at {@link XMLStreamConstants#START_ELEMENT}
     * @throws IOException if depth/node limits or interruption fails
     */
    private void start(XMLStreamReader reader) throws IOException {
        flush();
        if (stack.size() >= options.maxDepth()) {
            throw new PartitionException(PartitionException.Code.XML_DEPTH_LIMIT, "XML nesting limit exceeded");
        }
        if (++nodes > options.maxNodes()) {
            throw new PartitionException(PartitionException.Code.XML_NODE_LIMIT, "XML element-node limit exceeded");
        }
        final String name = expanded(reader.getName());
        final var path = new java.util.ArrayList<String>();
        if (!stack.isEmpty()) {
            path.addAll(stack.getLast().path());
        }
        path.add(name);
        final var attributes = new LinkedHashMap<String, Object>();
        if (options.includeAttributes()) {
            for (int i = 0; i < reader.getAttributeCount(); i++) {
                ElementCollector.checkInterrupted();
                attributes.put(expanded(reader.getAttributeName(i)), reader.getAttributeValue(i));
            }
        }
        // Inherit xml:space preservation from the parent frame unless overridden by an explicit xml:space attribute on
        // this element.
        boolean preserve = !stack.isEmpty() && stack.getLast().preserve();
        final String space = reader.getAttributeValue(XMLConstants.XML_NS_URI, "space");
        if ("preserve".equals(space)) {
            preserve = true;
        } else if ("default".equals(space)) {
            preserve = false;
        }
        stack.addLast(new Frame(name, List.copyOf(path), Map.copyOf(attributes), preserve));
    }

    /**
     * Emits accumulated character data for the current element frame when nonblank.
     * @throws IOException if element emission or text normalization is interrupted
     */
    private void flush() throws IOException {
        if (text.isEmpty()) {
            return;
        }
        final var frame = stack.getLast();
        final String value = options.preserveWhitespace() || frame.preserve() ? text.toString()
                                                                              : TextSupport.normalize(text.toString());
        text.setLength(0);
        if (TextSupport.blank(value)) {
            return;
        }
        final var metadata = new LinkedHashMap<String, Object>();
        metadata.put("xml_tag", frame.name());
        metadata.put("xml_path", frame.path());
        metadata.put("category_depth", stack.size() - 1);
        if (options.includeAttributes() && !frame.attributes().isEmpty()) {
            metadata.put("xml_attributes", frame.attributes());
        }
        context.emit(ElementType.UncategorizedText, value, metadata);
    }

    /**
     * Formats a qualified XML name in Clark notation when a namespace URI is present.
     * @param name StAX qualified name
     * @return local name or {@code {namespace}localPart}
     */
    private static String expanded(final QName name) {
        return name.getNamespaceURI().isEmpty() ? name.getLocalPart()
                                                : "{" + name.getNamespaceURI() + "}" + name.getLocalPart();
    }
}
