# P6 scan preprocessing candidates (PDF area 3): both rejected

**Outcome class: qualification-only.** Both frozen candidates fail acceptance. No runtime, API or default change.
Existing guarded column policies are untouched. Protocol: [PROTOCOL.md](PROTOCOL.md), frozen before any preprocessed
OCR output. Reports (metrics and hashes only): `p6-2-report.json`, `p6-4-report.json`. Tool:
`benchmarks/tools/EvaluateScanPreprocessing.java`.

P6 was previously blocked only on authorization to run the pinned Tesseract. That authorization and a reprovisioned
pinned runtime are now available.

## Setup

- **Engine:** Tesseract 5.5.3 (`9c19bce3…`; Leptonica 1.87.0), from the Arch Linux Archive with the recorded package
  hashes (`tesseract-5.5.3-1`, `leptonica-1.87.0-2`).
- **Models:** tessdata_best `deu` (`8407331d…`) and `deu_latf` (`429b8c33…`), already local.
- **Options:** page segmentation AUTO, OEM 1, default thresholding, no DPI override, one thread, unchanged
  `TesseractOptions` budgets.
- **Baselines:** the retained ALIGNMENT_COLUMN_V1 word captures. Input pixel hashes were verified, and all eight
  baselines reproduce the recorded `WIDE_GAPS` metrics.
- **Runtime:** Java 21.0.8. 16 calls (8 per candidate), all successful. Per-call OCR time 0.74–4.15 s, median about
  1.3 s; each candidate run took 23 s wall clock.
- **Not measured:** Tesseract child-process memory (no process accounting tool on this host). Cancellation is not
  exercised by this evaluation; the adapter's typed limits and interruption are covered by the existing OCR module
  tests.

## Results (WIDE_GAPS lines, unchanged scorer)

Values are associated / missing / extra lines and joint character errors, baseline → candidate.

| Scan / model | Role (P6-2) | P6-2: minimum filter in low/mid-contrast lines | Role (P6-4) | P6-4: specks and thin verticals outside words |
|---|---|---|---|---|
| 1835 deu | control | 21/1/0/103 → 21/1/0/103 | control | 21/1/0/103 → **22/0/0/101** |
| 1835 deu_latf | control | 21/1/0/50 → 21/1/0/**44** | control | 21/1/0/50 → **22/0/0/49** |
| 1879 deu | control | 50/0/1/7 → 50/0/1/**8** ✗ | control | unchanged |
| 1879 deu_latf | control | 50/0/1/53 → 50/0/1/**54** ✗ | control | unchanged |
| 1778 deu | **target** | 27/11/13/364 → **23/15/16/452** ✗ | control | 27/11/13/364 → 27/11/**17**/371 ✗ |
| 1778 deu_latf | **target** | 27/11/11/319 → **21/17/15/426** ✗ | control | 27/11/11/319 → 27/11/**17**/319 ✗ |
| 1780 deu | control | 9/1/9/51 → 9/1/8/**56** ✗ | **target** | 9/1/9/51 → 9/1/**10**/52 ✗ |
| 1780 deu_latf | control | 9/1/9/27 → 9/1/8/**32** ✗ | **target** | 9/1/9/27 → 9/1/**10**/27 ✗ |

- **P6-2 (stroke-weight restoration): rejected.**
  - It worsens its 1778 target under both models: fewer associated lines and 88–107 more character errors.
  - Filtering only predicted low- and mid-contrast lines cannot reach the 11 lines the baseline misses (expected
    limitation), and thickening faint strokes merges glyphs.
  - Small control gains (1835 `deu_latf`) do not offset the control losses.
- **P6-4 (artifact removal): rejected.**
  - On 1780 it removes about 2,875 components, yet extras rise 9→10 under both models.
  - On 1778, extras rise to 17. Removing specks changes Tesseract's own segmentation instead of only suppressing noise.
- **Side observation, not a promotion:** P6-4 recovers the one missing 1835 line under both models (21→22 associated,
  0 missing). That is P4's open page-number target. A separately frozen P4 hypothesis would be needed; nothing is
  retried here.

No threshold or alternate definition was tried after observing these results.

## Remaining (P4–P6)

- **P4 (historical recognition, 1835 page number):** still needs Q2, an original ICC-bearing scanned PDF with a line
  transcription, for PDF applicability. The P6-4 observation above is a possible seed.
- **P5 (degraded column separation):** the guarded opt-in policy is unchanged. The next step still needs an
  independent labelled two-column scan.
- **P6:** both prepared candidates are now measured and rejected. Faint-stroke errors and 1780 extras remain; a new
  mechanism needs a new frozen hypothesis.

## Reproduce

```sh
LD_LIBRARY_PATH=<tess-runtime>/usr/lib java -Xmx1024m -Djava.awt.headless=true -cp TOOLS:CP \
  io.docweave.validation.EvaluateScanPreprocessing P6_2|P6_4 benchmarks/review/pdf-ocr-column-alignment-evaluation/manifest.json \
  <tess-runtime>/usr/bin/tesseract <tessdata with deu, deu_latf> <four scans + XML> <retained ALIGNMENT_COLUMN_V1 captures> NEW.json
```
