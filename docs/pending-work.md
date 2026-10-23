# Consolidated pending work

Snapshot: 2026-10-22, runtime baseline `cf83cb2`. This is the index of recorded pending
work, including parked improvements. It consolidates the PDF quality backlog, roadmap,
deferred-functionality audit and latest accepted review. Historical experiments keep their
original scores. An item being recorded here does not make it a PDF completion blocker,
an implementation bug, or authorization to publish/run models/acquire another corpus.

## Parked PDF improvements

| ID | Remaining work | State / concrete next action |
|---|---|---|
| P1 | Paragraph-internal scientific sidebar ties | **Addressed for recent/next (opt-in):** `SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS` emits marked paragraph fragments. Recent goes 6→0 and next 1→0 with no correct pair lost; rejoined output equals the row-tie output. Upstream XY-cut ordering was rejected. Holdout 17 and eLife 43 residuals remain; they need an independently supported order signal. |
| P2 | Broader business-table headers, boundaries and continuation | **Split date headers addressed (opt-in):** `RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS` takes YOLOX 71→77/110 and TATR 36→38/110, every gained row a date header, with 5 source-consistent transfer splits. The whitespace-spacer exclusion was a disclosed amendment. Aligned-text native-first detection is refused by the glyph-ownership contract. Titles, parenthesis spacing and TATR absent proposals remain (Q1). |
| P3 | Positive non-NVIDIA trailing-row recovery | Two simulations: the painted row-band lattice was rejected (0/3 targets, 7 wrong lines). Numeric column continuation recovered the Amazon row with 0 extras but missed both Apple targets, so it fails acceptance. Optional, because upstream excludes these rows. Next: an independently labelled transfer document. |
| P4 | Historical OCR recognition, including the 1835 page number | Dual-decode union (harness) recovers 19 with fewer edits (104→101, 51→48) but adds 2 extras on 1778 at transfer, so it is not promoted. Sparse-segmentation union was rejected. A PDF-level change still needs Q2. |
| P5 | Degraded-scan column separation | **Addressed for ruled columns (opt-in):** `TesseractOptions.LineSplitting.WIDE_GAPS_AND_PRINTED_RULE` takes 1778 from 27→31/38 under both models, with fewer missing/extra lines and character errors; controls and two transfer pages are unchanged. Deskew had no effect. Next: an independent labelled two-column scan. |
| P6 | Degraded-scan preprocessing | Border masking and large-component suppression were both rejected through OCR (regressions). Reopen only with a different defect tied to observed errors. |
| P7 | Exact isolated source-image appearance / visibility | Prototypes: final-visibility difference passes 8/8 owned fixtures; isolated contribution passes 7/8 (luminosity soft mask fails under suppression). This is evidence for a consumer contract decision; no API has been added. |
| P8 | Remaining small-crop renderer disagreement | Analytic fractional-coverage sweep and local Poppler comparison: within 12 and 10 levels. No defect; no fix. |
| P9 | Procurement reference whitespace discrepancy | Extraction keeps both source separators (owned test added). An additive v2 reference proposal scores 154/154 against v1 152/154, pending independent review. No extraction change. |

Evidence: [parked-items review](../benchmarks/review/pdf-parked-items-2026-10-22/README.md) and
[two attempts per item](../benchmarks/review/pdf-parked-items-2026-10-22/attempts/README.md). Three opt-in
runtime additions resulted (P1, P2, P5); defaults are unchanged.

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
