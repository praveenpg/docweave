package io.docweave.partition;

import io.docweave.core.ElementType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/** Conservative explicit native label/image proximity strategy; no semantic, model or font-based caption oracle. */
final class ImageAdjacentPdfFigureCaptions implements PdfFigureCaptionDetection {
    /** Fixed maximum native output units considered on a page; larger pages retain ordinary types whole. */
    private static final int MAX_ITEMS = 4096;
    /** Fixed image/cue caps keep prospective geometry comparisons bounded before classification. */
    private static final int MAX_IMAGES = 128, MAX_CUES = 256;
    /** Strict ASCII label cue: numbered figure, period, horizontal space and nonblank following text. */
    private static final Pattern LABEL =
            Pattern.compile("^(?:Fig(?:ure)?\\.?|FIG(?:URE)?\\.?)[ \\t]+[1-9][0-9]{0,2}[A-Za-z]?\\.[ \\t]+\\S");
    /** Unicode horizontal separators are recognized without changing source strings or accepting vertical breaks. */
    private static final Pattern HORIZONTAL_LABEL = Pattern.compile(
            "^(?:Fig(?:ure)?\\.?|FIG(?:URE)?\\.?)[\\p{Zs}\\t]+[1-9][0-9]{0,2}[A-Za-z]?\\.[\\p{Zs}\\t]+[^\\p{Z}\\s]");
    /** Bare labels require a capitalized title; explicit period/colon punctuation still supplies a strong cue. */
    private static final Pattern TITLE_LABEL = Pattern.compile(
            "^(?:Fig(?:ure)?\\.?|FIG(?:URE)?\\.?)[\\p{Zs}\\t]+[1-9][0-9]{0,2}[A-Za-z]?(?:[.:][\\p{Zs}\\t]+[^\\p{Z}\\s]|[\\p{Zs}\\t]+[\\p{Lu}\\p{Lt}])");
    /** Explicit punctuation may be separated horizontally from its ordinal; source characters remain unchanged. */
    private static final Pattern EDGE_TITLE_LABEL = Pattern.compile(
            "^(?:Fig(?:ure)?\\.?|FIG(?:URE)?\\.?)[\\p{Zs}\\t]+[1-9][0-9]{0,2}[A-Za-z]?(?:[\\p{Zs}\\t]*[.:][\\p{Zs}\\t]+[^\\p{Z}\\s]|[\\p{Zs}\\t]+[\\p{Lu}\\p{Lt}])");
    /** Historical ASCII cue with unchanged provenance and geometry. */
    static final ImageAdjacentPdfFigureCaptions INSTANCE =
            new ImageAdjacentPdfFigureCaptions(LABEL, "image_adjacent_label_v1");
    /** Separately selected Unicode-horizontal cue with the same bounded geometric subset. */
    static final ImageAdjacentPdfFigureCaptions HORIZONTAL_INSTANCE =
            new ImageAdjacentPdfFigureCaptions(HORIZONTAL_LABEL, "image_adjacent_horizontal_label_v1");
    /** Separately selected numbered-title cue; original punctuation-based policies are retained. */
    static final ImageAdjacentPdfFigureCaptions TITLE_INSTANCE =
            new ImageAdjacentPdfFigureCaptions(TITLE_LABEL, "image_adjacent_numbered_title_v1");
    /** Separate right-or-left-edge strategy; original strategies retain their exact left-edge predicate. */
    static final ImageAdjacentPdfFigureCaptions EDGE_TITLE_INSTANCE = new ImageAdjacentPdfFigureCaptions(
            EDGE_TITLE_LABEL, "image_edge_aligned_numbered_title_v1", ImageAdjacentPdfFigureCaptions::edgeAdjacent);
    /** Immutable explicit label strategy; matchers remain call-confined. */
    private final Pattern label;
    /** Complete selected cue-policy identity. */
    private final String provenance;
    /** Immutable selected source geometry; matching state remains confined to each call. */
    private final BiPredicate<PdfLayoutItem.Bounds, PdfLayoutItem.Bounds> adjacency;

    /**
     * Captures an explicit immutable cue without changing the shared source geometry rules.
     * @param label compiled source-character label predicate
     * @param provenance selected policy identity
     */
    private ImageAdjacentPdfFigureCaptions(final Pattern label, final String provenance) {
        this(label, provenance, ImageAdjacentPdfFigureCaptions::adjacent);
    }

    /**
     * Captures immutable text and geometry strategies without changing output ownership or page budgets.
     * @param label source-character cue predicate
     * @param provenance complete explicit strategy identity
     * @param adjacency fixed source-bound relation
     */
    private ImageAdjacentPdfFigureCaptions(final Pattern label, final String provenance,
            final BiPredicate<PdfLayoutItem.Bounds, PdfLayoutItem.Bounds> adjacency) {
        this.label = label;
        this.provenance = provenance;
        this.adjacency = adjacency;
    }

    /**
     * Requires one uniquely matched image per cue and one cue per matched image; ambiguity falls back whole.
     * Eligible images are at least36x18 points. Cues contain at most1024 UTF-16 units and no rotation metadata.
     * Caption tops must follow image bottoms by0..24 points, left edges agree within12 points,
     * and horizontal overlap covers at least half the narrower rectangle. No threshold is confidence.
     * @param items complete native page units, including original image occurrences
     * @param work shared source/native geometry budget
     * @return same ordered units with only unambiguous cue type/provenance changed
     * @throws IOException if cancellation or geometry work fails
     */
    @Override
    public List<PdfLayoutItem> apply(final List<PdfLayoutItem> items, final PdfTableWork work) throws IOException {
        work.step();
        if (items.size() > MAX_ITEMS) {
            return items;
        }
        final var images = new ArrayList<Integer>();
        final var cues = new ArrayList<Integer>();
        for (int index = 0; index < items.size(); index++) {
            work.step();
            final var item = items.get(index);
            if (item.type() == ElementType.Image && width(item.box()) >= 36 && height(item.box()) >= 18) {
                images.add(index);
            } else if (item.type() == ElementType.UncategorizedText && item.text().length() <= 1024
                       && !item.metadata().containsKey("pdf_text_rotation") && label.matcher(item.text()).find()) {
                cues.add(index);
            }
        }
        if (images.isEmpty() || cues.isEmpty() || images.size() > MAX_IMAGES || cues.size() > MAX_CUES) {
            return items;
        }
        final var matches = new int[cues.size()];
        java.util.Arrays.fill(matches, -1);
        final var imageUses = new int[images.size()];
        for (int cue = 0; cue < cues.size(); cue++) {
            int count = 0;
            for (int image = 0; image < images.size(); image++) {
                work.step();
                if (adjacency.test(items.get(cues.get(cue)).box(), items.get(images.get(image)).box())) {
                    matches[cue] = image;
                    count++;
                    imageUses[image]++;
                }
            }
            if (count != 1) {
                matches[cue] = -1;
            }
        }
        final var result = new ArrayList<>(items);
        for (int cue = 0; cue < cues.size(); cue++) {
            final int image = matches[cue];
            if (image >= 0 && imageUses[image] == 1) {
                final int index = cues.get(cue);
                final var original = items.get(index);
                final var box = items.get(images.get(image)).box();
                final var metadata = new LinkedHashMap<>(original.metadata());
                metadata.put("pdf_figure_caption_policy", provenance);
                metadata.put("pdf_figure_caption_image_bounds",
                        List.of((double) box.left(), (double) box.top(), (double) box.right(), (double) box.bottom()));
                result.set(
                        index, new PdfLayoutItem(ElementType.FigureCaption, original.text(), original.box(), metadata));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Evaluates source-point proximity without caption text or reference labels.
     * @param caption whole existing native cue bounds
     * @param image original raster occurrence bounds
     * @return whether both boxes satisfy the fixed geometric subset
     */
    private static boolean adjacent(final PdfLayoutItem.Bounds caption, final PdfLayoutItem.Bounds image) {
        return alignedAdjacent(caption, image, false);
    }

    /**
     * Allows either aligned edge without widening the original tolerance, gap or overlap contract.
     * @param caption whole original native text bounds
     * @param image original raster occurrence bounds
     * @return whether either edge satisfies the fixed geometric relation
     */
    private static boolean edgeAdjacent(final PdfLayoutItem.Bounds caption, final PdfLayoutItem.Bounds image) {
        return alignedAdjacent(caption, image, true);
    }

    /**
     * Applies shared proximity limits with an explicitly selected horizontal edge relation.
     * @param caption whole original caption candidate bounds
     * @param image source raster bounds
     * @param eitherEdge whether right alignment may substitute for left alignment
     * @return bounded positive geometry predicate
     */
    private static boolean alignedAdjacent(
            final PdfLayoutItem.Bounds caption, final PdfLayoutItem.Bounds image, final boolean eitherEdge) {
        final double gap = caption.top() - image.bottom();
        final double overlap = Math.min(caption.right(), image.right()) - Math.max(caption.left(), image.left());
        return width(caption) > 0 && height(caption) > 0 && gap >= 0 && gap <= 24
                && (Math.abs(caption.left() - image.left()) <= 12
                        || eitherEdge && Math.abs(caption.right() - image.right()) <= 12)
                && overlap >= Math.min(width(caption), width(image)) * .5;
    }

    /**
     * Returns horizontal source extent without integer rounding.
     * @param box original displayed bounds
     * @return source-point width
     */
    private static double width(final PdfLayoutItem.Bounds box) {
        return (double) box.right() - box.left();
    }

    /**
     * Returns vertical source extent without integer rounding.
     * @param box original displayed bounds
     * @return source-point height
     */
    private static double height(final PdfLayoutItem.Bounds box) {
        return (double) box.bottom() - box.top();
    }
}
