# Consolidated pending work

Snapshot: 2026-10-22, runtime baseline `cf83cb2`. This is the index of recorded pending
work, including parked improvements. It consolidates the PDF quality backlog, roadmap,
deferred-functionality audit and latest accepted review. Historical experiments keep their
original scores. An item being recorded here does not make it a PDF completion blocker,
an implementation bug, or authorization to publish/run models/acquire another corpus.

## Parked PDF improvements

| ID | Remaining work | State / concrete next action |
|---|---|---|
| P1 | Paragraph-internal scientific sidebar ties | Retain recent's 6 reversals and next's 1 after the accepted whole-element policy. Check a distinct approach against paragraph-membership preservation; an insertion inside an indivisible paragraph cannot be fixed by whole-element sorting. Do not revive the splitting patch or count the completed row-tie implementation as unfinished. |
| P2 | Broader business-table headers, boundaries and continuation | Investigate a new independently supported structural counterexample. NVIDIA's three classified structural misses are already recovered; remaining reference titles/spacing/header presentation are not automatically extraction bugs. New detector work depends on Q1. |
| P3 | Positive non-NVIDIA trailing-row recovery | Find a source-supported membership signal and an independently labelled transfer case; validate no extra/missing/duplicate rows. Apple/Amazon lack the existing whitespace lattice and upstream also excludes their rows. Optional beyond current parity. |
| P4 | Historical OCR recognition, including the 1835 page number | Seek a correction that recovers the omitted line without worsening full-page recognition under either model. Raw-pixel decoding and magnification had trade-offs. Original scanned-PDF applicability depends on Q2. |
| P5 | Degraded-scan column separation | Re-examine retained geometry once for a distinct mechanism; gap, printed-rule and recurring-alignment approaches are rejected. Test any new mechanism on an independently labelled scan plus all four controls. Unstructured also leaves the 1778 columns unsplit. |
| P6 | Degraded-scan preprocessing | Reopen only with a specific source-supported correction distinct from rejected white-point/sparse, sharpening and magnification combinations. Keep both models, complete transcriptions and extra-output penalties. |
| P7 | Exact isolated source-image appearance / visibility | Optional future design, not a crop-service bug. Define isolated-asset versus page-region output first; never replace source PNGs or remove occurrences merely because a render is blank. |
| P8 | Remaining small-crop renderer disagreement | Parked without a demonstrated defect. Reopen with a reproducible contract/geometry/pixel defect and independent controls, not merely to imitate another renderer's snapping or JPEG loss. |
| P9 | Procurement reference whitespace discrepancy | Evidence/reference reconciliation only. Review the two ampersand-space differences against source and CSV; preserve source spaces and the historical 152/154 result unless independent evidence establishes a bug. |

## Future PDF qualification (not unfinished implementation)

| ID | Concrete deliverable | Prerequisite |
|---|---|---|
| Q1 | Financial-report detector TP/FP/FN study with exhaustive tight boxes, negative pages and producer transfer | Independently labelled business originals; the completed one-shard DocLayNet pilot contains no financial reports and no producer identity. |
| Q2 | Like-input ICC scanned-PDF comparison with pinned Unstructured and both OCR models | Original ICC-bearing scanned PDF, line transcription including page number, and a transfer scan. TIFF experiments and Java-built containers are insufficient. |
| Q3 | Independent acceptance on previously unused originals | New originals with independent cell/paragraph/line labels and recorded producer/rights. Existing local evaluation data are development data. |
| Q4 | Broader operational memory/latency/cancellation qualification | Freeze host, packaged workloads, process accounting and ceilings. The original ONNX gate passes; broader Amazon memory observations and parser/codec allocations do not establish a universal bound. |

## Other deferred work and new capabilities

These remain recorded, but PDF priority is unchanged. Contextual chunking itself is already implemented.

| ID | Concrete deliverable |
|---|---|
| N1 | Review collected Word style observations independently; turn any genuine semantic mismatch into an owned DOCX regression. Do not schedule another VM run without a specific missing observation. |
| N2 | Add independently supported DOCX numbering/style cases: international numbering, picture/font bullets, linked/multilevel counters and conflicting conditional overrides, one documented rule at a time. |
| N3 | Broaden DOCX revision/merge/supplemental-content and nested-table fidelity across independent authoring tools, preserving ownership and explicit unsupported diagnostics. |
| N4 | Expand structural chunking comparisons for header repetition, spans and strict HTML-budget segmentation with independent source references. |
| N5 | Design and implement opt-in token-budget chunking with a pinned tokenizer, explicit overlap/table semantics and a separate dependency boundary. |
| N6 | Evaluate existing contextual chunk enrichment on independently labelled retrieval data; do not implement ContextualChunker again or generalize the tiny owned live checks. |
| N7 | Address supported HTML/Markdown/TXT fidelity requirements individually: nested HTML, Markdown DOM/extensions and TXT grouping/classification, using pinned mismatch cases. Preserve XML/browser/network boundaries. |
| N8 | Migrate remaining Python-based DocWeave scoring/tests to JVM code with frozen-output parity. Retain Python for actual Unstructured generation and its guards; private release/export orchestration is separate. |
| N9 | Profile a representative large document and implement one measured per-document allocation/retention or cancellation improvement while preserving immutable ownership and current limits. |
| N10 | Add a separately scoped PPTX partitioner module with slide metadata and explicit unsupported content. |
| N11 | Add a separately scoped XLSX partitioner module with sheet/cell metadata, bounded workbook traversal and cached-formula policy. |
| N12 | Design an optional CLI over public services, with stable JSON/errors and documented provider selection. |
| N13 | Plan deferred Java-package migration and JPMS separately; current io.docweave Maven coordinates and dev.docweave imports remain valid. No bulk rename is required for current parsing. |
| N14 | Long-term format/adapter expansion recorded in the roadmap: legacy Office, email, EPUB, RTF, URL adapters and connectors. Each needs its own contract before implementation; none is an unfinished current partitioner. |
| N15 | Optional PDF scope expansion: interactive annotations/forms, vector-only figure representation and semantic image descriptions. Define output taxonomy, provenance, budgets and provider boundaries first; these are outside the current initial element contract, not missing fixes. |

## Maintenance, public synchronization and release work

| ID | Concrete deliverable |
|---|---|
| O1 | Prepare a fresh sanitized public-source projection of accepted development work; integrate incoming public changes first, validate the projection and record development/public mappings. The public repo and exporter already exist. Actual public push remains a separate action. |
| O2 | At an explicit release milestone: finalize beta notes, source projection, Java21/25 validation, consumer/signing/archive checks, annotated tag and matching prerelease; resolve Central file-count quota before upload if needed. Beta.6 is already published; namespace and signing setup are not missing tasks. |
| O3 | Maintain upstream drift tracking: review changed upstream sources against the saved reference and separately executed PDF runtime, then run only relevant comparisons. Reviewed release pins are not automatically execution pins. |

## Completed; do not reassign

- Whole-element sidebar row ties: accepted in `cf83cb2`; next 3->1, holdout 19->17
  (20->18 inline), recent/eLife unchanged. Plain-PARAGRAPHS regression is fixed.
- Final review: 1,402 Java21 tests pass; 408 older-policy hashes unchanged; no row-pair
  changes over 17 originals/181 pages/all six layouts. No review fix remains pending.
- One-shard DocLayNet pilot: 24 pages/48 calls, YOLOX TP/FP/FN16/1/3, TATR8/18/11.
  Q1 remains future financial-domain qualification, not a repeat of that pilot.
- Rendered-region crop implementation and review fixes, source corners/clip/group metadata.
- Original ONNX memory-gate fix, packaged OCR workload and captured upstream source decisions.
- Multi-module conversion, interface-based services, contextual enrichment service, nested-table
  preservation, Word observation collection, groupId change, Central namespace/signing setup,
  initial public-source export and private release/export tooling.

See [PDF backlog evidence](pdf-quality-backlog.md), [PDF scope](pdf-evaluation-scope.md),
[project roadmap](roadmap.md), [contextual enrichment](contextual-chunking.md) and
[upstream tracking](upstream-tracking.md). Individual support documents retain detailed evidence, limitations and acceptance boundaries.
