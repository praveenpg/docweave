/**
 * Public entry point for DocWeave's framework-independent Java 21+ document pipeline.
 * Start with {@link io.docweave.DocWeave}; pass its ordered elements to
 * {@code Chunker} from the optional chunking module and serialize with {@code ElementJson} from the optional JSON
 * module. Installed format modules cover TXT, HTML, Markdown, CSV, TSV, XML and DOCX; core alone installs none. Network
 * fetching, rendered-page analysis, and model inference are separate future capabilities.
 */
package io.docweave;
