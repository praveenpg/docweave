# Conservative native thin filled rules

Source geometry diagnosis used the pinned original eu-010 PDF and published ICDAR2013 XML from
../pdf-independent-cells/manifest.json. PDF content operators show separate thin filled rectangles
for black borders; larger header background fills include ICC colors. The old visitor discarded
all filled paths. Aligned mode consequently extracted10 body rows without the wrapped header.

The visitor now accepts one closed axis-aligned four-edge rectangle, no curves, thin dimension
>0 and<=1 unscaled user-space unit, length>=4 and>=8x thickness. Longitudinal caps are normalized
outward by half thickness (<=0.5); downstream closed-grid snapping/coverage tolerance remains0.5.
Colors are converted only after geometry acceptance. DeviceGray/RGB/CMYK and ICC are supported;
all RGB channels must be<=127, alpha>=0.99, normal blend, no soft mask. Compound, broad, skew,
curved, pale/white/transparent and unpainted paths do not qualify. Filled-and-stroked behavior stays
on the historical stroked-edge path. Clipping, overpaint and actual rendered visibility remain
outside this stored geometry model; tiny damaged gaps can still resemble intentional junctions.

This extends normal RULED_ONLY closed-grid recognition without a new dependency, public service
implementation or caller mode. Native multiline glyph grouping, table/cell metadata, image handling,
shared structured/work/operator budgets and chunk provenance remain in their existing components.
FilledPdfRule is a hidden stateless geometry helper; paint eligibility stays in PdfPageGraphics.

Owned PdfFilledRuleTest covers exact wrapped-header text/HTML, six anchors and source cell bounds;
crop/UserUnit2/all page quarter turns; explicit closed m/l/h rectangle paths; device black and ICC
black;0.125/0.5/1-unit fills; white/pale/alpha0.5/wide/curve/compound/skew/unpainted/gap negatives;
existing TableLimits and8 concurrent document calls. The five-unit missing vertical segment stays
rejected. These are extraction controls, not semantic/physical-scan accuracy evidence.

The independent test now gates eu-010 against fixed published11x2/22-cell expectations in both
RULED_ONLY and RULED_AND_MULTILINE_ALIGNED_TEXT. Original whole PDFs are used, without oracle crop,
raster/OCR, source repair, shifting row indexes, case/numeric/label correction or changing historical
reports. eu-010 gives22/22 positional cell-text matches including the wrapped header, one complete
Table in each mode. us-038 nested/double outer rules and eu-011 horizontal rules remain misses.
This establishes a narrow independent ruled multiline success, not positive borderless support or
full Unstructured PDF parity. Prior diagnostic reports remain unchanged in their historical folder.

Official upstream observation is saved separately: unchanged release/main0.27.28; the historical
0.27.27 reference remains six commits behind and optional freshness gating intentionally reports it.
Java21/25 reports, full test counts, source/report hashes and strict documentation results are saved
alongside this file. No external PDF/full annotation text/runtime/model is redistributed. Hosted
CI runs owned controls without downloaded corpus or actual OCR engine. No Windows VM required.
