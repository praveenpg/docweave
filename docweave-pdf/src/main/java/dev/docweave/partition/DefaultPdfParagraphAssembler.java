package dev.docweave.partition;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/** Pure bounded logical assembly; all request state is confined to assemble. */
final class DefaultPdfParagraphAssembler implements PdfParagraphAssembler {
    /** Fixed aggregate text ceiling, including separators, before allocating merged strings. */
    private static final long MAX_UNITS = 16_000_000;
    /** Original SHA256 and canonical nonnegative source ordinal, independent of filenames. */
    private static final Pattern SOURCE = Pattern.compile("[a-f0-9]{64}:(?:0|[1-9][0-9]{0,9})");
    /** Immutable caller limits. */
    private final PdfParagraphAssemblyOptions options;

    /**
     * One original occurrence; positions prevent duplicated/forged element IDs from dropping unrelated input.
     * @param index original list position
     * @param element original immutable element
     */
    private record Part(int index, Element element) {}

    /**
     * One validated complete source group, before any output is changed.
     * @param source original document-bound paragraph identity
     * @param parts complete ordered source occurrences
     * @param units projected UTF16 units including spaces
     */
    private record Plan(String source, List<Part> parts, int units) {}

    /**
     * Captures immutable budgets without processing source data.
     * @param options nonnull caller policy
     */
    DefaultPdfParagraphAssembler(final PdfParagraphAssemblyOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Element> assemble(final List<Element> elements) {
        Objects.requireNonNull(elements, "elements");
        checkInterrupted();
        if (elements.size() > options.maxElements()) {
            throw limit();
        }
        final var input = List.copyOf(elements);
        final var groups = new LinkedHashMap<String, List<Part>>();
        long units = 0;
        for (int index = 0; index < input.size(); index++) {
            checkInterrupted();
            final var element = input.get(index);
            units += element.text().length();
            if (units > MAX_UNITS) {
                throw limit();
            }
            final var identity = element.metadata().get("pdf_source_paragraph_id");
            if (identity instanceof final String source && source.length() >= 66 && source.length() <= 75
                    && SOURCE.matcher(source).matches()) {
                final var group = groups.computeIfAbsent(source, ignored -> new ArrayList<>());
                if (group.size() >= options.maxFragmentsPerParagraph()) {
                    throw limit();
                }
                group.add(new Part(index, element));
            }
        }
        final var plans = new ArrayList<Plan>();
        for (final var entry : groups.entrySet()) {
            checkInterrupted();
            final var plan = plan(entry.getKey(), entry.getValue());
            if (plan != null) {
                units += plan.parts().size() - 1L;
                if (units > MAX_UNITS) {
                    throw limit();
                }
                plans.add(plan);
            }
        }
        final var replacements = new LinkedHashMap<Integer, Element>();
        final var consumed = new boolean[input.size()];
        for (final var plan : plans) {
            checkInterrupted();
            replacements.put(plan.parts().getFirst().index(), combine(plan));
            for (final var part : plan.parts().subList(1, plan.parts().size())) {
                consumed[part.index()] = true;
            }
        }
        final var result = new ArrayList<Element>();
        for (int index = 0; index < input.size(); index++) {
            checkInterrupted();
            if (!consumed[index]) {
                result.add(replacements.getOrDefault(index, input.get(index)));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Requires a complete consecutive-page group in original input order and compatible source text policies.
     * @param source canonical source identity
     * @param parts all occurrences sharing that identity, including any malformed contenders
     * @return complete plan or null for unchanged fallback
     */
    private Plan plan(final String source, final List<Part> parts) {
        if (parts.size() < 2) {
            return null;
        }
        final var first = parts.getFirst().element().metadata();
        final int count = integer(first.get("pdf_source_paragraph_fragments"), 2);
        final int ordinal = integer(first.get("pdf_source_paragraph_ordinal"), 0);
        final int page = integer(first.get("page_number"), 1);
        final var wrap = first.get("pdf_source_word_wrap");
        final var unicode = first.get("pdf_native_unicode_policy");
        final var scripts = first.get("pdf_source_inline_scripts");
        if (scripts != null && !"nearby_small_glyphs_v1".equals(scripts)) {
            return null;
        }
        if (unicode != null && !"presentation_forms_nfkc_v1".equals(unicode)) {
            return null;
        }
        if (count != parts.size() || ordinal < 0 || page < 1 || !source.endsWith(":" + ordinal)
                || !(wrap instanceof String)
                || !(wrap.equals("preserve_v1") || wrap.equals("source_soft_hyphens_v1")
                        || wrap.equals("source_line_wraps_v1"))) {
            return null;
        }
        long units = parts.size() - 1L;
        for (int index = 0; index < parts.size(); index++) {
            checkInterrupted();
            final var element = parts.get(index).element();
            final var metadata = element.metadata();
            if (element.type() != ElementType.UncategorizedText || element.text().isBlank()
                    || !"source_tagged_paragraph_v1".equals(metadata.get("pdf_text_layout"))
                    || integer(metadata.get("pdf_source_paragraph_fragments"), 2) != count
                    || integer(metadata.get("pdf_source_paragraph_fragment"), 1) != index + 1
                    || integer(metadata.get("pdf_source_paragraph_ordinal"), 0) != ordinal
                    || integer(metadata.get("page_number"), 1) != (long) page + index
                    || !Objects.equals(wrap, metadata.get("pdf_source_word_wrap"))
                    || !Objects.equals(
                            first.get("pdf_source_inline_scripts"), metadata.get("pdf_source_inline_scripts"))
                    || !Objects.equals(
                            first.get("pdf_native_unicode_policy"), metadata.get("pdf_native_unicode_policy"))) {
                return null;
            }
            units += element.text().length();
            if (units > options.maxParagraphCharacters() || units > MAX_UNITS) {
                throw limit();
            }
        }
        return new Plan(source, List.copyOf(parts), (int) units);
    }

    /**
     * Retains every original character and metadata frame while making source fragments contiguous.
     * @param plan complete bounded source group
     * @return logical paragraph without a fabricated single-page coordinate box
     */
    private Element combine(final Plan plan) {
        final var text = new StringBuilder(plan.units());
        final var provenance = new ArrayList<Map<String, Object>>();
        final var pages = new ArrayList<Integer>();
        int points = 0;
        for (final var part : plan.parts()) {
            checkInterrupted();
            if (!text.isEmpty()) {
                text.append(' ');
                points++;
            }
            final int start = points;
            text.append(part.element().text());
            points += part.element().text().codePointCount(0, part.element().text().length());
            provenance.add(Map.of("element_id", part.element().elementId(), "metadata", part.element().metadata(),
                    "text_start_code_point", start, "text_end_code_point", points));
            pages.add(integer(part.element().metadata().get("page_number"), 1));
        }
        final var first = plan.parts().getFirst().element().metadata();
        final var metadata = new LinkedHashMap<String, Object>();
        for (final String key :
                List.of("filename", "file_directory", "filetype", "languages", "pdf_native_unicode_policy",
                        "pdf_source_paragraph_ordinal", "pdf_source_word_wrap", "pdf_source_inline_scripts")) {
            if (first.containsKey(key)
                    && plan.parts().stream().allMatch(
                            part -> Objects.equals(first.get(key), part.element().metadata().get(key)))) {
                metadata.put(key, first.get(key));
            }
        }
        metadata.put("pdf_text_layout", "source_tagged_paragraph_assembled_v1");
        metadata.put("pdf_source_paragraph_id", plan.source());
        metadata.put("pdf_source_paragraph_fragments", plan.parts().size());
        metadata.put("pdf_source_paragraph_parts", provenance);
        metadata.put("page_numbers", pages);
        final String value = text.toString();
        checkInterrupted();
        final String id = PdfParagraphIdentity.assembled(plan.source(), value);
        checkInterrupted();
        return new Element(id, ElementType.UncategorizedText, value, metadata);
    }

    /**
     * Accepts only exact bounded JSON integer values, including numerically integral decimal representations.
     * @param value original metadata value
     * @param minimum smallest allowed value
     * @return integer or minus one for unchanged malformed-metadata fallback
     */
    private static int integer(final Object value, final int minimum) {
        if (value instanceof Number) {
            try {
                final int result = new BigDecimal(value.toString()).intValueExact();
                return result >= minimum ? result : -1;
            } catch (final NumberFormatException | ArithmeticException invalid) {
                return -1;
            }
        }
        return -1;
    }

    /** Checks cooperative cancellation without clearing the caller's interrupt flag. */
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("PDF paragraph assembly interrupted");
        }
    }

    /**
     * Returns a content-free budget diagnostic; no partial result is returned.
     * @return resource-policy failure
     */
    private static IllegalArgumentException limit() {
        return new IllegalArgumentException("PDF paragraph assembly exceeds configured or aggregate text limits");
    }
}
