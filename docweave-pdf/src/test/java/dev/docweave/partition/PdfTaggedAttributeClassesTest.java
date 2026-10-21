package dev.docweave.partition;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** Source class identity, topology exclusion, malformed-input and bounded processing controls. */
class PdfTaggedAttributeClassesTest {
    /** Creates an isolated set of original structure dictionaries. */
    PdfTaggedAttributeClassesTest() {
    }

    /**
     * Accepts named standard/extension presentation attributes without interpreting their styling values.
     * @throws Exception if shared source validation is interrupted
     */
    @Test
    void namedOwnersAndDefinitionArraysPreserveDirectTableSemantics() throws Exception {
        final var classes = new COSDictionary();
        final var definitions = new COSArray();
        definitions.add(attribute("Layout"));
        definitions.add(attribute("ProducerPresentation"));
        classes.setItem("formatting", definitions);
        assertTrue(PdfTaggedAttributeClasses.supported(node("formatting"), classes, new PdfTableWork()));
        classes.setItem("tableFormatting", attribute("Table"));
        final var names = new COSArray();
        names.add(COSName.getPDFName("formatting"));
        names.add(COSName.getPDFName("tableFormatting"));
        final var node = new COSDictionary();
        node.setItem(COSName.C, names);
        assertTrue(PdfTaggedAttributeClasses.supported(node, classes, new PdfTableWork()));
        assertTrue(PdfTaggedAttributeClasses.supported(new COSDictionary(), null, new PdfTableWork()));
    }

    /**
     * Retains whole-table fallback for unresolved, topology-bearing, revisioned and malformed class definitions.
     * @throws Exception if shared source validation is interrupted
     */
    @Test
    void unresolvedTopologyRevisionAliasesAndMalformedDefinitionsStayUnsupported() throws Exception {
        final var classes = new COSDictionary();
        final var node = node("formatting");
        assertFalse(PdfTaggedAttributeClasses.supported(node, null, new PdfTableWork()));
        assertFalse(PdfTaggedAttributeClasses.supported(node, classes, new PdfTableWork()));
        classes.setItem("formatting", COSName.getPDFName("alias"));
        assertFalse(PdfTaggedAttributeClasses.supported(node, classes, new PdfTableWork()));
        classes.setItem("formatting", new COSDictionary());
        assertFalse(PdfTaggedAttributeClasses.supported(node, classes, new PdfTableWork()));
        for (final String key : new String[] {"RowSpan", "ColSpan"}) {
            final var definition = attribute("Table");
            definition.setInt(key, 1);
            classes.setItem("formatting", definition);
            assertFalse(PdfTaggedAttributeClasses.supported(node, classes, new PdfTableWork()));
        }
        classes.setItem("formatting", attribute("Layout"));
        node.setInt(COSName.R, 1);
        assertFalse(PdfTaggedAttributeClasses.supported(node, classes, new PdfTableWork()));
        node.setString(COSName.R, "invalid");
        assertFalse(PdfTaggedAttributeClasses.supported(node, classes, new PdfTableWork()));
        node.removeItem(COSName.R);
        final var names = new COSArray();
        names.add(COSName.getPDFName("formatting"));
        names.add(COSName.getPDFName("formatting"));
        node.setItem(COSName.C, names);
        assertFalse(PdfTaggedAttributeClasses.supported(node, classes, new PdfTableWork()));
        names.set(1, COSInteger.ZERO);
        assertFalse(PdfTaggedAttributeClasses.supported(node, classes, new PdfTableWork()));
    }

    /**
     * Rejects excessive class lists/definitions and cooperates with caller cancellation.
     * @throws Exception if constructing bounded source controls fails
     */
    @Test
    void boundedListsAndInterruptionCannotBypassSharedSourceWork() throws Exception {
        final var classes = new COSDictionary();
        classes.setItem("formatting", attribute("Layout"));
        final var names = new COSArray();
        for (int index = 0; index < 65; index++) {
            names.add(COSName.getPDFName("class" + index));
        }
        final var node = new COSDictionary();
        node.setItem(COSName.C, names);
        assertFalse(PdfTaggedAttributeClasses.supported(node, classes, new PdfTableWork()));
        final var definitions = new COSArray();
        for (int index = 0; index < 129; index++) {
            definitions.add(attribute("Layout"));
        }
        classes.setItem("formatting", definitions);
        assertFalse(PdfTaggedAttributeClasses.supported(node("formatting"), classes, new PdfTableWork()));
        classes.setItem("formatting", attribute("Layout"));
        try {
            Thread.currentThread().interrupt();
            assertThrows(IOException.class,
                    () -> PdfTaggedAttributeClasses.supported(node("formatting"), classes, new PdfTableWork()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * Resolves later class and attribute-array values while preserving default spans for missing keys.
     * @throws Exception if shared source validation is interrupted
     */
    @Test
    void cellSpansFollowOriginalClassAndAttributeArrayOrder() throws Exception {
        final var classes = new COSDictionary();
        final var earlier = attribute("Table");
        earlier.setInt("RowSpan", 2);
        classes.setItem("earlier", earlier);
        final var later = attribute("Table");
        later.setInt("ColSpan", 3);
        later.setInt("RowSpan", 4);
        final var definitions = new COSArray();
        definitions.add(later);
        final var last = attribute("Table");
        last.setInt("RowSpan", 5);
        definitions.add(last);
        classes.setItem("later", definitions);
        final var names = new COSArray();
        names.add(COSName.getPDFName("earlier"));
        names.add(COSName.getPDFName("later"));
        final var node = new COSDictionary();
        node.setItem(COSName.C, names);
        assertEquals(new PdfTaggedAttributeClasses.Spans(true, 5, 3),
                PdfTaggedAttributeClasses.spans(node, classes, new PdfTableWork()));
        assertFalse(PdfTaggedAttributeClasses.supported(node, classes, new PdfTableWork()));
        assertEquals(new PdfTaggedAttributeClasses.Spans(true, 1, 1),
                PdfTaggedAttributeClasses.spans(new COSDictionary(), null, new PdfTableWork()));
    }

    /**
     * Rejects invalid class spans before applying any incomplete row/column information.
     * @throws Exception if shared source validation is interrupted
     */
    @Test
    void invalidRangesOwnersTypesNamespacesAndClassesRejectCompleteCellSpans() throws Exception {
        final var classes = new COSDictionary();
        final var node = node("spans");
        for (final String variant : new String[] {"zero", "negative", "large", "string", "owner", "namespace"}) {
            final var definition = attribute("Table");
            definition.setInt("ColSpan", 2);
            definition.setInt("RowSpan", 1);
            switch (variant) {
            case "zero" -> definition.setInt("RowSpan", 0);
            case "negative" -> definition.setInt("RowSpan", -1);
            case "large" -> definition.setLong("RowSpan", (long) Integer.MAX_VALUE + 1);
            case "string" -> definition.setString("RowSpan", "2");
            case "owner" -> definition.setName(COSName.O, "Layout");
            case "namespace" -> definition.setItem(COSName.getPDFName("NS"), new COSDictionary());
            default -> throw new AssertionError(variant);
            }
            classes.setItem("spans", definition);
            assertFalse(PdfTaggedAttributeClasses.spans(node, classes, new PdfTableWork()).supported(), variant);
        }
        assertFalse(PdfTaggedAttributeClasses.spans(node, null, new PdfTableWork()).supported());
    }

    /**
     * Creates one original class reference, without an inferred definition or span.
     * @param name original class name
     * @return source element dictionary
     */
    private static COSDictionary node(final String name) {
        final var node = new COSDictionary();
        node.setName(COSName.C, name);
        return node;
    }

    /**
     * Supplies a presentation attribute with an explicit owner.
     * @param owner standard or extension attribute owner
     * @return source dictionary with a presentation-only value
     */
    private static COSDictionary attribute(final String owner) {
        final var attribute = new COSDictionary();
        attribute.setName(COSName.O, owner);
        attribute.setInt("Padding", 2);
        return attribute;
    }
}
