package dev.docweave.partition;

import org.jsoup.nodes.Document;

import java.io.IOException;
import java.util.Set;

/**
 * Document-text policy for non-content subtrees and transparent unknown/legacy wrappers.
 * This operates after the original depth/hidden-content checks; it does not render CSS,
 * fetch embedded resources, interpret MathML or infer namespaces from arbitrary prefixes.
 */
final class HtmlDomPolicy {
    /** Conventional ix:header is excluded literally, matching the reviewed upstream HTML rule. */
    private static final Set<String> NON_CONTENT = Set.of("audio", "canvas", "datalist", "dialog", "head", "iframe",
            "ix:header", "noembed", "noframes", "object", "select", "svg", "textarea", "title", "video", "xml");

    /** Utility class; policy is invocation-local and has no mutable shared state. */
    private HtmlDomPolicy() {
    }

    /**
     * Removes non-content subtrees before unwrapping tags, retaining child order and inline text adjacency.
     * Unknown wrappers and legacy font have no retained attributes; unwrapping exposes their block
     * children to the semantic walker without turning the wrapper itself into a document element.
     * @param document parsed DOM already checked against the original depth budget
     * @throws IOException if the caller thread is interrupted
     */
    static void apply(final Document document) throws IOException {
        for (final var element : document.getAllElements()) {
            ElementCollector.checkInterrupted();
            if (NON_CONTENT.contains(element.normalName())) {
                element.remove();
            }
        }
        // Take a new snapshot after subtree removal so excluded descendants cannot be promoted into content.
        for (final var element : document.body().getAllElements()) {
            ElementCollector.checkInterrupted();
            if (!element.tag().isKnownTag() || element.normalName().equals("font")) {
                element.unwrap();
            }
        }
    }
}
