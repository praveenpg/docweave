package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDTableAttributeObject;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Authored source-tag topology/ownership controls independent of learned table output. */
class PdfTaggedTablesTest {
    /** Creates isolated original source-tag fixtures. */
    PdfTaggedTablesTest() {
    }

    /**
     * Preserves an authored blank row/column without publishing invented cell coordinates.
     * @throws Exception if original owned PDF extraction fails
     */
    @Test
    void logicalEnvelopePreservesBlankAxesAndCopiesConfiguration() throws Exception {
        final var strict = PdfPartitioner.createWithTaggedTables(PdfOptions.defaults());
        final var logical = strict.withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE);
        final var bytes = logicalSource("valid");
        assertTrue(run(strict, bytes).elements().stream().noneMatch(e -> e.type() == ElementType.Table));
        for (final var service : List.of(logical, logical.withPasswords(request -> null),
                     logical.withTaggedParagraphs(PdfTaggedParagraphOptions.defaults()))) {
            final var result = run(service, bytes);
            final var table =
                    result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
            assertEquals("A B C D", table.text());
            assertEquals("<table><tbody><tr><td>A</td><td></td><td>B</td></tr>"
                                 + "<tr><td></td><td></td><td></td></tr>"
                                 + "<tr><td>C</td><td></td><td>D</td></tr></tbody></table>",
                    table.metadata().get("text_as_html"));
            assertEquals(5, table.metadata().get("pdf_table_tagged_empty_cells"));
            assertFalse(table.metadata().containsKey("pdf_table_cells"));
            assertEquals("source_logical_text_envelope_v1", table.metadata().get("pdf_table_geometry_policy"));
            assertEquals(1, result.elements().stream().filter(e -> e.text().equals("Outside")).count());
            assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
        }
        assertTrue(run(logical.withTaggedTableGeometry(PdfTaggedTableGeometry.SUPPORTED_CELL_AXES), bytes)
                        .elements()
                        .stream()
                        .noneMatch(e -> e.type() == ElementType.Table));
    }

    /**
     * Missing/duplicate ownership, blank tables and unclaimed source ink retain whole native fallback.
     * @throws Exception if owned negative extraction fails
     */
    @Test
    void logicalEnvelopeRejectsAmbiguousOwnershipAndUnsupportedSources() throws Exception {
        final var service = PdfPartitioner.createWithTaggedTables(PdfOptions.defaults())
                                    .withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE);
        for (final String variant : List.of("inside", "missing", "duplicate", "blank", "turned")) {
            final var result = run(service, logicalSource(variant));
            assertTrue(result.elements().stream().noneMatch(e -> e.type() == ElementType.Table), variant);
            assertEquals(1, result.elements().stream().filter(e -> e.text().equals("Outside")).count());
            if (variant.equals("inside")) {
                assertTrue(result.elements().stream().anyMatch(e -> e.text().contains("Unclaimed")));
            }
        }
        for (final String variant : List.of("duplicate-ref", "duplicate-marker", "missing-ref", "invalid-span",
                     "cross-page", "paint-text", "paint-space", "paint-actual", "paint-redeclared")) {
            assertTrue(
                    run(service, source(0, variant)).elements().stream().noneMatch(e -> e.type() == ElementType.Table),
                    variant);
        }
    }

    /**
     * Retains unrelated gap ink exactly once as ordinary text under an explicit source-cell envelope choice.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void logicalCellEnvelopesKeepGapTextSeparateWithoutInventingBlankCellGeometry() throws Exception {
        final var service = PdfPartitioner.createWithTaggedTables(PdfOptions.defaults())
                                    .withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_CELL_TEXT_ENVELOPES);
        final var bytes = logicalSource("inside");
        final var original = run(service.withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE), bytes);
        for (final var selected : List.of(service, service.withPasswords(request -> null),
                     service.withTaggedParagraphs(PdfTaggedParagraphOptions.defaults()))) {
            final var result = run(selected, bytes);
            assertEquals(characterCounts(original.elements()), characterCounts(result.elements()));
            final var table =
                    result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
            assertEquals("A B C D", table.text());
            assertEquals(5, table.metadata().get("pdf_table_tagged_empty_cells"));
            assertEquals("source_logical_cell_text_envelopes_v1", table.metadata().get("pdf_table_geometry_policy"));
            assertFalse(table.metadata().containsKey("pdf_table_cells"));
            assertEquals(1, result.elements()
                                    .stream()
                                    .filter(e -> e.type() != ElementType.Table && e.text().equals("Unclaimed"))
                                    .count());
            assertEquals(1, result.elements().stream().filter(e -> e.text().equals("Outside")).count());
            assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
        }
        assertTrue(run(
                service.withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE), logicalSource("inside"))
                        .elements()
                        .stream()
                        .noneMatch(e -> e.type() == ElementType.Table));
    }

    /**
     * Cell-interior ink, unsupported ownership and incompatible coordinate requests retain whole rejection.
     * @throws Exception if owned negative extraction fails
     */
    @Test
    void logicalCellEnvelopesRejectCellInteriorInkAndUnsupportedSourceOwnership() throws Exception {
        final var service = PdfPartitioner.createWithTaggedTables(PdfOptions.defaults())
                                    .withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_CELL_TEXT_ENVELOPES);
        for (final String variant : List.of("inside-cell", "missing", "duplicate", "blank", "turned")) {
            final var result = run(service, logicalSource(variant));
            assertTrue(result.elements().stream().noneMatch(e -> e.type() == ElementType.Table), variant);
            assertEquals(1, result.elements().stream().filter(e -> e.text().equals("Outside")).count());
        }
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner.createWithTaggedTables(PdfOptions.defaults().withTableCellCoordinates(true))
                                .withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_CELL_TEXT_ENVELOPES));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner.create(PdfOptions.defaults())
                                .withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_CELL_TEXT_ENVELOPES));
    }

    /**
     * Checks text preservation independently of layout-inserted ASCII whitespace and element grouping.
     * @param elements complete original or explicitly projected owned result
     * @return exact counts of other Unicode code points, without case or punctuation normalization
     */
    private static Map<Integer, Long> characterCounts(final List<Element> elements) {
        return elements.stream()
                .flatMapToInt(element -> element.text().codePoints())
                .filter(point -> point != 32 && point != 9 && point != 10 && point != 13 && point != 12)
                .boxed()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    /** Rejects invalid configuration before any source access or caller provider execution. */
    @Test
    void logicalGeometryRequiresExplicitTagsAndDisabledCellCoordinates() {
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner.create(PdfOptions.defaults())
                                .withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner.createWithTaggedTables(PdfOptions.defaults().withTableCellCoordinates(true))
                                .withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE));
        assertThrows(NullPointerException.class,
                () -> PdfPartitioner.createWithTaggedTables(PdfOptions.defaults()).withTaggedTableGeometry(null));
    }

    /**
     * Logical envelopes retain original source-table budgets and interruption behavior.
     * @throws Exception if owned resource controls fail
     */
    @Test
    void logicalEnvelopePreservesResourceLimitsAndInterruption() throws Exception {
        final var defaults = PdfOptions.defaults();
        final var limits =
                new PdfOptions(defaults.maxPages(), defaults.maxGlyphsPerPage(), defaults.maxOperatorsPerPage(),
                        defaults.maxImagePixels(), defaults.maxImageBytes(), false, new TableLimits(2, 10, 100, 100));
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> run(PdfPartitioner.createWithTaggedTables(limits).withTaggedTableGeometry(
                                               PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE),
                                        logicalSource("valid")))
                        .code());
        final var bytes = logicalSource("valid");
        final var logical = PdfPartitioner.createWithTaggedTables(defaults).withTaggedTableGeometry(
                PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE);
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> run(logical, bytes));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * Authors a logical three-by-three grid whose middle row and column contain declared empty source references.
     * @param variant bounded independent source mutation
     * @return original owned PDF with outside text and raster placement
     * @throws Exception if source authoring fails
     */
    private static byte[] logicalSource(final String variant) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            if (variant.equals("turned")) {
                page.setRotation(90);
            }
            document.addPage(page);
            final var root = new PDStructureTreeRoot();
            document.getDocumentCatalog().setStructureTreeRoot(root);
            final var table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);
            try (final var stream = new PDPageContentStream(document, page)) {
                for (int row = 0; row < 3; row++) {
                    final var tr = new PDStructureElement("TR", table);
                    table.appendKid(tr);
                    for (int column = 0; column < 3; column++) {
                        final int id = row * 3 + column;
                        cell(tr, "TD", variant.equals("duplicate") && id == 8 ? 0 : id, 1, 1);
                        if (!(variant.equals("missing") && id == 4)) {
                            stream.beginMarkedContent(COSName.getPDFName("Span"), id);
                            if (row != 1 && column != 1 && !variant.equals("blank")) {
                                draw(stream, String.valueOf((char) ('A' + row + column / 2)), 30 + column * 50,
                                        160 - row * 30);
                            }
                            stream.endMarkedContent();
                        }
                    }
                }
                if (variant.equals("inside")) {
                    draw(stream, "Unclaimed", 65, 130);
                } else if (variant.equals("inside-cell")) {
                    draw(stream, "Unclaimed", 30, 160);
                }
                draw(stream, "Outside", 20, 30);
                stream.drawImage(
                        LosslessFactory.createFromImage(document, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)),
                        170, 20, 8, 8);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Declared glyph-free table/row references retain exact cell ownership and source geometry.
     * @throws Exception if owned extraction fails
     */
    @Test
    void declaredNonTextReferencesPreserveTableOwnership() throws Exception {
        final var service = PdfPartitioner.createWithTaggedTables(PdfOptions.defaults().withTableCellCoordinates(true));
        for (final int rotation : new int[] {0, 90, 180, 270}) {
            final var original = run(service, source(rotation, "valid"))
                                         .elements()
                                         .stream()
                                         .filter(e -> e.type() == ElementType.Table)
                                         .findFirst()
                                         .orElseThrow();
            for (final String variant : List.of("table-paint", "row-paint", "mcr-paint")) {
                final var result = run(service, source(rotation, variant));
                final var table =
                        result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
                assertEquals(original.text(), table.text());
                assertEquals(original.metadata().get("pdf_table_cells"), table.metadata().get("pdf_table_cells"));
                assertEquals(original.metadata().get("coordinates"), table.metadata().get("coordinates"));
                assertEquals(1, table.metadata().get("pdf_table_tagged_nontext_references"));
                assertEquals(1, result.elements().stream().filter(e -> e.text().equals("Outside")).count());
                assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
            }
        }
    }

    /**
     * Unknown, ambiguous, text-bearing, cross-page and alternate references never become decoration evidence.
     * @throws Exception if owned fallback controls fail
     */
    @Test
    void unsupportedAuxiliaryContentKeepsWholeNativeFallback() throws Exception {
        final var service = PdfPartitioner.createWithTaggedTables(PdfOptions.defaults());
        for (final String variant : List.of("paint-missing", "paint-duplicate", "paint-ref-cell", "paint-text",
                     "paint-space", "paint-cross-page", "paint-actual", "paint-mcr-actual", "paint-redeclared",
                     "paint-mcr-child")) {
            final var result = run(service, source(0, variant));
            assertTrue(result.elements().stream().noneMatch(e -> e.type() == ElementType.Table), variant);
            assertTrue(result.elements().stream().anyMatch(e -> e.text().contains("Wrapped")), variant);
            if (variant.equals("paint-text")) {
                assertTrue(result.elements().stream().anyMatch(e -> e.text().contains("Auxiliary")));
            }
        }
    }

    /**
     * Auxiliary references share bounded structural processing rather than bypassing table budgets.
     * @throws Exception if owned source encoding fails
     */
    @Test
    void auxiliaryReferenceCountRemainsBounded() throws Exception {
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> run(PdfPartitioner.createWithTaggedTables(PdfOptions.defaults()),
                                        source(0, "paint-budget")))
                        .code());
    }

    /**
     * Verifies explicit source spans/header roles, source text/images and declared quarter-turn geometry.
     * @throws Exception if owned PDF encoding or extraction fails
     */
    @Test
    void sourceRolesSpansAndNativeGlyphsSurviveAllDeclaredTurns() throws Exception {
        for (final int rotation : new int[] {0, 90, 180, 270}) {
            for (final boolean coordinates : List.of(false, true)) {
                final var result = run(PdfPartitioner.createWithTaggedTables(
                                               PdfOptions.defaults().withTableCellCoordinates(coordinates)),
                        source(rotation, "valid"));
                final var tables = result.elements().stream().filter(e -> e.type() == ElementType.Table).toList();
                assertEquals(1, tables.size());
                final var table = tables.getFirst();
                assertEquals("Wrapped heading 2025-01- 02 A & <B> Last", table.text());
                assertEquals("tagged_structure_native_glyphs_v1", table.metadata().get("pdf_table_method"));
                assertEquals(1, table.metadata().get("pdf_table_header_rows"));
                assertEquals("source_structure_prefix_v1", table.metadata().get("pdf_table_header_policy"));
                final String html = table.metadata().get("text_as_html").toString();
                assertTrue(html.contains("<th colspan=\"2\">Wrapped heading</th>"));
                assertTrue(html.contains("<td rowspan=\"2\">2025-01- 02</td>"));
                assertTrue(html.contains("A &amp; &lt;B&gt;"));
                assertEquals(1, result.elements().stream().filter(e -> e.text().equals("Outside")).count());
                assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
                assertEquals(coordinates, table.metadata().containsKey("pdf_table_cells"));
                if (coordinates) {
                    final var cells = (java.util.Map<?, ?>) table.metadata().get("pdf_table_cells");
                    assertEquals(3, cells.get("row_count"));
                    assertEquals(2, cells.get("column_count"));
                    assertEquals(4, ((List<?>) cells.get("cells")).size());
                    assertEquals("tagged_text_bounds", cells.get("geometry"));
                }
            }
        }
    }

    /**
     * Keeps ordinary factories unchanged and makes source-date joining an explicit independent choice.
     * @throws Exception if owned PDF encoding or extraction fails
     */
    @Test
    void defaultsAndExplicitDatesRemainSeparate() throws Exception {
        final var bytes = source(0, "valid");
        final var ordinary = run(PdfPartitioner.create(PdfOptions.defaults()), bytes);
        assertTrue(ordinary.elements().stream().noneMatch(e -> e.type() == ElementType.Table));
        final var tagged = run(PdfPartitioner.createWithTaggedTables(
                                       PdfOptions.defaults(), PdfNativeLayoutOptions.CellTextJoin.WRAPPED_ISO_DATE),
                bytes);
        final var table =
                tagged.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        assertEquals("Wrapped heading 2025-01-02 A & <B> Last", table.text());
        assertEquals(1, table.metadata().get("pdf_table_joined_iso_dates"));
    }

    /**
     * Ignores only resolved span-free presentation classes across table, row and cell source roles.
     * @throws Exception if original fixture encoding or public extraction fails
     */
    @Test
    void resolvedPresentationClassesRetainOriginalTextSpansAndCoordinates() throws Exception {
        final var options = PdfOptions.defaults().withTableCellCoordinates(true);
        final var baseline = run(PdfPartitioner.createWithTaggedTables(options), source(0, "valid"));
        final var styled = run(PdfPartitioner.createWithTaggedTables(options), source(0, "presentation-classes"));
        assertEquals(baseline.elements().stream().map(e -> List.of(e.type(), e.text(), e.metadata())).toList(),
                styled.elements().stream().map(e -> List.of(e.type(), e.text(), e.metadata())).toList());
    }

    /**
     * Resolves authored class-only spans and gives direct attributes precedence before checking table dimensions.
     * @throws Exception if original fixture encoding or public extraction fails
     */
    @Test
    void classDefinedCellSpansAndDirectOverridesPreserveOriginalAuthoredGrid() throws Exception {
        final var service = PdfPartitioner.createWithTaggedTables(PdfOptions.defaults().withTableCellCoordinates(true));
        final var original = run(service, source(0, "valid"));
        for (final String variant : List.of("span-class-only", "span-class-override")) {
            final var candidate = run(service, source(0, variant));
            assertEquals(original.elements().stream().map(e -> List.of(e.type(), e.text(), e.metadata())).toList(),
                    candidate.elements().stream().map(e -> List.of(e.type(), e.text(), e.metadata())).toList(),
                    variant);
        }
    }

    /**
     * Keeps declared disjoint caption text in native fallback while retaining source table headers and spans.
     * @throws Exception if owned source encoding or public extraction fails
     */
    @Test
    void declaredTableCaptionsRemainSeparateWithoutRejectingCellStructure() throws Exception {
        for (final String variant : List.of("caption-valid", "caption-wrapped")) {
            for (final int rotation : new int[] {0, 90, 180, 270}) {
                final var service =
                        PdfPartitioner.createWithTaggedTables(PdfOptions.defaults().withTableCellCoordinates(true));
                final var original = run(service, source(rotation, "valid"));
                final var bytes = source(rotation, variant);
                final var nativeResult = run(PdfPartitioner.create(PdfOptions.defaults()), bytes);
                final var result = run(service, bytes);
                assertEquals(characterCounts(nativeResult.elements()), characterCounts(result.elements()));
                final var before = original.elements()
                                           .stream()
                                           .filter(e -> e.type() == ElementType.Table)
                                           .findFirst()
                                           .orElseThrow();
                final var table =
                        result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
                assertEquals(before.text(), table.text());
                assertEquals(before.metadata().get("text_as_html"), table.metadata().get("text_as_html"));
                assertEquals(before.metadata().get("pdf_table_cells"), table.metadata().get("pdf_table_cells"));
                assertEquals(1, table.metadata().get("pdf_table_caption_references"));
                assertEquals(1, result.elements()
                                        .stream()
                                        .filter(e -> e.type() != ElementType.Table && e.text().equals("Caption"))
                                        .count());
                assertFalse(table.text().contains("Caption"));
                assertEquals(1, result.elements().stream().filter(e -> e.text().equals("Outside")).count());
                assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
            }
        }
    }

    /**
     * Rejects unsupported caption ownership/geometry without dropping original source text.
     * @throws Exception if owned source encoding or public extraction fails
     */
    @Test
    void unsupportedOrSharedCaptionsKeepWholeNativeFallbackAndOriginalText() throws Exception {
        for (final String variant :
                List.of("caption-missing", "caption-duplicate", "caption-actual", "caption-empty", "caption-overlap",
                        "caption-cross-page", "caption-row", "caption-redeclared", "caption-shared-table")) {
            final var bytes = source(0, variant);
            final var original = run(PdfPartitioner.create(PdfOptions.defaults()), bytes);
            final var result = run(PdfPartitioner.createWithTaggedTables(PdfOptions.defaults()), bytes);
            assertTrue(result.elements().stream().noneMatch(e -> e.type() == ElementType.Table), variant);
            assertEquals(characterCounts(original.elements()), characterCounts(result.elements()), variant);
        }
    }

    /**
     * Caps source caption wrappers and references before they can expand the source table planner without bound.
     * @throws Exception if owned source encoding fails
     */
    @Test
    void captionWrapperAndReferenceBudgetsFailTheWholeCall() throws Exception {
        for (final String variant : List.of("caption-wrapper-budget", "caption-reference-budget")) {
            final var bytes = source(0, variant);
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class,
                            () -> run(PdfPartitioner.createWithTaggedTables(PdfOptions.defaults()), bytes))
                            .code());
        }
    }

    /**
     * Preserves authored multiline cells and existing spans while keeping default geometry and escaping.
     * @throws Exception if owned tagged PDF encoding or extraction fails
     */
    @Test
    void explicitNativeLineBreaksRetainTaggedSpansAndDefaultCoordinates() throws Exception {
        final var bytes = source(0, "valid");
        final var original = run(PdfPartitioner.createWithTaggedTables(PdfOptions.defaults()), bytes);
        final var result = run(PdfPartitioner.createWithTaggedTables(
                                       PdfOptions.defaults(), PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS),
                bytes);
        final var before =
                original.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        final var table =
                result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        assertEquals("Wrapped\nheading 2025-01-\n02 A & <B> Last", table.text());
        final String html = table.metadata().get("text_as_html").toString();
        assertTrue(html.contains("<td rowspan=\"2\">2025-01-<br>02</td>"));
        assertTrue(html.contains("<th colspan=\"2\">Wrapped<br>heading</th>"));
        assertTrue(html.contains("A &amp; &lt;B&gt;"));
        assertEquals(before.metadata().get("coordinates"), table.metadata().get("coordinates"));
        assertEquals(before.metadata().get("pdf_table_cells"), table.metadata().get("pdf_table_cells"));
        assertEquals(2, table.metadata().get("pdf_table_multiline_cells"));
        assertFalse(table.metadata().containsKey("pdf_table_joined_iso_dates"));
        assertEquals(original.elements().stream().filter(e -> e.type() != ElementType.Table).toList(),
                result.elements().stream().filter(e -> e.type() != ElementType.Table).toList());
        final var rotatedBytes = source(90, "valid");
        final var rotatedDefault = run(PdfPartitioner.createWithTaggedTables(PdfOptions.defaults()), rotatedBytes);
        final var rotatedPreserved = run(PdfPartitioner.createWithTaggedTables(PdfOptions.defaults(),
                                                 PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS),
                rotatedBytes);
        assertEquals(rotatedDefault.elements(), rotatedPreserved.elements());
    }

    /**
     * Malformed/cross-page/empty/duplicate source tags never consume only part of a logical table.
     * @throws Exception if owned fallback controls fail
     */
    @Test
    void unsupportedWholeTablesRetainEveryOriginalNativeTextFragment() throws Exception {
        for (final String variant :
                List.of("duplicate-ref", "duplicate-marker", "missing-ref", "empty-cell", "invalid-span", "cross-page",
                        "class-span", "nested-table", "alias-cycle", "open-marker", "cell-form", "old-revision")) {
            final var bytes = source(0, variant);
            final var plain = run(PdfPartitioner.create(PdfOptions.defaults()), bytes);
            final var tagged = run(PdfPartitioner.createWithTaggedTables(PdfOptions.defaults()), bytes);
            assertTrue(tagged.elements().stream().noneMatch(e -> e.type() == ElementType.Table), variant);
            assertEquals(plain.elements(), tagged.elements(), variant);
        }
    }

    /**
     * Explicit aliases can name standard source table/cell roles; source ActualText is not guessed or substituted.
     * @throws Exception if owned source-role controls fail
     */
    @Test
    void roleAliasesResolveWithoutUsingAlternateSourceText() throws Exception {
        final var result = run(PdfPartitioner.createWithTaggedTables(PdfOptions.defaults()), source(0, "alias"));
        final var table =
                result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        assertTrue(table.text().startsWith("Wrapped heading"));
        assertFalse(table.text().contains("Alternate"));
    }

    /**
     * Existing content-stream substitutions retain source identity without accepting later glyph mutations.
     * @throws Exception if owned substitution controls fail
     */
    @Test
    void contentStreamActualTextMatchesTheExistingNativePolicy() throws Exception {
        for (final String variant : List.of("stream-actual", "resource-actual", "nested-actual")) {
            final var bytes = source(0, variant);
            final var plain = run(PdfPartitioner.create(PdfOptions.defaults()), bytes);
            final var tagged = run(PdfPartitioner.createWithTaggedTables(PdfOptions.defaults()), bytes);
            final var table =
                    tagged.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
            assertTrue(table.text().contains("Resolved"), variant);
            assertTrue(plain.elements().stream().anyMatch(e -> e.text().contains("Resolved")), variant);
            assertFalse(table.text().contains("XY"), variant);
        }
        for (final String variant : List.of("cross-cell-diacritic", "actual-cross-cell-diacritic")) {
            final var bytes = source(0, variant);
            final var plain = run(PdfPartitioner.create(PdfOptions.defaults()), bytes);
            final var tagged = run(PdfPartitioner.createWithTaggedTables(PdfOptions.defaults()), bytes);
            assertTrue(tagged.elements().stream().noneMatch(e -> e.type() == ElementType.Table), variant);
            assertEquals(plain.elements(), tagged.elements(), variant);
        }
    }

    /**
     * Replacement text is charged as retained Unicode, independently of the number of underlying source glyphs.
     * @throws Exception if owned source encoding or budget controls fail
     */
    @Test
    void actualTextReplacementRetainsCellAndGlyphLimits() throws Exception {
        final var d = PdfOptions.defaults();
        final var bounded = new PdfOptions(d.maxPages(), d.maxGlyphsPerPage(), d.maxOperatorsPerPage(),
                d.maxImagePixels(), d.maxImageBytes(), false, new TableLimits(100, 10, 100, 50));
        final var bytes = source(0, "long-actual");
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> run(PdfPartitioner.createWithTaggedTables(bounded), bytes))
                        .code());
        final var exact = new PdfOptions(d.maxPages(), 37, d.maxOperatorsPerPage(), d.maxImagePixels(),
                d.maxImageBytes(), false, TableLimits.defaults());
        final var table = run(PdfPartitioner.createWithTaggedTables(exact), bytes)
                                  .elements()
                                  .stream()
                                  .filter(e -> e.type() == ElementType.Table)
                                  .findFirst()
                                  .orElseThrow();
        assertTrue(table.text().contains("R".repeat(100)));
        final var tooFew = new PdfOptions(d.maxPages(), 36, d.maxOperatorsPerPage(), d.maxImagePixels(),
                d.maxImageBytes(), false, TableLimits.defaults());
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                assertThrows(PartitionException.class, () -> run(PdfPartitioner.createWithTaggedTables(tooFew), bytes))
                        .code());
    }

    /**
     * Form-local IDs never invalidate same-numbered page IDs; forms inside tagged content fall back whole.
     * @throws Exception if owned form or link-object controls fail
     */
    @Test
    void formIdentifiersAndLinkObjectsStayOutsideNativePageScope() throws Exception {
        for (final String variant : List.of("outside-form", "link-reference")) {
            final var result = run(PdfPartitioner.createWithTaggedTables(PdfOptions.defaults()), source(0, variant));
            final var tables = result.elements().stream().filter(e -> e.type() == ElementType.Table).toList();
            assertEquals(1, tables.size(), variant);
            assertEquals("Wrapped heading 2025-01- 02 A & <B> Last", tables.getFirst().text());
            if (variant.equals("outside-form")) {
                assertEquals(1, result.elements().stream().filter(e -> e.text().equals("Form")).count());
            }
        }
    }

    /**
     * Source empty cells require independent row/column support and reject unclaimed text in inferred empty slots.
     * @throws Exception if owned empty-cell controls fail
     */
    @Test
    void emptySourceCellsRemainExplicitAndNeverHideUntaggedText() throws Exception {
        final var options = PdfOptions.defaults().withTableCellCoordinates(true);
        final var result = run(PdfPartitioner.createWithTaggedTables(options), source(0, "blank-header"));
        final var table =
                result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        assertEquals(1, table.metadata().get("pdf_table_tagged_empty_cells"));
        assertTrue(table.metadata().get("text_as_html").toString().contains("<th></th>"));
        final var geometry = (java.util.Map<?, ?>) table.metadata().get("pdf_table_cells");
        assertEquals("tagged_inferred_cell_bounds", geometry.get("geometry"));
        assertEquals(5, ((List<?>) geometry.get("cells")).size());
        final var conflict = source(0, "blank-with-text");
        final var plain = run(PdfPartitioner.create(options), conflict);
        final var fallback = run(PdfPartitioner.createWithTaggedTables(options), conflict);
        assertTrue(fallback.elements().stream().noneMatch(e -> e.type() == ElementType.Table));
        assertEquals(plain.elements(), fallback.elements());
    }

    /**
     * Source claims precede disjoint native fallback, while overlap rejection leaves every extra glyph ordinary.
     * @throws Exception if owned source/fallback composition fails
     */
    @Test
    void fallbackTablesClaimOnlyDisjointSourceAreas() throws Exception {
        for (final var mode :
                List.of(PdfOptions.TableDetection.RULED_ONLY, PdfOptions.TableDetection.RULED_AND_DOUBLE_BORDERS,
                        PdfOptions.TableDetection.RULED_AND_HORIZONTAL_TEXT,
                        PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT)) {
            final var options = PdfOptions.defaults().withTableDetection(mode);
            final var disjoint = run(PdfPartitioner.createWithTaggedTables(options), source(0, "disjoint-grid"));
            assertEquals(2, disjoint.elements().stream().filter(e -> e.type() == ElementType.Table).count(),
                    mode.toString());
            final var overlap = run(PdfPartitioner.createWithTaggedTables(options), source(0, "overlap-grid"));
            assertEquals(1, overlap.elements().stream().filter(e -> e.type() == ElementType.Table).count());
            assertEquals(1, overlap.elements().stream().filter(e -> e.text().equals("Extra")).count());
        }
    }

    /**
     * Source tables do not hide provider errors or bypass raw-overlap and combined-anchor validation.
     * @throws Exception if owned source encoding or provider controls fail
     */
    @Test
    void providerFailureAndDiscardedAnchorsStillCountAfterSourceSuccess() throws Exception {
        final var bytes = source(0, "valid");
        final var calls = new AtomicInteger();
        final PdfTableLayoutProvider overlapping = image -> {
            calls.incrementAndGet();
            final var box = new PdfTableLayoutProvider.Box(10, 20, 180, 140);
            return List.of(new PdfTableLayoutProvider.Table(
                    1, 1, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "overlap"));
        };
        final var failure = assertThrows(PartitionException.class,
                ()
                        -> run(PdfPartitioner.createWithTaggedTablesFirst(
                                       PdfOptions.defaults(), new PdfNativeLayoutOptions(72, 40_000, 4), overlapping),
                                bytes));
        assertEquals(PartitionException.Code.TABLE_LIMIT, failure.code());
        assertEquals(1, calls.get());
        final var result = run(PdfPartitioner.createWithTaggedTablesFirst(
                                       PdfOptions.defaults(), new PdfNativeLayoutOptions(72, 40_000, 5), overlapping),
                bytes);
        assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Table).count());
        final var providerFailure = assertThrows(PartitionException.class,
                ()
                        -> run(PdfPartitioner.createWithTaggedTablesFirst(PdfOptions.defaults(),
                                       new PdfNativeLayoutOptions(72, 40_000, 16),
                                       image -> { throw new IOException("owned"); }),
                                bytes));
        assertEquals(PartitionException.Code.OCR_FAILURE, providerFailure.code());
    }

    /**
     * Cancellation and raw source table limits stay typed; invalid service configuration fails before execution.
     * @throws Exception if owned source encoding or cancellation controls fail
     */
    @Test
    void sourceBudgetsCancellationAndFactoryBoundariesRemainExplicit() throws Exception {
        final var d = PdfOptions.defaults();
        final var limits = new PdfOptions(d.maxPages(), d.maxGlyphsPerPage(), d.maxOperatorsPerPage(),
                d.maxImagePixels(), d.maxImageBytes(), false, new TableLimits(2, 10, 100, 100));
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> run(PdfPartitioner.createWithTaggedTables(limits), source(0, "valid")))
                        .code());
        final var bytes = source(0, "valid");
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    java.io.InterruptedIOException.class, () -> run(PdfPartitioner.createWithTaggedTables(d), bytes));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertThrows(NullPointerException.class, () -> PdfPartitioner.createWithTaggedTables(null));
        assertThrows(NullPointerException.class, () -> PdfPartitioner.createWithTaggedTables(d, null));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner.createWithTaggedTables(
                                d.withTableDetection(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT)));
    }

    /**
     * Builds source-authored cells before extraction, including independently specified malformed variants.
     * @param rotation declared page rotation
     * @param variant explicit fixture variant
     * @return original owned PDF
     * @throws Exception if source authoring fails
     */
    private static byte[] source(final int rotation, final String variant) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            page.setRotation(rotation);
            document.addPage(page);
            final var root = new PDStructureTreeRoot();
            document.getDocumentCatalog().setStructureTreeRoot(root);
            final var table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);
            final var rows = new java.util.ArrayList<PDStructureElement>();
            for (int i = 0; i < 3; i++) {
                final var row = new PDStructureElement("TR", table);
                table.appendKid(row);
                rows.add(row);
            }
            if (variant.startsWith("caption-")) {
                final int count = variant.equals("caption-wrapper-budget") ? 17 : 1;
                for (int index = 0; index < count; index++) {
                    final var owner = variant.equals("caption-row") ? rows.getFirst() : table;
                    final var caption = new PDStructureElement("Caption", owner);
                    caption.setPage(page);
                    owner.appendKid(caption);
                    if (variant.equals("caption-cross-page")) {
                        final var other = new PDPage(new PDRectangle(200, 200));
                        document.addPage(other);
                        caption.setPage(other);
                    }
                    final int id = variant.equals("caption-missing")     ? 99
                                   : variant.equals("caption-duplicate") ? 1
                                                                         : 88 + index;
                    if (variant.equals("caption-wrapped")) {
                        final var paragraph = new PDStructureElement("P", caption);
                        caption.appendKid(paragraph);
                        paragraph.appendKid(id);
                    } else {
                        caption.appendKid(id);
                    }
                    if (variant.equals("caption-actual")) {
                        caption.setActualText("Alternate caption");
                    }
                    if (variant.equals("caption-reference-budget")) {
                        final var ids = new COSArray();
                        for (int reference = 0; reference < 4_097; reference++) {
                            ids.add(COSInteger.get(70_000 + reference));
                        }
                        caption.getCOSObject().setItem(COSName.K, ids);
                    }
                }
                if (variant.equals("caption-shared-table")) {
                    final var other = new PDStructureElement("Table", root);
                    other.setPage(page);
                    root.appendKid(other);
                    final var row = new PDStructureElement("TR", other);
                    other.appendKid(row);
                    cell(row, "TD", 88, 1, 1);
                }
            }
            final boolean auxiliary = variant.contains("paint");
            if (auxiliary) {
                final var owner = variant.equals("row-paint") ? rows.getFirst() : table;
                if (variant.equals("mcr-paint") || variant.equals("paint-cross-page")
                        || variant.equals("paint-mcr-actual") || variant.equals("paint-mcr-child")) {
                    final var reference = new COSDictionary();
                    reference.setName(COSName.TYPE, "MCR");
                    reference.setInt(COSName.MCID, 77);
                    if (variant.equals("paint-mcr-child")) {
                        reference.setInt(COSName.K, 78);
                    }
                    if (variant.equals("paint-mcr-actual")) {
                        reference.setString(COSName.ACTUAL_TEXT, "Alternate");
                    }
                    PDPage original = page;
                    if (variant.equals("paint-cross-page")) {
                        original = new PDPage(new PDRectangle(200, 200));
                        document.addPage(original);
                    }
                    reference.setItem(COSName.PG, original);
                    final var children = owner.getCOSObject().getCOSArray(COSName.K);
                    children.add(reference);
                } else {
                    final int count = variant.equals("paint-budget")      ? 4_097
                                      : variant.equals("paint-duplicate") ? 2
                                                                          : 1;
                    for (int i = 0; i < count; i++) {
                        owner.appendKid(variant.equals("paint-ref-cell") ? 0
                                        : variant.equals("paint-budget") ? 77 + i
                                                                         : 77);
                    }
                }
                if (variant.equals("paint-actual")) {
                    table.setActualText("Alternate");
                }
            }
            final boolean blankHeader = variant.equals("blank-header") || variant.equals("blank-with-text");
            final var header = cell(rows.get(0), "TH", 0, 1, blankHeader ? 1 : 2);
            if (blankHeader) {
                cell(rows.get(0), "TH", 4, 1, 1).getCOSObject().removeItem(COSName.K);
            }
            final var date = cell(rows.get(1), "TD", 1, 2, 1);
            final var label = cell(rows.get(1), "TD", 2, 1, 1);
            cell(rows.get(2), "TD", 3, 1, 1);
            if (variant.equals("presentation-classes")) {
                final var classes = new COSDictionary();
                final var presentation = new COSDictionary();
                presentation.setName(COSName.O, "ProducerPresentation");
                presentation.setInt("Padding", 2);
                classes.setItem("formatting", presentation);
                root.getCOSObject().setItem(COSName.CLASS_MAP, classes);
                for (final var styled : List.of(table, rows.getFirst(), header, label)) {
                    styled.getCOSObject().setName(COSName.C, "formatting");
                }
            }
            if (variant.startsWith("span-class-")) {
                final var classes = new COSDictionary();
                final var wide = new COSDictionary();
                wide.setName(COSName.O, "Table");
                wide.setInt("ColSpan", variant.equals("span-class-override") ? 3 : 2);
                final var tall = new COSDictionary();
                tall.setName(COSName.O, "Table");
                tall.setInt("RowSpan", variant.equals("span-class-override") ? 3 : 2);
                classes.setItem("wide", wide);
                classes.setItem("tall", tall);
                root.getCOSObject().setItem(COSName.CLASS_MAP, classes);
                header.getCOSObject().setName(COSName.C, "wide");
                date.getCOSObject().setName(COSName.C, "tall");
                if (variant.equals("span-class-only")) {
                    header.getCOSObject().removeItem(COSName.A);
                    date.getCOSObject().removeItem(COSName.A);
                }
            }
            if (variant.equals("duplicate-ref")) {
                label.getCOSObject().setInt(COSName.K, 1);
            } else if (variant.equals("missing-ref")) {
                label.getCOSObject().setInt(COSName.K, 99);
            } else if (variant.equals("invalid-span")) {
                final var attributes = new PDTableAttributeObject();
                attributes.setColSpan(0);
                label.addAttribute(attributes);
            } else if (variant.equals("class-span")) {
                label.getCOSObject().setName(COSName.C, "UnknownClass");
            } else if (variant.equals("cross-page")) {
                final var second = new PDPage(new PDRectangle(200, 200));
                document.addPage(second);
                label.setPage(second);
            } else if (variant.equals("nested-table")) {
                label.appendKid(new PDStructureElement("Table", label));
            } else if (variant.equals("old-revision")) {
                header.incrementRevisionNumber();
            } else if (variant.equals("link-reference")) {
                final var annotation = new COSDictionary();
                annotation.setItem(COSName.TYPE, COSName.ANNOT);
                annotation.setName(COSName.SUBTYPE, "Link");
                final var object = new COSDictionary();
                object.setName(COSName.TYPE, "OBJR");
                object.setItem(COSName.OBJ, annotation);
                final var children = new COSArray();
                children.add(COSInteger.get(2));
                children.add(object);
                label.getCOSObject().setItem(COSName.K, children);
            } else if (variant.equals("alias") || variant.equals("alias-cycle")) {
                final var aliases = new COSDictionary();
                aliases.setName(COSName.getPDFName("Grid"), "Table");
                if (variant.equals("alias-cycle")) {
                    aliases.setName(COSName.getPDFName("Table"), "Grid");
                }
                root.getCOSObject().setItem(COSName.ROLE_MAP, aliases);
                table.setStructureType("Grid");
                header.setActualText("Alternate");
            }
            try (final var stream = new PDPageContentStream(document, page)) {
                if (auxiliary && !variant.equals("paint-missing")) {
                    stream.beginMarkedContent(COSName.getPDFName("Span"), 77);
                    if (variant.equals("paint-text") || variant.equals("paint-space")) {
                        draw(stream, variant.equals("paint-text") ? "Auxiliary" : " ", 20, 30);
                    } else {
                        stream.addRect(15, 90, 120, 80);
                        stream.stroke();
                    }
                    stream.endMarkedContent();
                    if (variant.equals("paint-redeclared")) {
                        stream.beginMarkedContent(COSName.getPDFName("Span"), 77);
                        stream.endMarkedContent();
                    }
                }
                draw(stream, "Outside", 20, 185);
                if (variant.startsWith("caption-")) {
                    final var captionProperties = new COSDictionary();
                    captionProperties.setInt(COSName.MCID, 88);
                    stream.beginMarkedContent(COSName.getPDFName("Caption"),
                            org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(
                                    captionProperties));
                    if (!variant.equals("caption-empty")) {
                        draw(stream, "Caption", 20, variant.equals("caption-overlap") ? 160 : 175);
                    }
                    stream.endMarkedContent();
                    if (variant.equals("caption-redeclared")) {
                        stream.beginMarkedContent(COSName.getPDFName("Caption"),
                                org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(
                                        captionProperties));
                        draw(stream, "Caption", 20, 170);
                        stream.endMarkedContent();
                    }
                }
                if (variant.equals("outside-form")) {
                    form(stream, document, 1, "Form", 20, 20);
                }
                stream.beginMarkedContent(COSName.getPDFName("Span"), 0);
                draw(stream, "Wrapped", 20, 160);
                draw(stream, "heading", 20, 148);
                stream.endMarkedContent();
                stream.beginMarkedContent(COSName.getPDFName("Span"), 1);
                draw(stream, "2025-01-", 20, 125);
                draw(stream, "02", 20, 113);
                stream.endMarkedContent();
                stream.beginMarkedContent(COSName.getPDFName("Span"), variant.equals("duplicate-marker") ? 1 : 2);
                if (!variant.equals("empty-cell")) {
                    if (variant.equals("cell-form")) {
                        form(stream, document, 2, "A & <B>", 110, 125);
                    } else if (variant.equals("stream-actual") || variant.equals("resource-actual")
                               || variant.equals("nested-actual") || variant.equals("long-actual")
                               || variant.equals("actual-cross-cell-diacritic")) {
                        final var properties = new COSDictionary();
                        properties.setString(COSName.ACTUAL_TEXT, variant.equals("long-actual") ? "R".repeat(100)
                                                                  : variant.equals("actual-cross-cell-diacritic")
                                                                          ? "A"
                                                                          : "Resolved");
                        final var property =
                                org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(
                                        properties);
                        if (variant.equals("stream-actual")) {
                            // Author an inline dictionary explicitly; the ordinary writer uses resource properties.
                            stream.appendRawCommands(
                                    "/Span << /ActualText <FEFF0052006500AD0073006F006C007600650064> >> BDC\n");
                        } else {
                            stream.beginMarkedContent(COSName.getPDFName("Span"), property);
                        }
                        draw(stream, variant.equals("actual-cross-cell-diacritic") ? "X" : "XY", 110, 125);
                        if (variant.equals("nested-actual")) {
                            stream.beginMarkedContent(COSName.getPDFName("Span"));
                            draw(stream, "Z", 125, 125);
                            stream.endMarkedContent();
                        }
                        stream.endMarkedContent();
                    } else if (variant.equals("cross-cell-diacritic")) {
                        draw(stream, "A", 110, 125);
                    } else {
                        draw(stream, "A & <B>", 110, 125);
                    }
                }
                stream.endMarkedContent();
                stream.beginMarkedContent(COSName.getPDFName("Span"), 3);
                if (variant.equals("cross-cell-diacritic") || variant.equals("actual-cross-cell-diacritic")) {
                    draw(stream, "\u00b4", 110, 125);
                }
                draw(stream, "Last", 110, 92);
                if (!variant.equals("open-marker")) {
                    stream.endMarkedContent();
                }
                if (variant.equals("blank-with-text")) {
                    draw(stream, "Lost", 110, 160);
                }
                if (variant.equals("disjoint-grid")) {
                    grid(stream, 10, 10, 150, 70);
                    draw(stream, "One", 20, 55);
                    draw(stream, "Two", 90, 55);
                    draw(stream, "Three", 20, 25);
                    draw(stream, "Four", 90, 25);
                } else if (variant.equals("overlap-grid")) {
                    grid(stream, 10, 80, 180, 175);
                    draw(stream, "Extra", 110, 148);
                }
                stream.drawImage(
                        LosslessFactory.createFromImage(document, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)),
                        170, 30, 5, 5);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Defines one logical anchor with direct source Table-owner attributes.
     * @param parent source TR
     * @param role TH or TD
     * @param id original page MCID
     * @param rows authored row extent
     * @param columns authored column extent
     * @return original mutable structure element for fixture variants
     */
    private static PDStructureElement cell(
            final PDStructureElement parent, final String role, final int id, final int rows, final int columns) {
        final var result = new PDStructureElement(role, parent);
        parent.appendKid(result);
        result.appendKid(id);
        if (rows != 1 || columns != 1) {
            final var attributes = new PDTableAttributeObject();
            attributes.setRowSpan(rows);
            attributes.setColSpan(columns);
            result.addAttribute(attributes);
        }
        return result;
    }

    /**
     * Draws one original native text run inside the current marked-content scope.
     * @param stream original content stream
     * @param text source text
     * @param x original x
     * @param y original baseline
     * @throws IOException if source authoring fails
     */
    private static void draw(final PDPageContentStream stream, final String text, final float x, final float y)
            throws IOException {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
    }

    /**
     * Draws a form with its independent content identifier, without reusing the page ID scope.
     * @param destination page stream
     * @param document caller-owned source document
     * @param id form-local MCID
     * @param text original form text
     * @param x source x
     * @param y source baseline
     * @throws IOException if source authoring fails
     */
    private static void form(final PDPageContentStream destination, final PDDocument document, final int id,
            final String text, final float x, final float y) throws IOException {
        final var form = new PDFormXObject(document);
        form.setBBox(new PDRectangle(200, 200));
        form.setResources(new PDResources());
        try (final var stream = new PDFormContentStream(form)) {
            final var properties = new COSDictionary();
            properties.setInt(COSName.MCID, id);
            stream.beginMarkedContent(COSName.getPDFName("Span"),
                    org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(properties));
            stream.beginText();
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
            stream.newLineAtOffset(x, y);
            stream.showText(text);
            stream.endText();
            stream.endMarkedContent();
        }
        destination.drawForm(form);
    }

    /**
     * Draws a complete independent two-by-two painted grid.
     * @param stream caller-owned original content stream
     * @param left source left edge
     * @param bottom source lower edge
     * @param right source right edge
     * @param top source upper edge
     * @throws IOException if source authoring fails
     */
    private static void grid(final PDPageContentStream stream, final float left, final float bottom, final float right,
            final float top) throws IOException {
        for (final float x : new float[] {left, (left + right) / 2, right}) {
            stream.moveTo(x, bottom);
            stream.lineTo(x, top);
        }
        for (final float y : new float[] {bottom, (bottom + top) / 2, top}) {
            stream.moveTo(left, y);
            stream.lineTo(right, y);
        }
        stream.stroke();
    }

    /**
     * Runs the ordinary registry/source contract without any special test extraction path.
     * @param partitioner configured public service
     * @param bytes original authored source
     * @return immutable extraction result
     * @throws Exception if extraction fails
     */
    private static PartitionResult run(final PdfPartitioner partitioner, final byte[] bytes) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                .partition(DocumentSource.bytes(bytes, "tagged.pdf"));
    }
}
