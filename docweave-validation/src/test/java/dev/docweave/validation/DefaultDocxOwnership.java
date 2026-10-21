package dev.docweave.validation;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Hidden, stateless implementation; no production parser helpers or XML models are reused. */
final class DefaultDocxOwnership implements DocxOwnership {
    private static final Set<ElementType> PARAGRAPHS =
            Set.of(ElementType.NarrativeText, ElementType.Title, ElementType.ListItem);

    @Override
    public List<Owner> read(final Path path) throws IOException {
        return new XmlOwnerReader().read(path);
    }

    @Override
    public Report check(final List<Element> elements, final List<Owner> owners) {
        final var indexed = new HashMap<String, Element>();
        for (final var element : elements) {
            if (indexed.putIfAbsent(element.elementId(), element) != null) {
                throw new IllegalArgumentException("Duplicate output element identity");
            }
        }
        final var queues = new HashMap<Lane, List<Element>>();
        for (final var element : elements) {
            final String part = part(element);
            if ((!part.equals("body") && !part.equals("textbox")) || element.type() == ElementType.Image
                    || part.equals("textbox") && outsideBody(element, indexed)) {
                continue;
            }
            final var lane = new Lane(part, part.equals("textbox") ? parent(element) : null);
            queues.computeIfAbsent(lane, ignored -> new ArrayList<>()).add(element);
        }
        final var cursors = new HashMap<Lane, Integer>();
        final var mapped = new HashMap<String, Element>();
        final var checks = new ArrayList<OwnerCheck>();
        for (final var owner : owners) {
            final var parentElement = mapped.get(owner.parent());
            final String parentId = parentElement == null ? null : parentElement.elementId();
            final var lane = new Lane(owner.story() == Story.BODY ? "body" : "textbox", parentId);
            final var candidates = queues.getOrDefault(lane, List.of());
            final int offset = cursors.getOrDefault(lane, 0);
            final var element = offset < candidates.size() && (owner.story() == Story.BODY || parentId != null)
                                        ? candidates.get(offset)
                                        : null;
            if (element != null) {
                cursors.put(lane, offset + 1);
                mapped.put(owner.key(), element);
            }
            final boolean kindMatches = element != null
                                        && (owner.kind() == Kind.TABLE ? element.type() == ElementType.Table
                                                                       : PARAGRAPHS.contains(element.type()));
            final String text = kindMatches ? DocxOwnership.normalize(element.text()) : "";
            final var reserved = new ArrayList<Interval>();
            final var missing = new ArrayList<String>();
            final var longestFirst =
                    owner.paragraphs()
                            .stream()
                            .sorted(Comparator.comparingInt((String p) -> p.codePointCount(0, p.length())).reversed())
                            .toList();
            for (final String paragraph : longestFirst) {
                int start = text.indexOf(paragraph);
                while (start >= 0 && overlaps(reserved, start, start + paragraph.length())) {
                    start = text.indexOf(paragraph, start + 1);
                }
                if (start < 0) {
                    missing.add(DocxOwnership.textHash(paragraph));
                } else {
                    reserved.add(new Interval(start, start + paragraph.length()));
                }
            }
            checks.add(new OwnerCheck(
                    owner.key(), owner.story(), owner.kind(), owner.paragraphs().size(), kindMatches, missing));
        }
        final int unexpected =
                queues.entrySet()
                        .stream()
                        .mapToInt(entry -> entry.getValue().size() - cursors.getOrDefault(entry.getKey(), 0))
                        .sum();
        return new Report(checks, unexpected);
    }

    private static boolean overlaps(final List<Interval> intervals, final int start, final int end) {
        return intervals.stream().anyMatch(interval -> start < interval.end() && end > interval.start());
    }

    /** Known other-part roots are excluded; cycles and missing parents remain visible failures. */
    private static boolean outsideBody(final Element initial, final Map<String, Element> indexed) {
        var element = initial;
        final var seen = new HashSet<String>();
        while (part(element).equals("textbox")) {
            final String parent = parent(element);
            if (!seen.add(parent) || !indexed.containsKey(parent)) {
                return false;
            }
            element = indexed.get(parent);
        }
        return !part(element).equals("body");
    }

    private static String part(final Element element) {
        return element.metadata().getOrDefault("docx_part", "body").toString();
    }

    private static String parent(final Element element) {
        final var value = element.metadata().get("parent_id");
        return value == null ? null : value.toString();
    }

    private record Lane(String story, String parent) {}

    private record Interval(int start, int end) {}
}
