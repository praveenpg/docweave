# Round 2 freeze (written after round 1 results, before any round-2 measurement)

The scope and authorization limits of round 1 still apply: offline work only, no engine, model or
Unstructured execution. Budget per attempt: about 15 minutes of investigation and one focused measurement.

## P1-2: content-stream order signal (probe)
- **Hypothesis:** untagged eLife paints page-1 elements in a producer order that places the 43 residual
  pairs as upstream does.
- **Mechanism:** the round-1 simulation with the signal replaced by each glyph's content-stream index.
  Every paper is reported. Recent, next and holdout are controls, and content order is expected to repeat
  their round-1 structure-order conflict.
- **Metrics:** full pair transitions per paper and profile.
- **Pass:** eLife reversed pairs fall and no correct pair turns reversed or unmatched in any paper or
  profile. Otherwise REJECTED; the eLife residual pairs flipped by content order are reported as evidence.

## P2-2: competing-table ownership under row splitting (owned test)
- **Hypothesis:** with `SPLIT_ALIGNED_TWO_LINE_ROWS`, two provider tables on one page keep disjoint, exact
  glyph ownership. A split in one table never moves, duplicates or drops a glyph of the other.
- **Mechanism:**
  - An owned two-table page: the upper table has an aligned two-line header, the lower table single-line
    rows.
  - Extract it with `PROVIDER_ROWS` and with the split option.
- **Pass:**
  - The upper table gains exactly one row.
  - The lower table's cells, rows and metadata are identical under both options.
  - Every visible glyph is owned at most once.
- A failure is a defect and needs a failing-first regression before any fix; a pass is NO_DEFECT.

## P2-3: title-block contract against the pinned upstream (cached output)
- **Hypothesis:** the pinned Unstructured hi_res output (retained `nvidia.json`, no execution) also leaves
  the four statement-title lines out of its Table elements. If so, missing reference rows 0–3 are an HTML
  presentation difference, not a DocWeave defect.
- **Mechanism:** on statement pages 4–7, check whether each title-line text occurs inside any upstream
  Table element's `text_as_html` or text, or in non-table elements.
- **Pass:**
  - If upstream excludes all titles from tables, the slot is NO_DEFECT for titles.
  - If upstream includes them, the slot is DESIGN_ONLY: a title-attachment contract is needed before any
    runtime work.

## P3-2: column-gutter continuity (simulation)
- **Hypothesis:** genuine trailing table rows keep the table's internal column gutters free of glyphs;
  footnotes and prose cross them.
- **Mechanism:**
  - Gutters are the horizontal intervals between consecutive unmerged column ranges of the provider's
    last row, wider than 2 units.
  - Below the table, accept lines in baseline order while no glyph centre falls inside a gutter, and every
    glyph lies within the table's horizontal extent. Stop at the first failure.
- **Target and pass:** the same 3 targets and 12 tables. All 3 targets must be recovered with 0 other
  lines appended.

## P4-2: two-model consensus gate on dual-decode additions (cached replay)
- **Hypothesis:** genuine secondary-decode additions are recognised by both models; spurious additions are
  model-specific.
- **Mechanism:** keep a model's union addition only when the other model's retained union adds a line
  whose box overlaps it with IoU ≥ 0.5.
- **Controls, metrics and pass:** those of P4-1. 1835 rises, and no metric worsens on any scan or model.

## P5-2: two-sided rule guard plus column-major order (cached replay)
- **Hypothesis:** a printed column rule has recognised words on both sides along its run, while a scanner
  border does not. Guarding acceptance this way keeps the rule on 1778 and rejects it on 1780.
- **Mechanism:**
  - Accept the found rule only when at least 3 baseline lines that overlap the run vertically have a word
    ending left of the rule, and at least 3 have a word starting right of it.
  - Then apply P5-1's column-major order inside the band.
- **Pass:**
  - 1778 keeps 31/38 associated lines.
  - 1778 XML-order reversed pairs are at most the accepted-rule value of 43, and XML-sequence edits do not
    increase, under both models.
  - The 1835, 1879 and 1780 outputs are identical to their unsplit baselines.

## P6-2: stroke-weight restoration for faint strokes — BLOCKED (prepared)
- **Hypothesis (from P6-1):** low-contrast strokes lose recognition, and a 3×3 grayscale minimum filter
  applied only inside low-contrast text-line boxes thickens them without global contrast change.
  CLAHE, median and whitepoint are not repeated.
- **Prerequisite:** current-session authorization to run the pinned local Tesseract (`deu`, `deu_latf`)
  on the four ALIGNMENT_COLUMN_V1 scans. The acceptance metrics are those of P4.
- **Not run.**

## P7-2: nested, isolated and knockout groups and the soft-mask backdrop (fixtures)
- **New owned fixtures:**
  - An image inside a nested group (outer group alpha 0.5, inner group alpha 0.5).
  - An isolated plus knockout group whose earlier opaque fill lies under the image.
  - A luminosity soft mask whose `/BC` backdrop is white outside the mask's form bounding box.
- **Expectations:** hand-derived, alpha at x = 50, 70 and 140:
  - nested group: 64, 64, 0;
  - knockout group: 255, 255, 0;
  - backdrop soft mask: 255, 0, 255 under the mask-aware isolated contract.
- **Pass:** each prototype's agreement with these is recorded as DESIGN_ONLY evidence.

## P8-2: UserUnit and POINTS units
- **Hypothesis:** USER_SPACE edges scale by `dpi × UserUnit / 72` and POINTS edges are divided by UserUnit
  first, as documented.
- **Fixtures:** UserUnit 2.0 and 0.5 pages. Expected pixels use the renderer scale reported by the crop
  (documented single-precision, from P8-1) and exact outward rounding.
- **Checks:** pixel extents, a marker-colour inset and a background ring.
- **Pass:** zero deviations means NO_DEFECT.

## P9-2: whitespace serialization guards
- **Hypothesis:** source whitespace characters in cells (U+0009, U+00A0, U+2003, U+3000, U+200B) survive
  element text, `text_as_html` and the JSON round trip unchanged. Only DocWeave's documented HTML escapes
  apply.
- **Mechanism:** owned ToUnicode fixture cells, checked with an owned JVM test.
- **Pass:** the test passes (NO_DEFECT, guard added). Any normalization found is a defect, reported first.
