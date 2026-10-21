/**
 * Synchronous element-aware chunking using Unicode code-point budgets.
 * Basic chunking groups sequential elements; title-aware chunking stages section/page boundaries
 * before optionally combining small sections. Tables are isolated by default; opt-out permits mixed composites with
 * source geometry in provenance. CPU work stays on the calling thread; structured tables preserve cell/span ownership
 * through compact HTML and source-coordinate metadata, with explicit header repetition and pass-through policies.
 * Document-level concurrency belongs in {@code docweave-batch}.
 * Optional contextual enrichment runs after chunking with a caller-selected context provider.
 * It keeps generated text separate from source chunks and bounds per-call model concurrency,
 * whole-invocation deadlines and input/output sizes using an explicit virtual-thread window.
 */
package dev.docweave.chunking;
