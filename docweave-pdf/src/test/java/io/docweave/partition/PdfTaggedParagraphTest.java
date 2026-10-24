package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Owned source ownership, fallback, wrap and composition controls independent of publisher references. */
class PdfTaggedParagraphTest {
    /** Creates independent public extraction controls. */
    PdfTaggedParagraphTest() {
    }

    /**
     * Cross-page source references remain opt-in, page-local, bounded and independent of reused MCID values.
     * @throws Exception if authored multi-page sources or public extraction fail
     */
    @Test
    void explicitPageFragmentsKeepOriginalPagesAndLocalHints() throws Exception {
        final var policy = joining().withPageBoundary(PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS);
        assertEquals(PdfTaggedParagraphOptions.PageBoundary.SINGLE_PAGE, joining().pageBoundary());
        assertEquals(policy.pageBoundary(), policy.withWordWrap(policy.wordWrap()).pageBoundary());
        assertThrows(NullPointerException.class, () -> policy.withPageBoundary(null));
        for (final String variant : List.of("valid", "missing", "backward", "gap")) {
            final byte[] bytes = fragmentedSource(variant);
            final var defaults =
                    elements(PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(joining()), bytes);
            assertTrue(defaults.stream().noneMatch(
                    element -> Integer.valueOf(0).equals(element.metadata().get("pdf_source_paragraph_ordinal"))));
            final var actual =
                    elements(PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(policy), bytes);
            final var fragments =
                    actual.stream()
                            .filter(element -> element.metadata().containsKey("pdf_source_paragraph_fragment"))
                            .toList();
            if (variant.equals("backward") || variant.equals("gap")) {
                assertTrue(fragments.isEmpty());
                assertEquals(defaults, actual);
                continue;
            }
            assertEquals(variant.equals("missing") ? 1 : 2, fragments.size());
            assertEquals("capabilities", fragments.getFirst().text());
            assertEquals(1, fragments.getFirst().metadata().get("page_number"));
            assertEquals(1, fragments.getFirst().metadata().get("pdf_source_paragraph_fragment"));
            assertEquals(2, fragments.getFirst().metadata().get("pdf_source_paragraph_fragments"));
            final String sourceId =
                    java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                    + ":0";
            assertEquals(sourceId, fragments.getFirst().metadata().get("pdf_source_paragraph_id"));
            if (variant.equals("valid")) {
                assertEquals(sourceId, fragments.getLast().metadata().get("pdf_source_paragraph_id"));
                final var assembled = PdfParagraphAssembler.create().assemble(actual);
                assertEquals(actual.size() - 1, assembled.size());
                assertTrue(texts(assembled).contains("capabilities Across- pages"));
                assertTrue(texts(assembled).contains("SIDEBAR BODY"));
                assertTrue(texts(assembled).contains("Loose text"));
                final byte[] changedBytes = java.util.Arrays.copyOf(bytes, bytes.length + 1);
                changedBytes[bytes.length] = '\n';
                final var other =
                        elements(
                                PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(policy), changedBytes)
                                .stream()
                                .filter(element -> element.metadata().containsKey("pdf_source_paragraph_fragment"))
                                .toList();
                assertNotEquals(sourceId, other.getLast().metadata().get("pdf_source_paragraph_id"));
                final var mixed = List.of(fragments.getFirst(), other.getLast());
                assertEquals(mixed, PdfParagraphAssembler.create().assemble(mixed));
                assertEquals("Across- pages", fragments.getLast().text());
                assertEquals(2, fragments.getLast().metadata().get("page_number"));
                assertEquals(2, fragments.getLast().metadata().get("pdf_source_paragraph_fragment"));
                assertEquals(2, fragments.getLast().metadata().get("pdf_source_paragraph_fragments"));
                assertEquals(0, fragments.getLast().metadata().get("pdf_source_paragraph_ordinal"));
                assertNotEquals(fragments.getFirst().metadata().get("coordinates"),
                        fragments.getLast().metadata().get("coordinates"));
            } else {
                assertTrue(String.join(" ", texts(actual)).contains("Across-"));
                assertTrue(String.join(" ", texts(actual)).contains("pages"));
            }
            assertTrue(texts(actual).contains("SIDEBAR BODY"));
            assertTrue(texts(actual).contains("Loose text"));
        }
    }

    /**
     * Extends the original owned source with a second-page continuation and adversarial reference variants.
     * @param variant valid, missing MCID, backward page traversal or skipped original page
     * @return unchanged display text with explicitly authored paragraph references
     * @throws Exception if source construction fails
     */
    private static byte[] fragmentedSource(final String variant) throws Exception {
        try (final var document = Loader.loadPDF(source("valid")); final var output = new ByteArrayOutputStream()) {
            final var root = document.getDocumentCatalog().getStructureTreeRoot();
            final var paragraph = (PDStructureElement) root.getKids().getFirst();
            final var children = paragraph.getCOSObject().getCOSArray(COSName.K);
            if (variant.equals("gap")) {
                document.addPage(new PDPage(new PDRectangle(400, 400)));
            }
            final var second = new PDPage(new PDRectangle(400, 400));
            document.addPage(second);
            for (int id = 1; id <= 3; id++) {
                final var reference = new COSDictionary();
                reference.setItem(COSName.TYPE, COSName.MCR);
                reference.setItem(COSName.PG, second);
                reference.setInt(COSName.MCID, variant.equals("missing") && id == 3 ? 99 : id);
                children.add(reference);
            }
            if (variant.equals("backward")) {
                final var reference = new COSDictionary();
                reference.setItem(COSName.TYPE, COSName.MCR);
                reference.setItem(COSName.PG, document.getPage(0));
                reference.setInt(COSName.MCID, 6);
                children.add(reference);
            }
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (final var stream = new PDPageContentStream(document, second)) {
                draw(stream, font, 1, "Across", 30, 250);
                draw(stream, font, 2, "-", 30 + font.getStringWidth("Across") * 12 / 1000, 250);
                draw(stream, font, 3, "pages", 30, 236);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * P elements inside tables are never ordinary paragraphs; source/native table geometry and images remain intact.
     * @throws Exception if owned source extension or public extraction fails
     */
    @Test
    void tableCellsAndImagesKeepTheirOwnSourceOwnership() throws Exception {
        final byte[] bytes;
        try (final var document = Loader.loadPDF(source("valid")); final var output = new ByteArrayOutputStream()) {
            final var page = document.getPage(0);
            final var root = document.getDocumentCatalog().getStructureTreeRoot();
            final var table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (final var stream = new PDPageContentStream(
                         document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                for (int row = 0; row < 2; row++) {
                    final var tr = new PDStructureElement("TR", table);
                    table.appendKid(tr);
                    for (int column = 0; column < 2; column++) {
                        final int id = 10 + row * 2 + column;
                        final var cell = new PDStructureElement(row == 0 ? "TH" : "TD", tr);
                        tr.appendKid(cell);
                        final var paragraph = new PDStructureElement("P", cell);
                        paragraph.getCOSObject().setInt(COSName.K, id);
                        cell.appendKid(paragraph);
                        draw(stream, font, id, "T" + row + column, 30 + column * 100, 90 - row * 40);
                    }
                }
                for (final float x : new float[] {20, 110, 210}) {
                    stream.moveTo(x, 30);
                    stream.lineTo(x, 115);
                }
                for (final float y : new float[] {30, 75, 115}) {
                    stream.moveTo(20, y);
                    stream.lineTo(210, y);
                }
                stream.stroke();
                final var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
                try {
                    stream.drawImage(LosslessFactory.createFromImage(document, image), 340, 30, 8, 8);
                } finally {
                    image.flush();
                }
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        final var options = PdfOptions.defaults().withTableCellCoordinates(true);
        for (final var strategy :
                List.of(PdfPartitioner.create(options), PdfPartitioner.createWithTaggedTables(options))) {
            final var expected = elements(strategy, bytes);
            final var actual = elements(strategy.withTaggedParagraphs(joining()), bytes);
            assertEquals(2, actual.stream().filter(PdfTaggedParagraphTest::tagged).count());
            for (final var type : List.of(ElementType.Table, ElementType.Image)) {
                final var old = expected.stream().filter(element -> element.type() == type).toList();
                final var selected = actual.stream().filter(element -> element.type() == type).toList();
                assertEquals(1, old.size());
                assertEquals(1, selected.size());
                assertEquals(old.getFirst().text(), selected.getFirst().text());
                assertEquals(old.getFirst().metadata(), selected.getFirst().metadata());
            }
            assertEquals("T00 T01 T10 T11", actual.stream()
                                                    .filter(element -> element.type() == ElementType.Table)
                                                    .findFirst()
                                                    .orElseThrow()
                                                    .text()
                                                    .replaceAll("\\s+", " ")
                                                    .strip());
        }
    }

    /**
     * Logical source paragraphs separate interleaved sidebars; explicit hints join without changing geometry.
     * @throws Exception if owned source authoring or extraction fails
     */
    @Test
    void sourceParagraphsSeparateSidebarsAndJoinOnlyExplicitDiscretionaryWraps() throws Exception {
        for (final String variant : List.of("valid", "alias", "implicit-type")) {
            final byte[] bytes = source(variant);
            final var original = PdfPartitioner.create(PdfOptions.defaults());
            final var preserve = original.withTaggedParagraphs(PdfTaggedParagraphOptions.defaults());
            final var joined = original.withTaggedParagraphs(joining());
            final var kept = elements(preserve, bytes);
            final var repaired = elements(joined, bytes);
            assertEquals(List.of("capabili- ties", "SIDEBAR BODY", "Loose text"), texts(kept));
            assertEquals(List.of("capabilities", "SIDEBAR BODY", "Loose text"), texts(repaired));
            assertEquals(
                    kept.getFirst().metadata().get("coordinates"), repaired.getFirst().metadata().get("coordinates"));
            assertEquals("source_tagged_paragraph_v1", repaired.getFirst().metadata().get("pdf_text_layout"));
            assertEquals("source_soft_hyphens_v1", repaired.getFirst().metadata().get("pdf_source_word_wrap"));
            assertFalse(elements(original, bytes).stream().anyMatch(PdfTaggedParagraphTest::tagged));
            for (final var layout :
                    List.of(PdfOptions.TextLayout.PARAGRAPHS, PdfOptions.TextLayout.COLUMN_PARAGRAPHS)) {
                final var composed = PdfPartitioner.create(PdfOptions.defaults().withTextLayout(layout))
                                             .withTaggedParagraphs(joining());
                assertEquals(repaired, elements(composed, bytes));
            }
        }
    }

    /**
     * Missing/duplicate/nested/cross-page/form references reject whole paragraphs without partial claims.
     * @throws Exception if owned source authoring or extraction fails
     */
    @Test
    void unsupportedOwnershipPreservesAllUnclaimedSourceText() throws Exception {
        for (final String variant : List.of("missing", "duplicate-reference", "nested", "cross-page", "form",
                     "conflicting-paragraph", "two-columns", "missing-role", "wrong-type", "rtl")) {
            final byte[] bytes = source(variant);
            final var extracted =
                    elements(PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(joining()), bytes);
            assertEquals(List.of("SIDEBAR BODY"),
                    texts(extracted.stream().filter(PdfTaggedParagraphTest::tagged).toList()), variant);
            assertFalse(texts(extracted).contains("capabilities"), variant);
            final String all = String.join(" ", texts(extracted));
            assertTrue(all.contains("ties"), variant);
            assertTrue(all.contains("Loose text"), variant);
            assertTrue(all.contains("SIDEBAR BODY"), variant);
        }
        final byte[] untagged = source("untagged");
        assertEquals(elements(PdfPartitioner.create(PdfOptions.defaults()), untagged),
                elements(PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(joining()), untagged));
        final byte[] shared = source("shared-graph");
        assertEquals(elements(PdfPartitioner.create(PdfOptions.defaults()), shared),
                elements(PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(joining()), shared));
    }

    /**
     * Alternate strings are never substituted and unsupported discretionary geometry retains physical hyphens.
     * @throws Exception if owned source authoring or extraction fails
     */
    @Test
    void competingAlternativesAndNonwrapsKeepTheirNativeText() throws Exception {
        for (final String variant : List.of("nonsoft", "ancestor-actual", "multi-glyph", "same-line", "long-gap")) {
            final byte[] bytes = source(variant);
            final var preserved = elements(PdfPartitioner.create(PdfOptions.defaults())
                                                   .withTaggedParagraphs(PdfTaggedParagraphOptions.defaults()),
                    bytes);
            final var selected =
                    elements(PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(joining()), bytes);
            assertEquals(texts(preserved), texts(selected), variant);
            assertTrue(selected.getFirst().text().contains("-"), variant);
            assertFalse(selected.getFirst().text().contains("alternate replacement"));
        }
    }

    /**
     * Original text and raw-plan limits apply before shortening, and unsupported composition fails during setup.
     * @throws Exception if owned source authoring fails
     */
    @Test
    void prejoinBudgetsAndStrategyCompatibilityRemainExplicit() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new PdfTaggedParagraphOptions(0, 100, joining().wordWrap()));
        assertThrows(IllegalArgumentException.class, () -> new PdfTaggedParagraphOptions(10, 0, joining().wordWrap()));
        assertThrows(NullPointerException.class, () -> new PdfTaggedParagraphOptions(10, 100, null));
        assertThrows(NullPointerException.class,
                () -> PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(null));
        final byte[] bytes = source("valid");
        final byte[] blank;
        try (final var document = Loader.loadPDF(bytes); final var output = new ByteArrayOutputStream()) {
            document.getPage(0).setContents(new org.apache.pdfbox.pdmodel.common.PDStream(document));
            document.save(output);
            blank = output.toByteArray();
        }
        assertEquals(PartitionException.Code.LAYOUT_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> elements(PdfPartitioner.create(PdfOptions.defaults())
                                                    .withTaggedParagraphs(new PdfTaggedParagraphOptions(
                                                            1, 100, joining().wordWrap())),
                                        blank))
                        .code());
        for (final var policy : List.of(new PdfTaggedParagraphOptions(1, 100, joining().wordWrap()),
                     new PdfTaggedParagraphOptions(10, 13, joining().wordWrap()))) {
            final var failure = assertThrows(PartitionException.class,
                    () -> elements(PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(policy), bytes));
            assertEquals(PartitionException.Code.LAYOUT_LIMIT, failure.code());
        }
        assertEquals("capabilities",
                elements(PdfPartitioner.create(PdfOptions.defaults())
                                 .withTaggedParagraphs(new PdfTaggedParagraphOptions(10, 14, joining().wordWrap())),
                        bytes)
                        .getFirst()
                        .text());
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner.create(PdfOptions.defaults(), PdfOcrOptions.defaults(), image -> List.of())
                                .withTaggedParagraphs(joining()));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner
                                .createWithNativeLayout(
                                        PdfOptions.defaults(), PdfNativeLayoutOptions.defaults(), image -> List.of())
                                .withTaggedParagraphs(joining()));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner
                                .create(PdfOptions.defaults().withTableDetection(
                                        PdfOptions.TableDetection.RULED_AND_MULTILINE_ALIGNED_TEXT))
                                .withTaggedParagraphs(joining()));
    }

    /**
     * Shared source strategies remain deterministic and password configuration preserves paragraph policy both ways.
     * @throws Exception if owned authoring, encryption or bounded concurrent extraction fails
     */
    @Test
    void passwordCopiesAndConcurrentReusePreserveParagraphPolicy() throws Exception {
        final byte[] bytes = source("valid");
        final byte[] encrypted;
        try (final var document = Loader.loadPDF(bytes); final var output = new ByteArrayOutputStream()) {
            document.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
            document.save(output);
            encrypted = output.toByteArray();
        }
        final var base = PdfPartitioner.createWithTaggedTables(PdfOptions.defaults());
        final var configured = base.withTaggedParagraphs(joining());
        final var expected = elements(configured, bytes);
        assertEquals(expected, elements(configured.withPasswords(name -> "user".toCharArray()), encrypted));
        assertEquals(expected,
                elements(base.withPasswords(name -> "user".toCharArray()).withTaggedParagraphs(joining()), encrypted));
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 12)
                                      .mapToObj(index -> executor.submit(() -> elements(configured, bytes)))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(expected, task.get(10, TimeUnit.SECONDS));
            }
        }
    }

    /**
     * Authors page-stream source tags and adversarial variants without any publisher labels or model.
     * @param variant owned structure/content control
     * @return complete original PDF bytes
     * @throws Exception if PDF authoring fails
     */
    private static byte[] source(final String variant) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            document.addPage(page);
            final var root = new PDStructureTreeRoot();
            if (!variant.equals("untagged")) {
                document.getDocumentCatalog().setStructureTreeRoot(root);
            }
            final var paragraph = new PDStructureElement(variant.equals("alias") ? "CustomParagraph" : "P", root);
            paragraph.setPage(page);
            root.appendKid(paragraph);
            if (variant.equals("alias")) {
                final var roles = new COSDictionary();
                roles.setName(COSName.getPDFName("CustomParagraph"), "P");
                root.getCOSObject().setItem(COSName.ROLE_MAP, roles);
            }
            if (variant.equals("implicit-type")) {
                paragraph.getCOSObject().removeItem(COSName.TYPE);
            } else if (variant.equals("wrong-type")) {
                paragraph.getCOSObject().setItem(COSName.TYPE, COSName.MCR);
            } else if (variant.equals("missing-role")) {
                paragraph.getCOSObject().removeItem(COSName.S);
            }
            if (variant.equals("ancestor-actual")) {
                paragraph.setActualText("alternate replacement");
            }
            final var container = variant.equals("nested") ? new PDStructureElement("P", paragraph) : paragraph;
            if (container != paragraph) {
                paragraph.appendKid(container);
            }
            final var children = new COSArray();
            children.add(COSInteger.get(1));
            container.getCOSObject().setItem(COSName.K, children);
            final var hint = new PDStructureElement("Span", container);
            hint.setActualText(variant.equals("nonsoft") ? "other" : "\u00ad");
            hint.getCOSObject().setInt(COSName.K, 2);
            container.appendKid(hint);
            if (variant.equals("cross-page") || variant.equals("form")) {
                final var reference = new COSDictionary();
                reference.setItem(COSName.TYPE, COSName.MCR);
                reference.setInt(COSName.MCID, 3);
                if (variant.equals("cross-page")) {
                    final var second = new PDPage(new PDRectangle(400, 400));
                    document.addPage(second);
                    reference.setItem(COSName.PG, second.getCOSObject());
                } else {
                    reference.setItem(COSName.getPDFName("Stm"), new COSDictionary());
                }
                children.add(reference);
            } else {
                children.add(COSInteger.get(variant.equals("missing") ? 99 : 3));
            }
            if (variant.equals("duplicate-reference")) {
                children.add(COSInteger.get(3));
            }
            final var sidebar = new PDStructureElement("P", root);
            sidebar.setPage(page);
            final var side = new COSArray();
            side.add(COSInteger.get(4));
            side.add(COSInteger.get(5));
            sidebar.getCOSObject().setItem(COSName.K, side);
            root.appendKid(sidebar);
            if (variant.equals("conflicting-paragraph")) {
                final var conflict = new PDStructureElement("P", root);
                conflict.setPage(page);
                conflict.getCOSObject().setInt(COSName.K, 1);
                root.appendKid(conflict);
            } else if (variant.equals("shared-graph")) {
                root.appendKid(paragraph);
            }
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            if (variant.equals("rtl")) {
                final var cmap = document.getDocument().createCOSStream();
                try (final var stream = cmap.createOutputStream()) {
                    stream.write((
                            "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /OwnedRTL def "
                            + "/CMapType 2 def 1 begincodespacerange <00> <FF> endcodespacerange "
                            + "1 beginbfchar <61> <05D0> endbfchar endcmap CMapName currentdict /CMap defineresource pop end end")
                                    .getBytes(StandardCharsets.US_ASCII));
                }
                font.getCOSObject().setItem(COSName.TO_UNICODE, cmap);
            }
            try (final var stream = new PDPageContentStream(document, page)) {
                final float dash = 30 + font.getStringWidth("capabili") * 0.012f;
                draw(stream, font, 1, "capabili", 30, 300);
                draw(stream, font, 2, variant.equals("multi-glyph") ? "x-" : "-", dash, 300);
                draw(stream, font, 3, "ties",
                        variant.equals("same-line")     ? dash + 5
                        : variant.equals("two-columns") ? 160
                                                        : 30,
                        variant.equals("same-line")  ? 300
                        : variant.equals("long-gap") ? 220
                                                     : 286);
                draw(stream, font, 4, "SIDEBAR", 250, 300);
                draw(stream, font, 5, "BODY", 250, 286);
                draw(stream, font, -1, "Loose text", 30, 180);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Writes one actual page MCID or unmarked ordinary run.
     * @param stream owned page content
     * @param font owned source font
     * @param id nonnegative original identifier, or minus one for ordinary text
     * @param value original display text
     * @param x source baseline x
     * @param y source baseline y
     * @throws IOException if content encoding fails
     */
    private static void draw(final PDPageContentStream stream, final PDType1Font font, final int id, final String value,
            final float x, final float y) throws IOException {
        if (id >= 0) {
            final var properties = new COSDictionary();
            properties.setInt(COSName.MCID, id);
            stream.beginMarkedContent(COSName.getPDFName("Span"), PDPropertyList.create(properties));
        }
        stream.beginText();
        stream.setFont(font, 12);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
        if (id >= 0) {
            stream.endMarkedContent();
        }
    }

    /**
     * Selects source-only discretionary word joining with default original-text budgets.
     * @return immutable explicit policy
     */
    private static PdfTaggedParagraphOptions joining() {
        return PdfTaggedParagraphOptions.defaults().withWordWrap(
                PdfTaggedParagraphOptions.WordWrap.SOURCE_SOFT_HYPHENS);
    }

    /**
     * Runs normal source routing through the selected public strategy.
     * @param strategy immutable source/native policy
     * @param source owned PDF bytes
     * @return ordinary immutable public elements
     * @throws IOException if extraction fails
     */
    private static List<Element> elements(final PdfPartitioner strategy, final byte[] source) throws IOException {
        return DocWeave.create(PartitionerRegistry.of(List.of(strategy)))
                .partition(DocumentSource.bytes(source, "paragraph.pdf"))
                .elements();
    }

    /**
     * Projects complete output text without fuzzy normalization or reference-dependent filtering.
     * @param elements public result
     * @return source output strings in emitted order
     */
    private static List<String> texts(final List<Element> elements) {
        return elements.stream().map(Element::text).toList();
    }

    /**
     * Identifies only metadata emitted by this explicit source-paragraph policy.
     * @param element ordinary public output
     * @return whether the element is a source paragraph
     */
    private static boolean tagged(final Element element) {
        return element.metadata().containsKey("pdf_source_paragraph_ordinal");
    }
}
