# Explicit provider-layout anchor-cell OCR — 2026-10-17

PROVIDER_LAYOUT_CELLS uses the same explicit word/layout interfaces and complete geometry
validation, then recognizes each eligible row-major anchor in TABLE_CELL purpose.
The PAGE word call retains ordinary remainder ownership. No model or engine default changes.
Bounded crop preflight, white padding, shared PAGE/CELL raw budgets, Unicode cell limits,
transactional ownership and coordinate/span metadata are described in docs/pdf-support.md.

## Measured tradeoffs

Unchanged20 published upstream-train examples, whole PNG embedded at150DPI, English
Tesseract5.5.3 PAGE SINGLE_BLOCK and CELL SINGLE_BLOCK, pinned cross-domain structure graph
from [ONNX evidence](../pdf-onnx-layout/README.md). No oracle crop/repair or per-case tuning.

| Policy | Tables | Complete positional-span topologies | Exact positional/span/text cells | Matched strict tokens /2070 | Observed tokens | Fully exact tables |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| PAGE words,300DPI |19 |14 |342 |724 |1751 |0 |
| CELL,300DPI |19 |14 |336 |791 |1765 |0 |
| CELL,600DPI |19 |14 |272 |671 |1886 |0 |

The300DPI CELL policy improves strict token overlap but slightly reduces exact cell
matches.600DPI is worse on these measures. None is a universal improvement or a new
default. More raster pixels do not add information to the original low-resolution PNGs.
Candidate geometry also changes with raster resampling, so600DPI is not an OCR-only study.
These are not held-out model samples; training overlap remains unexcluded. No confidence
probability/general accuracy is inferred. Raw images and transcriptions remain fetch-only.

Paired300DPI Java21/25 result entries are identical. Older Java25 diagnostic report lacks
the subsequently added top-level cell_segmentation field; its source configured the same
SINGLE_BLOCK policy explicitly. All report paths were fresh; historical PAGE-word/RULED
reports remain unmodified. comparison.json retains denominators/aggregate counts.

## Owned controls

Three Java PDF integration controls verify one PAGE plus five ordered CELL calls, blank
cells/spans/escaped authoritative replacement with outside words preserved, all-blank
fallback preserving complete original PAGE lines, CELL population after empty PAGE output,
raster preflight before CELL invocation and aggregate PAGE/CELL line ceilings.
Existing malformed-layout, geometry, cancellation, dimensional and word-ownership controls
continue to run. A real installed-Tesseract fixture now verifies both ALIGNED_WORDS and
PROVIDER_LAYOUT_CELLS against the same independently authored six-cell borderless table:
all six exact cells, exact outside prose and one source Image remain. Fixed known geometry
isolates the OCR path from model quality. Owned evidence XMLs are recorded here.

Full Java21/25 reactor verification and strict Javadocs; nine installed engine families
Historical verification details remain scoped to the recorded benchmark receipt.
Optional corpus selection uses PROVIDER_LAYOUT_CELLS with the existing local model/hash/
engine/cache/fresh-report properties. cellSegmentation defaults to SINGLE_BLOCK; explicitly
select AUTO or SINGLE_LINE only as a uniformly recorded alternative, never case-specific
oracle adjustment. Whole-page detection and broader physical-scan quality remain open.
