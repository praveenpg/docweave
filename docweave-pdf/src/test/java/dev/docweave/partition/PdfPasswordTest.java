package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.Loader;
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
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Owned encrypted-document controls through public extraction, provider composition and font APIs. */
class PdfPasswordTest {
    /** Creates independent credential lifecycle controls. */
    PdfPasswordTest() {
    }

    /**
     * Real AES128/AES256 decryption retains native content/geometry and clears transferred credentials.
     * @throws Exception if authoring, encryption or public extraction fails
     */
    @Test
    void nativeOutputAndFontInventorySurviveSupportedEncryption() throws Exception {
        final byte[] plain = source(false);
        final var original = PdfPartitioner.create(PdfOptions.defaults());
        final var expected = elements(original, plain, "owned.pdf");
        assertTrue(expected.stream().anyMatch(element -> element.text().contains("Alpha")));
        for (final int bits : List.of(128, 256)) {
            final String secret = bits == 256 ? "owned-sécüré-文-🔑" : "owned-user-password";
            final byte[] locked = encrypt(plain, secret, true, bits);
            final var transferred = new AtomicReference<char[]>();
            final var calls = new AtomicInteger();
            final PdfPasswordProvider lookup = filename -> {
                assertEquals("owned.pdf", filename);
                calls.incrementAndGet();
                final char[] value = secret.toCharArray();
                transferred.set(value);
                return value;
            };
            final var configured = original.withPasswords(lookup);
            assertEquals(expected, elements(configured, locked, "owned.pdf"));
            assertEquals(1, calls.get());
            wiped(transferred.get());
            assertEquals(expected, elements(configured, plain, "owned.pdf"));
            assertEquals(1, calls.get(), "Unencrypted input must not request a password");
            assertCode(PartitionException.Code.UNSUPPORTED_OPTION, () -> elements(original, locked, "owned.pdf"));
            final var inspector = PdfFontInspector.create().withPasswords(filename -> {
                assertNull(filename);
                final char[] value = secret.toCharArray();
                transferred.set(value);
                return value;
            });
            assertEquals(PdfFontInspector.create().inspect(plain), inspector.inspect(locked));
            wiped(transferred.get());
        }
    }

    /**
     * No retry loop or permission override is introduced, including empty-user-password documents.
     * @throws Exception if owned authoring or opening fails
     */
    @Test
    void requiredLookupAndActualPdfPermissionsStayDistinct() throws Exception {
        final byte[] plain = source(false);
        final byte[] available = encrypt(plain, "", true, 128);
        final var never = PdfPartitioner.create(PdfOptions.defaults()).withPasswords(filename -> {
            fail("Readable permitted documents must not request credentials");
            return null;
        });
        assertEquals(elements(PdfPartitioner.create(PdfOptions.defaults()), available, "owned.pdf"),
                elements(never, available, "owned.pdf"));
        for (final String user : List.of("user", "")) {
            final byte[] restricted = encrypt(plain, user, false, 128);
            final var userPassword = user.toCharArray();
            final var denied = PdfPartitioner.create(PdfOptions.defaults()).withPasswords(filename -> userPassword);
            assertCode(PartitionException.Code.UNSUPPORTED_OPTION, () -> elements(denied, restricted, "owned.pdf"));
            wiped(userPassword);
            final var ownerPassword = "owned-owner-password".toCharArray();
            final var owner = PdfPartitioner.create(PdfOptions.defaults()).withPasswords(filename -> ownerPassword);
            assertEquals(elements(PdfPartitioner.create(PdfOptions.defaults()), plain, "owned.pdf"),
                    elements(owner, restricted, "owned.pdf"));
            wiped(ownerPassword);
            final var inspectionPassword = user.toCharArray();
            assertCode(PartitionException.Code.UNSUPPORTED_OPTION,
                    () -> PdfFontInspector.create().withPasswords(filename -> inspectionPassword).inspect(restricted));
            wiped(inspectionPassword);
        }
        final byte[] locked = encrypt(plain, "user", true, 128);
        final var calls = new AtomicInteger();
        final var wrong = "wrong-owned-password".toCharArray();
        assertCode(PartitionException.Code.UNSUPPORTED_OPTION,
                ()
                        -> elements(PdfPartitioner.create(PdfOptions.defaults()).withPasswords(filename -> {
                    calls.incrementAndGet();
                    return wrong;
                }),
                                locked, "owned.pdf"));
        assertEquals(1, calls.get());
        wiped(wrong);
        assertCode(PartitionException.Code.UNSUPPORTED_OPTION,
                ()
                        -> elements(PdfPartitioner.create(PdfOptions.defaults()).withPasswords(filename -> null),
                                locked, "owned.pdf"));
    }

    /**
     * Copying a configured strategy preserves source tags, native/provider precedence and actual cell geometry.
     * @throws Exception if source authoring, encryption or extraction fails
     */
    @Test
    void taggedAndNativeProviderCompositionRetainTheirSelectedPolicies() throws Exception {
        final byte[] plain = source(false);
        final byte[] locked = encrypt(plain, "user", true, 128);
        final var calls = new AtomicInteger();
        final PdfTableLayoutProvider layout = image -> {
            assertEquals(300, image.getWidth());
            calls.incrementAndGet();
            return List.of(layout());
        };
        final var options = PdfOptions.defaults().withTableCellCoordinates(true);
        final var render = new PdfNativeLayoutOptions(72, 1_000_000, 100);
        for (final var strategy : List.of(PdfPartitioner.createWithTaggedTables(options),
                     PdfPartitioner.createWithNativeLayout(options, render, layout),
                     PdfPartitioner.createWithNativeTablesFirst(options, render, layout),
                     PdfPartitioner.createWithTaggedTablesFirst(options, render, layout))) {
            final var expected = elements(strategy, plain, "owned.pdf");
            final var tables = expected.stream().filter(element -> element.type() == ElementType.Table).toList();
            assertEquals(1, tables.size());
            assertEquals("Alpha Beta Gamma Delta", tables.getFirst().text().replaceAll("\\s+", " ").strip());
            assertNotNull(tables.getFirst().metadata().get("pdf_table_cells"));
            final var supplied = "user".toCharArray();
            assertEquals(expected, elements(strategy.withPasswords(filename -> supplied), locked, "owned.pdf"));
            wiped(supplied);
        }
        assertEquals(6, calls.get(), "Every configured provider still runs once per native-text document");
    }

    /**
     * OCR, orientation and separately selected cell recognition remain active after password configuration.
     * @throws Exception if scanned-source authoring or extraction fails
     */
    @Test
    void scannedPageProvidersAndOrientationRemainActiveAfterOpening() throws Exception {
        final byte[] plain = source(true);
        final byte[] locked = encrypt(plain, "user", true, 128);
        final var pageCalls = new AtomicInteger();
        final var cellCalls = new AtomicInteger();
        final var orientationCalls = new AtomicInteger();
        final PdfWordOcrProvider words = (image, kind) -> {
            pageCalls.incrementAndGet();
            return List.of();
        };
        final PdfOcrProvider cells = (image) -> {
            cellCalls.incrementAndGet();
            return List.of(new PdfOcrProvider.Line("CELL", 1, 1, 15, 10));
        };
        final PdfOrientationEstimator orientation = image -> {
            orientationCalls.incrementAndGet();
            return new PdfOrientationEstimator.Estimate(0, true, 1, "owned-upright");
        };
        final var ocr = new PdfOcrOptions(72, 1_000_000, 100, 1000)
                                .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS);
        final var original =
                PdfPartitioner.createOrientedWithCellOcr(PdfOptions.defaults().withTableCellCoordinates(true), ocr,
                        words, image -> List.of(layout()), cells, orientation);
        final var expected = elements(original, plain, "scan.pdf");
        assertTrue(expected.stream().anyMatch(
                element -> element.type() == ElementType.Table && element.text().contains("CELL")));
        final var supplied = "user".toCharArray();
        assertEquals(expected, elements(original.withPasswords(filename -> supplied), locked, "scan.pdf"));
        assertEquals(2, pageCalls.get());
        assertEquals(8, cellCalls.get());
        assertEquals(2, orientationCalls.get());
        wiped(supplied);
    }

    /**
     * Failed providers do not expose their diagnostics; returned arrays clear before cancellation propagates.
     * @throws Exception if the owned document cannot be encrypted
     */
    @Test
    void lookupFailuresAndCancellationDoNotRetainCredentials() throws Exception {
        final byte[] locked = encrypt(source(false), "user", true, 128);
        for (final boolean unchecked : List.of(false, true)) {
            final var provider = PdfPartitioner.create(PdfOptions.defaults()).withPasswords(filename -> {
                if (unchecked) {
                    throw new IllegalStateException("owned-private-credential");
                }
                throw new IOException("owned-private-credential", new IOException("owned-private-cause"));
            });
            final var failure = assertThrows(PartitionException.class, () -> elements(provider, locked, "owned.pdf"));
            assertEquals(PartitionException.Code.UNSUPPORTED_OPTION, failure.code());
            assertFalse(failure.toString().contains("owned-private"));
            assertNull(failure.getCause());
        }
        final var supplied = "user".toCharArray();
        try {
            final var interrupted = PdfPartitioner.create(PdfOptions.defaults()).withPasswords(filename -> {
                Thread.currentThread().interrupt();
                return supplied;
            });
            assertThrows(InterruptedIOException.class, () -> elements(interrupted, locked, "owned.pdf"));
            assertTrue(Thread.currentThread().isInterrupted());
            wiped(supplied);
        } finally {
            Thread.interrupted();
        }
        try {
            final var interrupted = PdfPartitioner.create(PdfOptions.defaults()).withPasswords(filename -> {
                throw new InterruptedIOException("owned-private-credential");
            });
            final var failure =
                    assertThrows(InterruptedIOException.class, () -> elements(interrupted, locked, "owned.pdf"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(failure.toString().contains("owned-private"));
            assertNull(failure.getCause());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * A shared strategy routes credentials per call without retaining another document's array.
     * @throws Exception if a bounded concurrent extraction fails
     */
    @Test
    void concurrentDocumentsHaveIndependentCredentialOwnership() throws Exception {
        final byte[] first = encrypt(source(false), "first-user", true, 128);
        final byte[] second = encrypt(source(false), "second-user", true, 128);
        final var arrays = new ConcurrentLinkedQueue<char[]>();
        final var strategy = PdfPartitioner.create(PdfOptions.defaults()).withPasswords(filename -> {
            final char[] value =
                    filename.equals("first.pdf") ? "first-user".toCharArray() : "second-user".toCharArray();
            arrays.add(value);
            return value;
        });
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks =
                    java.util.stream.IntStream.range(0, 12)
                            .mapToObj(index -> executor.submit(() -> {
                                final var result = index % 2 == 0 ? elements(strategy, first, "first.pdf")
                                                                  : elements(strategy, second, "second.pdf");
                                assertTrue(result.stream().anyMatch(element -> element.text().contains("Delta")));
                                return result.size();
                            }))
                            .toList();
            for (final var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(12, arrays.size());
        arrays.forEach(PdfPasswordTest::wiped);
    }

    /**
     * Existing input/output/page limits remain authoritative before or after credential lookup.
     * @throws Exception if authoring or extraction fails unexpectedly
     */
    @Test
    void existingLimitsAndLazyConfigurationRemainAuthoritative() throws Exception {
        assertThrows(
                NullPointerException.class, () -> PdfPartitioner.create(PdfOptions.defaults()).withPasswords(null));
        assertThrows(NullPointerException.class, () -> PdfFontInspector.create().withPasswords(null));
        final var calls = new AtomicInteger();
        final PdfPasswordProvider lookup = filename -> {
            calls.incrementAndGet();
            return "user".toCharArray();
        };
        final var configured = PdfPartitioner.create(PdfOptions.defaults()).withPasswords(lookup);
        assertEquals(0, calls.get());
        assertFalse(java.lang.reflect.Modifier.isPublic(configured.getClass().getModifiers()));
        final byte[] locked = encrypt(source(false), "user", true, 128);
        final var input = PartitionOptions.builder().maxInputBytes(locked.length - 1).build();
        assertCode(PartitionException.Code.INPUT_TOO_LARGE,
                () -> processor(configured).partition(DocumentSource.bytes(locked, "owned.pdf"), input));
        assertCode(PartitionException.Code.INPUT_TOO_LARGE,
                ()
                        -> PdfFontInspector.create(PdfOptions.defaults(), 10, locked.length - 1)
                                .withPasswords(lookup)
                                .inspect(locked));
        assertEquals(0, calls.get());
        assertCode(PartitionException.Code.ELEMENT_LIMIT,
                ()
                        -> processor(configured)
                                .partition(DocumentSource.bytes(locked, "owned.pdf"),
                                        PartitionOptions.builder().maxElements(1).build()));
        assertEquals(1, calls.get());
        assertCode(PartitionException.Code.MALFORMED_DOCUMENT,
                ()
                        -> processor(configured)
                                .partition(DocumentSource.bytes(
                                        "%PDF-1.7\ninvalid".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                                        "bad.pdf")));
        assertEquals(1, calls.get());
        final byte[] twoPages;
        try (final var document = Loader.loadPDF(source(false)); final var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            twoPages = encrypt(output.toByteArray(), "user", true, 128);
        }
        final var onePage = new PdfOptions(1, 100_000, 100_000, 1_000_000, 1_000_000, false, TableLimits.defaults());
        assertCode(PartitionException.Code.INPUT_TOO_LARGE,
                () -> elements(PdfPartitioner.create(onePage).withPasswords(lookup), twoPages, "two-pages.pdf"));
        assertCode(PartitionException.Code.INPUT_TOO_LARGE,
                () -> PdfFontInspector.create(onePage, 10, twoPages.length).withPasswords(lookup).inspect(twoPages));
        assertEquals(3, calls.get());
        final var replaced = configured
                                     .withPasswords(filename -> {
                                         fail("Replaced credential provider must not run");
                                         return null;
                                     })
                                     .withPasswords(lookup);
        assertFalse(elements(replaced, locked, "owned.pdf").isEmpty());
        assertEquals(4, calls.get());
    }

    /**
     * Creates an independently authored2x2 tagged native table or one full-page owned raster.
     * @param scanned whether to omit all native text
     * @return unencrypted source bytes
     * @throws Exception if PDF authoring fails
     */
    private static byte[] source(final boolean scanned) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(300, 300));
            document.addPage(page);
            final var root = new PDStructureTreeRoot();
            document.getDocumentCatalog().setStructureTreeRoot(root);
            final var table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (final var stream = new PDPageContentStream(document, page)) {
                if (scanned) {
                    final var image = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
                    try {
                        stream.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, 300, 300);
                    } finally {
                        image.flush();
                    }
                } else {
                    final var labels = List.of("Alpha", "Beta", "Gamma", "Delta");
                    for (int row = 0; row < 2; row++) {
                        final var tr = new PDStructureElement("TR", table);
                        table.appendKid(tr);
                        for (int column = 0; column < 2; column++) {
                            final int id = row * 2 + column;
                            final var cell = new PDStructureElement(row == 0 ? "TH" : "TD", tr);
                            cell.getCOSObject().setInt(COSName.K, id);
                            tr.appendKid(cell);
                            final var properties = new org.apache.pdfbox.cos.COSDictionary();
                            properties.setInt(COSName.MCID, id);
                            stream.beginMarkedContent(COSName.getPDFName("Span"), PDPropertyList.create(properties));
                            stream.beginText();
                            stream.setFont(font, 12);
                            stream.newLineAtOffset(30 + column * 130, 260 - row * 40);
                            stream.showText(labels.get(id));
                            stream.endText();
                            stream.endMarkedContent();
                        }
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Applies actual PDFBox standard encryption without modifying source content or structure.
     * @param plain original owned PDF
     * @param user explicit user password
     * @param extraction whether user permissions allow extraction
     * @param bits AES key length128 or256
     * @return encrypted bytes
     * @throws Exception if document opening or encryption fails
     */
    private static byte[] encrypt(final byte[] plain, final String user, final boolean extraction, final int bits)
            throws Exception {
        try (final var document = Loader.loadPDF(plain); final var output = new ByteArrayOutputStream()) {
            final var permissions = new AccessPermission();
            permissions.setCanExtractContent(extraction);
            final var policy = new StandardProtectionPolicy("owned-owner-password", user, permissions);
            policy.setEncryptionKeyLength(bits);
            policy.setPreferAES(true);
            document.protect(policy);
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Supplies a complete owned2x2 pixel grid over the known native or scanned source.
     * @return fixed geometry with explicit header evidence
     */
    private static PdfTableLayoutProvider.Table layout() {
        final var cells = new java.util.ArrayList<PdfTableLayoutProvider.Anchor>();
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 2; column++) {
                cells.add(new PdfTableLayoutProvider.Anchor(row, column, 1, 1,
                        new PdfTableLayoutProvider.Box(
                                20 + column * 130, 20 + row * 45, 150 + column * 130, 65 + row * 45)));
            }
        }
        return new PdfTableLayoutProvider.Table(
                2, 2, new PdfTableLayoutProvider.Box(20, 20, 280, 110), cells, "owned-grid", 1);
    }

    /**
     * Configures the selected public PDF strategy through normal source routing.
     * @param strategy selected implementation behind its public interface
     * @return reusable document processor
     */
    private static DocWeave processor(final PdfPartitioner strategy) {
        return DocWeave.create(PartitionerRegistry.of(List.of(strategy)));
    }

    /**
     * Reads ordinary public output for the named owned document.
     * @param strategy selected PDF service
     * @param bytes original document bytes
     * @param filename source metadata name
     * @return immutable public elements
     * @throws IOException if extraction fails
     */
    private static List<Element> elements(final PdfPartitioner strategy, final byte[] bytes, final String filename)
            throws IOException {
        return processor(strategy).partition(DocumentSource.bytes(bytes, filename)).elements();
    }

    /**
     * Requires every transferred character to have been cleared.
     * @param value previously transferred owned credential
     */
    private static void wiped(final char[] value) {
        assertNotNull(value);
        assertArrayEquals(new char[value.length], value);
    }

    /**
     * Checks an existing typed processing category without accepting a partial result.
     * @param expected required failure category
     * @param action actual public operation
     */
    private static void assertCode(
            final PartitionException.Code expected, final org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(PartitionException.class, action).code());
    }
}
