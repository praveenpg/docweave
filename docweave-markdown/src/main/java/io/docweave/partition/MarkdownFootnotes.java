package io.docweave.partition;

import org.commonmark.ext.footnotes.FootnoteDefinition;
import org.commonmark.ext.footnotes.FootnoteReference;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Footnote numbering and placement shared by the AST walker and EXTRACT_DOM (#59), following commonmark-java's HTML
 * renderer so both modes number notes alike and neither loses text.
 *
 * <p>The renderer numbers references outside notes in document order, then references inside each rendered note in
 * number order, and renders the first definition of each label that is referenced. It silently omits every other
 * definition: unreferenced ones, later duplicates of a label and definitions nested in another definition. Such a
 * definition is treated as transparent, its blocks being ordinary content in place: EXTRACT_DOM unwraps it before
 * rendering, and both modes then number as the renderer does on the unwrapped document, where a transparent
 * definition is no longer a definition: a reference to it stays literal, and so does a reference to a note that only
 * transparent notes cite, which is transparent too.</p>
 */
final class MarkdownFootnotes {
    /** Rendered definitions with their numbers, in number order. */
    private final Map<FootnoteDefinition, Integer> numbers;
    /** Rendered definitions by normalized label. */
    private final Map<String, FootnoteDefinition> byLabel;
    /** Definitions the renderer omits, kept as ordinary content. */
    private final Set<FootnoteDefinition> transparent;

    /**
     * Stores an analysis.
     * @param numbers rendered definitions with their numbers
     * @param byLabel rendered definitions by normalized label
     * @param transparent omitted definitions
     */
    private MarkdownFootnotes(final Map<FootnoteDefinition, Integer> numbers,
            final Map<String, FootnoteDefinition> byLabel, final Set<FootnoteDefinition> transparent) {
        this.numbers = numbers;
        this.byLabel = byLabel;
        this.transparent = transparent;
    }

    /**
     * Normalizes a footnote label as CommonMark matches link labels: trimmed, case-folded, whitespace collapsed.
     * @param label label as written
     * @return matching key
     */
    static String label(final String label) {
        return label.trim().toLowerCase(Locale.ROOT).toUpperCase(Locale.ROOT).replaceAll("[ \\t\\r\\n]+", " ");
    }

    /**
     * Analyzes a parsed document without modifying it.
     * @param document parsed document
     * @return numbering and transparent definitions
     * @throws IOException if interrupted
     */
    static MarkdownFootnotes analyze(final Node document) throws IOException {
        // Transparent definitions only accumulate, and are never candidates, exactly as they are no longer definitions
        // once unwrapped. With a larger transparent set every previously numbered definition is still the first
        // non-transparent definition of its label and still reached (its references come from the body or from
        // numbered notes, which stay), so a second round adds nothing: the loop ends after at most two rounds of
        // linear walks. The bound is defensive.
        final Set<FootnoteDefinition> transparent = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<FootnoteDefinition> all = definitions(document, transparent, true);
        for (int round = 0; ; round++) {
            final var candidates = new HashMap<String, FootnoteDefinition>();
            for (final FootnoteDefinition definition : definitions(document, transparent, false)) {
                if (!transparent.contains(definition)) {
                    candidates.putIfAbsent(label(definition.getLabel()), definition);
                }
            }
            final Map<FootnoteDefinition, Integer> numbers = new IdentityHashMap<>();
            final var byLabel = new HashMap<String, FootnoteDefinition>();
            final var order = new ArrayList<FootnoteDefinition>();
            number(document, transparent, candidates, numbers, byLabel, order);
            for (int index = 0; index < order.size(); index++) {
                number(order.get(index), transparent, candidates, numbers, byLabel, order);
            }
            boolean added = false;
            for (final FootnoteDefinition definition : all) {
                if (!numbers.containsKey(definition) && transparent.add(definition)) {
                    added = true;
                }
            }
            if (!added || round >= 2) {
                return new MarkdownFootnotes(numbers, byLabel, transparent);
            }
        }
    }

    /**
     * Tests whether a link reference definition was meant as a footnote but is malformed (for example
     * {@code [^a b]:} or {@code [^]:}): CommonMark reads it as a link reference definition and renders nothing.
     * @param definition parsed link reference definition
     * @return whether its label starts with {@code ^}
     */
    static boolean malformed(final LinkReferenceDefinition definition) {
        return definition.getLabel().startsWith("^");
    }

    /**
     * Rebuilds the text of a malformed footnote definition so it is not lost.
     * @param definition parsed link reference definition
     * @return {@code [label]: destination}, with the title in double quotes when present
     */
    static String literal(final LinkReferenceDefinition definition) {
        final var literal = new StringBuilder("[").append(definition.getLabel()).append("]: ")
                                    .append(definition.getDestination());
        if (definition.getTitle() != null) {
            literal.append(" \"").append(definition.getTitle()).append('"');
        }
        return literal.toString();
    }

    /**
     * Replaces malformed footnote definitions by literal paragraphs, so HTML rendering keeps their text as the AST
     * walker does.
     * @param document parsed document, modified in place
     * @throws IOException if interrupted
     */
    static void keepMalformed(final Node document) throws IOException {
        final var found = new ArrayList<LinkReferenceDefinition>();
        final var pending = new ArrayDeque<Node>();
        pending.push(document);
        while (!pending.isEmpty()) {
            ElementCollector.checkInterrupted();
            final Node node = pending.pop();
            if (node instanceof final LinkReferenceDefinition definition && malformed(definition)) {
                found.add(definition);
            }
            for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) {
                pending.push(child);
            }
        }
        for (final LinkReferenceDefinition definition : found) {
            final var paragraph = new Paragraph();
            paragraph.appendChild(new Text(literal(definition)));
            definition.insertBefore(paragraph);
            definition.unlink();
        }
    }

    /**
     * Returns the number shown for a reference.
     * @param reference parsed reference
     * @return number, or {@code null} when the renderer shows the reference as literal text
     */
    Integer number(final FootnoteReference reference) {
        final FootnoteDefinition definition = byLabel.get(label(reference.getLabel()));
        return definition == null ? null : numbers.get(definition);
    }

    /**
     * Returns the number of a rendered definition.
     * @param definition parsed definition
     * @return number, or {@code null} for a definition the renderer omits
     */
    Integer number(final FootnoteDefinition definition) {
        return numbers.get(definition);
    }

    /**
     * Tests whether a definition is omitted by the renderer and so kept as ordinary content.
     * @param definition parsed definition
     * @return whether transparent
     */
    boolean transparent(final FootnoteDefinition definition) {
        return transparent.contains(definition);
    }

    /**
     * Replaces every transparent definition by its own blocks, in place, so the HTML renderer extracts their text.
     * @param document parsed document, modified in place
     */
    void unwrap(final Node document) {
        for (final FootnoteDefinition definition : transparent) {
            if (definition.getParent() == null) {
                continue;
            }
            for (Node child = definition.getFirstChild(); child != null;) {
                final Node next = child.getNext();
                definition.insertBefore(child);
                child = next;
            }
            definition.unlink();
        }
    }

    /**
     * Numbers the definitions referenced from a subtree, in document order, descending into transparent
     * definitions but not into rendered ones other than the root.
     * @param root subtree root
     * @param transparent omitted definitions
     * @param candidates first definition of each label outside non-transparent definitions
     * @param numbers receives definition numbers
     * @param byLabel receives numbered definitions by label
     * @param order numbered definitions in number order, extended
     * @throws IOException if interrupted
     */
    private static void number(final Node root, final Set<FootnoteDefinition> transparent,
            final Map<String, FootnoteDefinition> candidates, final Map<FootnoteDefinition, Integer> numbers,
            final Map<String, FootnoteDefinition> byLabel, final List<FootnoteDefinition> order) throws IOException {
        final var pending = new ArrayDeque<Node>();
        pending.push(root);
        while (!pending.isEmpty()) {
            ElementCollector.checkInterrupted();
            final Node node = pending.pop();
            if (node instanceof final FootnoteReference reference) {
                final String key = label(reference.getLabel());
                final FootnoteDefinition target = candidates.get(key);
                if (target != null && !numbers.containsKey(target)) {
                    numbers.put(target, numbers.size() + 1);
                    byLabel.put(key, target);
                    order.add(target);
                }
            }
            if (node != root && node instanceof final FootnoteDefinition definition
                    && !transparent.contains(definition)) {
                continue;
            }
            for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) {
                pending.push(child);
            }
        }
    }

    /**
     * Lists definitions in document order, descending into transparent definitions.
     * @param document parsed document
     * @param transparent omitted definitions
     * @param nested whether definitions inside non-transparent definitions are listed (and descended into) too
     * @return definitions
     * @throws IOException if interrupted
     */
    private static List<FootnoteDefinition> definitions(final Node document, final Set<FootnoteDefinition> transparent,
            final boolean nested) throws IOException {
        final var found = new ArrayList<FootnoteDefinition>();
        final var pending = new ArrayDeque<Node>();
        pending.push(document);
        while (!pending.isEmpty()) {
            ElementCollector.checkInterrupted();
            final Node node = pending.pop();
            if (node instanceof final FootnoteDefinition definition) {
                found.add(definition);
                if (!nested && !transparent.contains(definition)) {
                    continue;
                }
            }
            for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) {
                pending.push(child);
            }
        }
        return found;
    }
}
