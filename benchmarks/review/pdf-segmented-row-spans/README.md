# Source-segmented horizontal rows and body spans

The preceding eLife baseline finds the correct10x3 dimensions but only12/20 logical
span/text anchors. Its source audit exposes matching three-part top/bottom paint,
first-column rules at every row, and absent separators over the spanning body cells.
The candidate manifest was frozen before implementation and source extraction.

`RULED_AND_SEGMENTED_ROWS` keeps closed-grid and existing partial-rule precedence,
then invokes a separate hidden PdfNativeTableDetector strategy. It uses the original
segmented axes and horizontal ordinates, infers only column-local rectangular row
spans, validates every contained glyph and commits no ownership on rejection.
Native output has no model dependency and makes no semantic header declaration.
Geometry is labelled inferred_cell_bounds, not observed vertical borders.
See docs/pdf-support.md for the exact conservative pattern and ambiguity limits.

## Paired outcome

| Same original eLife table | Tagged/native/model baseline | New native policy |
| --- | --- | --- |
| Matching logical span keys |12/20|20/20|
| Exact logical span/text anchors |12/20|20/20|
| Header span/text anchors |3/3|3/3|
| Observed anchors |22|20|
| Missing/extra logical keys |8/10|0/0|

Both actualJava21/25 process the complete29-page original and agree. The first table
was selected and independently inspected before candidate extraction; no labels,
ROI, source crop, text repair, model threshold or source-specific configuration enters
inference. The model still runs/validates under native-first composition even though
its overlapping table is discarded. Publisher JATS is logical cell/text truth, not
physical PDF border annotations. This is one development improvement, not broad
scientific or historical-table accuracy.

All22 other fixed regions are capture-equal to the preceding tagged baseline on
bothJDKs (44unchanged comparisons); all46source frames agree. Includes all earlier
real/synthetic cases, the135/136 publisher result and the13/15 secondPLOS result.
No miss or case was dropped. Hashes and complete denominators are in replay-results.json.
Raw PDFs/XML/images/captures stay off-Git.

## Contract and replay

Six new authored tests cover rectangular spans and multiline cell text; whole-component
rejection for malformed/duplicate/missing paint, wrong endpoints, missing outer rules,
empty cells, crossing/rotated/out-of-page native text and raster/interior-vertical
barriers; multiple disjoint components; retained outside text; typed row/column/cell/
character limits, shared work exhaustion/cancellation; public strategy selection,
rowspan HTML/geometry, old-mode preservation and raw-provider anchors charged even
when native output wins. Existing partial-rule tests also pass. Default factories,
all existing enum modes, public record shapes and dependencies remain unchanged.

Use CaptureNativeModelTables with the frozen source cases/graph hashes, native-first,
-Ddocweave.pdf.native.tagged.first=true, -Ddocweave.pdf.native.iso.dates=true and the
additional -Ddocweave.pdf.native.segmented.rows=true uniformly. Scoring requires the
explicit -Ddocweave.pdf.scientific.segmented.rows=true as well as tagged-first and
publisher manifest/cache/captures/report properties. Historical profiles retain false
when the runtime field is absent; they cannot silently score this changed native policy.

Full regression/Javadoc and strict new-helper documentation results are recorded in
verification.json. All public behavior is opt-in. Known hard-hyphen text misses,
ambiguous wrapped headers, physical/degraded scan text, broader logical reading order,
calibrated confidence and final acceptance remain open. No release/tag/signing/Central.
