# Four further attempts per parked PDF item (2026-10-22)

This continues the [parked-items review](../pdf-parked-items-2026-10-22/README.md) and its
[two earlier attempts](../pdf-parked-items-2026-10-22/attempts/README.md), from development `6a02d01`.

- **Rounds:** four rounds of one attempt per item, nine items, 36 slots in total.
- **Freezing:** each round's hypotheses, mechanisms, controls, metrics and pass rules were frozen in
  `freeze/round-N.md` before that round's measurements. Freeze-file hashes and UTC times are logged
  privately.
- **Scope:** offline work only, against cached captures, retained page rasters, PDFBox and owned
  fixtures.
  - No Tesseract, ONNX model, Unstructured, Poppler, download or remote service ran.
  - Slots that need one of those are BLOCKED with the exact prerequisite.
- **Evidence:** reports in `reports/` hold counts, indices, coordinates and hashes. Text-bearing probe
  outputs stay private.

## 36-slot matrix

| Item | Attempt 1 | Attempt 2 | Attempt 3 | Attempt 4 |
|---|---|---|---|---|
| P1 | REJECTED: tagged structure order (144–241 correct→reversed). By-product: holdout residuals keep DocWeave's direction under the producer's logical structure | REJECTED: content-stream order (218–398 correct→reversed) | REJECTED: local side-by-side content tie-break (holdout 17→16, eLife +6 reversed) | DESIGN_ONLY: JATS order check (indicative): eLife residuals follow publisher order |
| P2 | DESIGN_ONLY: recall versus assembly (YOLOX 16/150 lines uncovered = title blocks; cash-flow misses = parenthesis cells) | NO_DEFECT: competing-table ownership under row splitting (guard added) | NO_DEFECT: the pinned upstream also excludes statement titles from tables | BLOCKED: TATR recall needs model execution or Q1 labels |
| P3 | REJECTED: content-stream contiguity (3/3 targets, 37 extras) | REJECTED: column gutters (frozen definition degenerate) | REJECTED: blank-gap numeric (1/3, 0 extras; 0 false additions on 5 ICDAR-2013 tables) | BLOCKED: needs independently labelled trailing-row statements (Q1) |
| P4 | REJECTED: margin-band gate (1778 +2 extras kept) | REJECTED: two-model consensus (both models agree on the extras) | IMPROVED (cached evaluation only): page-number-pattern gate, 1835 21→22 under both models, controls identical | BLOCKED: PDF applicability needs Q2 and authorized OCR |
| P5 | REJECTED: column-major order without a guard (1780 order changed) | **IMPROVED: guarded rule columns, new runtime opt-in** (1778 31/38, reversed 43→31, sequence edits 413→342 and 374→296) | DESIGN_ONLY: the 7 missing 1778 lines are segmentation, not recognition | REJECTED: same-baseline join (`deu` 31→33 associated but reversed 31→34) |
| P6 | DESIGN_ONLY: errors concentrate in faint strokes (1778) | BLOCKED: stroke-weight restoration (needs Tesseract authorization) | DESIGN_ONLY: 1780 extras are specks and thin vertical artifacts; measurement BLOCKED | BLOCKED: speckle and thin-artifact removal (needs Tesseract authorization) |
| P7 | DESIGN_ONLY: mask-aware isolation passes 8/8 (soft mask fixed) | DESIGN_ONLY: nested, knockout and `/BC` backdrop fixtures pass both prototypes | DESIGN_ONLY: blend, identical-colour and overpaint fixtures; visibility blind spot shown | DESIGN_ONLY: consumer contract decision matrix |
| P8 | NO_DEFECT: rotation and crop-box offsets | NO_DEFECT: UserUnit and POINTS units | NO_DEFECT: premultiplied 4× box sampling | NO_DEFECT: budget and typed-error boundaries |
| P9 | NO_DEFECT: v2 separators confirmed by raw operators (review closed) | NO_DEFECT: whitespace serialization guard added | NO_DEFECT: no whitespace-kind mismatches anywhere | BLOCKED: tabula-java provenance needs a download |

Details are in `results-round-1.md` through `results-round-4.md`.

## Delivered and validated

**Runtime opt-in (P5-2):** `TesseractOptions.LineSplitting.WIDE_GAPS_AND_GUARDED_RULE_COLUMNS`.
- Defaults and `WIDE_GAPS_AND_PRINTED_RULE` are unchanged.
- Owned tests: `TesseractPrintedRuleTest` gains 2.
- Runtime equivalence on 12 retained observations.

**Owned guards:**
- `PdfNativeRowLinesTest.competingTablesKeepDisjointOwnership`;
- `PdfWhitespaceSerializationTest`.

**Evaluation-only results, not delivered:**
- P4-3: the page-number gate.
- P7: the prototypes and contract matrix.

## Trade-offs and limits

**P5-2:**
- Measured from retained word captures through the public parser; the engine was not re-run this session.
- Development scans only.
- XML-order reversed pairs are 31 against the no-rule baseline's 30, while 113 more pairs are correct and
  association rises from 27 to 31.

**P1:** four signals tried in this pass, plus the earlier XY-cut and continuity attempts, cannot resolve
the holdout and eLife residuals without harming correct pairs. The next step needs a stated reading-order
contract.

## Reproduction

| Item | Tool and command arguments | Inputs |
|---|---|---|
| P1 | `SimulateSourceOrderSignals STRUCTURE\|CONTENT\|LOCAL_CONTENT sources captures report` | Private scientific originals and the pinned upstream captures |
| P2 | `DiagnoseProposalCoverage`; `CheckUpstreamTitleRows` | NVIDIA reference manifest, source, frozen captures and the retained upstream output |
| P4, P5 | `EvaluateCachedScanVariants UNION_MARGIN\|UNION_CONSENSUS\|UNION_PAGE_NUMBER\|RULE_COLUMN_ORDER\|RULE_GUARDED_COLUMN_ORDER\|RUNTIME_GUARDED_COLUMN_ORDER\|RULE_GUARDED_JOIN\|DIAGNOSE_GUARDED_MISSING\|DIAGNOSE_EXTRAS manifest sources baseline variants report` | ALIGNMENT_COLUMN_V1 manifest, retained captures and DUAL_UNION captures |
| P6 | `DiagnoseScanErrors` | As for P4 and P5 |
| P7 | `PrototypeImageContracts BASE\|GROUPS\|BLEND report` | Owned fixtures only |
| P8 | `CheckCropGeometry ROTATION\|USER_UNIT\|SAMPLING\|BUDGET report` | Owned fixtures only |
| P9 | `DiagnoseWhitespaceMismatches`; private raw-operator probe | Pinned Tabula cache and fresh native captures |
| P3 | Private probes (`SimulateTrailingContent`, `SimulateTrailingGutters`, `SimulateTrailingBlankGap`) | Retained provider captures and ICDAR-2013 labels |
