package io.docweave.partition;

import io.docweave.core.Element;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable output and observed diagnostics for one successful partition call.
 * Element order follows source reading/DOM order. Empty documents yield empty lists; warnings
 * describe nonfatal recovery/flattening. Duration includes loading and parsing. Serialize
 * {@link #elements()} with {@code ElementJson} from the optional JSON module, not this diagnostic envelope.
 * @param elements ordered semantic units
 * @param format detected or explicitly selected format
 * @param encoding decoding charset; Text sources report UTF-8 and binary DOCX reports "binary"
 * @param inputBytes consumed bytes including any BOM
 * @param elapsed nonnegative processing duration
 * @param warnings nonfatal diagnostics
 * @param sourceProvenance safe provenance of a resolved {@link DocumentSource.Remote} input, or null for local
 *        inputs; it never affects elements or their IDs
 */
public record PartitionResult(List<Element> elements, DocumentFormat format, String encoding, int inputBytes,
        Duration elapsed, List<Warning> warnings, SourceProvenance sourceProvenance) {
    /** Nonfatal conditions callers may review. */
    public enum WarningCode {
        /** Selected/content format differs from a supported filename hint. */ FORMAT_MISMATCH,
        /** jsoup reported syntax recovery; only a bounded count is retained. */ HTML_RECOVERED,
        /** Nested table content is cell text rather than a separate table. */ NESTED_TABLE_FLATTENED,
        /** CommonMark raw HTML nodes were retained as literal text rather than DOM content. */ RAW_HTML_PRESERVED,
        /** Inconsistent record widths were retained under an explicitly permissive table policy. */ RAGGED_TABLE,
        /** DOCX content not represented in the supported extraction model was skipped or flattened. */
        DOCX_CONTENT_OMITTED,
        /** PDF native extraction cannot provide OCR or full rendered layout semantics. */
        PDF_EXTRACTION_LIMITATION
    }

    /**
     * One diagnostic without document-content payloads.
     * @param code machine-readable condition
     * @param message human-readable description
     */
    public record Warning(WarningCode code, String message) {
        /**
         * Validates warning components.
         * @param code nonnull condition
         * @param message nonnull description
         * @throws NullPointerException if either component is null
         */
        public Warning {
            Objects.requireNonNull(code);
            Objects.requireNonNull(message);
        }
    }

    /**
     * Snapshots lists and validates observed diagnostics.
     * @param elements ordered output to snapshot
     * @param format actual selected format
     * @param encoding canonical charset name or "binary" for DOCX
     * @param inputBytes nonnegative input footprint
     * @param elapsed nonnegative duration
     * @param warnings diagnostics to snapshot
     * @param sourceProvenance remote provenance, or null for a local input
     * @throws NullPointerException if required values/list entries are null
     * @throws IllegalArgumentException if byte count or elapsed time is negative
     */
    public PartitionResult {
        elements = List.copyOf(elements);
        warnings = List.copyOf(warnings);
        Objects.requireNonNull(format);
        Objects.requireNonNull(encoding);
        Objects.requireNonNull(elapsed);
        if (inputBytes < 0 || elapsed.isNegative()) {
            throw new IllegalArgumentException("Negative diagnostic value");
        }
    }

    /**
     * Creates a result for a local input, preserving the constructor that existed before remote sources.
     * @param elements ordered output to snapshot
     * @param format actual selected format
     * @param encoding canonical charset name or "binary" for DOCX
     * @param inputBytes nonnegative input footprint
     * @param elapsed nonnegative duration
     * @param warnings diagnostics to snapshot
     * @throws NullPointerException if required values/list entries are null
     * @throws IllegalArgumentException if byte count or elapsed time is negative
     */
    public PartitionResult(final List<Element> elements, final DocumentFormat format, final String encoding,
            final int inputBytes, final Duration elapsed, final List<Warning> warnings) {
        this(elements, format, encoding, inputBytes, elapsed, warnings, null);
    }
}
