package io.docweave.core;

/**
 * Supported semantic categories. Enum names intentionally match compatibility JSON wire values.
 * This is a supported subset, not the complete upstream taxonomy. Unknown types are rejected by
 * the JSON codec rather than silently reclassified.
 */
public enum ElementType {
    /** Heading or title, possibly assigned by text heuristics. */ Title,
    /** Paragraph or narrative content. */ NarrativeText,
    /** Text with no supported semantic classification. */ UncategorizedText,
    /** One list item or a paragraph within a list item. */ ListItem,
    /** Atomic table, optionally with structured HTML metadata. */ Table,
    /** Image reference; current TXT/HTML parsers do not extract image assets. */ Image,
    /** Content of an HTML header or a source-provided document header. */ Header,
    /** Content of an HTML footer or a source-provided document footer. */ Footer,
    /** Figure or table caption. */ FigureCaption,
    /** Structural page marker, typically with empty text. */ PageBreak,
    /** Chunk combining one or more ordinary elements. */ CompositeElement,
    /** Fragment of a split table with reconstruction metadata. */ TableChunk,
    /** Code whose whitespace is preserved during chunking. */ CodeSnippet
}
