package dev.docweave.partition;

/** Formats supported by native partitioning; support does not imply rendered layout extraction. */
public enum DocumentFormat {
    /** Plain text, including text/log filename hints. */ TXT("text/plain"),
    /** HTML documents or fragments interpreted as a DOM. */ HTML("text/html"),
    /** CommonMark documents with optional GitHub-style tables. */ MARKDOWN("text/markdown"),
    /** Comma-delimited quoted records. */ CSV("text/csv"),
    /** Tab-delimited quoted records. */ TSV("text/tab-separated-values"),
    /** Well-formed XML extracted without DTDs or external resources. */ XML("application/xml"),
    /** Portable Document Format; extraction capabilities depend on the installed PDF strategy. */
    PDF("application/pdf"),
    /** OOXML Word documents; binary DOC and macro-enabled DOCM are excluded. */
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    /** Canonical wire MIME type string associated with this document format. */
    private final String mimeType;

    /**
     * Creates a document format constant with the given canonical MIME type.
     *
     * @param mimeType canonical wire MIME type
     */
    DocumentFormat(final String mimeType) {
        this.mimeType = mimeType;
    }

    /**
     * Returns the canonical wire MIME type.
     * @return canonical MIME value written into element {@code filetype} metadata
     */
    public String mimeType() {
        return mimeType;
    }
}
