# Explicit native financial aligned-text tables

`PdfOptions.TableDetection.RULED_AND_FINANCIAL_ALIGNED_TEXT` adds only numeric-token
classification to the existing conservative aligned detector. Currency prefixes, balanced
accounting parentheses and Unicode minus qualify while complete-block row count, pitch,
left/right alignment, column gaps, source barriers, source ownership and limits remain.
Source tokens are never parsed as amounts or rewritten. Missing-value dashes, dates,
footnotes and prose remain ineligible. Existing native policies and OCR detection stay
unchanged. This run-based policy cannot provide exact glyph ownership for native-first
composition; existing construction restrictions are shared through one hidden policy query.
The detector factory exposes its internal strategy interface, retaining hidden implementation.

Owned full-PDF `(10.00)`/`$20.00` body cells form one exact three-row table only when explicitly
selected; old ruled/aligned/multiline modes reject it unchanged. Twelve token positives
cover four currencies, accounting forms, padding, Unicode minus, percentages and leading
zeros. Nineteen token negatives plus ragged/pitch/rule-barrier controls reject whole.
Ordinary numeric input keeps its ID, text, geometry and all metadata except the explicit
`aligned_financial_v1` evidence label. HTML escapes source text and does not infer headers.

## Complete original replay

The frozen NVIDIA manifest retains all11 original pages, all12 prior profiles plus the new
financial profile, all three complete statements and110 reference rows per profile.
There are **39 successful case/profile observations**. All36 historical observations are
recursively identical to the original report, including complete denominators and failures.
The new policy also scores **0/110**, with0/6 associated statement regions. Narrow financial
syntax does not solve this document's structural-recognition gap. No source/region/row
repair, reduced denominator or per-source winning profile is used.

The manifest inherits the earlier original/reference `frozen_at` metadata; its new profile
list and five production source hashes were frozen before this financial-profile run.
Source/reference/region pins are unchanged from `../pdf-unseen-business-nvidia/`.
The scorer source remains commit8c4e990, SHA256
`f88af667ef1c160906003b3dc2b235107d3c34d7c2e4b7bb25b8d39de04c85f6`.
Its generic enum-based capture path accepts the new profile without scorer changes.
Run `optionalCurrentBusinessRows` from that isolated frozen scorer with this manifest,
original source cache and a fresh Java21 report; do not replace the scorer or loosen checks.
The small local launcher validates that source identity before invoking the opt-in method.

All five scientific originals and three existing native profiles remain **15/15 exact**
for complete element IDs/types/text/metadata/warnings. Source pins and fingerprint tool
are retained by `../pdf-explicit-rectangle-clips/` and prior compound-wrap evidence.
No positive independent financial-recognition or overall PDF accuracy claim is made.

## Validation

Focused financial/borderless/native-first checks:25PASS. Final full Java21 `mvn verify`:
**1,200 tests PASS**, zero failures/errors/skips, including strict public docs and standalone
source compilation. Six changed production/test files also pass private strict docs;
pinned formatting and diff checks pass. Initial full verification caught the separate
Poppler benchmark's compile-time reference to a test helper; reflection now keeps that
helper an explicit runtime dependency, and both standalone compilation and all14 independent
renderer comparisons pass again. Historical evidence remains intact alongside the new report.
No Java25, additional OCR, model integration, release or publication is claimed.
