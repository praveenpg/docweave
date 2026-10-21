/**
 * Document inputs, immutable options, diagnostics and native text-format partitioning.
 * Byte sources use strict decoding; format detection combines content signatures with filename hints.
 * Input-size, element-count and HTML-depth limits bound supported processing. No URLs are fetched,
 * CSS is not rendered, and text classification uses documented deterministic heuristics rather than NLP.
 * Parser strategies implement {@link dev.docweave.partition.Partitioner} and are selected through an
 * injectable {@link dev.docweave.partition.PartitionerRegistry}. A document-confined
 * {@link dev.docweave.partition.PartitionContext} preserves shared emission invariants.
 * TXT separates grouping from Unicode size policies; automatic grouping and minimum prose merges
 * are opt-in. TXT line discovery is lazy; shared Unicode scans and TXT sizing poll interruption
 * during long runs and preserve the thread flag through {@link java.io.InterruptedIOException}.
 * Individual third-party parser, regex, codec and bulk-copy operations remain indivisible; these
 * checkpoints do not guarantee a cancellation latency or introduce synchronous deadlines.
 * Markdown uses a CommonMark AST with configurable table/raw-HTML policies; CSV and TSV use
 * quoted-record parsing with explicit header policies and structured-table budgets.
 * XML streams namespace-aware text runs with DTD/external access forbidden and depth/node budgets.
 * DOCX composes bounded archive verification with per-document POI traversal, without exposing POI
 * types or changing global ZIP settings. Binary contexts supply defensive source-byte snapshots.
 */
package dev.docweave.partition;
