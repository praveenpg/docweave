package io.docweave.partition;

import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Collects a bounded page of glyphs without retaining PDFBox objects in the result. */
final class PdfPageText extends PDFTextStripper {
    /**
     * Upright text-direction metrics in unscaled crop-relative units, plus source counterclockwise direction.
     * @param text decoded source Unicode under the explicitly selected normalization policy
     * @param x source advance start in the text-direction frame
     * @param y source baseline in the text-direction frame
     * @param width source advance width
     * @param height approximate source glyph height
     * @param space font-reported width of a space
     * @param direction quarter-turn source text direction, in degrees
     */
    record Glyph(String text, float x, float y, float width, float height, float space, int direction) {}

    /** Optional source-tag identity tracking; absent on every historical factory. */
    private final PdfMarkedGlyphs marked;

    /** Active extraction budgets. */
    private final PdfOptions options;
    /** Remaining normalized UTF-16 units for the explicit presentation policy on this page. */
    private int remainingUnicodeUnits = PdfPresentationForms.MAX_UNITS_PER_PAGE;
    /** Document-local retained glyphs. */
    private final List<Glyph> glyphs = new ArrayList<>();
    /** Explicitly selected arbitrary-angle positions, sharing the same source-position ceiling. */
    private final List<PdfAngledText.Position> angled = new ArrayList<>();

    /**
     * Initializes sorted, duplicate-suppressed text traversal.
     * @param options immutable PDF budgets
     * @throws IOException if the PDFBox stripper cannot initialize
     */
    PdfPageText(final PdfOptions options) throws IOException {
        this(options, false);
    }

    /**
     * Optionally tracks page-stream marked content without changing native sorting/suppression.
     * @param options source text budgets
     * @param tagged whether source table ownership is explicitly requested
     * @throws IOException if the underlying stripper cannot initialize
     */
    PdfPageText(final PdfOptions options, final boolean tagged) throws IOException {
        this.options = options;
        marked = tagged ? new PdfMarkedGlyphs() : null;
        setSortByPosition(true);
    }

    /**
     * Observes source marker scope before retaining native glyph identities.
     * @param operator original content operator
     * @param operands original source operands
     * @throws IOException if PDF processing or property-resource loading fails
     */
    @Override
    protected void processOperator(final org.apache.pdfbox.contentstream.operator.Operator operator,
            final List<org.apache.pdfbox.cos.COSBase> operands) throws IOException {
        if (marked != null) {
            marked.operator(operator, operands, getResources());
        }
        super.processOperator(operator, operands);
    }

    /**
     * Records the expected existing PDFBox content-stream replacement before its native text callbacks.
     * This does not use structure-element alternate text or alter the underlying stripper behavior.
     * @param tag original marked-content tag
     * @param properties original resolved property dictionary, possibly absent
     */
    @Override
    public void beginMarkedContentSequence(
            final org.apache.pdfbox.cos.COSName tag, final org.apache.pdfbox.cos.COSDictionary properties) {
        if (marked != null) {
            marked.beginText(properties);
        }
        super.beginMarkedContentSequence(tag, properties);
    }

    /** Preserves the underlying stripper's replacement lifecycle and validates source scope nesting. */
    @Override
    public void endMarkedContentSequence() {
        if (marked != null) {
            marked.endText();
        }
        super.endMarkedContentSequence();
    }

    /**
     * Separates form MCID scope from original page streams while preserving ordinary source text.
     * @param form original form XObject
     * @throws IOException if form processing fails
     */
    @Override
    public void showForm(final org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) throws IOException {
        if (marked != null) {
            marked.enterForm();
        }
        try {
            super.showForm(form);
        } finally {
            if (marked != null) {
                marked.leaveForm();
            }
        }
    }

    /**
     * Returns original retained native positions for an unambiguous page-stream identifier.
     * @param id source MCID
     * @return original glyph identities or an empty list
     */
    List<Glyph> marked(final int id) {
        return marked == null ? List.of() : marked.get(id);
    }

    /**
     * Distinguishes valid empty marked content from missing or ambiguous source identifiers.
     * @param id source MCID
     * @return whether the page contains one valid unambiguous declaration
     */
    boolean markedDeclared(final int id) {
        return marked != null && marked.declared(id);
    }

    /**
     * Checks input position count before PDFBox retains glyphs for layout.
     * @param position source glyph
     */
    @Override
    protected void processTextPosition(final TextPosition position) {
        // PDFBox's callback has no checked exception; the page limit is enforced through a private bridge.
        if (Thread.currentThread().isInterrupted()) {
            throw new PdfTraversalFailure(true);
        }
        if (++seen > options.maxGlyphsPerPage()) {
            throw new PdfTraversalFailure(false);
        }
        final var mode = getGraphicsState().getTextState().getRenderingMode();
        if (options.textRendering() != PdfOptions.TextRendering.ALL && !mode.isFill() && !mode.isStroke()) {
            // Filter before PDFBox duplicate suppression: an invisible copy must not hide a painted one.
            // Charge every source position first so hidden layers cannot bypass the page budget.
            return;
        }
        if (options.textRendering().nonzeroAlpha()) {
            final var state = getGraphicsState();
            final boolean filled = mode.isFill() && state.getNonStrokeAlphaConstant() > 0;
            final boolean stroked = mode.isStroke() && state.getAlphaConstant() > 0;
            if (!filled && !stroked) {
                // Filter before duplicate suppression and marked-content capture; source glyphs are already charged.
                return;
            }
        }
        if (options.textRendering() == PdfOptions.TextRendering.NONZERO_ALPHA_NONEMPTY_CLIP_PAINTED_MODES
                && getGraphicsState().getCurrentClippingPath().isEmpty()) {
            // Charge source positions first; discard before duplicate suppression and marked-content ownership.
            // Nonempty inherited clips deliberately do not establish individual glyph visibility.
            return;
        }
        if (marked != null) {
            marked.capture(position);
        }
        super.processTextPosition(position);
    }

    /** Number of visited glyph positions, including suppressed duplicates. */
    private int seen;

    /**
     * Retains the sorted glyphs delivered by PDFBox; no emitted PDFBox text buffer is accumulated.
     * @param text PDFBox's combined string, unused because geometry supplies cell ownership
     * @param positions source positions
     * @throws IOException if cancelled or a non-quarter-turn baseline is encountered
     */
    @Override
    protected void writeString(final String text, final List<TextPosition> positions) throws IOException {
        ElementCollector.checkInterrupted();
        for (final var position : positions) {
            final var matrix = position.getTextMatrix();
            final double determinant =
                    (double) matrix.getScaleX() * matrix.getScaleY() - (double) matrix.getShearX() * matrix.getShearY();
            if (!Double.isFinite(determinant) || determinant <= 0) {
                throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                        "Native PDF text does not support mirrored or degenerate transforms");
            }
            final double angle = Math.toDegrees(Math.atan2(matrix.getShearY(), matrix.getScaleX()));
            final int direction = Math.floorMod((int) Math.round(angle / 90) * 90, 360);
            if (!Double.isFinite(angle) || Math.abs(Math.IEEEremainder(angle - direction, 360)) > 0.01
                    || (int) position.getDir() != direction) {
                if (!options.textLayout().oriented()) {
                    throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                            "Native PDF text requires a quarter-turn baseline orientation");
                }
                final double x = matrix.getTranslateX();
                final double y = matrix.getTranslateY();
                final double width = Math.hypot(position.getEndX() - x, position.getEndY() - y);
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width)
                        || !Float.isFinite(position.getHeightDir()) || width <= 0 || position.getHeightDir() <= 0) {
                    throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                            "Arbitrary-angle native PDF text requires finite positive glyph metrics");
                }
                angled.add(new PdfAngledText.Position(nativeUnicode(position.getUnicode()), x, y, width,
                        position.getHeightDir(), position.getWidthOfSpace(), angle));
                continue;
            }
            final var glyph =
                    new Glyph(nativeUnicode(position.getUnicode()), position.getXDirAdj(), position.getYDirAdj(),
                            position.getWidthDirAdj(), position.getHeightDir(), position.getWidthOfSpace(), direction);
            glyphs.add(glyph);
            if (marked != null) {
                marked.retain(position, glyph);
            }
        }
    }

    /**
     * Applies only the explicitly selected native Unicode policy and charges retained normalized units.
     * @param value PDFBox-decoded glyph text
     * @return source text or presentation-expanded text with the same source glyph geometry
     * @throws IOException if cancellation or normalized-output accounting fails
     */
    private String nativeUnicode(final String value) throws IOException {
        if (options.unicodeNormalization() == PdfOptions.UnicodeNormalization.RAW) {
            return value;
        }
        final var normalized = PdfPresentationForms.expand(value, remainingUnicodeUnits);
        remainingUnicodeUnits -= normalized.length();
        return normalized;
    }

    /**
     * Groups explicitly selected arbitrary-angle source positions in their projected baseline frames.
     * @param geometry displayed page crop/rotation frame
     * @return ordinary lines with displayed enclosing geometry
     * @throws IOException if interrupted or projected geometry is unsupported
     */
    List<PdfLayoutItem> angledLines(final PdfPageGeometry geometry) throws IOException {
        return PdfAngledText.lines(angled, geometry);
    }

    /**
     * Returns document-confined metrics for this page.
     * @return retained glyph list
     */
    List<Glyph> glyphs() {
        return glyphs;
    }
}
