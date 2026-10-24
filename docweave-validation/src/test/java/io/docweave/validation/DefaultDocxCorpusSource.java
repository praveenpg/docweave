package io.docweave.validation;

import org.w3c.dom.Node;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Hidden JDK XML traversal preserving the historical inventory and substring diagnostic semantics. */
final class DefaultDocxCorpusSource implements DocxCorpusSource {
    private static final String WORD = XmlOwnerReader.WORD;
    private static final Map<String, String> COUNTS = Map.of("txbxContent", "text_boxes", "sdt", "content_controls",
            "ins", "insertions", "del", "deletions", "fldSimple", "simple_fields", "fldChar", "field_markers",
            "hyperlink", "hyperlinks", "drawing", "drawings");

    @Override
    public Snapshot inspect(final Path input) throws IOException {
        final var counts = new TreeMap<String, Integer>();
        final var paragraphs = new ArrayList<String>();
        final int depth = visit(XmlOwnerReader.document(input).getDocumentElement(), 0, 0, counts, paragraphs);
        final var parts = new TreeMap<String, Integer>();
        try (final var zip = new ZipFile(input.toFile())) {
            for (final var entry : zip.stream().toList()) {
                final String name = entry.getName();
                if (!name.startsWith("word/") || !name.endsWith(".xml")) {
                    continue;
                }
                final String basename = name.substring(name.lastIndexOf('/') + 1);
                for (final String kind : List.of("header", "footer", "footnotes", "endnotes", "comments")) {
                    if (basename.startsWith(kind)) {
                        parts.merge(kind, 1, Integer::sum);
                    }
                }
            }
        }
        return new Snapshot(counts, depth, parts, paragraphs);
    }

    private static int visit(final Node node, final int tableDepth, final int paragraphDepth,
            final Map<String, Integer> counts, final List<String> paragraphs) {
        int tables = tableDepth;
        int nesting = paragraphDepth;
        if (word(node, "tbl")) {
            tables++;
            counts.merge("tables", 1, Integer::sum);
            counts.merge("nested_tables", tables > 1 ? 1 : 0, Integer::sum);
        }
        if (word(node, "p")) {
            counts.merge("paragraphs", 1, Integer::sum);
            counts.merge("nested_paragraphs", nesting > 0 ? 1 : 0, Integer::sum);
            nesting++;
            final var text = new StringBuilder();
            storedText(node, text);
            final String normalized = DocxOwnership.normalize(text.toString());
            if (!normalized.isEmpty()) {
                paragraphs.add(normalized);
            }
        }
        if (WORD.equals(node.getNamespaceURI()) && COUNTS.containsKey(node.getLocalName())) {
            counts.merge(COUNTS.get(node.getLocalName()), 1, Integer::sum);
        }
        int maximum = tables;
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                maximum = Math.max(maximum, visit(child, tables, nesting, counts, paragraphs));
            }
        }
        return maximum;
    }

    /** Legacy policy skips deleted/move-from descendants, preserves tab/break boundaries, and never runs fields. */
    private static void storedText(final Node node, final StringBuilder output) {
        if (word(node, "del") || word(node, "moveFrom")) {
            return;
        }
        if (word(node, "t")) {
            output.append(node.getTextContent());
        } else if (WORD.equals(node.getNamespaceURI()) && Set.of("tab", "br", "cr").contains(node.getLocalName())) {
            output.append(' ');
        } else {
            for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    storedText(child, output);
                }
            }
        }
    }

    private static boolean word(final Node node, final String local) {
        return WORD.equals(node.getNamespaceURI()) && local.equals(node.getLocalName());
    }
}
