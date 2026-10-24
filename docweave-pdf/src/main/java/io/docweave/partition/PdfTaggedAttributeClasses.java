package io.docweave.partition;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Bounded resolution of revision-zero source attribute classes without interpreting their styling values. */
final class PdfTaggedAttributeClasses {
    /** Maximum named classes on one structure element. */
    private static final int MAX_CLASSES = 64;
    /** Maximum attribute dictionaries in one class definition. */
    private static final int MAX_ATTRIBUTES = 128;

    /**
     * Complete class-derived cell spans, with an explicit unsupported signal for whole-table fallback.
     * @param supported whether every source class and span was supported
     * @param rows positive resolved row span, default one
     * @param columns positive resolved column span, default one
     */
    record Spans(boolean supported, int rows, int columns) {}

    /** Prevents construction of the source-only attribute utility. */
    private PdfTaggedAttributeClasses() {
    }

    /**
     * Validates presentation-only classes for table/row nodes without allowing cell topology attributes.
     * @param node source table or row dictionary
     * @param classes original root ClassMap, null when missing or malformed
     * @param work shared document structural budget
     * @return true when absent or wholly span-free; false requests whole-table fallback
     * @throws IOException if cancelled or over the shared structural work budget
     */
    static boolean supported(final COSDictionary node, final COSDictionary classes, final PdfTableWork work)
            throws IOException {
        final var attributes = attributes(node, classes, work);
        if (attributes == null) {
            return false;
        }
        for (final var attribute : attributes) {
            work.step();
            if (attribute.containsKey(COSName.getPDFName("RowSpan"))
                    || attribute.containsKey(COSName.getPDFName("ColSpan"))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves positive standard Table-owner cell spans in original class/attribute array order.
     * Later values override earlier ones. Direct A attributes are resolved separately by the caller
     * and take precedence. Namespaced spans, malformed values and historical classes remain unsupported;
     * no attribute inheritance, styles, source text or geometry are inferred.
     * @param node source cell dictionary
     * @param classes original root ClassMap
     * @param work shared document structural budget
     * @return complete supported spans or an explicit whole-table fallback signal
     * @throws IOException if cancelled or over the shared structural work budget
     */
    static Spans spans(final COSDictionary node, final COSDictionary classes, final PdfTableWork work)
            throws IOException {
        final var attributes = attributes(node, classes, work);
        if (attributes == null) {
            return new Spans(false, 1, 1);
        }
        int rows = 1;
        int columns = 1;
        for (final var attribute : attributes) {
            work.step();
            final int row = span(attribute, "RowSpan");
            final int column = span(attribute, "ColSpan");
            if (row < 0 || column < 0) {
                return new Spans(false, 1, 1);
            }
            if (row > 0) {
                rows = row;
            }
            if (column > 0) {
                columns = column;
            }
        }
        return new Spans(true, rows, columns);
    }

    /**
     * Checks one standard span without coercing strings, floats, nulls, namespaces or invalid ranges.
     * @param attribute original class attribute object
     * @param name standard cell span key
     * @return zero when absent, positive source integer, or minus one for unsupported data
     */
    private static int span(final COSDictionary attribute, final String name) {
        final var key = COSName.getPDFName(name);
        if (!attribute.containsKey(key)) {
            return 0;
        }
        final var value = attribute.getDictionaryObject(key);
        if (!"Table".equals(attribute.getNameAsString(COSName.O)) || attribute.containsKey(COSName.getPDFName("NS"))
                || !(value instanceof final COSInteger integer) || integer.longValue() < 1
                || integer.longValue() > Integer.MAX_VALUE) {
            return -1;
        }
        return integer.intValue();
    }

    /**
     * Resolves bounded original class definitions without following aliases or nested extension properties.
     * @param node source element
     * @param classes document map
     * @param work shared structural budget
     * @return immutable original attribute sequence, empty when absent, or null when unsupported
     * @throws IOException if cancelled or over budget
     */
    private static List<COSDictionary> attributes(
            final COSDictionary node, final COSDictionary classes, final PdfTableWork work) throws IOException {
        if (!node.containsKey(COSName.C)) {
            return List.of();
        }
        final var revision = node.getDictionaryObject(COSName.R);
        if (classes == null
                || revision != null && (!(revision instanceof COSInteger integer) || integer.longValue() != 0)) {
            return null;
        }
        final var raw = node.getDictionaryObject(COSName.C);
        final var attributes = new ArrayList<COSDictionary>();
        if (raw instanceof final COSName name) {
            return definition(name, classes, work, attributes) ? List.copyOf(attributes) : null;
        }
        if (!(raw instanceof final COSArray array) || array.size() < 1 || array.size() > MAX_CLASSES) {
            return null;
        }
        final var names = new HashSet<COSName>();
        for (int index = 0; index < array.size(); index++) {
            work.step();
            if (!(array.getObject(index) instanceof final COSName name) || !names.add(name)
                    || !definition(name, classes, work, attributes)) {
                return null;
            }
        }
        return List.copyOf(attributes);
    }

    /**
     * Appends one source class in original array order, validating every attribute owner.
     * @param name original class name
     * @param classes original map
     * @param work shared structural budget
     * @param attributes call-confined prospective original attribute sequence
     * @return whether the whole class definition is supported
     * @throws IOException if cancelled or over budget
     */
    private static boolean definition(final COSName name, final COSDictionary classes, final PdfTableWork work,
            final List<COSDictionary> attributes) throws IOException {
        work.step();
        final var value = classes.getDictionaryObject(name);
        if (value instanceof final COSDictionary dictionary) {
            return attribute(dictionary, work, attributes);
        }
        if (!(value instanceof final COSArray array) || array.size() < 1 || array.size() > MAX_ATTRIBUTES) {
            return false;
        }
        for (int index = 0; index < array.size(); index++) {
            work.step();
            if (!(array.getObject(index) instanceof final COSDictionary dictionary)
                    || !attribute(dictionary, work, attributes)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Requires an explicit owner before retaining an attribute object; nested style values are not traversed.
     * @param dictionary original attribute dictionary
     * @param work shared structural budget
     * @param attributes prospective sequence discarded on any validation failure
     * @return true when the owner is a source name
     * @throws IOException if cancelled or over budget
     */
    private static boolean attribute(final COSDictionary dictionary, final PdfTableWork work,
            final List<COSDictionary> attributes) throws IOException {
        work.step();
        if (!(dictionary.getDictionaryObject(COSName.O) instanceof COSName)) {
            return false;
        }
        attributes.add(dictionary);
        return true;
    }
}
