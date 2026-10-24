package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Nested forms must not silently discard source images at the native traversal boundary. */
class PdfFormDepthTest {
    /** Creates isolated native form-depth controls. */
    PdfFormDepthTest() {
    }

    /**
     * The existing fifty-form boundary still permits an image inside its deepest accepted form.
     * @throws Exception if owned source authoring or public extraction fails
     */
    @Test
    void fiftiethFormRetainsItsOriginalImage() throws Exception {
        for (final boolean transparent : new boolean[] {false, true}) {
            final var result =
                    DocWeave.create().partition(DocumentSource.bytes(source(50, false, transparent), "forms-50.pdf"));
            assertEquals(1, result.elements().stream().filter(element -> element.type() == ElementType.Image).count());
        }
    }

    /**
     * A valid finite but unsupported depth must fail explicitly rather than return success with its image omitted.
     * @throws Exception if owned source authoring fails
     */
    @Test
    void fiftyFirstFormProducesTypedFailureInsteadOfMissingImage() throws Exception {
        for (final boolean transparent : new boolean[] {false, true}) {
            final var bytes = source(51, false, transparent);
            final var failure = assertThrows(PartitionException.class,
                    () -> DocWeave.create().partition(DocumentSource.bytes(bytes, "forms-51.pdf")));
            assertEquals(PartitionException.Code.INPUT_TOO_LARGE, failure.code());
            assertTrue(failure.getMessage().contains("form nesting"));
        }
    }

    /**
     * A resource cycle reaches the same typed boundary, without returning a successful partial extraction.
     * @throws Exception if owned cyclic source authoring fails
     */
    @Test
    void recursiveFormProducesTypedFailure() throws Exception {
        for (final boolean transparent : new boolean[] {false, true}) {
            final var bytes = source(1, true, transparent);
            final var failure = assertThrows(PartitionException.class,
                    () -> DocWeave.create().partition(DocumentSource.bytes(bytes, "forms-cycle.pdf")));
            assertEquals(PartitionException.Code.INPUT_TOO_LARGE, failure.code());
            assertTrue(failure.getMessage().contains("form nesting"));
        }
    }

    /**
     * Authors a complete native resource graph whose only image is in the leaf form.
     * @param depth actual count of nested form invocations before the leaf image
     * @param cycle whether the outer form invokes itself instead of a finite child
     * @param transparent whether every form uses the transparency-group callback
     * @return complete source bytes
     * @throws Exception if source encoding fails
     */
    private static byte[] source(final int depth, final boolean cycle, final boolean transparent) throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(100, 100));
            document.addPage(page);
            var root = form(document, transparent);
            if (cycle) {
                try (final var content = new PDFormContentStream(root)) {
                    content.drawForm(root);
                }
            } else {
                final var pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
                final var image = LosslessFactory.createFromImage(document, pixels);
                pixels.flush();
                try (final var content = new PDFormContentStream(root)) {
                    content.drawImage(image, 10, 10, 20, 20);
                }
                for (int index = 1; index < depth; index++) {
                    final var parent = form(document, transparent);
                    try (final var content = new PDFormContentStream(parent)) {
                        content.drawForm(root);
                    }
                    root = parent;
                }
            }
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawForm(root);
            }
            final var output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Creates a source form with a valid local resource and geometry scope.
     * @param document owned resource container
     * @param transparent whether the form is a source transparency group
     * @return complete source form shell
     */
    private static PDFormXObject form(final PDDocument document, final boolean transparent) {
        final PDFormXObject result = transparent ? new PDTransparencyGroup(document) : new PDFormXObject(document);
        result.setResources(new PDResources());
        result.setBBox(new PDRectangle(100, 100));
        if (transparent) {
            final var attributes = new COSDictionary();
            attributes.setItem(COSName.S, COSName.TRANSPARENCY);
            result.getCOSObject().setItem(COSName.GROUP, attributes);
        }
        return result;
    }
}
