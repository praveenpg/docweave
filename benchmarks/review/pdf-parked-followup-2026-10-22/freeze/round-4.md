# Round 4 freeze (written after round 3 results, before any round-4 measurement)

The scope and authorization limits are unchanged. P2 used all four slots in rounds 1–3.

## P1-4: independent publisher-order check for eLife residual pairs (diagnostic)
- **Question:** for each element pair behind eLife's 43 residual reversed pairs, which order agrees with the
  publisher's JATS XML?
- **Mechanism:** a private probe that runs no engine:
  - Map page-1 elements of the row-fragment output to JATS document order by text inclusion (front-matter
    nodes before body nodes, in XML order).
  - Elements without a JATS counterpart are "unmapped".
- **Outcome:**
  - If DocWeave's order agrees with JATS for most mapped residual pairs, record NO_DEFECT for those pairs:
    an upstream-order difference under an independent logical reference.
  - If upstream's order agrees, record DESIGN_ONLY: a reordering contract would be needed.
- Public output is counts only.

## P3-4: independently labelled trailing-row transfer — BLOCKED
Prerequisite:
- Independently labelled business statements (not the Apple, Amazon or NVIDIA development sources) whose
  labels include trailing rows below provider tables (Q1).
- Provider captures of those statements.

ICDAR-2013 negatives (P3-3) cover false additions only and contain no positive trailing rows.

## P4-4: PDF applicability of the page-number gate — BLOCKED
Prerequisites:
1. Q2: the original ICC-bearing scanned PDF of the 1835 page with an independent line transcription
   including the page number, plus a transfer scan.
2. Current-session authorization to run the pinned Tesseract, to integrate and measure the gated secondary
   decode in the historical harness.

## P5-4: same-baseline join of over-split lines within a column (cached replay)
- **Hypothesis (from P5-3):** consecutive output lines on the same side of the accepted rule that overlap
  vertically by at least half the smaller height are one visual line split by Tesseract line identity.
- **Mechanism:** apply the join to the P5-2 output (guarded rule plus column order) inside the rule band only.
  Joined text is the space-joined original texts in output order, and the joined box is their union. No word
  is dropped.
- **Pass:**
  - 1778 associated lines rise above 31 under both models.
  - XML-order reversed pairs do not rise above 31, and no other metric worsens.
  - The 1835, 1879 and 1780 outputs and the two transfer pages are identical to the P5-2 output.

## P6-4: small-speckle and thin-vertical-artifact removal — BLOCKED
- **Hypothesis (from P6-3):** removing isolated dark components smaller than 0.75 median word heights, or
  thinner than 0.15 and taller than 5 median word heights, outside any recognized word box removes 1780 noise
  lines without harming text.
- **Prerequisite:** current-session authorization to run the pinned Tesseract on the four scans under both
  models.
- **Not run.**

## P7-4: consumer contract decision matrix (design)
- **Mechanism:** consolidate the 14 fixtures (8 base, 3 group, 3 blend) into a matrix of the semantics each
  prototype satisfies:
  - isolated contribution with mask awareness;
  - final visibility;
  - the known blind spots (identical colour, blend colour effect, clip-only labels).
- **Outcome:** DESIGN_ONLY. It states the decisions a consumer must make before any runtime API, with no API
  added.

## P8-4: crop budget and typed-error boundaries
- **Expected values (derived from the contract):**
  - At exactly `maxCropPixels`, the crop succeeds; at one pixel more, `IMAGE_LIMIT`.
  - A request entirely outside the page gives `UNSUPPORTED_OPTION`.
  - `maxRequests` + 1 requests gives `IMAGE_LIMIT`.
  - A page above `maxPagePixels` gives `IMAGE_LIMIT`, checked before rendering.
  - A missing page gives `UNSUPPORTED_OPTION`.
  - No partial result is returned on any failure.
- **Pass:** all boundaries are as documented (NO_DEFECT). A deviation is a defect, reported with a failing
  regression first.

## P9-4: provenance of the v1 separator omission — BLOCKED
Prerequisite: the pinned tabula-java build at commit `2cdf3b4f`, which is not available offline. Running its
extractor would confirm whether its glyph handling drops U+0009-mapped glyphs and produced the v1 CSV.
Downloading or executing it requires authorization. P9-1 already confirms the source evidence, so the review
item is closable without it.
