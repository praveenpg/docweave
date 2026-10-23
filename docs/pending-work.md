# Consolidated pending work

Snapshot: 2026-10-22, runtime baseline `6a02d01` plus the unreviewed local follow-up. This is the index of recorded pending
work, including parked improvements. It consolidates the PDF quality backlog, roadmap,
deferred-functionality audit and latest accepted review. Historical experiments keep their
original scores. An item being recorded here does not make it a PDF completion blocker,
an implementation bug, or authorization to publish/run models/acquire another corpus.

## Parked PDF improvements

| ID | Remaining work | State / concrete next action |
|---|---|---|
| P1 | Paragraph-internal scientific sidebar ties | **Addressed for recent/next (opt-in):** `SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS` (recent 6→0, next 1→0). The 2026-10-22 follow-up tried four signals. Tagged structure order, content-stream order and a local side-by-side content tie-break were rejected; the best fixed one holdout pair but reversed 6 correct eLife pairs. A JATS check (indicative) suggests eLife's 43 residuals follow the publisher's logical order. Holdout 17 and eLife 43 remain; next, a stated reading-order contract (front matter, sidebar membership), not more geometry. |
| P2 | Broader business-table headers, boundaries and continuation | **Split date headers addressed (opt-in):** `RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS` (YOLOX 71→77/110, TATR 36→38/110). Follow-up diagnostics, by profile: YOLOX misses 16/150 region lines, which are the statement title blocks, rows 0–3. The pinned upstream also keeps those titles out of tables (no defect). YOLOX cash-flow misses are publisher parenthesis cells. TATR misses are detector recall, which is BLOCKED on model execution or Q1. A competing-table ownership guard was added. |
| P3 | Positive non-NVIDIA trailing-row recovery | Five simulations were rejected (bands, numeric, content contiguity, gutters, blank-gap numeric); none recovered all three targets with zero extras. Blank-gap numeric recovered 1/3 with 0 extras and 0 false additions on five independent ICDAR-2013 tables. Optional, because upstream excludes these rows. BLOCKED on independently labelled business statements with trailing rows (Q1). |
| P4 | Historical OCR recognition, including the 1835 page number | A page-number-pattern gate on dual-decode additions passes on cached replays: 1835 21→22 under both models, 1778/1879/1780 unchanged. Margin and model-consensus gates were rejected. Evaluation only: the secondary decode is a TIFF harness behaviour, so PDF applicability needs Q2 plus authorized OCR. |
| P5 | Degraded-scan column separation | **Addressed (opt-in):** `WIDE_GAPS_AND_PRINTED_RULE` (1778 27→31/38) and the new `WIDE_GAPS_AND_GUARDED_RULE_COLUMNS`. The new policy rejects one-sided rules such as the 1780 border, and emits band lines column by column (XML-order reversed 43→31, sequence edits 413→342 and 374→296). Column order without a guard, and a same-baseline join (`deu` reversals 31→34), were rejected. The 7 remaining missing 1778 lines are segmentation, not recognition. Next: an independent labelled two-column scan. |
| P6 | Degraded-scan preprocessing | Diagnostics: 1778 errors concentrate in low- and mid-contrast strokes; 1780 extras are small dense specks and thin vertical artifacts. Stroke-weight restoration and speckle/thin-artifact removal are prepared but BLOCKED on current-session authorization to run the pinned Tesseract. Border masking, large components, CLAHE, median and deskew remain rejected or ineffective. |
| P7 | Exact isolated source-image appearance / visibility | Prototypes on 14 owned fixtures. Mask-aware isolated contribution passes everything, including soft masks, nested and knockout groups and the `/BC` backdrop. Final visibility passes everything, but by construction it misses identical-colour contributions and overpainted images. A consumer contract matrix records the decisions needed; no API added. |
| P8 | Remaining small-crop renderer disagreement | No defect in six probes: analytic, Poppler, rotation and crop-box offsets, UserUnit/POINTS, premultiplied 4× box sampling, and budget/typed-error boundaries. Rounding follows the documented single-precision renderer scale. |
| P9 | Procurement reference whitespace discrepancy | **Reference review closed:** a raw content-stream review confirms both v2 separators are painted U+0009-mapped glyphs, so v2 is an additive reference repair (v1 152/154 kept). A whitespace serialization guard was added, and no whitespace-kind mismatch exists elsewhere. Tabula provenance is BLOCKED (download). No extraction change. |

Evidence: [parked-items review](../benchmarks/review/pdf-parked-items-2026-10-22/README.md),
[two attempts per item](../benchmarks/review/pdf-parked-items-2026-10-22/attempts/README.md) and
[four further attempts per item](../benchmarks/review/pdf-parked-followup-2026-10-22/README.md). Four
opt-in runtime additions have resulted (P1, P2 and two for P5); defaults are unchanged.

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
| N8 | **Done 2026-10-22:** DocWeave scoring/tests migrated to JVM (`ScoreBenchmarkCaptures`, 29 Python methods → JUnit, byte-identical replay of retained reports). Python remains only for actual Unstructured/upstream execution and reference-generator guards (18 methods). Private release/export tooling is separate. |
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
