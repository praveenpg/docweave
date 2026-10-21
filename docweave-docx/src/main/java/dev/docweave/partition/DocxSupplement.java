package dev.docweave.partition;

import org.apache.poi.xwpf.usermodel.IBody;
import org.w3c.dom.Node;

/**
 * Document-local supplemental XML anchored to normalized containing-element text.
 *
 * @param kind supplement kind ({@code "image"} or {@code "textbox"})
 * @param node supplemental XML DOM node
 * @param owner owning Word body part
 * @param offset code-point anchor offset in the containing element text
 */
record DocxSupplement(String kind, Node node, IBody owner, int offset) {
    /**
     * Returns a copy of this supplement with its anchor offset shifted by the specified amount.
     *
     * @param amount code-point offset delta
     * @return shifted supplement descriptor
     */
    DocxSupplement shifted(final int amount) {
        return new DocxSupplement(kind, node, owner, offset + amount);
    }
}
