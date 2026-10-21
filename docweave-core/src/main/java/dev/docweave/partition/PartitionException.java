package dev.docweave.partition;

import java.io.IOException;

/**
 * Typed processing failure distinguishable from ordinary filesystem I/O errors.
 * No partial result is returned after a limit/decoding failure. In batches this exception is the
 * cause of an {@link java.util.concurrent.ExecutionException}. Messages contain diagnostics rather
 * than document text; use {@link #code()} for programmatic handling.
 */
public final class PartitionException extends IOException {
    /** Stable failure categories for native document processing. */
    public enum Code {
        /** Binary signature, unsupported extension or binary control characters. */ UNSUPPORTED_FORMAT,
        /** Input footprint exceeds maxInputBytes. */ INPUT_TOO_LARGE,
        /** Unsupported charset, conflicting BOM, malformed bytes or invalid Unicode. */ INVALID_ENCODING,
        /** Semantic-element count would exceed maxElements. */ ELEMENT_LIMIT,
        /** Body DOM nesting exceeds maxHtmlDepth. */ HTML_DEPTH_LIMIT,
        /** XML or quoted delimited syntax, or required column consistency, is invalid. */ MALFORMED_DOCUMENT,
        /** Table geometry/text or nested-table preservation budget is exceeded. */ TABLE_LIMIT,
        /** Parsed CommonMark AST exceeds its configured depth. */ MARKDOWN_DEPTH_LIMIT,
        /** XML nesting exceeds the configured bound. */ XML_DEPTH_LIMIT,
        /** XML element-node count exceeds the configured bound. */ XML_NODE_LIMIT,
        /** XML includes forbidden DTD or external entity declarations. */ UNSAFE_XML,
        /** DOCX expansion, entry, XML-node or XML-depth budget is exceeded. */ ARCHIVE_LIMIT,
        /** Embedded image pixels or encoded payload bytes exceed their configured budget. */ IMAGE_LIMIT,
        /** Native PDF paragraph or reading-order traversal exceeds its per-page work ceiling. */ LAYOUT_LIMIT,
        /** PDF OCR rendering or returned-output budget is exceeded. */ OCR_LIMIT,
        /** Selected OCR provider exceeded its admission or processing deadline. */ OCR_TIMEOUT,
        /** Selected OCR provider failed or returned invalid output. */ OCR_FAILURE,
        /** Selected adapter cannot honor an explicitly requested extraction policy. */ UNSUPPORTED_OPTION
    }

    /**
     * Retains the processing category when the exception is serialized.
     * @serial Machine-readable partition failure category.
     */
    private final Code code;

    /**
     * Creates a failure without an underlying exception.
     * @param code failure category
     * @param message diagnostic description
     */
    public PartitionException(final Code code, final String message) {
        super(message);
        this.code = code;
    }

    /**
     * Creates a failure retaining its original decoding/library cause.
     * @param code failure category
     * @param message diagnostic description
     * @param cause original exception, or null
     */
    public PartitionException(final Code code, final String message, final Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * Returns the machine-readable failure category.
     * @return processing-failure category
     */
    public Code code() {
        return code;
    }
}
