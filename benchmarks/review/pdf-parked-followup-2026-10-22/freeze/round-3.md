# Round 3 freeze (written after round 2 results, before any round-3 measurement)

The scope and authorization limits are unchanged: offline only.

## P1-3: local producer-order tie-break for overlapping cross-column elements (probe)
- **Hypothesis (from P1-2):** the residual pairs agree with local producer order. Using content-stream
  order only to order elements that share a horizontal band but sit in different columns would fix them,
  without the global damage seen in P1-2.
- **Mechanism:**
  - On the committed row-fragment elements, find maximal runs of consecutive output elements in which each
    adjacent pair overlaps vertically (positive intersection of top–bottom ranges) and does not overlap
    horizontally.
  - Stable-sort each run of at least 2 elements by the element's smallest content-stream glyph index.
    Elements without glyphs keep their position.
- **Metrics:** full transitions for all four papers and both layouts and inline settings.
- **Pass:** holdout or eLife reversed pairs fall with no correct→reversed or correct→unmatched transition
  anywhere.

## P2-4: TATR detector recall on uncovered statement pages — BLOCKED
P2-1 found that TATR proposals cover no line of one balance-sheet page and half of a cash-flow page.
Recovering that recall needs one of the following:
1. Execution of a detector model or setting other than the frozen proposals.
2. Independently labelled table boxes (Q1) to qualify any proposal change.

Neither is authorized or available. No runtime work is substituted.

## P3-3: blank-gap numeric continuation with independent negative transfer (simulation)
- **Disclosed derivation:** this is the rejected round-0 numeric continuation with its distance limit
  (1.5 × pitch) replaced by an adjacency criterion. The gap above an accepted line must contain no visible
  glyph within the table's horizontal extent.
- **Rule:** otherwise as frozen before:
  - The first token sits in column 0 and every other token is numeric, centred in a distinct value column.
  - A colon-terminated label-only line is accepted only when the next line is accepted.
  - Stop at the first failure.
- **Development measurement:** the 3 targets and 12 provider tables. Pass: 3/3 targets and 0 other lines.
- **Independent negative transfer** (required for any pass): the five completely labelled ICDAR-2013 tables
  (us-001, us-002, us-030). The oracle table is the union of the labelled cell boxes, and the columns are the
  labelled last-row cell boxes. Any appended line is a false addition. Pass: 0 additions.

## P4-3: page-number-pattern gate on dual-decode additions (cached replay)
- **Hypothesis:** the intended recovery is a page number, and the 1778 extras are not page numbers.
- **Mechanism:** keep a union addition only if its whole text matches `[0-9]{1,4}`, optionally inside
  brackets or with dashes on both sides, and it lies wholly outside the baseline text block (the P4-1
  region).
- **Controls, metrics and pass:** as in P4-1. 1835 rises and no metric worsens on any scan or model.

## P5-3: remaining 1778 missing reference lines (diagnostic)
- **Question:** under the P5-2 output, are the 7 missing 1778 reference lines recognition misses or
  segmentation and association failures?
- **Mechanism:** for each missing reference line, count overlapping recognised word boxes and test the
  overlap of their union box with IoU ≥ 0.5. No words means recognition; words whose union would associate
  means segmentation.
- **Outcome:** DESIGN_ONLY.

## P6-3: interior extras on 1780 (offline geometry diagnostic plus BLOCKED preprocessing)
- **Offline:** characterise the 8 interior extra lines per model on 1780 by box size, word count and stroke
  contrast. If most are tiny low-contrast components, small-speckle removal (distinct from the rejected
  large-component suppression) is the preprocessing candidate.
- **Measurement:** needs current-session authorization to run the pinned Tesseract. BLOCKED.

## P7-3: blend modes, coloured backdrop and identical-colour contribution (fixtures)
- **Fixtures** (red image at displayed x 40–80):
  1. **Multiply blend over a blue backdrop.** Isolated alpha is 255/255/0. Final pixels change at
     x = 50 and 70, so visible is 255/255/0.
  2. **Red image over an identical red backdrop.** Isolated alpha is 255/255/0. Visible is 0/0/0, because
     the contribution is invisible by construction.
  3. **Half-transparent white fill painted after the image.** Isolated alpha is 255/255/0. The page still
     differs where the image was, so visible is 255/255/0.
- **Outcome:** recorded per prototype as DESIGN_ONLY. Fixture 2 is expected to show the final-visibility
  contract's documented blind spot.

## P8-3: transparent background with 4×4 box sampling at a half-covered pixel
- **Fixture:**
  - Marker left edge at displayed x 10.5 at 72 DPI, so output pixel 10 is half covered on the 4× render
    grid.
  - `Background.TRANSPARENT`, `Sampling.BOX_4X`.
- **Expected:**
  - Pixel 10: alpha 127 or 128 and unpremultiplied red 255 (± 8), green and blue ≤ 8.
  - Pixel 11: opaque red.
  - Pixel 9: alpha 0.
- **Outcome:** a darkened unpremultiplied colour (red < 247) at pixel 10 is a premultiplication defect.
  Otherwise NO_DEFECT.

## P9-3: whitespace-only mismatches across the business comparison (diagnostic)
- **Hypothesis:** among all 944 reference positions × 6 native profiles, the only mismatches that disappear
  when all Unicode whitespace is collapsed are the two procurement separator cells, under every profile.
- **Mechanism:** this round's fresh native captures and the JVM business scorer. Compare the official
  ASCII-whitespace result with a diagnostic all-whitespace normalization. The diagnostic never replaces the
  official score.
- **Pass:** exactly 2 whitespace-only mismatches per profile, both procurement cells. NO_DEFECT. Any other
  case is reported.
