package io.docweave.validation;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Bounded in-memory OPC payload snapshot used only by owned fixture generation/tests. */
final class FixturePackage {
    private static final int MAX_PART = 16 * 1024 * 1024;
    private final Map<String, byte[]> parts = new TreeMap<>();

    /** Rejects duplicate entries and expansion beyond 4096 entries/128 MiB before any destination write. */
    FixturePackage(final byte[] bytes) throws IOException {
        try (final var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            long total = 0;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                final byte[] data = zip.readNBytes(MAX_PART + 1);
                total += data.length;
                if (data.length > MAX_PART || total > 128L * 1024 * 1024 || parts.size() >= 4096
                        || parts.putIfAbsent(entry.getName(), data) != null) {
                    throw new IOException("Fixture package exceeds budgets or contains duplicate entries");
                }
            }
        }
        if (!parts.containsKey("word/document.xml")) {
            throw new IOException("Fixture package requires word/document.xml");
        }
    }

    /** Uses the shared secure XML parser, preserving namespaces and property order. */
    Document xml(final String name) throws IOException {
        final byte[] bytes = parts.get(name);
        if (bytes == null) {
            throw new IOException("Missing fixture part: " + name);
        }
        return XmlOwnerReader.parse(bytes);
    }

    /** Serializes only explicitly changed parts; other uncompressed payloads retain exact bytes. */
    void replace(final String name, final Document document) throws IOException {
        try {
            final var factory = TransformerFactory.newDefaultInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            final var output = new ByteArrayOutputStream();
            factory.newTransformer().transform(new DOMSource(document), new StreamResult(output));
            parts.put(name, output.toByteArray());
        } catch (final TransformerException error) {
            throw new IOException("Cannot serialize fixture XML", error);
        }
    }

    /** Stable entry order/timestamps make repeated Java generation deterministic. */
    byte[] bytes() throws IOException {
        final var output = new ByteArrayOutputStream();
        try (final var zip = new ZipOutputStream(output)) {
            for (final var part : parts.entrySet()) {
                final var entry = new ZipEntry(part.getKey());
                entry.setTimeLocal(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
                zip.putNextEntry(entry);
                zip.write(part.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    /** Takes a stable descendant snapshot, allowing safe DOM mutation during traversal. */
    static List<Element> descendants(final Node parent, final String local) {
        final var nodes = parent instanceof Document document
                                  ? document.getElementsByTagNameNS(XmlOwnerReader.WORD, local)
                                  : ((Element) parent).getElementsByTagNameNS(XmlOwnerReader.WORD, local);
        final var result = new ArrayList<Element>();
        for (int index = 0; index < nodes.getLength(); index++) {
            result.add((Element) nodes.item(index));
        }
        return result;
    }

    /** Finds a direct Word child without conflating nested property layers. */
    static Element child(final Node parent, final String local) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (XmlOwnerReader.WORD.equals(node.getNamespaceURI()) && local.equals(node.getLocalName())) {
                return (Element) node;
            }
        }
        return null;
    }

    /** Appends an owned Word element, setting a namespaced attribute when requested. */
    static Element append(final Node parent, final String local, final String attribute, final String value) {
        final var node = parent.getOwnerDocument().createElementNS(XmlOwnerReader.WORD, "w:" + local);
        if (attribute != null) {
            node.setAttributeNS(XmlOwnerReader.WORD, "w:" + attribute, value);
        }
        parent.appendChild(node);
        return node;
    }
}
