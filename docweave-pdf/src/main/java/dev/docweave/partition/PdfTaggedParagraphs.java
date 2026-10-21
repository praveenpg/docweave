package dev.docweave.partition;

import dev.docweave.core.ElementType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Transactional native source-paragraph grouping without semantic guesses for untagged content. */
final class PdfTaggedParagraphs {
    /** Prevents utility instantiation. */
    private PdfTaggedParagraphs() {
    }

    /**
     * One candidate before shared-source ownership is validated.
     * @param item complete original-frame paragraph
     * @param glyphs exact retained source identities
     */
    private record Prepared(PdfLayoutItem item, List<PdfPageText.Glyph> glyphs) {}

    /**
     * Accepted paragraphs and still-unclaimed original native glyphs.
     * @param paragraphs complete supported source elements
     * @param remaining source positions not claimed by accepted paragraphs
     */
    record Result(List<PdfLayoutItem> paragraphs, List<PdfPageText.Glyph> remaining) {}

    /**
     * Validates all supported page plans before committing disjoint native ownership.
     * Existing source tables have already claimed their glyphs and take precedence over conflicting P references.
     * Conflicting paragraph candidates are both rejected whole; no partial paragraph consumes source glyphs.
     * @param plans original single-page source plans
     * @param page zero-based original page
     * @param text complete retained MCID identity map
     * @param available glyphs unclaimed by prior source tables
     * @param geometry original displayed crop/rotation frame
     * @param options explicit paragraph limits and wrap interpretation
     * @param work shared page work/cancellation counter
     * @return accepted paragraphs and exact remaining source positions
     * @throws IOException if resource or cancellation limits fail
     */
    static Result extract(final List<PdfTaggedStructure.Paragraph> plans, final int page, final PdfPageText text,
            final List<PdfPageText.Glyph> available, final PdfPageGeometry geometry,
            final PdfTaggedParagraphOptions options, final PdfTableWork work) throws IOException {
        return extract(plans, page, text, available, geometry, options, work, null);
    }

    /**
     * Validates source plans with an optional original-document fingerprint for logical assembly.
     * @param plans original source plans
     * @param page zero-based source page
     * @param text retained native identities
     * @param available unclaimed glyphs
     * @param geometry displayed page frame
     * @param options paragraph policy
     * @param work shared cancellation and work budget
     * @param documentIdentity SHA256 of original PDF bytes, or null for unlinked diagnostics
     * @return accepted paragraphs and unclaimed glyphs
     * @throws IOException if resource or cancellation limits fail
     */
    static Result extract(final List<PdfTaggedStructure.Paragraph> plans, final int page, final PdfPageText text,
            final List<PdfPageText.Glyph> available, final PdfPageGeometry geometry,
            final PdfTaggedParagraphOptions options, final PdfTableWork work, final String documentIdentity)
            throws IOException {
        final Set<PdfPageText.Glyph> allowed = Collections.newSetFromMap(new IdentityHashMap<>());
        allowed.addAll(available);
        final var candidates = new ArrayList<Prepared>();
        int prospective = 0;
        for (final var plan : plans) {
            work.step();
            if (plan.page() == page) {
                if (++prospective > options.maxParagraphsPerPage()) {
                    throw limit("Source paragraphs exceed maxParagraphsPerPage");
                }
                final var prepared = prepare(plan, text, allowed, geometry, options, work, documentIdentity);
                if (prepared != null) {
                    candidates.add(prepared);
                }
            }
        }
        final var blocked = new boolean[candidates.size()];
        final Map<PdfPageText.Glyph, Integer> owners = new IdentityHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            for (final var glyph : candidates.get(i).glyphs()) {
                work.step();
                final var prior = owners.putIfAbsent(glyph, i);
                if (prior != null) {
                    blocked[i] = true;
                    blocked[prior] = true;
                }
            }
        }
        final Set<PdfPageText.Glyph> claims = Collections.newSetFromMap(new IdentityHashMap<>());
        final var paragraphs = new ArrayList<PdfLayoutItem>();
        for (int i = 0; i < candidates.size(); i++) {
            if (!blocked[i]) {
                paragraphs.add(candidates.get(i).item());
                for (final var glyph : candidates.get(i).glyphs()) {
                    work.step();
                    claims.add(glyph);
                }
            }
        }
        final var remaining = new ArrayList<PdfPageText.Glyph>();
        for (final var glyph : available) {
            work.step();
            if (!claims.contains(glyph)) {
                remaining.add(glyph);
            }
        }
        return new Result(List.copyOf(paragraphs), List.copyOf(remaining));
    }

    /**
     * Resolves every native reference and admits only complete upright single-column source text.
     * @param plan original paragraph plan
     * @param text complete retained identity map
     * @param allowed positions not reserved by source tables
     * @param geometry original displayed source frame
     * @param options explicit character budget and discretionary policy
     * @param documentIdentity optional original-document SHA256
     * @param work shared page counter
     * @return complete candidate or null without claims
     * @throws IOException if resource or cancellation limits fail
     */
    private static Prepared prepare(final PdfTaggedStructure.Paragraph plan, final PdfPageText text,
            final Set<PdfPageText.Glyph> allowed, final PdfPageGeometry geometry,
            final PdfTaggedParagraphOptions options, final PdfTableWork work, final String documentIdentity)
            throws IOException {
        final var members = new ArrayList<PdfPageText.Glyph>();
        final Set<PdfPageText.Glyph> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        long units = 0;
        for (final var reference : plan.content()) {
            work.step();
            if (!text.markedDeclared(reference.id())) {
                return null;
            }
            for (final var glyph : text.marked(reference.id())) {
                work.step();
                if (!allowed.contains(glyph) || !unique.add(glyph) || geometry.textRotation(glyph.direction()) != 0) {
                    return null;
                }
                units += glyph.text().length();
                if (units > options.maxParagraphCharacters()) {
                    throw limit("Source paragraph exceeds maxParagraphCharacters");
                }
                for (int index = 0; index < glyph.text().length();) {
                    work.step();
                    final int point = glyph.text().codePointAt(index);
                    final byte direction = Character.getDirectionality(point);
                    if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                            || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                            || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
                            || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE
                            || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE) {
                        return null;
                    }
                    index += Character.charCount(point);
                }
                members.add(glyph);
            }
        }
        final var originalLines = PdfGlyphLines.lines(members);
        if (options.inlineScripts() == PdfTaggedParagraphOptions.InlineScripts.NEARBY_SMALL_GLYPHS) {
            long originalUnits = Math.max(0, originalLines.size() - 1L);
            for (final var line : originalLines) {
                work.step();
                originalUnits += line.text().length();
            }
            if (originalUnits > options.maxParagraphCharacters()) {
                throw limit("Source paragraph exceeds maxParagraphCharacters before inline grouping");
            }
        }
        final var inline = options.inlineScripts() == PdfTaggedParagraphOptions.InlineScripts.NEARBY_SMALL_GLYPHS
                                   ? PdfParagraphInlineScripts.lines(members, work)
                                   : new PdfParagraphInlineScripts.Result(originalLines, 0);
        final var lines = inline.lines();
        if (lines.isEmpty()) {
            return null;
        }
        float intersectionLeft = Float.NEGATIVE_INFINITY;
        float intersectionRight = Float.POSITIVE_INFINITY;
        float left = Float.POSITIVE_INFINITY;
        float top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float bottom = Float.NEGATIVE_INFINITY;
        float baseline = Float.NEGATIVE_INFINITY;
        long joinedUnits = lines.size() - 1L;
        for (final var line : lines) {
            work.step();
            if (line.box().bottom() - baseline <= 2) {
                return null;
            }
            baseline = line.box().bottom();
            intersectionLeft = Math.max(intersectionLeft, line.box().left());
            intersectionRight = Math.min(intersectionRight, line.box().right());
            left = Math.min(left, line.box().left());
            top = Math.min(top, line.box().top());
            right = Math.max(right, line.box().right());
            bottom = Math.max(bottom, line.box().bottom());
            joinedUnits += line.text().length();
        }
        if (joinedUnits > options.maxParagraphCharacters()) {
            throw limit("Source paragraph exceeds maxParagraphCharacters");
        }
        if (intersectionLeft >= intersectionRight || left < 0 || top < 0 || right > geometry.width()
                || bottom > geometry.height() || !Float.isFinite(left) || !Float.isFinite(top) || !Float.isFinite(right)
                || !Float.isFinite(bottom)) {
            return null;
        }
        final String original = String.join(" ", lines.stream().map(PdfLayoutItem::text).toList());
        final var retainedHyphens = options.wordWrap() == PdfTaggedParagraphOptions.WordWrap.SOURCE_LINE_WRAPS
                                            ? PdfSourceHyphenWraps.boundaries(members, lines, work)
                                            : Set.<Integer>of();
        final var joined = options.wordWrap() == PdfTaggedParagraphOptions.WordWrap.PRESERVE
                                   ? new PdfSourceDiscretionaryText.Result(original, 0, 0)
                                   : PdfSourceDiscretionaryText.join(
                                             plan.softHyphens(), text, lines, original, work, retainedHyphens);
        final String value = joined.text();
        final var metadata = new LinkedHashMap<String, Object>(Map.of("pdf_text_layout", "source_tagged_paragraph_v1",
                "pdf_source_paragraph_ordinal", plan.ordinal(), "pdf_source_word_wrap", switch (options.wordWrap()) {
                    case PRESERVE -> "preserve_v1";
                    case SOURCE_SOFT_HYPHENS -> "source_soft_hyphens_v1";
                    case SOURCE_LINE_WRAPS -> "source_line_wraps_v1";
                }));
        if (options.wordWrap() == PdfTaggedParagraphOptions.WordWrap.SOURCE_LINE_WRAPS) {
            metadata.put("pdf_source_joined_soft_hyphens", joined.joins());
            metadata.put("pdf_source_retained_hyphen_wraps", joined.retainedHyphenWraps());
        }
        if (options.inlineScripts() == PdfTaggedParagraphOptions.InlineScripts.NEARBY_SMALL_GLYPHS) {
            metadata.put("pdf_source_inline_scripts", "nearby_small_glyphs_v1");
            metadata.put("pdf_source_inline_script_glyphs", inline.assignedGlyphs());
        }
        if (plan.fragments() > 1) {
            if (documentIdentity != null) {
                metadata.put("pdf_source_paragraph_id", documentIdentity + ":" + plan.ordinal());
            }
            metadata.put("pdf_source_paragraph_fragment", plan.fragment());
            metadata.put("pdf_source_paragraph_fragments", plan.fragments());
        }
        final var item = new PdfLayoutItem(ElementType.UncategorizedText, value,
                new PdfLayoutItem.Bounds(left, top, right, bottom), Map.copyOf(metadata));
        return new Prepared(item, List.copyOf(members));
    }

    /**
     * Creates a content-free typed source-paragraph limit failure.
     * @param message stable policy diagnostic
     * @return typed layout failure without source text
     */
    private static PartitionException limit(final String message) {
        return new PartitionException(PartitionException.Code.LAYOUT_LIMIT, message);
    }
}
