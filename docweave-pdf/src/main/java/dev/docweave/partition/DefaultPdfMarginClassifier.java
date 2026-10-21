package dev.docweave.partition;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded document-local repeated-margin evidence; no mutable state is shared between calls. */
final class DefaultPdfMarginClassifier implements PdfMarginClassifier {
    /** Unicode whitespace equivalence applies only to comparison keys, never to source element text. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    /** Immutable explicit margin/body policy, shared safely by concurrent calls. */
    private final PdfMarginOptions options;
    /** Whether tags must record additional configured policy fields. */
    private final boolean configured;

    /**
     * Captures immutable classification policy without inspecting source elements.
     * @param options nonnull explicit policy, validated by the public factory
     */
    DefaultPdfMarginClassifier(final PdfMarginOptions options) {
        this.options = options;
        configured = !options.equals(PdfMarginOptions.defaults());
    }

    /**
     * Normalized repeated-text grouping key; case, punctuation and numbers stay significant.
     * @param text whitespace-normalized source text
     * @param role inferred margin role
     */
    private record Key(String text, ElementType role) {}

    /**
     * Source index and displayed, normalized box of one eligible line.
     * @param index index in the original ordered element list
     * @param page one-based physical page
     * @param left normalized left edge
     * @param top normalized top edge
     * @param right normalized right edge
     * @param bottom normalized bottom edge
     */
    private record Candidate(int index, int page, double left, double top, double right, double bottom) {}

    /**
     * Recognizes exact numeric zero independently of its JSON-compatible Java representation.
     * BigDecimal uses its sign so a tiny nonzero angle cannot underflow into upright eligibility.
     * @param value declared source rotation
     * @return whether the value denotes numeric zero
     */
    private static boolean zeroRotation(final Object value) {
        return value instanceof final BigDecimal decimal
                ? decimal.signum() == 0
                : value instanceof final Number number && number.doubleValue() == 0;
    }

    @Override
    public List<Element> classify(final List<Element> elements, final int pageCount) throws IOException {
        if (pageCount < 1 || pageCount > 10_000) {
            throw new IllegalArgumentException("physical pageCount must be from1 through10000");
        }
        if (elements.size() > 100_000) {
            throw new PartitionException(
                    PartitionException.Code.ELEMENT_LIMIT, "PDF margin classification exceeds100000 elements");
        }
        final var input = List.copyOf(elements);
        final var groups = new LinkedHashMap<Key, List<Candidate>>();
        final Set<String> bodyText = new HashSet<>();
        final var converter = PdfCoordinateConverter.create();
        final Set<Object> filenames = new HashSet<>();
        for (int index = 0; index < input.size(); index++) {
            ElementCollector.checkInterrupted();
            final var element = input.get(index);
            final var pageValue = element.metadata().get("page_number");
            if (!(pageValue instanceof Number page) || !Double.isFinite(page.doubleValue())
                    || page.doubleValue() != page.intValue() || page.intValue() < 1 || page.intValue() > pageCount) {
                throw new IllegalArgumentException("Each source element requires a valid physical page_number");
            }
            if (element.metadata().containsKey("filename")) {
                filenames.add(element.metadata().get("filename"));
                if (filenames.size() > 1) {
                    throw new IllegalArgumentException("Mixed source filenames are not one PDF document");
                }
            }
            final var filetype = element.metadata().get("filetype");
            if (filetype != null && !filetype.equals("application/pdf")) {
                throw new IllegalArgumentException("PDF margin classification requires one PDF document");
            }
            if (!ordinary(element.type()) || element.text().length() > 640
                    || !element.metadata().containsKey("coordinates")) {
                continue;
            }
            final String text = WHITESPACE.matcher(element.text()).replaceAll(" ").strip();
            if (text.length() > 160 || text.isBlank() || text.codePoints().noneMatch(Character::isLetter)) {
                continue;
            }
            if (element.metadata().containsKey("pdf_text_rotation")
                    && !zeroRotation(element.metadata().get("pdf_text_rotation"))) {
                continue;
            }
            final var coordinates = (Map<?, ?>)converter.toUserSpace(element).metadata().get("coordinates");
            final double width = ((Number) coordinates.get("layout_width")).doubleValue();
            final double height = ((Number) coordinates.get("layout_height")).doubleValue();
            double left = Double.POSITIVE_INFINITY;
            double top = Double.POSITIVE_INFINITY;
            double right = Double.NEGATIVE_INFINITY;
            double bottom = Double.NEGATIVE_INFINITY;
            for (final var point : (List<?>) coordinates.get("points")) {
                final var pair = (List<?>) point;
                final double x = ((Number) pair.get(0)).doubleValue() / width;
                final double y = ((Number) pair.get(1)).doubleValue() / height;
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
            final ElementType role;
            if (left >= 0 && right <= 1 && bottom > top && bottom - top <= 0.04 && right > left && top >= 0
                    && bottom <= options.headerBandFraction()) {
                role = ElementType.Header;
            } else if (left >= 0 && right <= 1 && bottom > top && bottom - top <= 0.04 && right > left
                       && top >= 1 - options.footerBandFraction() && bottom <= 1) {
                role = ElementType.Footer;
            } else {
                if (!options.allowFirstPageBodyText() || page.intValue() != 1) {
                    bodyText.add(text);
                }
                continue;
            }
            groups.computeIfAbsent(new Key(text, role), ignored -> new ArrayList<>())
                    .add(new Candidate(index, page.intValue(), left, top, right, bottom));
        }
        final var tags = new HashMap<Integer, Map.Entry<Key, Integer>>();
        for (final var entry : groups.entrySet()) {
            ElementCollector.checkInterrupted();
            final var candidates = entry.getValue();
            if (bodyText.contains(entry.getKey().text()) || !supported(candidates, pageCount)) {
                continue;
            }
            for (final var candidate : candidates) {
                tags.put(candidate.index(), Map.entry(entry.getKey(), candidates.size()));
            }
        }
        final var output = new ArrayList<Element>(input.size());
        for (int index = 0; index < input.size(); index++) {
            ElementCollector.checkInterrupted();
            final var original = input.get(index);
            final var tag = tags.get(index);
            if (tag == null) {
                output.add(original);
            } else {
                final var metadata = new LinkedHashMap<>(original.metadata());
                final var evidence =
                        new LinkedHashMap<String, Object>(Map.of("policy", "repeated_margin_v1", "source_type",
                                original.type().name(), "matching_pages", tag.getValue(), "physical_pages", pageCount));
                if (configured) {
                    evidence.put("policy", "repeated_margin_configured_v1");
                    evidence.put("header_band_fraction", options.headerBandFraction());
                    evidence.put("footer_band_fraction", options.footerBandFraction());
                    evidence.put("first_page_body_text_allowed", options.allowFirstPageBodyText());
                }
                metadata.put("pdf_margin_classification", evidence);
                output.add(new Element(original.elementId(), tag.getKey().role(), original.text(), metadata));
            }
        }
        return List.copyOf(output);
    }

    /**
     * Restricts inference to ordinary source text; structural/explicit semantic elements remain intact.
     * @param type source category
     * @return whether margin inference is allowed
     */
    private static boolean ordinary(final ElementType type) {
        return type == ElementType.Title || type == ElementType.NarrativeText || type == ElementType.UncategorizedText;
    }

    /**
     * Requires unique pages, absolute support and bounded normalized extent variation.
     * @param candidates same-text/same-margin occurrences
     * @param pages physical document page count, including blank pages
     * @return whether all evidence is consistent
     */
    private static boolean supported(final List<Candidate> candidates, final int pages) {
        if (candidates.size() < Math.max(3, (int) Math.ceil(0.6 * pages))) {
            return false;
        }
        final var seen = new HashSet<Integer>();
        double minLeft = 1;
        double maxLeft = 0;
        double minTop = 1;
        double maxTop = 0;
        double minRight = 1;
        double maxRight = 0;
        double minBottom = 1;
        double maxBottom = 0;
        for (final var candidate : candidates) {
            if (!seen.add(candidate.page())) {
                return false;
            }
            minLeft = Math.min(minLeft, candidate.left());
            maxLeft = Math.max(maxLeft, candidate.left());
            minTop = Math.min(minTop, candidate.top());
            maxTop = Math.max(maxTop, candidate.top());
            minRight = Math.min(minRight, candidate.right());
            maxRight = Math.max(maxRight, candidate.right());
            minBottom = Math.min(minBottom, candidate.bottom());
            maxBottom = Math.max(maxBottom, candidate.bottom());
        }
        return maxLeft - minLeft <= 0.02 && maxTop - minTop <= 0.02 && maxRight - minRight <= 0.02
                && maxBottom - minBottom <= 0.02;
    }
}
