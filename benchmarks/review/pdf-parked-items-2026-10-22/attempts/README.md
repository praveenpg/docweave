# Two concrete attempts per parked item (2026-10-22)

This extends the [bounded review](../README.md) with two attempts per item.

**Scope.** The study used the already-installed pinned Tesseract locally and evaluated
an opt-in paragraph-fragment design for P1.

Nothing was downloaded, and no remote service or Unstructured process ran. The local system
Poppler 26.10 was used once, as an independent renderer, for P8.

**Method.** Each mechanism was frozen in `freeze-*.md` before it was measured. Amendments made
after a result are labelled as such. Reports here hold counts, indices, coordinates and hashes
only; text-bearing probes and word captures stay private.

## Outcome by item

| ID | Attempt 1 | Attempt 2 | Disposition |
|---|---|---|---|
| P1 | **Explicit row-tie paragraph fragments** (`SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS`): recent 6 → 0, next 1 → 0 on every column profile, with no correct pair lost and holdout/eLife unchanged. Rejoined output equals row-tie output on 204 source profiles; strict paragraphs are unchanged; one row pair improves; 612 existing-policy hashes are identical. | Upstream XY-cut ordering applied to whole elements: 2–40 correct → reversed per paper. **Rejected.** | **Runtime opt-in delivered** |
| P2 | **Physical-line provider rows** (`RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS`). As frozen: no effect, because whitespace spacer glyphs counted as lines. Amendment 1b, disclosed, ignores whitespace glyphs: YOLOX 71 → 77/110, TATR 36 → 38/110, every gained row a split date header, nothing lost, fewer unmatched rows. Transfer: 5 source-consistent splits on Apple/Amazon captures. | Existing aligned-text native detector under native-first composition: refused by an existing contract (no exact glyph ownership). **Blocked; nothing substituted.** | **Runtime opt-in delivered** |
| P3 | Painted row-band lattice (simulation): 0 of 3 targets and 7 wrong lines appended. **Rejected.** | Column-aligned numeric continuation (simulation): Amazon target recovered with 0 extras on 12 tables, but both Apple targets missed (blank-band gap > 1.5 pitch). **Fails frozen acceptance** (partial). | Parked; optional, since upstream excludes these rows |
| P4 | **Dual-decode union**: managed decode plus non-overlapping stored-sample lines. On the frozen population, 1835 goes 21 → 22/22 under both models, character edits 104 → 101 and 51 → 48, word edits 74 → 73 and 35 → 34, no extras, and 1879 is byte-identical. **Transfer** to 1778, which shares the scanner profile, adds 2 extra lines per model and worsens XML-sequence edits. | Sparse-segmentation union: 3 extras per model and worse character edits. **Rejected.** | Harness diagnostic only; not promotable (fails transfer, and a PDF change still needs Q2) |
| P5 | Existing public deskew decorator: identical output everywhere; the estimator applied no correction. **No effect.** | **Ridge-based printed-rule split** (`LineSplitting.WIDE_GAPS_AND_PRINTED_RULE`). On 1778 the rule is found at x 968 and associated lines go 27 → 31/38 under both models, missing 11 → 7, extras 13 → 11 and 11 → 9, joint errors 364 → 311 and 319 → 260. Controls and two transfer pages are identical, and the runtime option equals the evaluated rule on 12 observations. | **Runtime opt-in delivered** |
| P6 | Scanner-border masking through OCR: worse on 1835 and 1780. **Rejected.** | Large dark-component suppression: destroys 1778 text (associations 18 → 0). **Rejected.** | Parked |
| P7 | Isolated-contribution prototype (suppress all other painting): 7/8 fixtures; the luminosity soft mask fails because its own group paint is suppressed. | Final-visibility prototype (page difference with only the target suppressed): 8/8 fixtures. Limitation by construction: a contribution identical to its backdrop is invisible. | Contract evidence for a consumer decision; no API added |
| P8 | Fractional-coverage sweep against hand-derived 1/16 values: exact except the 0.3-unit bar edge (12 levels, within tolerance). | Local Poppler at 288 DPI, box-averaged: max difference 10. | No defect; no fix |
| P9 | Extracted cells keep a source whitespace character on both sides of each `&`. New owned test `PdfSourceWhitespaceGlyphTest` covers U+0009-mapped glyphs. | Additive proposed reference v2, with only the two glyph-evidenced separators restored: v1 152/154 is unchanged, v2 154/154. Pending independent review. | No extraction change; reference proposal |

## Notes and limits

- **P1 fragments** change paragraph membership explicitly and only in opt-in output. The fragment
  keys make rejoining exact. Holdout and eLife residuals remain.
- **P2:**
  - The 1b amendment followed a zero-effect result. It is a definitional change (spacer glyphs are
    structure), not a threshold change, and it is disclosed in `freeze-P2.md`.
  - TATR's absent proposals remain.
  - Attempt 2 shows that aligned-text detection cannot be composed with provider tables under the
    exact-glyph-ownership contract.
- **P3** simulations use PDFBox positions and 2-unit baseline grouping. The first run used 1.0
  units, which split the Amazon label from its values. That was a fidelity correction to
  DocWeave's documented common-baseline tolerance, made before any rule change.
- **P5:** no independent labelled two-column degraded scan exists locally. On 1780 the detector
  selects the scanner border but makes no split.
- **P4** fails transfer on 1778, so stored-sample secondary recognition is not safe in general
  even though it recovers the 1835 page number.

## Reproduction

| Area | Tools | Inputs |
|---|---|---|
| P1 | `SimulateUpstreamXyCut`, `CompareRowFragmentPolicy`, and the existing `CompareUpstreamLineOrder`, `CompareRowAssociation` and `PdfScientificReadingOrderTest` with the `SIDE_STREAMS_ROW_FRAGMENTS` modifier | Private scientific originals and captures |
| P2 | `AuditStatementRowPresence --section-row-trailing-spacers[-row-lines\|-aligned-text]`, run from the frozen scorer workspace | NVIDIA captures |
| P4 | `PdfHistoricalTextCorpusTest` with `-Ddocweave.pdf.historical.dual.decode.union=true` or `-Ddocweave.pdf.historical.sparse.union=true` | Pinned executable and models, `OMP_THREAD_LIMIT=1` |
| P4 transfer, P5, P6 | `EvaluateDegradedScanAttempts` with `DESKEW`, `RULE`, `RUNTIME_RULE`, `BORDER`, `COMPONENTS`, `DUAL_UNION` or `CAPTURE` | ALIGNMENT_COLUMN_V1 manifest and retained captures |
| P7 | `PrototypeImageAppearance` | None (owned fixtures) |
| P8 | `CheckCropCoverage`, with an optional `pdftoppm -r 288 -gray` image | None (owned page) |
| P9 | `VerifyProcurementReferenceVersions` | Private v2 CSV (sha256 in the report) |
| P3 | `SimulateTrailingBands.java.txt` | Retained provider captures |
| P2 transfer | `ProbeRowLinesTransfer.java.txt` | Retained provider captures |
