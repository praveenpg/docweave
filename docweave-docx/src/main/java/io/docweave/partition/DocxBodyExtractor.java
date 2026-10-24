package io.docweave.partition;

import io.docweave.core.ElementType;
import org.apache.poi.xwpf.usermodel.*;

import java.io.IOException;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Document-local heading/list state and ordered body traversal; no rendered pagination is inferred. */
final class DocxBodyExtractor {
    /** Nested-table preservation helper when metadata preservation is enabled, or {@code null}. */
    private final DocxNestedTables nested;
    /** Active partition context and element sink. */
    private final PartitionContext context;
    /** Parsed POI Word document. */
    private final XWPFDocument document;
    /** Ordered Word body view adapter. */
    private final DocxBodyView view;
    /** Paragraph style and numbering policy resolver. */
    private final DocxParagraphPolicyResolver policies;
    /** Active heading element IDs keyed by 1-based heading level. */
    private final TreeMap<Integer, String> headings = new TreeMap<>();
    /** Active list item element IDs keyed by numbering ID and list level. */
    private final Map<BigInteger, TreeMap<Integer, String>> lists = new java.util.HashMap<>();

    /**
     * Creates a body extractor for the given partition context and Word document.
     *
     * @param context active partition context
     * @param document parsed Word document
     */
    DocxBodyExtractor(final PartitionContext context, final XWPFDocument document) {
        this.nested = context.options().nestedTableOptions().mode() == NestedTableOptions.Mode.PRESERVE_METADATA
                              ? new DocxNestedTables(context.options().nestedTableOptions())
                              : null;
        this.context = context;
        this.document = document;
        this.view = new DocxBodyView(context);
        this.policies = new DocxParagraphPolicyResolver(document);
    }

    /** Emitted element IDs indexed by note/comment reference key ({@code kind:id}). */
    private final Map<String, java.util.LinkedHashSet<String>> references = new java.util.HashMap<>();
    /** Base metadata attributes for the currently active document part. */
    private Map<String, Object> part = Map.of();
    /** Default parent element ID for the currently active document part, or {@code null}. */
    private String partParent;
    /** Active numbering counter resolver for the current document part. */
    private DocxNumberingResolver numbering;

    /**
     * Extracts headers, main body, notes, comments, and footers according to configured options.
     *
     * @throws IOException if the thread is interrupted or package reading fails
     */
    void extract() throws IOException {
        if (context.options().docxOptions().includeHeadersAndFooters()) {
            for (final var header : document.getHeaderList()) {
                section(header, ElementType.Header, Map.of("docx_part", "header"), null);
            }
        }
        section(document, null, Map.of("docx_part", "body"), null);
        final var content = context.options().docxOptions().contentOptions();
        if (content.includeNotes()) {
            for (final var note : document.getFootnotes()) {
                note(note, "footnote");
            }
            for (final var note : document.getEndnotes()) {
                note(note, "endnote");
            }
        }
        if (content.includeComments() && document.getComments() != null) {
            for (final var comment : document.getComments()) {
                final var metadata = new LinkedHashMap<String, Object>();
                metadata.put("docx_part", "comment");
                metadata.put("docx_comment_id", comment.getId());
                if (comment.getAuthor() != null) {
                    metadata.put("docx_comment_author", comment.getAuthor());
                }
                final String parent = referenceMetadata(metadata, "comment", comment.getId());
                section(comment, null, metadata, parent);
            }
        }
        if (context.options().docxOptions().includeHeadersAndFooters()) {
            for (final var footer : document.getFooterList()) {
                section(footer, ElementType.Footer, Map.of("docx_part", "footer"), null);
            }
        }
    }

    /**
     * Extracts a non-separator footnote or endnote part.
     *
     * @param note footnote or endnote part
     * @param kind note kind ({@code "footnote"} or {@code "endnote"})
     * @throws IOException if extraction fails or is interrupted
     */
    private void note(final XWPFAbstractFootnoteEndnote note, final String kind) throws IOException {
        final String type = String.valueOf(note.getCTFtnEdn().getType());
        if (type.equals("separator") || type.equals("continuationSeparator") || type.equals("continuationNotice")) {
            return;
        }
        final var metadata = new LinkedHashMap<String, Object>();
        metadata.put("docx_part", kind);
        metadata.put("docx_note_id", note.getId().toString());
        final String parent = referenceMetadata(metadata, kind, note.getId().toString());
        section(note, null, metadata, parent);
    }

    /**
     * Populates {@code docx_reference_element_ids} on a note or comment metadata map and returns the first referencing
     * element ID.
     *
     * @param metadata mutable part metadata map
     * @param kind reference kind ({@code "footnote"}, {@code "endnote"}, or {@code "comment"})
     * @param id note or comment identifier
     * @return first referencing element ID, or {@code null} if unreferenced
     */
    private String referenceMetadata(final Map<String, Object> metadata, final String kind, final String id) {
        final var ids = references.get(kind + ":" + id);
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        metadata.put("docx_reference_element_ids", java.util.List.copyOf(ids));
        return ids.getFirst();
    }

    /**
     * Resets part-local heading/list/numbering state and extracts all elements in a body part.
     *
     * @param body Word body part to traverse
     * @param forced forced element type for paragraphs (such as {@link ElementType#Header}), or {@code null}
     * @param metadata base metadata for elements emitted in this part
     * @param parent default parent element ID for this part, or {@code null}
     * @throws IOException if traversal fails or is interrupted
     */
    private void section(final IBody body, final ElementType forced, final Map<String, Object> metadata,
            final String parent) throws IOException {
        headings.clear();
        lists.clear();
        part = Map.copyOf(metadata);
        partParent = parent;
        numbering = new DocxNumberingResolver(document);
        bodyElements(view.elements(body), forced);
    }

    /**
     * Extracts an ordered sequence of body elements (paragraphs and tables).
     *
     * @param elements ordered body elements to extract
     * @param forced forced element type for paragraphs, or {@code null}
     * @throws IOException if extraction fails or is interrupted
     */
    private void bodyElements(final java.util.List<IBodyElement> elements, final ElementType forced)
            throws IOException {
        for (final var element : elements) {
            ElementCollector.checkInterrupted();
            if (element instanceof final XWPFParagraph paragraph) {
                paragraph(paragraph, forced);
            } else if (element instanceof final XWPFTable table) {
                final var value = new DocxTableExtractor(context, document, numbering, nested).extract(table);
                final var metadata = new LinkedHashMap<>(value.metadata());
                metadata.putAll(part);
                if (forced == null) {
                    parent(metadata);
                }
                final var emitted = context.emit(ElementType.Table, value.text(), metadata);
                recordReferences(emitted);
                supplements(value.content().supplements(), emitted.elementId());
            }
        }
    }

    /**
     * Records footnote, endnote, and comment references carried by an emitted element.
     *
     * @param element emitted semantic element
     */
    private void recordReferences(final io.docweave.core.Element element) {
        if (!(element.metadata().get("docx_references") instanceof final java.util.List<?> values)) {
            return;
        }
        for (final var value : values) { if (value instanceof final Map<?, ?> item) {
                final String kind =
                        item.get("kind").toString().startsWith("comment") ? "comment" : item.get("kind").toString();
                references.computeIfAbsent(kind + ":" + item.get("id"), ignored -> new java.util.LinkedHashSet<>())
                        .add(element.elementId());
            }
        }
    }

    /**
     * Emits supplementary image and textbox elements anchored to a parent element.
     *
     * @param values supplements collected during paragraph or table rendering
     * @param parent parent element ID, or {@code null}
     * @throws IOException if supplement extraction fails or is interrupted
     */
    private void supplements(final java.util.List<DocxSupplement> values, final String parent) throws IOException {
        for (final var value : values) {
            final var anchor = new LinkedHashMap<String, Object>(part);
            anchor.put("docx_anchor_text_offset", value.offset());
            if (parent != null) {
                anchor.put("parent_id", parent);
            }
            if (value.kind().equals("image")) {
                final var image =
                        new DocxImageExtractor(context.options().docxOptions().contentOptions()).extract(value);
                anchor.putAll(image.metadata());
                context.emit(ElementType.Image, image.text(), anchor);
            } else {
                // Save and restore outer part/heading/list/numbering state so textbox traversal is isolated.
                final var oldPart = part;
                final String oldParent = partParent;
                final var oldNumbering = numbering;
                final var oldHeadings = new TreeMap<>(headings);
                final var oldLists = new java.util.HashMap<>(lists);
                try {
                    anchor.put("docx_part", "textbox");
                    part = Map.copyOf(anchor);
                    partParent = parent;
                    numbering = new DocxNumberingResolver(document);
                    headings.clear();
                    lists.clear();
                    bodyElements(new DocxBodyView(context).elements(value.node(), value.owner()), null);
                } finally {
                    part = oldPart;
                    partParent = oldParent;
                    numbering = oldNumbering;
                    headings.clear();
                    headings.putAll(oldHeadings);
                    lists.clear();
                    lists.putAll(oldLists);
                }
            }
        }
    }

    /**
     * Renders and emits a paragraph element along with any anchored supplements.
     *
     * @param paragraph paragraph to extract
     * @param forced forced element type (such as {@link ElementType#Header} or {@link ElementType#Footer}), or {@code
     *         null}
     * @throws IOException if rendering fails or is interrupted
     */
    private void paragraph(final XWPFParagraph paragraph, final ElementType forced) throws IOException {
        final var rendered = new DocxParagraphText(context, document).render(paragraph);
        // Empty paragraphs without inline annotations still emit any anchored images or textboxes.
        if (rendered.text().isEmpty() && rendered.annotations().isEmpty()) {
            supplements(rendered.supplements(),
                    partParent == null && !headings.isEmpty() ? headings.lastEntry().getValue() : partParent);
            return;
        }
        final var metadata = new LinkedHashMap<>(rendered.metadata());
        metadata.putAll(part);
        final var policy = policies.resolve(paragraph);
        if (policy.style() != null) {
            metadata.put("docx_style", policy.style());
        }
        if (policy.implicitStyle()) {
            metadata.put("docx_style_source", "default");
        }
        if (!policy.documentDefaults().isEmpty()) {
            metadata.put("docx_document_defaults", policy.documentDefaults());
        }
        if (forced != null) {
            final var emitted = context.emit(forced, rendered.text(), metadata);
            recordReferences(emitted);
            supplements(rendered.supplements(), emitted.elementId());
            return;
        }
        // Advance numbering counters when the paragraph participates in a numbered list or numbered heading.
        if (policy.numId() != null && policy.numId().signum() > 0) {
            metadata.put("docx_num_id", policy.numId().toString());
            metadata.putAll(numbering.consume(policy.numId(), policy.listLevel()));
            if (metadata.containsKey("docx_numbering_unsupported")
                    || metadata.containsKey("docx_numbering_unresolved")) {
                context.warn(PartitionResult.WarningCode.DOCX_CONTENT_OMITTED,
                        "DOCX numbering reference or format could not produce a label");
            }
        }
        // Classify as Title (heading), ListItem, or NarrativeText and maintain parent-child hierarchy.
        if (policy.heading() > 0) {
            headings.tailMap(policy.heading()).clear();
            lists.clear();
            parent(metadata);
            metadata.put("category_depth", policy.heading() - 1);
            final var heading = context.emit(ElementType.Title, rendered.text(), metadata);
            headings.put(policy.heading(), heading.elementId());
            recordReferences(heading);
            supplements(rendered.supplements(), heading.elementId());
        } else if (policy.numId() != null && policy.numId().signum() > 0) {
            final var anchors = lists.computeIfAbsent(policy.numId(), ignored -> new TreeMap<>());
            anchors.tailMap(policy.listLevel()).clear();
            metadata.put("category_depth", policy.listLevel());
            if (!anchors.isEmpty()) {
                metadata.put("parent_id", anchors.lastEntry().getValue());
            } else {
                parent(metadata);
            }
            final var item = context.emit(ElementType.ListItem, rendered.text(), metadata);
            anchors.put(policy.listLevel(), item.elementId());
            recordReferences(item);
            supplements(rendered.supplements(), item.elementId());
        } else {
            parent(metadata);
            final var emitted = context.emit(ElementType.NarrativeText, rendered.text(), metadata);
            recordReferences(emitted);
            supplements(rendered.supplements(), emitted.elementId());
        }
    }

    /**
     * Attaches the active heading or part parent ID to an element's metadata map.
     *
     * @param metadata mutable element metadata map
     */
    private void parent(final Map<String, Object> metadata) {
        if (!headings.isEmpty()) {
            metadata.put("parent_id", headings.lastEntry().getValue());
        } else if (partParent != null) {
            metadata.put("parent_id", partParent);
        }
    }
}
