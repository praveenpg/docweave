package dev.docweave.partition;

import org.apache.poi.xwpf.usermodel.*;
import org.w3c.dom.Node;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordered Word inline visitor: revisions, cached fields, references, links and drawing anchors. */
final class DocxParagraphText {
    /** Office Document relationships XML namespace URI. */
    static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /**
     * Rendered inline text and associated links, annotations, supplements, and nested-table entries.
     *
     * @param text normalized inline text
     * @param links extracted hyperlink descriptors
     * @param annotations inline reference, revision, and field annotations
     * @param supplements anchored image and textbox supplements
     * @param nested captured nested-table entries
     */
    record Rendered(String text, List<Map<String, Object>> links, Map<String, Object> annotations,
            List<DocxSupplement> supplements, List<DocxNestedTables.Entry> nested) {
        /**
         * Creates a rendered result without nested-table entries.
         *
         * @param text normalized inline text
         * @param links extracted hyperlink descriptors
         * @param annotations inline reference, revision, and field annotations
         * @param supplements anchored image and textbox supplements
         */
        Rendered(final String text, final List<Map<String, Object>> links, final Map<String, Object> annotations,
                final List<DocxSupplement> supplements) {
            this(text, links, annotations, supplements, List.of());
        }

        /**
         * Creates a rendered result containing only text and hyperlink descriptors.
         *
         * @param text normalized inline text
         * @param links extracted hyperlink descriptors
         */
        Rendered(final String text, final List<Map<String, Object>> links) {
            this(text, links, Map.of(), List.of());
        }

        /**
         * Builds the element metadata map combining inline annotations and hyperlink parallel arrays.
         *
         * @return mutable metadata map
         */
        Map<String, Object> metadata() {
            final var result = new LinkedHashMap<String, Object>(annotations);
            if (!links.isEmpty()) {
                result.put("links", links);
                result.put("link_texts", links.stream().map(link -> link.get("text")).toList());
                result.put("link_urls", links.stream().map(link -> link.get("url")).toList());
                result.put("link_start_indexes", links.stream().map(link -> link.get("start_index")).toList());
            }
            return result;
        }
    }

    /** Active partition context. */
    private final PartitionContext context;
    /** DOCX content extraction options. */
    private final DocxContentOptions options;
    /** Accumulated normalized paragraph text. */
    private final StringBuilder text = new StringBuilder();
    /** Extracted hyperlink descriptors. */
    private final List<Map<String, Object>> links = new ArrayList<>();
    /** Extracted note and comment reference descriptors. */
    private final List<Map<String, Object>> references = new ArrayList<>();
    /** Extracted revision range descriptors. */
    private final List<Map<String, Object>> revisions = new ArrayList<>();
    /** Extracted complex and simple field instructions. */
    private final List<String> fields = new ArrayList<>();

    /** Mutable state for an open complex Word field ({@code w:fldChar}). */
    private static final class Field {
        /** Index of this field's instruction slot in {@link DocxParagraphText#fields}. */
        final int index;
        /** Accumulated field instruction text. */
        final StringBuilder instruction = new StringBuilder();
        /** Character and code-point start offsets of the cached field result. */
        int start = -1, startPoints;

        /**
         * Creates an open complex field tracker for the given instruction list slot.
         *
         * @param index index in {@link DocxParagraphText#fields}
         */
        Field(final int index) {
            this.index = index;
        }
    }

    /** Stack of currently open complex fields. */
    private final java.util.ArrayDeque<Field> openFields = new java.util.ArrayDeque<>();
    /** Collected image and textbox supplements anchored to this paragraph. */
    private final List<DocxSupplement> supplements = new ArrayList<>();
    /** Owning Word body part of the paragraph being rendered. */
    private IBody owner;
    /** Total Unicode code points appended to {@link #text}. */
    private int points;
    /** Whether a normalized space is pending before the next non-whitespace code point. */
    private boolean pendingSpace;

    /**
     * Creates an inline paragraph text renderer for the given partition context and document.
     *
     * @param context active partition context
     * @param document parsed Word document
     */
    DocxParagraphText(final PartitionContext context, final XWPFDocument document) {
        this.context = context;
        options = context.options().docxOptions().contentOptions();
    }

    /**
     * Renders a Word paragraph into normalized text, links, annotations, and supplements.
     *
     * @param paragraph Word paragraph to render
     * @return rendered paragraph content
     * @throws IOException if traversal fails or is interrupted
     */
    Rendered render(final XWPFParagraph paragraph) throws IOException {
        owner = paragraph.getBody();
        walk(paragraph.getCTP().getDomNode());
        final var annotations = new LinkedHashMap<String, Object>();
        if (!references.isEmpty()) {
            annotations.put("docx_references", clamp(references));
        }
        if (!revisions.isEmpty()) {
            annotations.put("docx_revisions", clamp(revisions));
        }
        if (fields.stream().anyMatch(s -> !s.isBlank())) {
            annotations.put("docx_field_instructions", fields.stream().filter(s -> !s.isBlank()).toList());
            annotations.put("docx_field_policy", "cached_results");
        }
        return new Rendered(text.toString(), List.copyOf(links), Map.copyOf(annotations),
                supplements.stream()
                        .map(s -> new DocxSupplement(s.kind(), s.node(), s.owner(), Math.min(points, s.offset())))
                        .toList());
    }

    /**
     * Clamps {@code start_index} and {@code end_index} values in annotation maps to the final code-point length.
     *
     * @param values annotation maps to clamp
     * @return immutable list of clamped annotation maps
     */
    private List<Map<String, Object>> clamp(final List<Map<String, Object>> values) {
        return values.stream()
                .map(value -> {
                    final var copy = new LinkedHashMap<>(value);
                    for (final String key : List.of("start_index", "end_index")) {
                        if (copy.get(key) instanceof final Integer n) {
                            copy.put(key, Math.min(points, n));
                        }
                    }
                    return Map.<String, Object>copyOf(copy);
                })
                .toList();
    }

    /**
     * Returns the current code-point offset including any pending space.
     *
     * @return current code-point offset
     */
    private int offset() {
        return points + (pendingSpace ? 1 : 0);
    }

    /**
     * Visits a DOM node and its descendants to extract inline text, links, annotations, and supplements.
     *
     * @param node DOM node to visit
     * @throws IOException if traversal is interrupted
     */
    private void walk(final Node node) throws IOException {
        ElementCollector.checkInterrupted();
        final String local = node.getLocalName();
        final String ns = node.getNamespaceURI();
        if (DocxBodyView.WORD.equals(ns)) {
            if (!DocxBodyView.include(local, options.revisions())) {
                return;
            }
            switch (local) {
            case "pPr", "rPr", "sdtPr", "sdtEndPr", "delInstrText" -> {
                return;
            }
            case "t", "delText" -> {
                append(DocxBodyView.text(node));
                return;
            }
            case "sym" -> {
                // Stored font/character codes cannot be safely interpreted as Unicode glyphs.
                context.warn(PartitionResult.WarningCode.DOCX_CONTENT_OMITTED,
                        "DOCX font-based symbol is not extracted; glyph mapping is unavailable");
                return;
            }
            case "tab", "br", "cr" -> {
                pendingSpace = !text.isEmpty();
                return;
            }
            case "instrText" -> {
                // Accumulate field instructions into the innermost open complex field or as a standalone entry.
                final String instruction = DocxBodyView.text(node);
                if (openFields.isEmpty()) {
                    if (!instruction.isBlank()) {
                        fields.add(instruction.strip());
                    }
                } else {
                    final var field = openFields.getLast();
                    field.instruction.append(instruction);
                    fields.set(field.index, field.instruction.toString().strip());
                }
                return;
            }
            case "fldChar" -> {
                // Track complex field lifecycle (begin -> separate -> end) so cached result spans can be linked.
                final String kind = DocxBodyView.attribute(node, DocxBodyView.WORD, "fldCharType");
                if ("begin".equals(kind)) {
                    openFields.addLast(new Field(fields.size()));
                    fields.add("");
                } else if ("separate".equals(kind) && !openFields.isEmpty()) {
                    final var field = openFields.getLast();
                    field.start = text.length();
                    field.startPoints = points;
                } else if ("end".equals(kind) && !openFields.isEmpty()) {
                    final var field = openFields.removeLast();
                    addFieldLink(field.instruction.toString(), field.start, field.startPoints);
                }
                return;
            }
            case "fldSimple" -> {
                // Record simple field instruction and extract its child runs as the cached display result.
                final String instruction = DocxBodyView.attribute(node, DocxBodyView.WORD, "instr");
                if (instruction != null && !instruction.isBlank()) {
                    fields.add(instruction.strip());
                }
                final int start = text.length(), startPoints = points;
                children(node);
                addFieldLink(instruction, start, startPoints);
                return;
            }
            case "drawing", "pict" -> {
                // Traverse drawing/VML subtrees for image blips or textboxes; warn if neither is found.
                final int count = supplements.size();
                children(node);
                if (count == supplements.size() && options.images() != DocxContentOptions.Images.SKIP
                        && options.includeTextBoxes()) {
                    context.warn(PartitionResult.WarningCode.DOCX_CONTENT_OMITTED,
                            "DOCX drawing has no extractable image reference or text box");
                }
                return;
            }
            case "txbxContent" -> {
                if (options.includeTextBoxes()) {
                    supplements.add(new DocxSupplement("textbox", node, owner, offset()));
                }
                return;
            }
            case "footnoteReference", "endnoteReference", "commentReference", "commentRangeStart",
                    "commentRangeEnd" -> {
                // Record inline note and comment anchor offsets so extracted note/comment parts can link back.
                final String kind = switch (local) {
                    case "footnoteReference" -> "footnote";
                    case "endnoteReference" -> "endnote";
                    case "commentRangeStart" -> "comment_start";
                    case "commentRangeEnd" -> "comment_end";
                    default -> "comment";
                };
                final String id = DocxBodyView.attribute(node, DocxBodyView.WORD, "id");
                if (id != null) {
                    references.add(Map.of("kind", kind, "id", id, "start_index", offset()));
                }
                return;
            }
            case "hyperlink" -> {
                // Render hyperlink display text first, then trim leading whitespace from the recorded link span.
                final int startChars = text.length();
                final int startPoints = points;
                children(node);
                int begin = startChars;
                while (begin < text.length() && TextSupport.space(text.codePointAt(begin))) {
                    begin += Character.charCount(text.codePointAt(begin));
                }
                final String url = hyperlink(node);
                if (url != null && begin < text.length()) {
                    links.add(Map.of("text", text.substring(begin), "url", url, "start_index",
                            startPoints + text.codePointCount(startChars, begin)));
                }
                return;
            }
            case "ins", "del", "moveTo", "moveFrom" -> {
                // Capture revision span boundaries and author/date metadata around the revised child runs.
                final int start = offset();
                children(node);
                final var revision = new LinkedHashMap<String, Object>();
                revision.put("kind", local);
                revision.put("start_index", start);
                revision.put("end_index", points);
                for (final String key : List.of("id", "author", "date")) {
                    final String value = DocxBodyView.attribute(node, DocxBodyView.WORD, key);
                    if (value != null) {
                        revision.put(key, value);
                    }
                }
                revisions.add(revision);
                return;
            }
            default -> {
            }
            }
        }
        // Anchor DrawingML blips and VML imagedata nodes at the current code-point offset.
        if ("blip".equals(local) && "http://schemas.openxmlformats.org/drawingml/2006/main".equals(ns)
                || "imagedata".equals(local) && "urn:schemas-microsoft-com:vml".equals(ns)) {
            if (options.images() != DocxContentOptions.Images.SKIP) {
                supplements.add(new DocxSupplement("image", node, owner, offset()));
            }
            return;
        }
        // In Markup Compatibility AlternateContent blocks, prefer Choice over Fallback to avoid duplicate extraction.
        if ("AlternateContent".equals(local)
                && "http://schemas.openxmlformats.org/markup-compatibility/2006".equals(ns)) {
            Node selected = null;
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                if ("Choice".equals(child.getLocalName())) {
                    selected = child;
                    break;
                }
                if ("Fallback".equals(child.getLocalName())) {
                    selected = child;
                }
            }
            if (selected != null) {
                children(selected);
            }
            return;
        }
        children(node);
    }

    /**
     * Visits all direct child nodes of the given DOM node in order.
     *
     * @param node parent DOM node
     * @throws IOException if traversal is interrupted
     */
    private void children(final Node node) throws IOException {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            walk(child);
        }
    }

    /**
     * Appends literal text while collapsing whitespace runs into single spaces.
     *
     * @param literal raw text literal to append
     * @throws IOException if the thread is interrupted
     */
    private void append(final String literal) throws IOException {
        // Collapse consecutive whitespace code points and defer emitting a space until non-whitespace follows.
        for (int offset = 0; offset < literal.length();) {
            if ((offset & 4095) == 0) {
                ElementCollector.checkInterrupted();
            }
            final int point = literal.codePointAt(offset);
            offset += Character.charCount(point);
            if (TextSupport.space(point)) {
                pendingSpace = !text.isEmpty();
                continue;
            }
            if (pendingSpace) {
                text.append(' ');
                points++;
                pendingSpace = false;
            }
            text.appendCodePoint(point);
            points++;
        }
    }

    /**
     * Records a hyperlink entry when a field instruction represents a {@code HYPERLINK} field with non-empty display
     * text.
     *
     * @param instruction field instruction string, or {@code null}
     * @param start character start offset of the field result in {@link #text}
     * @param startPoints code-point start offset of the field result
     */
    private void addFieldLink(final String instruction, final int start, final int startPoints) {
        if (instruction == null || start < 0) {
            return;
        }
        final var pattern =
                java.util.regex.Pattern.compile("(?is)^\\s*HYPERLINK\\s+(?:\"([^\"]*)\"|(\\S+))").matcher(instruction);
        if (!pattern.find()) {
            return;
        }
        final String target = pattern.group(1) == null ? pattern.group(2) : pattern.group(1);
        int begin = start;
        while (begin < text.length() && TextSupport.space(text.codePointAt(begin))) {
            begin += Character.charCount(text.codePointAt(begin));
        }
        if (!target.isEmpty() && begin < text.length()) {
            links.add(Map.of("text", text.substring(begin), "url", resolve(target), "start_index",
                    startPoints + text.codePointCount(start, begin)));
        }
    }

    /**
     * Resolves the target URL or internal anchor of a {@code w:hyperlink} element.
     *
     * @param node hyperlink DOM node
     * @return resolved URL or fragment anchor, or {@code null} if unresolved
     */
    private String hyperlink(final Node node) {
        final String id = DocxBodyView.attribute(node, REL, "id");
        if (id != null) {
            final var relation = owner.getPart().getPackagePart().getRelationship(id);
            if (relation != null) {
                return resolve(relation.getTargetURI().toString());
            }
        }
        final String anchor = DocxBodyView.attribute(node, DocxBodyView.WORD, "anchor");
        return anchor == null ? null : "#" + anchor;
    }

    /**
     * Resolves a relative hyperlink URL against the configured base URI when applicable.
     *
     * @param url raw hyperlink target URL
     * @return resolved URL string
     */
    private String resolve(final String url) {
        if (context.options().baseUri() == null || url.startsWith("#")) {
            return url;
        }
        try {
            return context.options().baseUri().resolve(URI.create(url)).toString();
        } catch (final IllegalArgumentException error) {
            return url;
        }
    }
}
