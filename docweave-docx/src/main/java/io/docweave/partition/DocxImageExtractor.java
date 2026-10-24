package io.docweave.partition;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.w3c.dom.Node;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves package media without fetching external references, rendering vectors or decoding pixels. */
final class DocxImageExtractor {
    /**
     * Extracted image description and metadata.
     *
     * @param text normalized alt text or title
     * @param metadata image relationship and payload metadata
     */
    record Image(String text, Map<String, Object> metadata) {}

    /** DOCX content extraction options. */
    private final DocxContentOptions options;

    /**
     * Creates an image extractor configured with the given content options.
     *
     * @param options DOCX content extraction options
     */
    DocxImageExtractor(final DocxContentOptions options) {
        this.options = options;
    }

    /**
     * Extracts image alt text and relationship/payload metadata from a supplement node.
     *
     * @param source image supplement descriptor
     * @return extracted image text and metadata
     * @throws IOException if reading an embedded image part fails or is interrupted
     */
    Image extract(final DocxSupplement source) throws IOException {
        final Node node = source.node();
        String id = null;
        // Resolve the relationship ID from DrawingML (r:embed / r:link) or VML (r:id) attributes.
        for (final String attribute : new String[] {"embed", "link", "id"}) {
            id = DocxBodyView.attribute(node, DocxParagraphText.REL, attribute);
            if (id != null) {
                break;
            }
        }
        final var owner = source.owner().getPart().getPackagePart();
        final var relation = id == null ? null : owner.getRelationship(id);
        if (relation == null || !relation.getRelationshipType().endsWith("/image")) {
            throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, "Invalid DOCX image relationship");
        }
        final var metadata = new LinkedHashMap<String, Object>();
        final String alt = description(node);
        if (relation.getTargetMode() == TargetMode.EXTERNAL) {
            metadata.put("image_url", relation.getTargetURI().toString());
            metadata.put("docx_image_external", true);
        } else {
            try {
                final var part = owner.getRelatedPart(relation);
                final String name = part.getPartName().getName();
                metadata.put("docx_image_part", name);
                metadata.put("image_mime_type", part.getContentType());
                metadata.put("image_filename", name.substring(name.lastIndexOf('/') + 1));
                // When image embedding is enabled, enforce the per-image byte cap and record Base64 and SHA-256
                // metadata.
                if (options.images() == DocxContentOptions.Images.EMBED) {
                    ElementCollector.checkInterrupted();
                    if (part.getSize() > options.maxEmbeddedImageBytes()) {
                        throw limit();
                    }
                    byte[] bytes;
                    try (final var stream = part.getInputStream()) {
                        bytes = stream.readNBytes(options.maxEmbeddedImageBytes() + 1);
                    }
                    if (bytes.length > options.maxEmbeddedImageBytes()) {
                        throw limit();
                    }
                    ElementCollector.checkInterrupted();
                    metadata.put("image_base64", Base64.getEncoder().encodeToString(bytes));
                    try {
                        metadata.put("image_sha256",
                                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                    } catch (final NoSuchAlgorithmException error) {
                        throw new IllegalStateException("SHA-256 unavailable", error);
                    }
                }
            } catch (final InvalidFormatException error) {
                throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, "Invalid DOCX image target");
            }
        }
        ElementCollector.checkInterrupted();
        return new Image(alt, Map.copyOf(metadata));
    }

    /**
     * Resolves normalized alt text or fallback title/name from the enclosing drawing or picture container.
     *
     * @param image image reference DOM node
     * @return normalized description text, or an empty string if none is present
     * @throws IOException if the thread is interrupted during DOM inspection
     */
    private String description(final Node image) throws IOException {
        Node root = image;
        // Climb to the enclosing w:drawing or w:pict container so docPr/cNvPr sibling metadata is reachable.
        for (Node parent = image.getParentNode(); parent != null; parent = parent.getParentNode()) {
            ElementCollector.checkInterrupted();
            root = parent;
            if (DocxBodyView.WORD.equals(parent.getNamespaceURI())
                    && ("drawing".equals(parent.getLocalName()) || "pict".equals(parent.getLocalName()))) {
                break;
            }
        }
        String fallback = "";
        final var pending = new ArrayDeque<Node>();
        pending.push(root);
        // Perform a preorder traversal of the drawing container, preferring descr over title/name fallback.
        while (!pending.isEmpty()) {
            ElementCollector.checkInterrupted();
            final Node node = pending.pop();
            if ("docPr".equals(node.getLocalName()) || "cNvPr".equals(node.getLocalName())) {
                for (final String key : new String[] {"descr", "title", "name"}) {
                    final String value = DocxBodyView.attribute(node, null, key);
                    if (value != null && !value.isBlank()) {
                        if (key.equals("descr")) {
                            return TextSupport.normalize(value);
                        }
                        if (fallback.isEmpty()) {
                            fallback = TextSupport.normalize(value);
                        }
                    }
                }
            }
            for (Node child = node.getLastChild(); child != null; child = child.getPreviousSibling()) {
                pending.push(child);
            }
        }
        return fallback;
    }

    /**
     * Creates an embedded-image payload budget exception.
     *
     * @return partition exception with {@link PartitionException.Code#IMAGE_LIMIT}
     */
    private PartitionException limit() {
        return new PartitionException(
                PartitionException.Code.IMAGE_LIMIT, "DOCX embedded-image payload budget exceeded");
    }
}
