# Round 2 results (measured against freeze `freeze/round-2.md`)

## P1-2: content-stream order signal — REJECTED

Tool: `SimulateSourceOrderSignals CONTENT`. Content order turns 218–398 correct pairs reversed in every
paper and profile.

It does flip all 17 holdout residual pairs and 12 of eLife's 43, so the residuals agree with local
producer order even though global producer order conflicts with the upstream geometric order.

## P2-2: competing-table ownership under row splitting — NO_DEFECT (guard added)

New owned test `PdfNativeRowLinesTest.competingTablesKeepDisjointOwnership`. On a page with two provider
tables:
- the upper table gains exactly one split row;
- the lower table is identical under both options, and so are unclaimed glyphs;
- the owned character total is preserved.

## P2-3: title block against the pinned upstream — NO_DEFECT

Tool: `CheckUpstreamTitleRows`, reading the retained hi_res output with no execution. All 12 reference title rows
(four per statement) appear only in upstream non-table elements, never inside a Table. DocWeave's table extent
matches upstream, so missing reference rows 0–3 are an issuer-HTML presentation difference.

## P3-2: column-gutter continuity — REJECTED (frozen definition degenerate)

Provider cell boxes touch, so the frozen gutter definition (gaps of more than 2 units between last-row column
ranges) yields no gutters. The rule then reduces to an extent check: the Apple targets are not recovered and
37 extra lines are appended. Disclosed and not amended: a working gutter definition would be a new attempt.

## P4-2: two-model consensus gate — REJECTED

Both models agree on 1778's two spurious additions, so consensus keeps them. Extra lines go 9→11 and
XML-sequence edits go 413→431 and 374→411. 1835 keeps its gain.

## P5-2: two-sided rule guard plus column-major order — IMPROVED (new runtime opt-in)

On the cached replay the frozen rule passes.

1778, under both models:

| Metric | No-rule baseline | Rule alone | Guarded rule with column-major order |
|---|---:|---:|---:|
| Associated lines | 27 | 31 | 31 |
| XML-order correct pairs | 321 | 422 | 434 |
| XML-order reversed pairs | 30 | 43 | 31 |
| XML-sequence edits | 413 / 374 | 413 / 374 | 342 / 296 |

The guard rejects the 1780 scanner border: 1 line has words left of it and 2 right. The 1835, 1879 and 1780
outputs, and both retained transfer pages (1778_0066 and 1780_00002), are identical to their baselines.

**Delivered opt-in:** `TesseractOptions.LineSplitting.WIDE_GAPS_AND_GUARDED_RULE_COLUMNS`. Defaults and
`WIDE_GAPS_AND_PRINTED_RULE` are unchanged.
- Owned tests:
  - `guardedPolicyIgnoresARuleThatDoesNotSeparateText`;
  - `guardedPolicyEmitsLinesInsideTheRunColumnByColumn`.
- Runtime equivalence: TSV rebuilt from the retained words and parsed by the public policy reproduces the
  evaluated output on all 12 observations (4 scans and 2 transfer pages under 2 models).

**Limits:**
- The engine itself was not re-run this session.
- These are development scans only; no independent two-column scan exists locally.
- Reversed pairs are 31 against the no-rule baseline's 30, while 113 more pairs are correct.

## P6-2: stroke-weight restoration — BLOCKED

Prepared but not run. Prerequisite: current-session authorization to run the pinned local Tesseract (`deu`,
`deu_latf`) on the four ALIGNMENT_COLUMN_V1 scans.

## P7-2: nested, knockout and soft-mask backdrop groups — DESIGN_ONLY

Tool: `PrototypeImageContracts GROUPS`. Every sample matches the hand-derived expectations under both the
mask-aware isolated and the final-visibility prototypes:

| Fixture | Isolated alpha (x 50 / 70 / 140) |
|---|---|
| Nested alpha groups | 64 / 64 / 0 |
| Isolated knockout group | 255 / 255 / 0 |
| Luminosity soft mask with white `/BC` outside its bounding box | 255 / 0 / 255 |

PDFBox honours the backdrop.

## P8-2: UserUnit and POINTS units — NO_DEFECT

Tool: `CheckCropGeometry USER_UNIT`, covering UserUnit 2.0 and 0.5 at 72 and 150 DPI.
- All 16 USER_SPACE and POINTS crops match the exact documented expectations.
- Inset crops are pure marker colour.
- The reported UserUnit and scale are correct.

The one listed difference is in the page-height expectation, which I computed in double precision
(624.99997…, flooring to 624). PDFRenderer multiplies in single precision (150f × 4.1666665f = 625.0f), and the
rendered raster really has 625 rows.

## P9-2: whitespace serialization guards — NO_DEFECT (guard added)

New owned test `PdfWhitespaceSerializationTest`. U+0009, U+00A0, U+2003, U+3000 and U+200B survive line and
paragraph text and the JSON round trip unchanged. In ruled-table cell HTML only the documented `&nbsp;`
escape applies.
