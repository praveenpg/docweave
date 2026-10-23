# Round 1 freeze (written 2026-10-22 before any round-1 measurement)

Baseline: development HEAD `6a02d01`, whose committed opt-in policies are the accepted state.
Authorization this session covers offline work only: PDFBox, owned fixtures, cached captures and
retained page rasters. No Tesseract, ONNX model, Unstructured, Poppler, download or remote service runs.
An attempt that needs one of those is BLOCKED with the exact prerequisite. Budget per attempt: about
15 minutes of investigation and one focused measurement. Each slot ends as IMPROVED, REJECTED,
NO_DEFECT, DESIGN_ONLY or BLOCKED.

## P1-1: tagged logical-structure order (probe)
- **Hypothesis:** the residual reversed pairs of tagged originals (holdout, plus recent and next as
  controls) follow the producer's logical structure order.
- **Source evidence:** holdout, recent and next carry a structure tree and MarkInfo. eLife is untagged,
  so the signal is absent there and its order must stay unchanged.
- **Mechanism:**
  - Map every page-1 element to the smallest structure-traversal position among its glyphs' marked-content
    identifiers.
  - Apply a stable sort by that position. Elements with no tagged glyph keep their relative order and are
    placed by their predecessor.
  - Run on top of the committed `SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS` elements, so the accepted gains stay
    the starting point.
- **Target and controls:**
  - Target: holdout (17 reversed pairs).
  - Recent and next must stay at 0 reversed; eLife must be identical.
- **Metrics:** full pair transitions against the pinned upstream capture (correct, reversed, unmatched),
  under both column layouts and both inline-script settings.
- **Pass:** holdout reversed pairs fall, with zero correct pairs turning reversed or unmatched in any
  paper or profile. A pass is evidence only; a runtime option would need strict-paragraph and
  17-source reconstruction checks.

## P2-1: proposal-recall versus assembly diagnostic (text-free)
- **Hypothesis:** the remaining NVIDIA reference rows split into rows whose source lines lie outside
  every detector crop (model recall) and rows inside a crop that assembly loses.
- **Mechanism:**
  - For each case region (the reference rectangle in points), find the source text lines with PDFBox.
  - Mark each line covered when its vertical centre lies inside a captured crop of that page; crops
    convert to points through `source.tsv`.
  - Count covered and uncovered lines per profile.
- **Metrics:** per profile and case, the number of uncovered region lines, compared with the missing
  reference rows.
- **Pass:** this is a diagnostic, so it cannot pass as an improvement. It ends DESIGN_ONLY and directs
  P2-2 to P2-4.

## P3-1: content-stream contiguity continuation (simulation)
- **Hypothesis:** the producer paints trailing rows in the same uninterrupted text run as the table body,
  so the table ends where the first non-table text object begins.
- **Mechanism:**
  - Define table glyphs as those owned by the provider rows.
  - Below the table, accept a line only when every glyph's content-stream index lies between the table's
    last body glyph and the first glyph outside the table's horizontal extent painted after it.
  - The line must also lie within the table's horizontal extent. Stop at the first failure.
- **Target:** the same three frozen targets (two Apple, one Amazon). The negatives are all 12 retained
  provider tables.
- **Pass:** all three targets are recovered, zero other lines are appended, and nothing is removed.

## P4-1: margin-band gate on dual-decode additions (cached replay)
- **Hypothesis:** the 1835 page number sits in a page margin band, while the 1778 extras sit inside
  the text block. Accepting secondary lines only in a margin band keeps the gain and drops the extras.
- **Mechanism:**
  - Start from the retained DUAL_UNION captures; the additions are the lines absent from the baseline
    capture.
  - The text block is the vertical extent of baseline lines with at least 2 words. Keep an addition
    only when it lies wholly above or wholly below that extent.
- **Controls and metrics:**
  - All four scans under both models.
  - Metrics: associated lines, missing lines, extra lines, joint line errors, global edits, and
    XML-sequence edits.
- **Pass:**
  - 1835 associated lines rise.
  - No metric worsens on any scan or model, and 1778 must not worsen.

## P5-1: column-major order inside the accepted rule band (cached replay)
- **Hypothesis:** the XML-order reversals that rose 30→43 come from the y-interleaving of split
  segments.
- **Mechanism:**
  - Inside the accepted rule's vertical band, emit segments left of the rule top to bottom, then
    segments right of it top to bottom.
  - Lines above and below the band keep their order.
- **Controls:** none of the 1835, 1879 or 1780 outputs may change. 1780 has an accepted border rule but
  no split, so it must stay identical.
- **Metrics:** association (must stay at 31/38) and XML-sequence edits, plus the evaluator's
  order-sensitive metrics.
- **Pass:** association is unchanged, XML-sequence edits fall on 1778 under both models, and controls are
  identical.

## P6-1: recognition-error taxonomy (offline diagnostic)
- **Hypothesis:** the remaining errors concentrate in identifiable pixel conditions (edge bands, faint
  strokes, show-through), and a preprocessing mechanism should target the dominant one.
- **Mechanism:**
  - For each scan and model, attribute missing and extra lines and character errors to page zones (edge
    band versus interior).
  - Attribute them also to local contrast quantiles, computed from retained page pixels and baseline
    word boxes.
- **Pass:** this is a diagnostic. It ends DESIGN_ONLY and directs P6-2 to P6-4. Measuring any of those
  needs Tesseract, which is not authorized here.

## P7-1: mask-aware isolated contribution
- **Hypothesis:** the isolated prototype fails the luminosity soft-mask fixture only because it also
  suppresses painting inside the soft-mask group, which is mask definition rather than page paint.
- **Mechanism:** suppress non-target painting only outside `processSoftMask`.
- **Controls:** the 8 existing owned fixtures.
- **Pass:** isolated passes 8/8 with no change to any other fixture's samples. This is a prototype, not
  an API.

## P8-1: rotation and crop-box offsets
- **Hypothesis:** crop geometry is correct under /Rotate 90, 180 and 270 combined with a non-zero
  crop-box origin.
- **Expected values:** derived independently from the documented contract (displayed frame, top-left
  origin, `pixelsPerUnit = dpi/72 x UserUnit`, outward rounding, intersection with the rendered page
  raster) before running.
- **Mechanism:**
  - Owned pages draw a solid marker at a known user-space rectangle.
  - Crop the marker's displayed rectangle and check the returned pixel extents and the colour of every
    returned pixel.
- **Pass:** zero deviations means NO_DEFECT. Any reproducible deviation is a defect that needs a
  failing-first regression before any fix.

## P9-1: independent content-stream review of reference v2
- **Hypothesis:** both separators that v2 restores are present in the source as painted glyphs with a
  whitespace Unicode mapping.
- **Mechanism:** check this directly from the content-stream operators, with no DocWeave extraction: show-text
  operands, glyph codes, ToUnicode mapping and advance positions.
- **Pass:**
  - Both separators are confirmed by raw operator evidence. v2 is then reviewed as an additive reference
    repair; v1 is kept, as is the 152/154 result.
  - If either separator is not confirmed, v2 is rejected.
