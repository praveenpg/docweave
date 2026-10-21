# Explicit compact dark-grid production strategy

The public PdfTableLayoutProvider interface now exposes `ruled(limits, options, profile)` and
an explicit-profile overload of `preferRuled`. The nested immutable RuledProfile enum selects
STANDARD (historical64-pixel runs,32x24 minimum atomic cells) or COMPACT (32-pixel runs,16x12
cells). Implementations remain hidden. Construction reads no pixels, loads no engine/model,
and discovers no optional native runtime. Existing factories/direct RULED/word-layout barriers
retain STANDARD behavior; no policy is selected automatically from a source or reference labels.

This production implementation follows the [source-pinned prototype audit](../pdf-raster-rule-audit/README.md).
It keeps the dark RGB<=80 predicate, closed rectangular-grid/merge requirements, eight-pixel rule
thickness and all pixel/run/geometry/dimension/anchor/cancellation checks. Compact shorter runs
preserve evidence of some partial borders; the cell-size-only/old-run candidate failed an owned
broken-border control and was rejected. Arbitrary damaged borders can still resemble merges.
Colored, open, skewed or ambiguous grids are not repaired into accepted tables.

| Fixed whole-page150DPI production profile | Standard | Compact |
| --- | ---: | ---: |
| Emitted rule-only tables | 0 | 2 |
| One-to-one reference envelopes at IoU>=0.5 | 0 | 2 |
| Exact dimensions and complete positional/span anchor sets | 0 | 2 |
| Original source pixel-limit inputs | 1 | 1 |
| Additional resource failures | 0 | 0 |

All9 original cTDaR inputs/20 reference tables remain. s102 yields exact17x4/68-anchor and
5x4/20-anchor tables, at envelope IoUs approximately0.995/0.982. These same tables were already
model successes and300DPI rule successes; this is a150DPI rule-only improvement without an
optional model, not an additional model-pipeline success or general PDF accuracy. Other seven
admitted sources still yield no table. Modern raster provenance is not necessarily physical-scan
provenance; this development-selected set does not establish business/held-out/calibrated quality.
No text oracle is available; OCR quality is not measured. Annotation blanks/geometry are unchanged.

## Interior geometry, ownership and budgets

Actual production anchors are five-pixel ceil/floor interiors, not the prototype's full rule-
centerline rectangles. The comparator requires identical parent bounds/dimensions/spans and
exactly that interior transform, using predicted geometry only. It also verifies source/raster
identity against historical captures, all original JPEG/XML pins and paired runtime bytes.
Annotation association uses one-to-one geometry at IoU>=0.5 before topology scoring; no labels
select a crop, profile or prediction repair. Physical PDF extent/DPI mapping is retained.

Both profiles keep the same five-pixel inset matching supported border thickness. At the minimum
compact dimensions, interiors can be as small as6x2 pixels (or smaller after fractional rounding).
They remain positive, but can omit useful glyph pixels or contain insufficient text. Rendering at
higher DPI, cell OCR or caller scaling does not guarantee accuracy; no inset is silently reduced.
`rule_profile=compact_dark_v1` is appended only to compact table provenance and is retained in
`pdf_table_layout_provenance`. Historical provenance strings remain unchanged.

Rule-only and composed calls share the existing hidden validator and per-call counters. Both raw
layout sets are validated before precedence; discarded secondary anchors still consume the raw
combined budget, and secondary failure propagates. The provider never changes, flushes or retains
borrowed rasters, and decorators do not own secondary engine/model lifetime. Immutable profiles
and call-local geometry permit concurrent use when the supplied secondary is thread-safe.

Six new owned production tests cover unchanged default behavior, exact compact interiors and
immutable output/source ownership; broken/colored/gray/too-small grids; prospective and combined
raw-anchor budgets including discarded overlaps/pixel preflight; preserved error causes and
interruption;16 virtual-thread calls; and an actual image-only PDF through separate PAGE/CELL
strategies. That integration verifies all30 padded30x26 cell rasters, independent blue interior
markers, white padding,30 exact stub-returned HTML cells, retained outside word, coordinates and
one source image. Stub text is an owned plumbing expectation, not OCR recognition evidence.

## Reproduction and validation

Compile CaptureCompactRuledLayout.java with release21 against built core/PDF classes and runtime
dependencies. Run on actual21 and25 with four arguments: original JPEG cache, filename/SHA256 TSV
from the source manifest, NEW output directory and fixed DPI150. It embeds original JPEG bytes at
150DPI physical size, preflights pixels before rendering, then invokes the real public factories.
No diagnostic classpath overrides, private reflection, model or OCR are used in this capture.

```sh
python3 benchmarks/tools/compare_compact_layout.py \
  --capture21 /path/to/capture21 --capture25 /path/to/capture25 \
  --cache /path/to/ctdar \
  --manifest benchmarks/review/pdf-physical-scans/extended-manifest.json \
  --output /path/to/NEW-summary.json
python3 -m unittest discover -s benchmarks/tools -p 'test_*.py'
```

All66 captures/JDK are byte-identical (132 hashed numeric files). Actual21/25 clean verification
passes911 tests each with zero failures/errors/skips and strict Javadocs. The helper also passes
release21 compilation/strict standalone docs on both. All28 offline tooling guards pass, including
two new fractional-inset/invalid-interior controls. No originals, derivative images, weights or
transcriptions are committed. Optional Python tooling is not a JVM runtime dependency.

The fresh official upstream check still reports0.27.28/main1b02b4d189c260ed9b1aca29583203e233d853cb.
The strict historical0.27.27 guard therefore reports drift; this is not a freshness pass or a
reference/golden update. The reviewed six-commit delta remains unchanged, including OCR agent-load
error-cause propagation rather than a new raster table algorithm. Metadata is in upstream-status.json.
This explicit project profile does not claim Unstructured/commercial-service parity.

This useful scoped addition is the proposed0.1.0-beta.3 milestone, cumulative with the locally
prepared beta2 feature. Local candidate preparation/signing and actual remote publication remain
distinct. Broad colored/open/native-layout/OCR/held-out/calibration completion gates remain open.
