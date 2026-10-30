# Consolidated pending work

Snapshot: 2026-10-22. Accepted implementation: JVM scorer migration and guarded OCR
column ordering, development `8156086`, synchronized publicly at `c2d6d4a`. Java 21 review
verification: 1,461 tests, zero failures/errors/skips. The remaining Python checks are 18
methods in six files for upstream execution and reference-generation guards.

## Backlog status: deferred for later

All unresolved items in this index are deferred. There is no active PDF improvement or
qualification task. Resume a specific item when it is selected again; do not automatically
repeat experiments, model runs, resource searches, corpus acquisition or release preparation.
Existing regressions remain required for future changes. This deferral does not mean the
unresolved cases have been fixed or independently qualified.

- **Parked PDF work:** P1–P7 retain concrete next actions below. P8 has no demonstrated defect;
  reopen only for a reproducible contract failure. P9's reference review is closed; optional
  Tabula provenance work remains deferred pending the external tool and a defined question.
- **Future qualification:** Q1–Q4 remain deferred with their stated inputs and acceptance needs.
- **Deferred capabilities:** N1–N7 and N9–N15 remain in the backlog. N8 migration is complete.
- **Maintenance:** O1 source synchronization is complete for the accepted baseline. O2 release
  preparation and O3 further upstream review are deferred. A future source change still needs
  the normal reviewed public synchronization; this is not an instruction to accumulate drift.

When an item resumes, read its retained failures, freeze one distinct hypothesis and controls,
and preserve defaults, source fidelity and full scoring denominators. The previous four-attempt
exercise is complete; it is not a standing instruction to run another four rounds.

## Parked PDF improvements

| ID | Remaining work | State / concrete next action |
|---|---|---|
| P1 | Paragraph-internal scientific sidebar ties | **Addressed for recent/next (opt-in):** `SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS` (recent 6→0, next 1→0). The 2026-10-22 follow-up tried four signals. Tagged structure order, content-stream order and a local side-by-side content tie-break were rejected; the best fixed one holdout pair but reversed 6 correct eLife pairs. A JATS check (indicative) suggests eLife's 43 residuals follow the publisher's logical order. Holdout 17 and eLife 43 remain. **2026-10-29:** [reading-order contract v1](pdf-reading-order-contract.md) written. Adjudicated against it, the real defect is R4: every policy interleaves the first-page margin column (8/8/7/8 runs). 33/60 residual pairs are reference differences. New opt-in `SMALLER_TYPE_BLOCKS_CONTIGUOUS` gives runs of 1/2/1 with 0 contract violations, but Unstructured `fast` agreement drops ([evidence](../benchmarks/review/pdf-reading-order-contract/README.md)). Next: eLife margin-column detection (not detected as a side stream). |
| P2 | Broader business-table headers, boundaries and continuation | **Split date headers addressed (opt-in):** `RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS` (YOLOX 71→77/110, TATR 36→38/110). Follow-up diagnostics, by profile: YOLOX misses 16/150 region lines, which are the statement title blocks, rows 0–3. The pinned upstream also keeps those titles out of tables (no defect). YOLOX cash-flow misses are publisher parenthesis cells. TATR misses are detector recall, which is BLOCKED on model execution or Q1. A competing-table ownership guard was added. **2026-10-29 public labelled transfer** ([results](../benchmarks/review/pdf-public-table-ground-truth/README.md), Unstructured eval-metrics NVIDIA FY24 page, 100 labelled cells, plus a labelled table-free negative): `yolox-provider` 94/100 cells, 19/20 rows. The one error is a projected-row-header prediction absorbing a row's values (model limitation; guard designed, not implemented, needs a second labelled instance). `SPLIT_ALIGNED_TWO_LINE_ROWS` costs 10 cells, because Unstructured labels keep two-line dates as one cell. Native 0/3; TATR 1/3 plus a false table on the negative. Pinned upstream `hi_res` 37/100 on the same scorer. |
| P3 | Positive non-NVIDIA trailing-row recovery | Five simulations were rejected (bands, numeric, content contiguity, gutters, blank-gap numeric); none recovered all three targets with zero extras. Blank-gap numeric recovered 1/3 with 0 extras and 0 false additions on five independent ICDAR-2013 tables. Optional, because upstream excludes these rows. BLOCKED on independently labelled business statements with trailing rows (Q1). The 2026-10-29 public labelled page has no trailing-row case (trailing-row options changed nothing); continuation is not measurable on single-page labels. |
| P4 | Historical OCR recognition, including the 1835 page number | A page-number-pattern gate on dual-decode additions passes on cached replays: 1835 21→22 under both models, 1778/1879/1780 unchanged. Margin and model-consensus gates were rejected. Evaluation only: the secondary decode is a TIFF harness behaviour, so PDF applicability needs Q2 plus authorized OCR. |
| P5 | Degraded-scan column separation | **Addressed (opt-in):** `WIDE_GAPS_AND_PRINTED_RULE` (1778 27→31/38) and the new `WIDE_GAPS_AND_GUARDED_RULE_COLUMNS`. The new policy rejects one-sided rules such as the 1780 border, and emits band lines column by column (XML-order reversed 43→31, sequence edits 413→342 and 374→296). Column order without a guard, and a same-baseline join (`deu` reversals 31→34), were rejected. The 7 remaining missing 1778 lines are segmentation, not recognition. Next: an independent labelled two-column scan. |
| P6 | Degraded-scan preprocessing | Diagnostics: 1778 errors concentrate in low- and mid-contrast strokes; 1780 extras are small dense specks and thin vertical artifacts. Stroke-weight restoration and speckle/thin-artifact removal are prepared but BLOCKED on current-session authorization to run the pinned Tesseract. Border masking, large components, CLAHE, median and deskew remain rejected or ineffective. **2026-10-29:** both prepared candidates were run with the pinned Tesseract under a frozen protocol and **rejected** ([results](../benchmarks/review/pdf-scan-preprocessing-p6/README.md)). Stroke restoration worsens 1778 (364→452 / 319→426 errors). Artifact removal raises 1780 extras 9→10 and 1778 extras to 17. Side observation: artifact removal recovers the 1835 missing line (a possible P4 seed, not promoted). |
| P7 | Exact isolated source-image appearance / visibility | Prototypes on 14 owned fixtures. Mask-aware isolated contribution passes everything, including soft masks, nested and knockout groups and the `/BC` backdrop. Final visibility passes everything, but by construction it misses identical-colour contributions and overpainted images. A consumer contract matrix records the decisions needed; no API added. **2026-10-29:** [image output contracts](pdf-image-contracts.md) define A (intrinsic samples, provided), B (isolated contribution, not provided: consumer decisions on blend, overpaint and identical colour) and C (final appearance, provided), with a per-feature matrix. `PdfImageOutputContractsTest` pins A and C on 11 owned feature fixtures; no API added. |
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

These remain deferred alongside the PDF improvement backlog. Contextual chunking itself is already implemented.

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
| N13 | Plan deferred Java-package migration and JPMS separately; current io.docweave Maven coordinates and io.docweave imports remain valid. No bulk rename is required for current parsing. |
| N14 | Long-term format/adapter expansion recorded in the roadmap: legacy Office, email, EPUB, RTF, URL adapters and connectors. Each needs its own contract before implementation; none is an unfinished current partitioner. |
| N15 | Optional PDF scope expansion: interactive annotations/forms, vector-only figure representation and semantic image descriptions. Define output taxonomy, provenance, budgets and provider boundaries first; these are outside the current initial element contract, not missing fixes. |

## Maintenance, public synchronization and release work

| ID | Concrete deliverable |
|---|---|
| O1 | **Complete for the current baseline:** development `8156086` maps to public `c2d6d4a`, with 1,461 Java 21 tests passing on the public tree. Repeat reviewed synchronization for future accepted source changes; preserve incoming contributions and private-artifact exclusions. |
| O2 | At an explicit release milestone: finalize beta notes, source projection, Java21/25 validation, consumer/signing/archive checks, annotated tag and matching prerelease; resolve Central file-count quota before upload if needed. Beta.6 is already published; namespace and signing setup are not missing tasks. |
| O3 | Maintain upstream drift tracking: review changed upstream sources against the saved reference and separately executed PDF runtime, then run only relevant comparisons. Reviewed release pins are not automatically execution pins. |

## Completed; do not reassign

- Latest accepted follow-up: guarded OCR columns, JVM migration of 29 Python test methods,
  retained historical-report parity, packaged API review and 1,461 passing Java 21 tests.
- Latest public source synchronization: development `8156086` / public `c2d6d4a`.
- P9 additive reference review and source-whitespace serialization guard are complete.

The earlier counts below describe their historical validation snapshots.

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
