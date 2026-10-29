# Frozen protocol: P6 scan preprocessing candidates (PDF area 3)

Frozen on 2026-10-29 before any preprocessed OCR output was observed. Hypotheses are those frozen in
`benchmarks/review/pdf-parked-followup-2026-10-22/freeze/round-2.md` (P6-2) and `round-4.md` (P6-4); only their
operational definitions are fixed here. Results are not used to edit this file.

## Inputs (unchanged from ALIGNMENT_COLUMN_V1)

- **Manifest:** `benchmarks/review/pdf-ocr-column-alignment-evaluation/manifest.json`, with the four retained scans
  (1835, 1879, 1778, 1780; development data).
- **Engine and models:** Tesseract 5.5.3 (`9c19bce3…`, reprovisioned from the Arch Linux Archive with the recorded
  package hashes) and the tessdata_best `deu` (`8407331d…`) and `deu_latf` (`429b8c33…`) models.
- **Pixels and options:** colour-managed RGB exactly as in the harness, page segmentation AUTO, OEM 1, default
  thresholding, no DPI override, one thread, the same `TesseractOptions` budgets.
- **Baseline:** the retained ALIGNMENT_COLUMN_V1 word captures, replayed with identity checks. Lines use the
  unchanged `WIDE_GAPS` regrouping and the unchanged historical scorer.

## Candidate P6-2: stroke-weight restoration in low-contrast predicted lines

1. Line boxes are the baseline OCR's own word-line boxes. No reference labels are used.
2. Each box's stroke contrast is the luminance background median minus the darkest-decile luminance (the
   `DiagnoseScanErrors` formula). Terciles are taken over the page's predicted lines.
3. In every box of the low and mid terciles, each pixel becomes the minimum of its 3×3 neighbourhood (per channel,
   read from the unmodified image). Pixels outside those boxes are unchanged.
4. One Tesseract call per scan and model.

**Expected limitation:** a line the baseline missed has no box and cannot be targeted.

## Candidate P6-4: speckle and thin-vertical-artifact removal outside recognized words

1. Binarize luminance with the page's global Otsu threshold (dark = ink). Take 8-connected dark components.
2. With *h* = the median baseline word height on the page, a component is removed when it lies wholly outside every
   baseline word box and either its larger bounding dimension is below 0.75*h* (a speck), or its width is below
   0.15*h* and its height above 5*h* (a thin vertical artifact).
3. Removed pixels become the page's median background colour (the median RGB of non-ink pixels).
4. One Tesseract call per scan and model.

Inside-word components are never touched, which protects recognized punctuation and faint letters. Table rules are
not thin vertical components shorter than 5*h* and are kept unless taller and thinner than the limit; the count of
removed components is reported.

## Metrics (unchanged scorer)

Per observation:
- associated reference lines;
- missing reference lines;
- predicted lines without a reference (extras);
- joint reference-line character errors;
- wall time.

## Acceptance (per candidate; all must hold)

| Candidate | Target (both models) | Controls (both models) |
|---|---|---|
| P6-2 | 1778: character errors decrease; associated does not decrease; missing and extras do not increase | 1835, 1879, 1780: associated does not decrease; missing, extras and character errors do not increase |
| P6-4 | 1780: extras decrease; associated does not decrease; missing and character errors do not increase | 1835, 1879, 1778: the same no-harm rules |

Every call must succeed. A pass makes the candidate eligible for an opt-in implementation in a separate change; a
fail is recorded, and the area moves on without retrying thresholds.
