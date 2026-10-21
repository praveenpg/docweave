package dev.docweave.validation;

import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipFile;

/** Independent stored-XML clues for the audited warning causes; not a shape renderer or full style resolver. */
final class CorpusOmissionSource {
    private static final String VML = "urn:schemas-microsoft-com:vml";
    private static final String OFFICE = "urn:schemas-microsoft-com:office:office";

    /** Prevents construction of a stateless diagnostic utility. */
    private CorpusOmissionSource() {
    }

    /**
     * Counts simple nontext rectangles and unresolved direct table-style IDs in document.xml.
     * Stored branches are counted without accepted/rejected/AlternateContent selection. Direct
     * style checks deliberately do not resolve inheritance, built-ins or default-style fallback.
     * @param path independently hash-verified local package
     * @return immutable counts/IDs; no source paragraph, graphic payload or raw XML is retained
     * @throws IOException on malformed/oversized XML or invalid package entries
     */
    static Map<String, Object> inspect(final Path path) throws IOException {
        final var document = XmlOwnerReader.document(path);
        int rectangles = 0;
        int horizontal = 0;
        final var pictures = document.getElementsByTagNameNS(XmlOwnerReader.WORD, "pict");
        for (int index = 0; index < pictures.getLength(); index++) {
            final var children = ((Element) pictures.item(index)).getElementsByTagName("*");
            if (children.getLength() != 1) {
                continue;
            }
            final var shape = (Element) children.item(0);
            if (VML.equals(shape.getNamespaceURI()) && "rect".equals(shape.getLocalName())
                    && shape.getTextContent().isBlank() && shape.getAttribute("alt").isBlank()
                    && shape.getAttribute("title").isBlank()) {
                rectangles++;
                if ("t".equals(shape.getAttributeNS(OFFICE, "hr"))) {
                    horizontal++;
                }
            }
        }
        final var declared = new HashSet<String>();
        try (final var zip = new ZipFile(path.toFile())) {
            if (zip.getEntry("word/styles.xml") != null) {
                final var styles = XmlOwnerReader.document(path, "word/styles.xml")
                                           .getElementsByTagNameNS(XmlOwnerReader.WORD, "style");
                for (int index = 0; index < styles.getLength(); index++) {
                    final var style = (Element) styles.item(index);
                    if ("table".equals(style.getAttributeNS(XmlOwnerReader.WORD, "type"))) {
                        declared.add(style.getAttributeNS(XmlOwnerReader.WORD, "styleId"));
                    }
                }
            }
        }
        final var missing = new TreeSet<String>();
        final var references = document.getElementsByTagNameNS(XmlOwnerReader.WORD, "tblStyle");
        for (int index = 0; index < references.getLength(); index++) {
            final String id = ((Element) references.item(index)).getAttributeNS(XmlOwnerReader.WORD, "val");
            if (!id.isBlank() && !declared.contains(id)) {
                missing.add(id);
            }
        }
        return Map.of("empty_vml_rectangles", rectangles, "vml_rectangles_with_office_hr_t", horizontal,
                "unresolved_direct_table_style_ids", List.copyOf(missing));
    }
}
