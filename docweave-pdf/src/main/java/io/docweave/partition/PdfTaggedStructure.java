package io.docweave.partition;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded source-only table planning; no raster, model, reference labels or text inference. */
final class PdfTaggedStructure {
    /**
     * One exact page-stream content reference.
     * @param page zero-based original source page
     * @param id page-scoped marked-content identifier
     */
    record Content(int page, int id) {}

    /**
     * One source-authored logical anchor, retaining its original TH/TD role.
     * @param row logical origin row
     * @param column logical origin column
     * @param rows explicit positive row span
     * @param columns explicit positive column span
     * @param header whether the original role resolves to TH
     * @param content original content references, empty only for an authored empty cell
     * @param softHyphens unambiguous single-reference source soft-hyphen alternatives
     */
    record Cell(int row, int column, int rows, int columns, boolean header, List<Content> content,
            Set<Content> softHyphens) {}

    /**
     * One complete source-authored table confined to one original page.
     * @param page zero-based source page
     * @param rows logical row count
     * @param columns logical column count
     * @param headers contiguous leading rows covered entirely by TH cells
     * @param cells complete row-major logical anchors
     * @param nonText original page references outside cells, accepted only after empty-glyph validation
     * @param captions source Caption references retained separately from table cell text
     */
    record Table(int page, int rows, int columns, int headers, List<Cell> cells, List<Content> nonText,
            List<Content> captions) {
        /**
         * Retains planning without separately declared captions.
         * @param page zero-based source page
         * @param rows logical rows
         * @param columns logical columns
         * @param headers source header prefix
         * @param cells complete source anchors
         * @param nonText declared auxiliary references awaiting glyph-free validation
         */
        Table(final int page, final int rows, final int columns, final int headers, final List<Cell> cells,
                final List<Content> nonText) {
            this(page, rows, columns, headers, cells, nonText, List.of());
        }

        /**
         * Retains construction without auxiliary non-text content.
         * @param page original zero-based page
         * @param rows logical rows
         * @param columns logical columns
         * @param headers source header prefix
         * @param cells complete source anchors
         */
        Table(final int page, final int rows, final int columns, final int headers, final List<Cell> cells) {
            this(page, rows, columns, headers, cells, List.of());
        }
    }

    /**
     * One source paragraph or explicitly selected page fragment outside tables, confined to one original page.
     * @param page zero-based original page
     * @param ordinal original document traversal ordinal, including rejected source paragraphs
     * @param content exact ordered page-stream references
     * @param softHyphens unambiguous single-reference source discretionary hints
     * @param fragment one-based index within the supported source paragraph's page plans
     * @param fragments total prospective original page fragments, not a native acceptance count
     */
    record Paragraph(
            int page, int ordinal, List<Content> content, Set<Content> softHyphens, int fragment, int fragments) {}

    /** Content-free unsupported-tag signal, caught only at a whole-table boundary. */
    private static final class Unsupported extends RuntimeException {
        /** Creates a content-free signal for whole-candidate fallback. */
        private Unsupported() {
        }
    }

    /** Existing table limits remain authoritative for tagged tables. */
    private final TableLimits limits;
    /** Document-scoped structural work; page glyph/native/provider work has its own shared counter. */
    private final PdfTableWork work = new PdfTableWork();
    /** Original page identities, without following parent links or comparing dictionaries structurally. */
    private final Map<COSDictionary, Integer> pages = new IdentityHashMap<>();
    /** Original role aliases; absent maps preserve standard roles. */
    private final COSDictionary roles;
    /** Original named attribute definitions; unsupported classes retain whole-table fallback. */
    private final COSDictionary classes;
    /** Number of source tables rejected without claiming glyphs. */
    private int rejected;
    /** Unsupported source paragraph count, independent of table fallback. */
    private int rejectedParagraphs;
    /** Monotonic source P ordinal, without inventing order for untagged page content. */
    private int paragraphOrdinal;
    /** Whether explicitly requested cross-page paragraphs may supply independently validated page fragments. */
    private final boolean paragraphFragments;

    /**
     * Captures original page identities and role aliases without retaining them beyond the extraction call.
     * @param document current caller-owned loaded document
     * @param limits structured table budgets
     * @param paragraphFragments explicit support for independently validated consecutive-page P fragments
     */
    private PdfTaggedStructure(final PDDocument document, final TableLimits limits, final boolean paragraphFragments) {
        this.limits = limits;
        this.paragraphFragments = paragraphFragments;
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            pages.put(document.getPage(i).getCOSObject(), i);
        }
        final var root = document.getDocumentCatalog().getStructureTreeRoot();
        final var map = root == null ? null : root.getCOSObject().getDictionaryObject(COSName.ROLE_MAP);
        roles = map instanceof final COSDictionary dictionary ? dictionary : new COSDictionary();
        final var classMap = root == null ? null : root.getCOSObject().getDictionaryObject(COSName.CLASS_MAP);
        classes = classMap instanceof final COSDictionary dictionary ? dictionary : null;
    }

    /**
     * Reads original single-page table definitions; malformed candidates remain available to native fallback.
     * @param document loaded original PDF
     * @param limits active table limits
     * @return immutable accepted plans, plus a rejection count
     * @throws IOException if cancelled or structural/table work limits fail
     */
    static Result read(final PDDocument document, final TableLimits limits) throws IOException {
        return read(document, limits, true, false);
    }

    /**
     * Shares structural work across explicitly selected table and paragraph discovery.
     * @param document loaded original document
     * @param limits table-specific policy, unchanged when paragraph grouping alone is enabled
     * @param includeTables whether source tables are explicitly enabled
     * @param includeParagraphs whether source paragraphs outside tables are explicitly enabled
     * @return immutable plans and separate unsupported counts
     * @throws IOException if structural work or cancellation limits fail
     */
    static Result read(final PDDocument document, final TableLimits limits, final boolean includeTables,
            final boolean includeParagraphs) throws IOException {
        return read(document, limits, includeTables, includeParagraphs, false);
    }

    /**
     * Reads source plans with explicit per-page fragmentation, without changing table discovery.
     * @param document original loaded document
     * @param limits table resource policy
     * @param includeTables whether source tables are enabled
     * @param includeParagraphs whether source paragraphs are enabled
     * @param paragraphFragments whether consecutive-page source P references may be partitioned by original page
     * @return immutable original plans and unsupported counts
     * @throws IOException if structural work or cancellation bounds fail
     */
    static Result read(final PDDocument document, final TableLimits limits, final boolean includeTables,
            final boolean includeParagraphs, final boolean paragraphFragments) throws IOException {
        final var reader = new PdfTaggedStructure(document, limits, paragraphFragments);
        final var tables = new ArrayList<Table>();
        final var paragraphs = new ArrayList<Paragraph>();
        final var root = document.getDocumentCatalog().getStructureTreeRoot();
        if (root != null) {
            if (includeTables) {
                reader.discover(root.getCOSObject().getDictionaryObject(COSName.K), null,
                        root.getCOSObject().containsKey(COSName.ACTUAL_TEXT), tables,
                        Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            }
            if (includeParagraphs) {
                try {
                    reader.discoverParagraphs(root.getCOSObject().getDictionaryObject(COSName.K), null,
                            root.getCOSObject().containsKey(COSName.ACTUAL_TEXT), paragraphs,
                            Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                } catch (final Unsupported unsupported) {
                    // A cyclic/shared discovery graph cannot supply an unambiguous paragraph traversal.
                    paragraphs.clear();
                    reader.rejectedParagraphs++;
                }
            }
        }
        return new Result(List.copyOf(tables), reader.rejected, List.copyOf(paragraphs), reader.rejectedParagraphs);
    }

    /**
     * Original plans and content-free unsupported-table count.
     * @param tables immutable accepted source plans
     * @param rejected number of wholly unsupported source table definitions
     * @param paragraphs immutable source paragraph plans
     * @param rejectedParagraphs unsupported source paragraphs or a rejected discovery graph
     */
    record Result(List<Table> tables, int rejected, List<Paragraph> paragraphs, int rejectedParagraphs) {
        /**
         * Preserves the internal table-only empty/default result construction.
         * @param tables immutable original source tables
         * @param rejected unsupported table count
         */
        Result(final List<Table> tables, final int rejected) {
            this(tables, rejected, List.of(), 0);
        }
    }

    /**
     * Discovers only source P elements outside Table subtrees, without following parent or annotation links.
     * @param value original K child
     * @param inheritedPage nearest original page dictionary
     * @param actualAncestor whether an ancestor already supplies alternate text
     * @param paragraphs prospective supported source plans
     * @param seen discovery identity guard
     * @param depth bounded structural depth
     * @throws IOException if work or interruption limits fail
     */
    private void discoverParagraphs(final COSBase value, final COSDictionary inheritedPage,
            final boolean actualAncestor, final List<Paragraph> paragraphs, final Set<COSBase> seen, final int depth)
            throws IOException {
        work.step();
        if (value == null || !(value instanceof COSDictionary || value instanceof COSArray)) {
            return;
        }
        if (depth > 128 || !seen.add(value)) {
            throw new Unsupported();
        }
        if (value instanceof final COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                discoverParagraphs(array.getObject(i), inheritedPage, actualAncestor, paragraphs, seen, depth + 1);
            }
        } else if (value instanceof final COSDictionary node) {
            final String role = role(node);
            if ("Table".equals(role)) {
                return;
            }
            final var page = page(node, inheritedPage);
            if ("P".equals(role)) {
                final int ordinal = paragraphOrdinal++;
                try {
                    final var type = node.getDictionaryObject(COSName.TYPE);
                    // Type is optional for a structure element; explicit conflicting types remain unsupported.
                    if (type != null && !COSName.STRUCT_ELEM.equals(type)) {
                        throw new Unsupported();
                    }
                    final var references = new ArrayList<Content>();
                    final var hints = new HashSet<Content>();
                    content(node.getDictionaryObject(COSName.K), page,
                            actualAncestor || node.containsKey(COSName.ACTUAL_TEXT), references, hints,
                            Collections.newSetFromMap(new IdentityHashMap<>()), 0, true);
                    if (references.isEmpty() || references.size() != new HashSet<>(references).size()) {
                        throw new Unsupported();
                    }
                    final var grouped = new LinkedHashMap<Integer, List<Content>>();
                    int previousPage = references.getFirst().page();
                    for (final var reference : references) {
                        work.step();
                        if (reference.page() != previousPage
                                && (!paragraphFragments || reference.page() != previousPage + 1)) {
                            throw new Unsupported();
                        }
                        previousPage = reference.page();
                        grouped.computeIfAbsent(reference.page(), ignored -> new ArrayList<>()).add(reference);
                    }
                    int fragment = 0;
                    for (final var entry : grouped.entrySet()) {
                        work.step();
                        final var pageHints = new HashSet<Content>();
                        for (final var hint : hints) {
                            work.step();
                            if (hint.page() == entry.getKey()) {
                                pageHints.add(hint);
                            }
                        }
                        paragraphs.add(new Paragraph(entry.getKey(), ordinal, List.copyOf(entry.getValue()),
                                Set.copyOf(pageHints), ++fragment, grouped.size()));
                    }
                } catch (final Unsupported unsupported) {
                    rejectedParagraphs++;
                }
            } else {
                discoverParagraphs(node.getDictionaryObject(COSName.K), page,
                        actualAncestor || node.containsKey(COSName.ACTUAL_TEXT), paragraphs, seen, depth + 1);
            }
        }
    }

    /**
     * Traverses only K children; parent links and object/annotation references are never followed.
     * @param value source child
     * @param inheritedPage nearest original Pg dictionary
     * @param actualAncestor whether an ancestor declares any alternate text
     * @param tables output plans
     * @param seen structural identity guard
     * @param depth bounded structural depth
     * @throws IOException if structural work/cancellation fails
     */
    private void discover(final COSBase value, final COSDictionary inheritedPage, final boolean actualAncestor,
            final List<Table> tables, final Set<COSBase> seen, final int depth) throws IOException {
        work.step();
        if (value == null || depth > 128 || !(value instanceof COSDictionary || value instanceof COSArray)
                || !seen.add(value)) {
            return;
        }
        if (value instanceof final COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                discover(array.getObject(i), inheritedPage, actualAncestor, tables, seen, depth + 1);
            }
        } else if (value instanceof final COSDictionary node) {
            final var page = page(node, inheritedPage);
            if ("Table".equals(role(node))) {
                try {
                    tables.add(table(node, page, actualAncestor || node.containsKey(COSName.ACTUAL_TEXT)));
                } catch (final Unsupported unsupported) {
                    rejected++;
                }
            } else {
                discover(node.getDictionaryObject(COSName.K), page,
                        actualAncestor || node.containsKey(COSName.ACTUAL_TEXT), tables, seen, depth + 1);
            }
        }
    }

    /**
     * Builds a complete nonoverlapping logical slot grid from explicit rows/cells/spans.
     * @param node original Table
     * @param page inherited source page, possibly absent until leaf references
     * @param actualAncestor whether source table/ancestors already declare alternate text
     * @return accepted single-page plan
     * @throws IOException if active table/work limits fail
     */
    private Table table(final COSDictionary node, final COSDictionary page, final boolean actualAncestor)
            throws IOException {
        if (!PdfTaggedAttributeClasses.supported(node, classes, work)) {
            throw new Unsupported();
        }
        final var rows = new ArrayList<Node>();
        final var captionNodes = new ArrayList<Node>();
        final var nonText = new ArrayList<Content>();
        descendants(node.getDictionaryObject(COSName.K), page, actualAncestor, "TR", rows, nonText, captionNodes,
                Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        if (rows.isEmpty()) {
            throw new Unsupported();
        }
        limit(rows.size() <= limits.maxRows());
        final var cells = new ArrayList<Cell>();
        final var slots = new HashMap<Long, Boolean>();
        final var references = new HashSet<Content>();
        int width = 0;
        int sourcePage = -1;
        for (int r = 0; r < rows.size(); r++) {
            final var row = rows.get(r);
            if (!PdfTaggedAttributeClasses.supported(row.node(), classes, work)) {
                throw new Unsupported();
            }
            final var anchors = new ArrayList<Node>();
            descendants(row.node().getDictionaryObject(COSName.K), row.page(), row.actualAncestor(), "cell", anchors,
                    nonText, null, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            int column = 0;
            for (final var anchor : anchors) {
                work.step();
                while (slots.containsKey(slot(r, column))) {
                    work.step();
                    column++;
                }
                final int rs = span(anchor.node(), "RowSpan");
                final int cs = span(anchor.node(), "ColSpan");
                if (rs > rows.size() - r) {
                    throw new Unsupported();
                }
                limit((long) column + cs <= limits.maxColumns() && (long) rs * cs <= limits.maxCells()
                        && cells.size() < limits.maxCells());
                final boolean header = "TH".equals(role(anchor.node()));
                for (int y = r; y < r + rs; y++) {
                    for (int x = column; x < column + cs; x++) {
                        work.step();
                        limit(slots.size() < limits.maxCells());
                        if (slots.putIfAbsent(slot(y, x), header) != null) {
                            throw new Unsupported();
                        }
                    }
                }
                final var content = new ArrayList<Content>();
                final var softHyphens = new HashSet<Content>();
                content(anchor.node().getDictionaryObject(COSName.K), anchor.page(), anchor.actualAncestor(), content,
                        softHyphens, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                if (content.isEmpty() && anchor.page() != null) {
                    final var emptyPage = pages.get(anchor.page());
                    if (emptyPage == null || sourcePage >= 0 && sourcePage != emptyPage) {
                        throw new Unsupported();
                    }
                    sourcePage = emptyPage;
                }
                for (final var reference : content) {
                    work.step();
                    if (!references.add(reference) || sourcePage >= 0 && sourcePage != reference.page()) {
                        throw new Unsupported();
                    }
                    sourcePage = reference.page();
                }
                cells.add(new Cell(r, column, rs, cs, header, List.copyOf(content), Set.copyOf(softHyphens)));
                column += cs;
                width = Math.max(width, column);
            }
        }
        if (cells.isEmpty() || sourcePage < 0 || (long) rows.size() * width != slots.size()) {
            throw new Unsupported();
        }
        int headers = 0;
        for (int r = 0; r < rows.size(); r++) {
            boolean all = true;
            for (int c = 0; c < width; c++) {
                work.step();
                all &= Boolean.TRUE.equals(slots.get(slot(r, c)));
            }
            if (!all) {
                break;
            }
            headers++;
        }
        for (final var reference : nonText) {
            work.step();
            if (reference.page() != sourcePage || !references.add(reference)) {
                throw new Unsupported();
            }
        }
        final var captions = new ArrayList<Content>();
        for (final var caption : captionNodes) {
            work.step();
            final var content = new ArrayList<Content>();
            if (caption.actualAncestor()
                    || content(caption.node().getDictionaryObject(COSName.K), caption.page(), false, content,
                            new HashSet<>(), Collections.newSetFromMap(new IdentityHashMap<>()), 0)
                    || content.isEmpty()) {
                throw new Unsupported();
            }
            limit(content.size() <= 4_096 - captions.size());
            for (final var reference : content) {
                work.step();
                if (reference.page() != sourcePage || !references.add(reference)) {
                    throw new Unsupported();
                }
                captions.add(reference);
            }
        }
        return new Table(sourcePage, rows.size(), width, headers, List.copyOf(cells), List.copyOf(nonText),
                List.copyOf(captions));
    }

    /**
     * Structure node with its nearest inherited source page.
     * @param node original borrowed structure dictionary
     * @param page inherited original page, possibly absent until a descendant reference
     * @param actualAncestor whether this node or an ancestor declares alternate text
     */
    private record Node(COSDictionary node, COSDictionary page, boolean actualAncestor) {}

    /**
     * Collects explicit rows or cells through wrappers without descending into nested tables/cells.
     * @param value original K child
     * @param inheritedPage nearest source page
     * @param actualAncestor whether an ancestor declares alternate text
     * @param wanted TR or cell
     * @param result ordered matching nodes
     * @param nonText bounded original references outside cell ownership, pending empty-glyph validation
     * @param captions prospective Caption nodes outside rows/cells, null when captions are unsupported here
     * @param seen cycle/shared-child guard
     * @param depth bounded depth
     * @throws IOException if work/cancellation fails
     */
    private void descendants(final COSBase value, final COSDictionary inheritedPage, final boolean actualAncestor,
            final String wanted, final List<Node> result, final List<Content> nonText, final List<Node> captions,
            final Set<COSBase> seen, final int depth) throws IOException {
        work.step();
        if (value == null) {
            return;
        }
        if (depth > 128 || !seen.add(value)) {
            throw new Unsupported();
        }
        if (value instanceof final COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                descendants(array.getObject(i), inheritedPage, actualAncestor, wanted, result, nonText, captions, seen,
                        depth + 1);
            }
        } else if (value instanceof final COSDictionary node) {
            if (COSName.getPDFName("MCR").equals(node.getCOSName(COSName.TYPE))) {
                nonText(value, inheritedPage, actualAncestor, nonText);
                return;
            }
            final String role = role(node);
            final var page = page(node, inheritedPage);
            if ("Caption".equals(role)) {
                if (captions == null || actualAncestor || node.containsKey(COSName.ACTUAL_TEXT)) {
                    throw new Unsupported();
                }
                limit(captions.size() < 16);
                captions.add(new Node(node, page, false));
                return;
            }
            final boolean cell = "TD".equals(role) || "TH".equals(role);
            if (wanted.equals(role) || wanted.equals("cell") && cell) {
                result.add(new Node(node, page, actualAncestor || node.containsKey(COSName.ACTUAL_TEXT)));
            } else {
                if ("Table".equals(role) || cell || "TR".equals(role)) {
                    throw new Unsupported();
                }
                descendants(node.getDictionaryObject(COSName.K), page,
                        actualAncestor || node.containsKey(COSName.ACTUAL_TEXT), wanted, result, nonText, captions,
                        seen, depth + 1);
            }
        } else if (value instanceof COSInteger) {
            nonText(value, inheritedPage, actualAncestor, nonText);
        } else {
            throw new Unsupported();
        }
    }

    /**
     * Stages only explicit page-stream references outside cell roles; no text or alternate text is discarded.
     * @param value original integer or MCR leaf
     * @param inheritedPage nearest original page
     * @param actualAncestor whether an ancestor owns alternate text
     * @param result references pending actual declared-empty marked-content validation
     * @throws IOException if shared work or structural bounds fail
     */
    private void nonText(final COSBase value, final COSDictionary inheritedPage, final boolean actualAncestor,
            final List<Content> result) throws IOException {
        limit(result.size() < 4_096);
        if (value instanceof final COSDictionary node && node.containsKey(COSName.K)) {
            throw new Unsupported();
        }
        final var reference = new ArrayList<Content>();
        if (actualAncestor
                || content(value, inheritedPage, false, reference, new HashSet<>(),
                        Collections.newSetFromMap(new IdentityHashMap<>()), 0)
                || reference.size() != 1) {
            throw new Unsupported();
        }
        result.add(reference.getFirst());
    }

    /**
     * Reads explicit page MCIDs, rejecting form/object references and mixed-page ownership.
     * @param value original cell child
     * @param inheritedPage nearest Pg dictionary
     * @param actualAncestor whether an ancestor already owns alternate text
     * @param result exact ordered page references
     * @param softHyphens source alternatives owning exactly one content reference
     * @return whether this subtree contains any alternate text, including a conflicting descendant
     * @param seen structural identity guard
     * @param depth bounded depth
     * @throws IOException if shared work/cancellation fails
     */
    private boolean content(final COSBase value, final COSDictionary inheritedPage, final boolean actualAncestor,
            final List<Content> result, final Set<Content> softHyphens, final Set<COSBase> seen, final int depth)
            throws IOException {
        return content(value, inheritedPage, actualAncestor, result, softHyphens, seen, depth, false);
    }

    /**
     * Reads source content with an additional whole-paragraph ban on nested P elements.
     * @param value original child
     * @param inheritedPage nearest source page
     * @param actualAncestor whether an ancestor declares alternate text
     * @param result ordered exact source references
     * @param softHyphens unambiguous source discretionary hints
     * @param seen content identity guard
     * @param depth bounded content depth
     * @param paragraph whether nested source paragraphs must reject the whole candidate
     * @return whether this subtree owns any alternate text
     * @throws IOException if shared work or cancellation limits fail
     */
    private boolean content(final COSBase value, final COSDictionary inheritedPage, final boolean actualAncestor,
            final List<Content> result, final Set<Content> softHyphens, final Set<COSBase> seen, final int depth,
            final boolean paragraph) throws IOException {
        work.step();
        if (value == null || value instanceof org.apache.pdfbox.cos.COSNull) {
            // Empty formatting/annotation wrappers have no native page glyphs; an entirely empty cell still fails.
            return false;
        }
        if (depth > 128) {
            throw new Unsupported();
        }
        if (value instanceof final COSInteger id) {
            final var page = pages.get(inheritedPage);
            if (page == null || id.longValue() < 0 || id.longValue() > Integer.MAX_VALUE) {
                throw new Unsupported();
            }
            result.add(new Content(page, id.intValue()));
            return false;
        } else if (!seen.add(value)) {
            throw new Unsupported();
        } else if (value instanceof final COSArray array) {
            boolean alternative = false;
            for (int i = 0; i < array.size(); i++) {
                alternative |= content(array.getObject(i), inheritedPage, actualAncestor, result, softHyphens, seen,
                        depth + 1, paragraph);
            }
            return alternative;
        } else if (value instanceof final COSDictionary node) {
            if (COSName.getPDFName("OBJR").equals(node.getCOSName(COSName.TYPE))) {
                final var object = node.getDictionaryObject(COSName.OBJ);
                if (object instanceof final COSDictionary annotation
                        && COSName.getPDFName("Link").equals(annotation.getCOSName(COSName.SUBTYPE))
                        && (annotation.getDictionaryObject(COSName.TYPE) == null
                                || COSName.ANNOT.equals(annotation.getCOSName(COSName.TYPE)))) {
                    // Link annotations contain no native page glyphs. Their sibling MCIDs still own displayed text;
                    // no URI/action/annotation appearance is followed or substituted for source text.
                    return false;
                }
                throw new Unsupported();
            }
            if (node.containsKey(COSName.getPDFName("Stm")) || node.containsKey(COSName.getPDFName("StmOwn"))
                    || node.containsKey(COSName.OBJ)) {
                throw new Unsupported();
            }
            final String role = role(node);
            if ("Table".equals(role) || "TR".equals(role) || "TD".equals(role) || "TH".equals(role)
                    || paragraph && "P".equals(role)) {
                throw new Unsupported();
            }
            final var page = page(node, inheritedPage);
            final int start = result.size();
            final var replacement = node.getDictionaryObject(COSName.ACTUAL_TEXT);
            final boolean ownsText = node.containsKey(COSName.ACTUAL_TEXT);
            final var child = node.getDictionaryObject(
                    COSName.MCR.equals(node.getCOSName(COSName.TYPE)) ? COSName.MCID : COSName.K);
            final boolean nestedAlternative =
                    content(child, page, actualAncestor || ownsText, result, softHyphens, seen, depth + 1, paragraph);
            if (!actualAncestor && !nestedAlternative && role != null
                    && !COSName.MCR.equals(node.getCOSName(COSName.TYPE))
                    && replacement instanceof final COSString string && string.getBytes().length <= 8
                    && "\u00ad".equals(string.getString()) && result.size() == start + 1) {
                // Only a single source reference can label one discretionary glyph. Arbitrary replacement
                // strings and ancestors with competing ActualText never become substitution instructions.
                softHyphens.add(result.get(start));
            }
            return ownsText || nestedAlternative;
        } else {
            throw new Unsupported();
        }
    }

    /**
     * Resolves bounded custom role aliases without following arbitrary object graph links.
     * @param node original structure element
     * @return standard/raw role or null on a malformed alias chain
     */
    private String role(final COSDictionary node) {
        String role = node.getNameAsString(COSName.S);
        final var seen = new HashSet<String>();
        while (role != null && roles.containsKey(COSName.getPDFName(role))) {
            if (seen.size() >= 32 || !seen.add(role)) {
                return null;
            }
            final var next = roles.getDictionaryObject(COSName.getPDFName(role));
            role = next instanceof final COSName name ? name.getName() : null;
        }
        return role;
    }

    /**
     * Reads a direct Table-owner span before a resolved class span; historical attribute mixes remain rejected.
     * @param node source cell
     * @param name RowSpan or ColSpan
     * @return positive direct span, class-derived span or default one
     * @throws IOException if attribute work/cancellation bounds fail
     */
    private int span(final COSDictionary node, final String name) throws IOException {
        final var classSpans = PdfTaggedAttributeClasses.spans(node, classes, work);
        if (!classSpans.supported()) {
            throw new Unsupported();
        }
        final var raw = node.getDictionaryObject(COSName.A);
        final var attributes = new ArrayList<COSDictionary>();
        if (raw instanceof final COSDictionary dictionary) {
            attributes.add(dictionary);
        } else if (raw instanceof final COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (!(array.getObject(i) instanceof final COSDictionary dictionary)) {
                    throw new Unsupported();
                }
                work.step();
                attributes.add(dictionary);
                if (i + 1 < array.size() && array.getObject(i + 1) instanceof final COSInteger revision) {
                    if (revision.longValue() != node.getInt(COSName.R, 0)) {
                        throw new Unsupported();
                    }
                    i++;
                } else if (node.getInt(COSName.R, 0) != 0) {
                    throw new Unsupported();
                }
            }
        } else if (raw != null) {
            throw new Unsupported();
        }
        Integer result = null;
        for (final var attribute : attributes) {
            work.step();
            final var value = attribute.getDictionaryObject(COSName.getPDFName(name));
            if (value != null) {
                if (!"Table".equals(attribute.getNameAsString(COSName.O))
                        || !(value instanceof final COSInteger integer) || integer.longValue() < 1
                        || integer.longValue() > Integer.MAX_VALUE || result != null) {
                    throw new Unsupported();
                }
                result = integer.intValue();
            }
        }
        if (result != null) {
            return result;
        }
        return name.equals("RowSpan") ? classSpans.rows() : classSpans.columns();
    }

    /**
     * Resolves only explicit Pg dictionaries, retaining inherited page identity when absent.
     * @param node source element/reference
     * @param inherited inherited page
     * @return original page dictionary or null when malformed/unavailable
     */
    private static COSDictionary page(final COSDictionary node, final COSDictionary inherited) {
        final var value = node.getDictionaryObject(COSName.PG);
        return value == null ? inherited : value instanceof final COSDictionary dictionary ? dictionary : null;
    }

    /**
     * Encodes nonnegative logical coordinates without allocating strings or overflowing column multiplication.
     * @param row logical row
     * @param column logical column
     * @return collision-free slot key
     */
    private static long slot(final int row, final int column) {
        return (long) row << 32 | column & 0xffffffffL;
    }

    /**
     * Preserves typed active table-limit failures rather than silently downgrading an oversized tagged table.
     * @param allowed whether the current operation fits its caller budget
     * @throws PartitionException if a table limit is exceeded
     */
    private static void limit(final boolean allowed) throws PartitionException {
        if (!allowed) {
            throw new PartitionException(PartitionException.Code.TABLE_LIMIT, "Tagged PDF table exceeds limits");
        }
    }
}
