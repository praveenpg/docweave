package dev.docweave.partition;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ordered normalized inline rendering with optional source-table range capture. */
final class HtmlInlineText {
    /** Normalized inline text buffer. */
    private final StringBuilder text = new StringBuilder();
    /** Extracted hyperlink descriptors in document order. */
    private final List<Map<String, Object>> links = new ArrayList<>();
    /** Number of Unicode code points appended to {@link #text}. */
    private int points;
    /** Whether a single space separator should be emitted before the next non-space code point. */
    private boolean pendingSpace;
    /** Set of DOM elements whose normalized code-point intervals should be recorded. */
    private final Set<Element> tracked;
    /** Captured code-point intervals indexed by tracked DOM element. */
    private final Map<Element, Range> ranges;
    /** Maximum allowed code points for nested-table text and link payloads. */
    private final long maxPoints;
    /** Accumulated code points across extracted link labels and URLs. */
    private long linkPayload;

    /**
     * Half-open Unicode code-point interval within normalized inline text.
     *
     * @param start inclusive start code-point index
     * @param end exclusive end code-point index
     */
    record Range(int start, int end) {}

    /**
     * Creates an unbounded inline text accumulator without element range tracking.
     */
    HtmlInlineText() {
        this(Set.of(), Long.MAX_VALUE);
    }

    /**
     * Creates an inline text accumulator that records code-point ranges for {@code tracked} elements
     * and enforces {@code maxPoints}.
     *
     * @param tracked elements whose normalized text intervals should be captured
     * @param maxPoints maximum allowed code-point budget
     */
    HtmlInlineText(final Set<Element> tracked, final long maxPoints) {
        this.tracked = tracked;
        this.ranges = tracked.isEmpty() ? Map.of() : new IdentityHashMap<>();
        this.maxPoints = maxPoints;
    }

    /**
     * Returns the captured code-point ranges for tracked elements.
     *
     * @return map from tracked element to its code-point {@link Range}
     */
    Map<Element, Range> ranges() {
        return ranges;
    }

    /**
     * Returns an immutable copy of the extracted hyperlink descriptors.
     *
     * @return ordered list of hyperlink metadata maps
     */
    List<Map<String, Object>> links() {
        return List.copyOf(links);
    }

    /**
     * Returns the normalized inline text string.
     *
     * @return normalized inline text
     */
    String text() {
        return text.toString();
    }

    /**
     * Appends a DOM node and its descendants to the normalized inline text buffer.
     *
     * @param node DOM node to append
     * @throws IOException if the thread is interrupted or the code-point budget is exceeded
     */
    void append(final Node node) throws IOException {
        ElementCollector.checkInterrupted();
        if (node instanceof final TextNode value) {
            appendText(value.getWholeText());
        } else if (node instanceof final Element element) {
            if (element.normalName().equals("br")) {
                pendingSpace = !text.isEmpty();
                return;
            }
            if (element.normalName().equals("img")) {
                appendText(element.attr("alt"));
                return;
            }
            if (element.isBlock()) {
                pendingSpace = !text.isEmpty();
            }
            int start = points;
            for (final Node child : element.childNodes()) {
                append(child);
            }
            // Record the normalized code-point range for tracked nested tables, skipping any leading separator space.
            if (tracked.contains(element)) {
                final int begin =
                        points > start && text.charAt(text.offsetByCodePoints(0, start)) == ' ' ? start + 1 : start;
                ranges.put(element, new Range(begin, points));
            }
            // Extract hyperlink label, resolved URL, and starting code-point offset for non-empty <a href> elements.
            if (element.normalName().equals("a") && element.hasAttr("href") && points > start) {
                String href = element.absUrl("href");
                if (href.isEmpty()) {
                    href = element.attr("href");
                }
                String label = text.substring(text.offsetByCodePoints(0, start));
                if (label.startsWith(" ")) {
                    label = label.substring(1);
                    start++;
                }
                if (!label.isEmpty()) {
                    final long payload =
                            (long) label.codePointCount(0, label.length()) + href.codePointCount(0, href.length());
                    if (payload > maxPoints - linkPayload) {
                        throw new PartitionException(PartitionException.Code.TABLE_LIMIT,
                                "Nested-table link payload exceeds metadata budget");
                    }
                    linkPayload += payload;
                    links.add(Map.of("text", label, "url", href, "start_index", start));
                }
            }
            if (element.isBlock()) {
                pendingSpace = !text.isEmpty();
            }
        }
    }

    /**
     * Normalizes whitespace and appends character data to the inline buffer while enforcing {@link #maxPoints}.
     *
     * @param value raw text string to normalize and append
     * @throws IOException if the thread is interrupted or the code-point budget is exceeded
     */
    private void appendText(final String value) throws IOException {
        // Collapse consecutive whitespace code points into a single deferred space while enforcing maxPoints.
        for (int offset = 0; offset < value.length();) {
            if ((offset & 1023) == 0) {
                ElementCollector.checkInterrupted();
            }
            final int begin = offset;
            final int point = value.codePointAt(offset);
            offset += Character.charCount(point);
            if (TextSupport.space(point)) {
                pendingSpace = !text.isEmpty();
            } else {
                if ((long) points + (pendingSpace ? 1 : 0) + 1 > maxPoints) {
                    throw new PartitionException(
                            PartitionException.Code.TABLE_LIMIT, "Nested-table text exceeds metadata budget");
                }
                if (pendingSpace) {
                    text.append(' ');
                    points++;
                }
                // Copy the original UTF-16 slice: supplementary pairs need no temporary char array.
                text.append(value, begin, offset);
                points++;
                pendingSpace = false;
            }
        }
    }

    /**
     * Builds element link metadata entries from the accumulated hyperlinks.
     *
     * @return link metadata map produced by {@link HtmlDomWalker#linkMetadata(List)}
     */
    Map<String, Object> metadata() {
        return HtmlDomWalker.linkMetadata(links);
    }
}
