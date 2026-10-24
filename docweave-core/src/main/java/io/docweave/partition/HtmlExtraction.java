package io.docweave.partition;

import java.io.IOException;

/**
 * Optional HTML extraction service used by Markdown's rendered-DOM mode.
 * The HTML module supplies this service through {@link java.util.ServiceLoader}; the Markdown
 * module does not depend on that module at compile time or transitively. Implementations must
 * be thread-safe and keep traversal state within each call. No linked resources may be fetched.
 */
public interface HtmlExtraction {
    /**
     * Declares whether this service honors nested-table preservation and its document-wide budgets.
     * The default is false so existing providers cannot silently flatten an explicit preservation request.
     * Callers must check this capability before delegating PRESERVE_METADATA. Implementations advertising
     * true must validate the policy at their own entry point and keep all capture state document-confined.
     * @return true only when bounded nested-table descriptor extraction is implemented
     */
    default boolean supportsNestedTablePreservation() {
        return false;
    }

    /**
     * Extracts already-rendered markup using the supplied context's emission and safety policies.
     * @param context document-confined original extraction context
     * @param html complete rendered HTML to traverse instead of the original source text
     * @param tableLimits per-table resource budgets
     * @throws IOException if markup processing, limits or interruption fails
     */
    void partition(final PartitionContext context, final String html, final TableLimits tableLimits) throws IOException;
}
