package io.docweave.validation;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Bounded independent source traversal; source text is never returned in inventory reports. */
final class DefaultDocxStyleProbe implements DocxStyleProbe {
    static final String STYLE = "DocWeaveConditionalCorpusProbeV1";
    private static final String WORD = XmlOwnerReader.WORD;
    private static final List<String> REGIONS = List.of("firstRow", "lastRow", "firstCol", "lastCol", "band1Horz",
            "band2Horz", "band1Vert", "band2Vert", "nwCell", "neCell", "swCell", "seCell");
    private static final int MAX_PART = 16 * 1024 * 1024;
    private static final long MAX_PACKAGE = 128L * 1024 * 1024;

    @Override
    public Map<String, Object> inspect(final Path source) throws IOException {
        final var document = XmlOwnerReader.document(source);
        final var styles = XmlOwnerReader.document(source, "word/styles.xml");
        final var conditions = descendants(styles, "tblStylePr");
        final var counts = new TreeMap<String, Integer>();
        int semantic = 0;
        for (final var condition : conditions) {
            final String region =
                    condition.hasAttributeNS(WORD, "type") ? condition.getAttributeNS(WORD, "type") : "missing";
            counts.merge(region, 1, Integer::sum);
            final var properties = child(condition, "pPr");
            if (properties != null && (child(properties, "numPr") != null || child(properties, "outlineLvl") != null)) {
                semantic++;
            }
        }
        int nonblank = 0;
        final var tables = descendants(document, "tbl");
        for (final var table : tables) {
            for (final var paragraph : descendants(table, "p")) {
                if (descendants(paragraph, "t")
                                .stream()
                                .anyMatch(text -> !DocxOwnership.normalize(text.getTextContent()).isEmpty())) {
                    nonblank++;
                }
            }
        }
        String producer = null;
        try (final var zip = new ZipFile(source.toFile())) {
            if (zip.getEntry("docProps/app.xml") != null) {
                final var app = XmlOwnerReader.document(source, "docProps/app.xml");
                for (var node = app.getDocumentElement().getFirstChild(); node != null; node = node.getNextSibling()) {
                    if ("Application".equals(node.getLocalName()) && node.getNamespaceURI() != null) {
                        producer = node.getTextContent().isEmpty() ? null : node.getTextContent();
                        break;
                    }
                }
            }
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("tables", tables.size());
        result.put("stored_table_paragraph_visits_nonblank", nonblank);
        result.put(
                "paragraph_visit_limit", "Nested table paragraphs can be counted more than once; not unique ownership");
        result.put("conditional_definitions", Map.copyOf(counts));
        result.put("semantic_conditional_definitions", semantic);
        result.put("stored_cnf_flags", descendants(document, "cnfStyle").size());
        result.put("grid_spans", descendants(document, "gridSpan").size());
        result.put("vertical_merge_markers", descendants(document, "vMerge").size());
        result.put("horizontal_merge_markers", descendants(document, "hMerge").size());
        result.put("producer_metadata", producer);
        result.put("producer_limit", "Self-reported package metadata, not proof of authorship/application");
        return java.util.Collections.unmodifiableMap(result);
    }

    @Override
    public byte[] derive(final Path source, final Mode mode) throws IOException {
        Objects.requireNonNull(mode, "mode");
        final var document = XmlOwnerReader.document(source);
        final var styles = XmlOwnerReader.document(source, "word/styles.xml");
        if (descendants(styles, "style")
                        .stream()
                        .anyMatch(style -> STYLE.equals(style.getAttributeNS(WORD, "styleId")))) {
            throw new IOException("Probe style ID collides with source");
        }
        final var style = styles.createElementNS(WORD, "w:style");
        style.setAttributeNS(WORD, "w:type", "table");
        style.setAttributeNS(WORD, "w:styleId", STYLE);
        property(ensure(style, "pPr"), "outlineLvl", "9");
        for (int index = 0; index < REGIONS.size(); index++) {
            final var condition = styles.createElementNS(WORD, "w:tblStylePr");
            condition.setAttributeNS(WORD, "w:type", REGIONS.get(index));
            property(ensure(condition, "pPr"), "outlineLvl", Integer.toString(index % 9));
            style.appendChild(condition);
        }
        styles.getDocumentElement().appendChild(style);
        for (final var flag : descendants(document, "cnfStyle")) {
            flag.getParentNode().removeChild(flag);
        }
        for (final var table : descendants(document, "tbl")) {
            final var properties = ensure(table, "tblPr");
            property(properties, "tblStyle", STYLE);
            final var controls = new ArrayList<Element>();
            controls.add(properties);
            controls.addAll(descendants(table, "tblPrEx"));
            for (final var control : controls) {
                property(control, "tblLook", mode == Mode.DISABLED ? "0600" : "01E0");
                property(control, "tblStyleRowBandSize", "1");
                property(control, "tblStyleColBandSize", "1");
            }
            if (mode == Mode.DIRECT_OVERRIDE) {
                for (final var paragraph : descendants(table, "p")) {
                    property(ensure(paragraph, "pPr"), "outlineLvl", "9");
                }
            }
        }
        final var output = new ByteArrayOutputStream();
        try (final var zip = new ZipFile(source.toFile()); final var target = new ZipOutputStream(output)) {
            long total = 0;
            int count = 0;
            for (final var entry : zip.stream().toList()) {
                if (++count > 4096) {
                    throw new IOException("Probe package exceeds entry budget");
                }
                final byte[] bytes;
                if (entry.getName().equals("word/document.xml")) {
                    bytes = serialize(document);
                } else if (entry.getName().equals("word/styles.xml")) {
                    bytes = serialize(styles);
                } else {
                    try (final var input = zip.getInputStream(entry)) {
                        bytes = input.readNBytes(MAX_PART + 1);
                    }
                }
                total += bytes.length;
                if (bytes.length > MAX_PART || total > MAX_PACKAGE) {
                    throw new IOException("Probe package exceeds byte budget");
                }
                final var copy = new ZipEntry(entry.getName());
                copy.setTime(
                        0); // Stable derivatives across runs; original compressed bytes/timestamps are not claimed.
                target.putNextEntry(copy);
                target.write(bytes);
                target.closeEntry();
            }
        }
        return output.toByteArray();
    }

    /** Snapshots descendant nodes before DOM mutation, avoiding live NodeList traversal bugs. */
    private static List<Element> descendants(final Node parent, final String local) {
        final var nodes = parent instanceof Document document ? document.getElementsByTagNameNS(WORD, local)
                                                              : ((Element) parent).getElementsByTagNameNS(WORD, local);
        final var result = new ArrayList<Element>();
        for (int index = 0; index < nodes.getLength(); index++) {
            result.add((Element) nodes.item(index));
        }
        return result;
    }

    /** Finds only a direct child, so paragraph/table layers remain distinct. */
    private static Element child(final Node parent, final String local) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (WORD.equals(node.getNamespaceURI()) && local.equals(node.getLocalName())) {
                return (Element) node;
            }
        }
        return null;
    }

    /** Creates missing property containers at the start, matching the legacy instrumentation. */
    private static Element ensure(final Element parent, final String local) {
        final var existing = child(parent, local);
        if (existing != null) {
            return existing;
        }
        final var created = parent.getOwnerDocument().createElementNS(WORD, "w:" + local);
        parent.insertBefore(created, parent.getFirstChild());
        return created;
    }

    /** Replaces the direct property while leaving unrelated source properties intact. */
    private static void property(final Element parent, final String local, final String value) {
        final var old = child(parent, local);
        if (old != null) {
            parent.removeChild(old);
        }
        final var created = parent.getOwnerDocument().createElementNS(WORD, "w:" + local);
        created.setAttributeNS(WORD, "w:val", value);
        parent.appendChild(created);
    }

    /** Serializes DOM while retaining namespace declarations, without external stylesheet access. */
    private static byte[] serialize(final Document document) throws IOException {
        try {
            final var factory = TransformerFactory.newDefaultInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            final var output = new ByteArrayOutputStream();
            factory.newTransformer().transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (final TransformerException error) {
            throw new IOException("Cannot serialize instrumented probe", error);
        }
    }
}
