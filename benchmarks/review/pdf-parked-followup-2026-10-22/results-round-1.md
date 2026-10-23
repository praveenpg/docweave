# Round 1 results (measured against freeze `freeze/round-1.md`)

Reports contain counts, indices, coordinates and hashes only. Text-bearing probe outputs stay private.

## P1-1: tagged logical-structure order — REJECTED

Tool: `SimulateSourceOrderSignals STRUCTURE`.

Holdout, recent and next are tagged; eLife is untagged and stays unchanged. Correct→reversed counts:

| Paper | Correct→reversed |
|---|---:|
| recent | 222–241 |
| next | 158–175 |
| holdout | 144–177 |

Holdout's reversed pairs rise from 17 to 161 (from 18 to 195 with inline scripts).

**Sanity check:** structure keys follow content-stream order on 3,638 of 3,639 adjacent glyph pairs. The
producer's logical order puts the main column first and the left sidebar last, so this is a contract
conflict with the geometric upstream order, not a mapping bug.

**By-product:** no residual pair flips under the producer's structure order. All 17 holdout residual pairs
keep DocWeave's direction, which independently supports treating them as upstream-order differences rather
than DocWeave defects.

## P2-1: recall versus assembly — DESIGN_ONLY

Tool: `DiagnoseProposalCoverage`. Line counts over 150 region lines:

| Profile | Outside every crop (recall) | Inside a crop, outside tables (assembly) |
|---|---:|---:|
| YOLOX | 16 | 1 |
| TATR | 30 | 1 |

**YOLOX recall:** the uncovered lines are the 4–5 lines above each statement. These are the reference's
title block (company, statement title, units, "Unaudited"), and they account for missing reference rows 0–3
in every case.

**YOLOX assembly:** the 24 missing cash-flow rows lie inside emitted tables. A private inspection shows that
nearly all of them are rows where the publisher HTML puts a closing parenthesis in its own cell.

Both are presentation differences, which the handoff says are not automatically extraction bugs. TATR's
remaining gaps are detector recall: one balance-sheet page and half of a cash-flow page are uncovered.

## P3-1: content-stream contiguity — REJECTED

All 3 targets are recovered, but 37 other lines are appended: 22 Apple lines (section lines of neighbouring
provider tables) and 15 Amazon footnote lines. The frozen rule requires zero extras.

**Observation:** on Apple's balance sheet, provider tables leave source lines between two tables of the same
statement. That is an inter-table gap, distinct from trailing rows.

## P4-1: margin-band gate on dual-decode additions — REJECTED

Tool: `EvaluateCachedScanVariants UNION_MARGIN`, retained captures only.

- 1835 keeps its gain under both models: associated lines 21→22, character edits 104→101 and 51→48.
- 1778's two additions also lie outside the text block, so 1778 still worsens. Extra lines go 9→11, and
  XML-sequence edits go 413→431 and 374→411.

The page-number gain and the 1778 extras cannot be separated by margin position.

## P5-1: column-major order inside the rule band — REJECTED (frozen control)

Tool: `EvaluateCachedScanVariants RULE_COLUMN_ORDER`. On 1778, compared with the accepted rule alone, under
both models:

| Metric | Rule alone | Column-major order |
|---|---:|---:|
| Associated lines | 31 | 31 (unchanged) |
| XML-order correct pairs | 422 | 434 |
| XML-order reversed pairs | 43 | 31 |
| XML-sequence edits | 413 / 374 | 342 / 296 |

The 1835 and 1879 outputs are identical. On 1780, the accepted border "rule" reorders 3 lines while every
metric stays unchanged. That violates the frozen identical-output control. A rule-selection guard is the
next distinct mechanism.

## P6-1: error taxonomy — DESIGN_ONLY

Tool: `DiagnoseScanErrors`, retained baselines.

- **1778:** all 11 missing lines and most character errors fall in the low and mid stroke-contrast terciles,
  with error rates of 155/247 and 150/243 against 59/421 for high contrast. The edge band is not dominant.
- **1780:** the extra lines (8 per model) are in the interior.
- **1835:** its contrast range is narrow, so its errors are not contrast-driven.
- **1879:** it is binarized.

Faint strokes are the dominant defect. Contrast-limited equalization, median filtering and their whitepoint
composition were already rejected, and local whitepoint is already an opt-in, so later attempts must use
different mechanisms.

## P7-1: mask-aware isolated contribution — DESIGN_ONLY

Tool: `PrototypeImageContracts BASE`. Suppressing non-target paint only outside `processSoftMask` passes 8/8
base fixtures. The original full-suppression prototype reproduces its 7/8, failing only the luminosity soft
mask, and no other fixture's samples change. This is a prototype, not an API.

## P8-1: rotation and crop-box offsets — NO_DEFECT

Tool: `CheckCropGeometry ROTATION`, run at 3 DPIs × 4 rotations. Every inset-colour and background-ring check
passes, and rotation and scale are reported correctly.

Four pixel-extent differences from the frozen exact-rational expectation were analysed after measurement,
with no re-run and no tolerance change:
- **Two come from the expectation itself:** in double arithmetic, 120 × 150/72 = 250.00000000000003, which
  ceils to 251.
- **Two come from the renderer's documented single-precision scale:** 120 × (float)(150/72) = 249.99999…,
  which floors to 249.

The implementation rounds the exact product of the edge and the float scale, as its Javadoc states, and
reports that scale as `pixelsPerUnit`. The extra column holds less than 1e-5 px of coverage, so no content is
lost. Possible documentation clarification: the public "round exactly outward" wording could name the
renderer's float scale.

## P9-1: independent content-stream review of reference v2 — NO_DEFECT (reference repair confirmed)

A private raw-operator probe (`ReviewSeparators`, no DocWeave extraction) examined each ampersand in the
procurement table on page 1. Each is followed by glyph code 3, which ToUnicode maps to U+0009, painted with
a 220-unit advance after a small TJ adjustment. Five of the six ampersands in the document have a
whitespace-mapped glyph on both sides. The sixth starts a show-text operand, and its trailing separator is
confirmed.

v2 restores painted separators. It is an additive reference repair: v1 and its 152/154 are kept, and this
is not an extraction gain.
