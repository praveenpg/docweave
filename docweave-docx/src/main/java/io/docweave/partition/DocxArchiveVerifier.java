package io.docweave.partition;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

/** Per-document package preflight; never writes archive entries to disk or changes global POI limits. */
final class DocxArchiveVerifier {
    /** Content type for a non-macro main WordprocessingML document part. */
    static final String MAIN_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    /** DOCX archive and XML resource options. */
    private final DocxOptions options;
    /** Hardened StAX factory with DTDs and external entities disabled. */
    private final XMLInputFactory factory;
    /** Total XML start elements observed across all parts in the archive. */
    private long nodes;

    /**
     * Creates an archive verifier configured with the given DOCX options.
     *
     * @param options DOCX archive and XML resource options
     */
    DocxArchiveVerifier(final DocxOptions options) {
        this.options = options;
        factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External resolution is forbidden");
        });
    }

    /**
     * Verifies archive structure, entry names, size budgets, content types, and XML parts.
     *
     * @param bytes raw DOCX archive bytes
     * @throws IOException if the thread is interrupted during verification
     */
    void verify(final byte[] bytes) throws IOException {
        final var parts = new LinkedHashMap<String, byte[]>();
        final Set<String> names = new HashSet<>();
        long total = 0;
        int count = 0;
        try (final var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            // Stream all ZIP entries in memory while enforcing entry count, path safety, macro rejection, and byte
            // budgets.
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                ElementCollector.checkInterrupted();
                if (++count > options.maxArchiveEntries()) {
                    throw limit();
                }
                final String name = entry.getName();
                if (!names.add(name) || name.startsWith("/") || name.contains("\\")
                        || java.util.Arrays.asList(name.split("/")).contains("..")) {
                    throw malformed();
                }
                if (name.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) {
                    throw new PartitionException(
                            PartitionException.Code.UNSUPPORTED_FORMAT, "Macro-enabled Word packages are unsupported");
                }
                final var output = new ByteArrayOutputStream();
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    ElementCollector.checkInterrupted();
                    total += read;
                    if (total > options.maxExpandedBytes() || output.size() + (long) read > options.maxEntryBytes()) {
                        throw limit();
                    }
                    output.write(buffer, 0, read);
                }
                if (!entry.isDirectory()) {
                    parts.put(name, output.toByteArray());
                }
                zip.closeEntry();
            }
        } catch (final PartitionException | java.io.InterruptedIOException error) {
            throw error;
        } catch (final IOException error) {
            throw malformed();
        }
        if (!parts.containsKey("[Content_Types].xml") || !parts.containsKey("word/document.xml")) {
            throw new PartitionException(
                    PartitionException.Code.UNSUPPORTED_FORMAT, "ZIP package is not a supported DOCX document");
        }
        // Parse [Content_Types].xml first to verify the main document content type and classify remaining XML parts.
        final var overrides = new HashMap<String, String>();
        final var defaults = new HashMap<String, String>();
        xml(parts.get("[Content_Types].xml"), overrides, defaults);
        if (!MAIN_TYPE.equals(overrides.get("/word/document.xml"))) {
            throw new PartitionException(
                    PartitionException.Code.UNSUPPORTED_FORMAT, "Word package is not a non-macro DOCX document");
        }
        // Validate every non-Content_Types part against macro content types and XML depth/node/entity budgets.
        for (final var part : parts.entrySet()) {
            final String name = part.getKey();
            if (name.equals("[Content_Types].xml")) {
                continue;
            }
            final String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            final String type = overrides.getOrDefault("/" + name, defaults.getOrDefault(extension, ""));
            if (type.toLowerCase(Locale.ROOT).contains("macroenabled")
                    || type.toLowerCase(Locale.ROOT).contains("vbaproject")) {
                throw new PartitionException(
                        PartitionException.Code.UNSUPPORTED_FORMAT, "Macro-enabled Word packages are unsupported");
            }
            if (extension.equals("xml") || extension.equals("rels") || type.toLowerCase(Locale.ROOT).endsWith("+xml")
                    || type.equals("application/xml") || type.equals("text/xml")) {
                xml(part.getValue(), null, null);
            }
        }
    }

    /**
     * Scans an XML part for depth/node budgets and forbidden DTD/entity constructs, optionally collecting content
     * types.
     *
     * @param bytes XML part bytes
     * @param overrides target map for {@code Override} part-name content types, or {@code null}
     * @param defaults target map for {@code Default} extension content types, or {@code null}
     * @throws IOException if the thread is interrupted during scanning
     */
    private void xml(final byte[] bytes, final Map<String, String> overrides, final Map<String, String> defaults)
            throws IOException {
        javax.xml.stream.XMLStreamReader reader = null;
        int depth = 0;
        try {
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
            // Stream-scan XML events to enforce element nesting depth, cumulative node count, and DTD/entity bans.
            while (reader.hasNext()) {
                ElementCollector.checkInterrupted();
                switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    if (++depth > options.maxXmlDepth() || ++nodes > options.maxXmlNodes()) {
                        throw limit();
                    }
                    if (overrides != null
                            && "http://schemas.openxmlformats.org/package/2006/content-types".equals(
                                    reader.getNamespaceURI())) {
                        final String type = reader.getAttributeValue(null, "ContentType");
                        if ("Override".equals(reader.getLocalName()) && type != null) {
                            overrides.put(reader.getAttributeValue(null, "PartName"), type);
                        } else if ("Default".equals(reader.getLocalName()) && type != null) {
                            defaults.put(reader.getAttributeValue(null, "Extension"), type);
                        }
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> depth--;
                case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
                    throw new PartitionException(
                            PartitionException.Code.UNSAFE_XML, "DOCX DTD and custom entities are forbidden");
                default -> {
                }
                }
            }
        } catch (final XMLStreamException error) {
            throw malformed();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (final XMLStreamException ignored) {
                }
            }
        }
    }

    /**
     * Creates an archive or XML resource budget exception.
     *
     * @return partition exception with {@link PartitionException.Code#ARCHIVE_LIMIT}
     */
    private PartitionException limit() {
        return new PartitionException(
                PartitionException.Code.ARCHIVE_LIMIT, "DOCX archive or XML resource budget exceeded");
    }

    /**
     * Creates a malformed DOCX package exception.
     *
     * @return partition exception with {@link PartitionException.Code#MALFORMED_DOCUMENT}
     */
    private PartitionException malformed() {
        return new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, "DOCX package is malformed");
    }
}
