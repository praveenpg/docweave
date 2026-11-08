package io.docweave.partition;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #56 / docweave-studio #258: documents the library opens keep fonts cached but never retain image XObjects, so a
 * worker document reused across pages does not keep every page's decoded raster reachable.
 */
class PdfResourceCacheTest {
    private static final int PAGES = 4;

    @Test
    void openedDocumentsUseTheImageFreeCache() throws Exception {
        try (PDDocument document = PdfDocumentLoader.load(scan(), "scan.pdf", null)) {
            assertInstanceOf(PdfResourceCache.class, document.getResourceCache());
        }
        final var worker = new PdfPageDocuments(ByteBuffer.wrap(scan()), "scan.pdf", null);
        try (worker) {
            assertInstanceOf(PdfResourceCache.class, worker.page().getResourceCache());
            assertInstanceOf(PdfResourceCache.class, worker.open().getResourceCache());
        }
    }

    @Test
    void imagesAreResolvedFreshWhileFontsStayCached() throws Exception {
        try (PDDocument document = PdfDocumentLoader.load(scan(), "scan.pdf", null)) {
            for (final PDPage page : document.getPages()) {
                final var resources = page.getResources();
                final COSName image = resources.getXObjectNames().iterator().next();
                if (!(resources.getXObject(image) instanceof PDImageXObject)) {
                    continue; // drawn through a form; covered by the worker test
                }
                final var first = (PDImageXObject) resources.getXObject(image);
                final var second = (PDImageXObject) resources.getXObject(image);
                assertNotSame(first, second, "an image XObject must not be cached across lookups");
                assertNull(document.getResourceCache().getXObject(indirect(resources, COSName.XOBJECT, image)));
                final COSName font = resources.getFontNames().iterator().next();
                assertSame(resources.getFont(font), resources.getFont(font), "fonts stay cached");
            }
        }
    }

    @Test
    void aReusedWorkerDocumentRendersEveryPageWithoutCachingAnyImage() throws Exception {
        // The OCR path: one worker document reused for every page, each page rendered as for recognition, with
        // half of the pages drawing their image through a form XObject.
        final var worker = new PdfPageDocuments(ByteBuffer.wrap(scan()), "scan.pdf", null);
        try (worker) {
            final PDDocument document = worker.page();
            assertSame(document, worker.page(), "the worker reuses its document");
            for (int index = 0; index < PAGES; index++) {
                final var image = new InterruptiblePdfRenderer(document).renderImageWithDPI(index, 72, ImageType.RGB);
                assertTrue(image.getWidth() > 0);
            }
            for (final PDPage page : document.getPages()) {
                final var resources = page.getResources();
                for (final COSName name : resources.getXObjectNames()) {
                    final var xobject = resources.getXObject(name);
                    final COSObject reference = indirect(resources, COSName.XOBJECT, name);
                    if (xobject instanceof final PDFormXObject form) {
                        final var inner = form.getResources();
                        final COSName nested = inner.getXObjectNames().iterator().next();
                        assertInstanceOf(PDImageXObject.class, inner.getXObject(nested));
                        assertNull(document.getResourceCache().getXObject(indirect(inner, COSName.XOBJECT, nested)),
                                "an image inside a form must not be cached");
                    } else {
                        assertNull(document.getResourceCache().getXObject(reference), "a page image must not be cached");
                    }
                }
            }
        }
    }

    @Test
    void pageCropsReadByteSourcesInPlace() throws Exception {
        final var renderer = new DefaultPdfPageCropRenderer(PdfPageCropOptions.defaults(), null);
        final var copying = (DocumentSource.Bytes) DocumentSource.bytes(scan(), "scan.pdf");
        assertSame(copying.snapshot(), renderer.read(copying).bytes());
        final var owned = (DocumentSource.OwnedBytes) DocumentSource.ownedBytes(scan(), "scan.pdf");
        assertSame(owned.snapshot(), renderer.read(owned).bytes());
    }

    @Test
    void anApplicationConfiguredCacheIsLeftInPlace() throws Exception {
        final var previous = org.apache.pdfbox.pdmodel.ResourceCacheFactory.getResourceCacheCreateFunction();
        try {
            org.apache.pdfbox.pdmodel.ResourceCacheFactory.setResourceCacheCreateFunction(() -> null);
            try (PDDocument document = PdfDocumentLoader.load(scan(), "scan.pdf", null)) {
                assertNull(document.getResourceCache(), "an application that disabled caching keeps it disabled");
            }
        } finally {
            org.apache.pdfbox.pdmodel.ResourceCacheFactory.setResourceCacheCreateFunction(previous);
        }
    }

    @Test
    void aDecodedRasterIsCollectableOnceItsPageIsDone() throws Exception {
        Assumptions.assumeFalse(ManagementFactory.getRuntimeMXBean().getInputArguments().contains(
                "-XX:+DisableExplicitGC"), "needs explicit collections");
        try (PDDocument document = PdfDocumentLoader.load(scan(), "scan.pdf", null)) {
            final var resources = document.getPage(0).getResources();
            final COSName name = resources.getXObjectNames().iterator().next();
            final WeakReference<BufferedImage> decoded =
                    new WeakReference<>(((PDImageXObject) resources.getXObject(name)).getImage());
            // The document stays open, as a worker's does for the whole call. Nothing it owns may keep the raster.
            for (int attempt = 0; attempt < 50 && decoded.get() != null; attempt++) {
                System.gc();
                Thread.sleep(20);
            }
            assertNull(decoded.get(), "the open document must not retain a decoded page raster");
        }
    }

    private static COSObject indirect(final org.apache.pdfbox.pdmodel.PDResources resources, final COSName kind,
            final COSName name) {
        final COSBase item = resources.getCOSObject().getCOSDictionary(kind).getItem(name);
        return assertInstanceOf(COSObject.class, item, "test images are indirect objects");
    }

    /**
     * A small scan-like PDF: each page holds one full-page gray image and one line of text; odd pages draw the image
     * through a form XObject.
     */
    private static byte[] scan() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int index = 0; index < PAGES; index++) {
                final PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                final var raster = new BufferedImage(170, 220, BufferedImage.TYPE_BYTE_GRAY);
                raster.getRaster().setSample(index, index, 0, 255);
                final var image = LosslessFactory.createFromImage(document, raster);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    if (index % 2 == 1) {
                        final var form = new PDFormXObject(document);
                        form.setBBox(page.getMediaBox());
                        form.setResources(new org.apache.pdfbox.pdmodel.PDResources());
                        try (var inner = new org.apache.pdfbox.pdmodel.PDFormContentStream(form)) {
                            inner.drawImage(image, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                        }
                        content.drawForm(form);
                    } else {
                        content.drawImage(image, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                    }
                    content.beginText();
                    content.setFont(font, 12);
                    content.newLineAtOffset(72, 72);
                    content.showText("Page " + (index + 1));
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
