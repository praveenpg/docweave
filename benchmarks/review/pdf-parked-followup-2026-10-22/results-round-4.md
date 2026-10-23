# Round 4 results (measured against freeze `freeze/round-4.md`)

## P1-4: publisher JATS order for the eLife residuals — DESIGN_ONLY (indicative)

A private probe, running no engine, places page-1 elements of the row-fragment output in three sequences by
text inclusion (40-character keys): DocWeave's order, the upstream capture order and JATS document order.

| Paper | Mapped disagreeing pairs supporting upstream | Supporting DocWeave | Unmapped in JATS |
|---|---:|---:|---:|
| eLife | 8 | 0 | 12 (front matter) |
| holdout | 0 | 1 | — |
| recent (control) | 10 | 3 | — |

**Validity:** recent shows 19 element-level disagreements although its line-level metric has 0 reversed pairs,
so the inclusion mapping is noisy and the result is indicative only.

**Reading:**
- The eLife residuals are probably genuine ordering differences backed by the publisher's logical order,
  not upstream quirks.
- Four geometric and producer-order signals have now failed to resolve them without harming correct pairs
  (P1-1 to P1-3 here, and the earlier XY-cut and continuity attempts).
- Any further attempt needs a stated reading-order contract, such as front matter before body with sidebar
  membership. It must not reuse rejected geometry.

## P2: four slots used in rounds 1–3

## P3-4: independently labelled trailing-row transfer — BLOCKED

Needs independently labelled business statements with trailing-row labels, and their provider captures (Q1).

## P4-4: PDF applicability of the page-number gate — BLOCKED

Needs Q2 (the original ICC scanned PDF with an independent transcription including the page number) and
authorized Tesseract execution.

## P5-4: same-baseline join of over-split lines — REJECTED

Compared with P5-2:

| Model | Associated lines | Missing | Extras | Joint errors | XML-order reversed |
|---|---|---|---|---|---|
| `deu` | 31→33 | 7→5 | 11→7 | 311→298 | 31→34 |
| `deu_latf` | 31→32 | 7→6 | 9→7 | 260→250 | 31 (unchanged) |

The 1835, 1879 and 1780 outputs and both transfer pages are identical to P5-2. The `deu` reversals exceed
the frozen limit of 31. Association and ordering pull in different directions once more lines are matched.

## P6-4: small-speckle and thin-vertical-artifact removal — BLOCKED

Needs authorized Tesseract execution on the four scans under both models.

## P7-4: consumer contract decision matrix — DESIGN_ONLY

Fourteen owned fixtures were run across rounds 1–3: 8 base, 3 group and 3 blend.

| Semantics | Isolated (full suppression) | Isolated (mask-aware) | Final visibility |
|---|---|---|---|
| Plain, clip, alpha, group alpha, repeated, multiply | pass | pass | pass |
| Luminosity soft mask | **fail** (mask paint suppressed) | pass | pass |
| Nested alpha groups (64) | — | pass | pass |
| Isolated knockout group | — | pass | pass |
| Soft-mask `/BC` backdrop outside the bounding box | — | pass | pass |
| Later opaque overpaint | contribution kept (by definition) | contribution kept | hidden (0) |
| Identical-colour backdrop | contribution kept | contribution kept | **invisible by construction** |
| Blend-mode colour effect | not represented (alpha only) | not represented | represented as a change |

Decisions a consumer must make before any runtime API:
1. **What is reported:** "what the image contributes", meaning coverage and alpha under its own graphics
   state, or "what is visible on the final page". The two disagree on overpaint and identical-colour cases.
2. **Soft masks:** a contribution contract must render mask groups. Full suppression is wrong.
3. **Blend modes:** whether blend colour effects belong to the contribution (premultiplied colour) or only
   to visibility.
4. **Labels:** labels such as "visible" must come from a raster comparison, never from clip bounds or group
   alpha alone. The handoff already requires this.

No API is added. Source image extraction, crops and image bytes are unchanged.

## P8-4: crop budget and typed-error boundaries — NO_DEFECT

Tool: `CheckCropGeometry BUDGET`. All 11 boundary cases behave as documented:
- A crop or page exactly at its limit succeeds. One pixel over gives `IMAGE_LIMIT`, including the 4×
  supersampled raster for `BOX_4X`.
- A request outside the page or for a missing page gives `UNSUPPORTED_OPTION`.
- Exceeding `maxPages`, `maxRequests` or `maxEncodedBytes` gives `IMAGE_LIMIT`.
- No partial result is returned.

## P9-4: tabula-java provenance of the v1 omission — BLOCKED

Needs the pinned tabula-java build at commit `2cdf3b4f`, which requires an authorized download and execution.
The review item is already closable from P9-1's source evidence.
