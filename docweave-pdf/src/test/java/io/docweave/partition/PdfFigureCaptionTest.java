package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Owned source-geometry controls for explicit native label/image caption classification. */
class PdfFigureCaptionTest {
    /** Creates isolated JVM-only caption controls. */
    PdfFigureCaptionTest() {
    }

    /**
     * Either-edge selection retains fixed thresholds, source characters and whole ambiguity fallback.
     * @throws Exception if owned geometry/cue checks fail
     */
    @Test
    void edgeAlignedNumberedTitlesPreserveOldPoliciesAndRejectAmbiguousImages() throws Exception {
        final var detector =
                PdfFigureCaptionDetection.select(PdfOptions.FigureCaptions.IMAGE_EDGE_ALIGNED_NUMBERED_TITLES);
        final var image = item(ElementType.Image, "", 40, 40, 140, 90, Map.of());
        for (final String text : List.of("Fig. 2 . Results", "Fig.\u00a02\u00a0.\u2002Results",
                     "Figure 2 : lower-case title", "Figure 2 Results")) {
            final var cue = item(ElementType.UncategorizedText, text, 80, 100, 140, 110, Map.of());
            final var input = List.of(image, cue);
            final var selected = detector.apply(input, new PdfTableWork()).get(1);
            assertEquals(ElementType.FigureCaption, selected.type());
            assertEquals(text, selected.text());
            assertEquals(cue.box(), selected.box());
            assertEquals("image_edge_aligned_numbered_title_v1", selected.metadata().get("pdf_figure_caption_policy"));
            for (final var old : List.of(PdfOptions.FigureCaptions.IMAGE_ADJACENT_LABELS,
                         PdfOptions.FigureCaptions.IMAGE_ADJACENT_HORIZONTAL_LABELS,
                         PdfOptions.FigureCaptions.IMAGE_ADJACENT_NUMBERED_TITLES)) {
                assertEquals(input, PdfFigureCaptionDetection.select(old).apply(input, new PdfTableWork()));
            }
        }
        for (final String text : List.of("Figure 2 shows results", "Fig. 2 \n. Results", "Fig. 2 \u2028. Results",
                     "Fig. 2\u200b. Results", "Fig. 2 . ", "Figure 1000 . Results")) {
            final var input = List.of(image, item(ElementType.UncategorizedText, text, 80, 100, 140, 110, Map.of()));
            assertEquals(input, detector.apply(input, new PdfTableWork()));
        }
        final var cue = item(ElementType.UncategorizedText, "Fig. 2 . Results", 80, 100, 140, 110, Map.of());
        for (final var input : List.of(List.of(image, item(ElementType.Image, "", 80, 40, 180, 90, Map.of()), cue),
                     List.of(image, cue,
                             item(ElementType.UncategorizedText, "Fig. 3 . Results", 40, 100, 100, 110, Map.of())),
                     List.of(image, item(ElementType.UncategorizedText, cue.text(), 60, 100, 120, 110, Map.of())),
                     List.of(image, item(ElementType.UncategorizedText, cue.text(), 80, 114.01f, 140, 125, Map.of())),
                     List.of(image,
                             item(ElementType.UncategorizedText, cue.text(), 80, 100, 152.01f, 110, Map.of())))) {
            assertEquals(input, detector.apply(input, new PdfTableWork()));
        }
        final var boundary = item(ElementType.UncategorizedText, cue.text(), 80, 114, 152, 125, Map.of());
        assertEquals(
                ElementType.FigureCaption, detector.apply(List.of(image, boundary), new PdfTableWork()).get(1).type());
    }

    /**
     * Bare numbered titles require capitalization while ordinary lowercase references retain their source type.
     * @throws Exception if bounded owned cue classification fails
     */
    @Test
    void numberedTitlesSupportBareAndColonLabelsWithoutWeakeningExistingPolicies() throws Exception {
        final var detector = PdfFigureCaptionDetection.select(PdfOptions.FigureCaptions.IMAGE_ADJACENT_NUMBERED_TITLES);
        final var image = item(ElementType.Image, "", 40, 40, 140, 90, Map.of());
        for (final String text : List.of("Figure 12 Results", "Fig\u200212B\u2002Results", "FIGURE\t2\tResults",
                     "Figure 2: lower-case caption", "Fig 1.\u2002lower-case caption")) {
            final var cue = item(ElementType.UncategorizedText, text, 40, 100, 140, 110, Map.of());
            final var result = detector.apply(List.of(image, cue), new PdfTableWork());
            assertEquals(ElementType.FigureCaption, result.get(1).type());
            assertEquals(text, result.get(1).text());
            assertEquals(cue.box(), result.get(1).box());
            assertEquals("image_adjacent_numbered_title_v1", result.get(1).metadata().get("pdf_figure_caption_policy"));
        }
        for (final String text : List.of("Figure 1 shows results", "Fig 2 illustrates results.", "Figure1 Results",
                     "Table 1 Results", "Figure 0 Results", "Figure 1000 Results", "Figure 1 \nResults",
                     "Figure 1 \u2028Results", "Figure 1 \u200bResults")) {
            final var input = List.of(image, item(ElementType.UncategorizedText, text, 40, 100, 140, 110, Map.of()));
            assertEquals(input, detector.apply(input, new PdfTableWork()));
        }
        final var bare =
                List.of(image, item(ElementType.UncategorizedText, "Figure 1 Results", 40, 100, 140, 110, Map.of()));
        assertEquals(bare, apply(bare));
        assertEquals(bare, PdfFigureCaptionDetection.select(PdfOptions.FigureCaptions.IMAGE_ADJACENT_HORIZONTAL_LABELS)
                                   .apply(bare, new PdfTableWork()));
    }

    /**
     * A separate cue recognizes horizontal source separators without normalizing text or widening geometry.
     * @throws Exception if owned Unicode/vertical-boundary classification fails
     */
    @Test
    void explicitHorizontalCueRetainsOriginalUnicodeAndRejectsVerticalOrEmptyBodies() throws Exception {
        final var detector =
                PdfFigureCaptionDetection.select(PdfOptions.FigureCaptions.IMAGE_ADJACENT_HORIZONTAL_LABELS);
        final var image = item(ElementType.Image, "", 40, 40, 140, 90, Map.of());
        for (final String separator :
                List.of(" ", "\t", "\u00a0", "\u1680", "\u2002", "\u2003", "\u202f", "\u205f", "\u3000")) {
            final String text = "Fig" + separator + "2." + separator + "Caption";
            final var cue = item(ElementType.UncategorizedText, text, 40, 100, 140, 110, Map.of());
            final var input = List.of(image, cue);
            final var result = detector.apply(input, new PdfTableWork());
            assertEquals(ElementType.FigureCaption, result.get(1).type());
            assertEquals(text, result.get(1).text());
            assertEquals(cue.box(), result.get(1).box());
            assertEquals(
                    "image_adjacent_horizontal_label_v1", result.get(1).metadata().get("pdf_figure_caption_policy"));
            if (!separator.equals(" ") && !separator.equals("\t")) {
                assertEquals(input, apply(input));
            }
        }
        for (final String text : List.of("Fig 2.\nCaption", "Fig 2.\rCaption", "Fig 2.\u2028Caption",
                     "Fig 2.\u2029Caption", "Fig 2.\u200bCaption", "Fig 2.\u2002\u00a0", "Fig2. Caption")) {
            final var input = List.of(image, item(ElementType.UncategorizedText, text, 40, 100, 140, 110, Map.of()));
            assertEquals(input, detector.apply(input, new PdfTableWork()));
        }
    }

    /**
     * Real native and tagged factories retain original text/geometry/images while explicitly changing one type.
     * @throws Exception if owned PDF creation or partitioning fails
     */
    @Test
    void explicitStrategyClassifiesOnlyTheAuthoredCaptionAndPreservesSourceUnits() throws Exception {
        final var source = fixture();
        for (final boolean tagged : List.of(false, true)) {
            final var defaults = PdfOptions.defaults();
            final var selected = defaults.withFigureCaptions(PdfOptions.FigureCaptions.IMAGE_ADJACENT_LABELS);
            final var before = extract(source, defaults, tagged);
            final var after = extract(source, selected, tagged);
            assertEquals(before.elements().size(), after.elements().size());
            assertEquals(0,
                    before.elements().stream().filter(element -> element.type() == ElementType.FigureCaption).count());
            assertEquals(1,
                    after.elements().stream().filter(element -> element.type() == ElementType.FigureCaption).count());
            for (int index = 0; index < before.elements().size(); index++) {
                final var original = before.elements().get(index);
                final var candidate = after.elements().get(index);
                assertEquals(original.text(), candidate.text());
                if (candidate.type() == ElementType.FigureCaption) {
                    assertEquals("Fig 1. Caption title.", candidate.text());
                    assertEquals(original.metadata().get("coordinates"), candidate.metadata().get("coordinates"));
                    assertEquals("image_adjacent_label_v1", candidate.metadata().get("pdf_figure_caption_policy"));
                    final var image = after.elements()
                                              .stream()
                                              .filter(element -> element.type() == ElementType.Image)
                                              .findFirst()
                                              .orElseThrow();
                    final var points = (List<?>) ((Map<?, ?>) image.metadata().get("coordinates")).get("points");
                    final var first = (List<?>) points.getFirst();
                    final var last = (List<?>) points.get(2);
                    assertEquals(List.of(first.get(0), first.get(1), last.get(0), last.get(1)),
                            candidate.metadata().get("pdf_figure_caption_image_bounds"));
                } else {
                    assertEquals(original, candidate);
                }
            }
        }
    }

    /**
     * Ambiguous images/cues, ordinary references, table labels, rotations and unsupported geometry retain text.
     * @throws Exception if bounded native classification fails
     */
    @Test
    void ambiguousAndUnsupportedCandidatesRemainUnchanged() throws Exception {
        final var image = item(ElementType.Image, "", 40, 40, 140, 90, Map.of());
        final var cue = item(ElementType.UncategorizedText, "Figure 2. Caption", 40, 100, 140, 110, Map.of());
        assertEquals(ElementType.FigureCaption, apply(List.of(image, cue)).get(1).type());
        for (final String text : List.of("Figure 2 shows results", "Table 2. Results", "Fig 0. Results",
                     "Fig 1000. Results", "Fig2. Results")) {
            final var input = List.of(image, item(ElementType.UncategorizedText, text, 40, 100, 140, 110, Map.of()));
            assertEquals(input, apply(input));
        }
        for (final var rejected : List.of(item(ElementType.UncategorizedText, "Fig 2. Rotated", 40, 100, 140, 110,
                                                  Map.of("pdf_text_rotation", 90)),
                     item(ElementType.UncategorizedText, "Fig 2. Far", 40, 114.1f, 140, 124, Map.of()),
                     item(ElementType.UncategorizedText, "Fig 2. Above", 40, 80, 140, 89, Map.of()),
                     item(ElementType.UncategorizedText, "Fig 2. Shifted", 53, 100, 153, 110, Map.of()),
                     item(ElementType.UncategorizedText,
                             "Fig 2. "
                                     + "x".repeat(1024),
                             40, 100, 140, 110, Map.of()))) {
            final var input = List.of(image, rejected);
            assertEquals(input, apply(input));
        }
        final var secondImage = item(ElementType.Image, "", 41, 40, 141, 90, Map.of());
        final var ambiguousImages = List.of(image, secondImage, cue);
        assertEquals(ambiguousImages, apply(ambiguousImages));
        final var secondCue = item(ElementType.UncategorizedText, "Fig 3. Another", 40, 101, 140, 111, Map.of());
        final var ambiguousCues = List.of(image, cue, secondCue);
        assertEquals(ambiguousCues, apply(ambiguousCues));
        final var logo = item(ElementType.Image, "", 40, 80, 60, 90, Map.of());
        final var smallImage = List.of(logo, cue);
        assertEquals(smallImage, apply(smallImage));
        assertEquals(List.of(cue), apply(List.of(cue)));
    }

    /**
     * Fixed page/cue caps retain the entire page, interruption is preserved and immutable copies retain policy.
     * @throws Exception if owned policy/cancellation controls fail
     */
    @Test
    void capsCancellationAndLegacyOptionsRemainExplicit() throws Exception {
        final var image = item(ElementType.Image, "", 40, 40, 140, 90, Map.of());
        final var cue = item(ElementType.UncategorizedText, "Fig 1. Caption", 40, 100, 140, 110, Map.of());
        final var large = new ArrayList<>(java.util.Collections.nCopies(4096, image));
        large.add(cue);
        assertSame(large, apply(large));
        final var manyImages = new ArrayList<>(java.util.Collections.nCopies(129, image));
        manyImages.add(cue);
        assertSame(manyImages, apply(manyImages));
        final var manyCues = new ArrayList<>(java.util.Collections.nCopies(257, cue));
        manyCues.add(image);
        assertSame(manyCues, apply(manyCues));
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> apply(List.of(image, cue)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        final var d = PdfOptions.defaults()
                              .withFigureCaptions(PdfOptions.FigureCaptions.IMAGE_ADJACENT_LABELS)
                              .withTableDetection(PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT)
                              .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS)
                              .withTableCellCoordinates(true)
                              .withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER)
                              .withUnicodeNormalization(PdfOptions.UnicodeNormalization.PRESENTATION_FORMS)
                              .withTextRendering(PdfOptions.TextRendering.NONZERO_ALPHA_PAINTED_MODES);
        assertEquals(PdfOptions.FigureCaptions.IMAGE_ADJACENT_LABELS, d.figureCaptions());
        final var legacy = new PdfOptions(d.maxPages(), d.maxGlyphsPerPage(), d.maxOperatorsPerPage(),
                d.maxImagePixels(), d.maxImageBytes(), d.includeImageBytes(), d.tableLimits(), d.tableDetection(),
                d.textLayout(), d.includeTableCellCoordinates(), d.tableContinuation(), d.unicodeNormalization(),
                d.textRendering());
        assertEquals(PdfOptions.FigureCaptions.NONE, legacy.figureCaptions());
        assertThrows(NullPointerException.class, () -> d.withFigureCaptions(null));
    }

    /**
     * Applies the explicit internal strategy to independently authored native geometry.
     * @param items whole page units
     * @return bounded classified units
     * @throws Exception if work/cancellation checks fail
     */
    private static List<PdfLayoutItem> apply(final List<PdfLayoutItem> items) throws Exception {
        return PdfFigureCaptionDetection.select(PdfOptions.FigureCaptions.IMAGE_ADJACENT_LABELS)
                .apply(items, new PdfTableWork());
    }

    /**
     * Creates one owned displayed native unit without parsing or source repair.
     * @param type original category
     * @param text original characters
     * @param left lower horizontal bound
     * @param top lower vertical bound
     * @param right upper horizontal bound
     * @param bottom upper vertical bound
     * @param metadata original source properties
     * @return owned test value
     */
    private static PdfLayoutItem item(final ElementType type, final String text, final float left, final float top,
            final float right, final float bottom, final Map<String, Object> metadata) {
        return new PdfLayoutItem(type, text, new PdfLayoutItem.Bounds(left, top, right, bottom), metadata);
    }

    /**
     * Partitions a complete owned fixture through public native/source-tagged service interfaces.
     * @param source original complete fixture
     * @param options explicit extraction policy
     * @param tagged whether source-tagged table precedence is selected
     * @return real partition result
     * @throws Exception if extraction fails
     */
    private static PartitionResult extract(final DocumentSource source, final PdfOptions options, final boolean tagged)
            throws Exception {
        return DocWeave
                .create(PartitionerRegistry.of(List.of(
                        tagged ? PdfPartitioner.createWithTaggedTables(options) : PdfPartitioner.create(options))))
                .partition(source);
    }

    /**
     * Builds one substantial raster with a separately authored caption and distant ordinary narrative.
     * @return original owned encoded PDF
     * @throws Exception if PDF serialization fails
     */
    private static DocumentSource fixture() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            final var pixels = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, pixels), 40, 600, 100, 50);
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(40, 585);
                content.showText("Fig 1. Caption title.");
                content.newLineAtOffset(0, -60);
                content.showText("Figure 1 shows an ordinary reference.");
                content.endText();
            } finally {
                pixels.flush();
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "owned-native-caption.pdf");
        }
    }
}
