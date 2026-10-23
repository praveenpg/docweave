# Round 3 results (measured against freeze `freeze/round-3.md`)

## P1-3: local producer-order tie-break — REJECTED

Tool: `SimulateSourceOrderSignals LOCAL_CONTENT`. Restricting content order to runs of side-by-side elements
avoids the global damage of P1-2, but the result still fails the frozen rule:

| Paper | Effect |
|---|---|
| recent, next | Unchanged |
| holdout | 17→16 reversed (one reversed pair becomes correct) |
| eLife | 6 correct pairs turn reversed (43→49) |

## P2-4: TATR recall on uncovered statement pages — BLOCKED

Prerequisite: execution of an alternative detector or setting, or independently labelled table boxes (Q1).

## P3-3: blank-gap numeric continuation — REJECTED (partial)

- **Development set:** 1 of 3 targets (Amazon) recovered, with 0 extra lines on all 12 provider tables. Both
  Apple targets are missed.
- **Independent negative transfer:** 0 false additions on the five completely labelled ICDAR-2013 tables
  (us-001, us-002, us-030, using oracle cell boxes).

Not amended after measurement.

## P4-3: page-number-pattern gate on dual-decode additions — IMPROVED (cached evaluation only)

The frozen rule passes:
- 1835 rises from 21 to 22 associated lines under both models, with character edits 104→101 and 51→48.
- 1778, 1879 and 1780 are identical to baseline.

This is not promotable to runtime. The secondary decode is a TIFF stored-sample harness behaviour (no
production TIFF feature), PDF applicability still needs Q2, and wiring it into the historical harness needs
an authorized OCR run. No transfer dual-decode captures exist.

## P5-3: remaining 1778 missing lines — DESIGN_ONLY

Under the P5-2 output, none of the 7 missing reference lines is a pure recognition miss; each has 2–6
overlapping recognized words.

| Model | Words would associate if joined | Box geometry differs |
|---|---:|---:|
| `deu` | 4 | 3 |
| `deu_latf` | 3 | 4 |

The joinable lines are either merged into an over-wide output line or split across 2–4 lines. The next lever
is line segmentation.

## P6-3: 1780 extra lines — DESIGN_ONLY (measurement BLOCKED)

The extra lines (9 per model) are:
- 6 small dense specks: 0.4–0.73 median word heights, 1 word, 1–4 characters;
- 2 tall vertical artifacts: 6.5× and 13× median height;
- 1 wide line overlapping a reference line.

The preprocessing target is small-speckle and thin-vertical-artifact removal, which differs from the rejected
large-component suppression. A post-recognition filter that drops these lines is excluded, because dropping
recognized text to raise scores is not allowed. Measurement needs authorized Tesseract.

## P7-3: blend, identical-colour and overpaint fixtures — DESIGN_ONLY

Tool: `PrototypeImageContracts BLEND`. Every sample matches the hand-derived expectations.

| Fixture | Isolated alpha | Visible |
|---|---|---|
| Multiply over blue | 255 / 255 / 0 | 255 / 255 / 0 |
| Identical red backdrop | 255 / 255 / 0 | 0 / 0 / 0 |
| Half-white overpaint | 255 / 255 / 0 | 255 / 255 / 0 |

The identical-backdrop fixture shows the final-visibility blind spot. The isolated contract does not carry a
blend's colour effect.

## P8-3: transparent 4×4 box sampling — NO_DEFECT

At the half-covered pixel, `BOX_4X` gives alpha 128 with unpremultiplied red 255. Averaging is correctly
premultiplied with no darkening; neighbours are opaque and transparent as expected. `RENDERER` mode leaves the
exact half pixel uncovered (informational).

## P9-3: whitespace-only mismatches — NO_DEFECT (frozen hypothesis mis-specified)

No position in any of the 18 case/profile runs mismatches only by whitespace kind. The two procurement
mismatches survive all-Unicode-whitespace collapsing because they are a missing separator in reference v1, a
presence difference rather than a kind difference. This is consistent with P9-1: v2 restores the painted
separator. The official ASCII-only score is unchanged.
