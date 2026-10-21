# Whole-page rule composition and fixed-resolution comparison

The existing public layout strategies are evaluated on9 pinned original cTDaR JPEGs at fixed150
and300DPI rendering profiles. Whole JPEGs are embedded at the same150DPI physical size in both
profiles; only PDFBox rendering resolution changes. No source repair, oracle crops, per-case
resolution choice, annotations or OCR enter inference. Modern business/scientific PDFs remain
the first development priority, historical scans second, degraded scans third. This development-
selected small set does not establish business-document coverage, held-out quality or calibration.

| Modern subset, 12 references / four inputs | 150 DPI | 300 DPI |
| --- | ---: | ---: |
| Model-only tables / exact topologies | 10 / 2 | 10 / 2 |
| Rules-only tables / exact topologies | 0 / 0 | 2 / 2 |
| Rule-preferred tables / exact topologies | 10 / 2 | 10 / 2 |
| Model-only or composed matching positional/span keys | 246 | 251 |
| Source pixel-limit outcomes | 0 | 0 |

At300DPI the rule-only profile recognizes s102's17x4/68-anchor and5x4/20-anchor tables. Those
are already model successes at both resolutions. Composition replaces their model geometry
with observed-rule interiors; no additional table becomes topology-exact. On s104 the model's
reference2 grid improves19 to29 matching keys, while reference3 regresses34 to29 (both have34
published keys). Both300DPI grids are5x6/30 anchors, still incorrect against published topology.
The net five-key modern gain does not establish a safe resolution upgrade or calibrated quality.

All9 inputs/20 reference tables remain at each resolution. At150DPI, model/composed yield12
tables, three exact topologies, and one original oversized-source OCR_LIMIT. At300DPI they yield10
tables, two exact topologies, and four OCR_LIMIT outcomes: s001–s004 exceed the unchanged16M
pixel ceiling. Thus the historical s002 success is lost to the budget. t00040 remains admitted
without any table under either profile. Do not exclude these limits or compare only successes.

Exact topology requires identical row/column counts and positional/span anchor sets after
geometry-only maximum-cardinality one-to-one envelope association at IoU>=0.5. Counts are not
PDF/OCR accuracy. Original annotation coverage and geometry caveats remain; missing blank labels
are not repaired. The older highest-positive-overlap metric is retained separately for continuity.

**Decision: retain current defaults.** Neither the existing composition nor uniform higher
resolution adds an exact-topology success on the prioritized modern inputs; higher resolution
also incurs new historical limits. No production change or new beta follows. The signed beta2
candidate stays unchanged. Existing explicit composition and its earlier scientific-crop positive
remain useful within documented scope.

## Controls and investigation targets

Three independent profiles use public interfaces: whole-page OnnxTableLayout, rules-only through
preferRuled with an empty secondary, and preferRuled over the same managed model provider.
Default16M raster pixels,256 combined raw anchors, table/work/cancellation ceilings and model
settings remain fixed. The model retains max-side800 normalization, score0.5, KEEP_HIGHER_SCORE
and its own30-second shared detector/structure deadline. Composition does not add a new total
native deadline across independent profiles. The decorator does not own the managed model.

Original JPEG dimensions are read as metadata without allocating a decoded source pixel raster.
The helper preflights rendered dimensions before PDFBox raster allocation, asserting actual
renderer dimensions match the prediction. This is diagnostic preflight, not a new production
resource or aggregate-memory guarantee. Render PNGs are encoded transiently for identity hashes;
no derivative image is persisted or committed. Physical source placement stays identical across
resolutions; annotations map through PDF extent/DPI, not floored raster-canvas ratios.

Current raster rules require every RGB channel<=80, uninterrupted runs>=64 pixels, thin bands
<=8 pixels, closed grids, atomic columns>=32 pixels and rows>=24 pixels. Composition uses five-
pixel interior insets at either resolution. Visual review of original rendered s104 shows colored
rules and compact rows: relevant counterexamples, not a proven cause for every grid rejection.
Next audit the remaining modern failures by rule-extraction/topology stage, including colored,
compact and open boundaries, before proposing an explicit new recognition policy. Preserve a
fixed independent set and controls against photographic/text false grids; do not tune per case.

## Evidence and validation

The150DPI stage isolation reproduces the earlier end-to-end no-gain observation in
[the historical composition evaluation](../pdf-ruled-scans/README.md); it is not a new capability.
Public model-only full table/anchor geometry reproduces the prior raw structure control on all
eight admitted inputs, after ignoring capture-local IDs. Model/composed tables, anchors and
outcomes are byte-identical at150DPI. New300DPI outcomes are deliberately not required to equal
the150DPI model control; original image identity and physical placement must still match.

All90 capture files/JDK at150DPI and63/JDK at300DPI match byte-for-byte between actual Java21
and25:306 hashed numeric captures. The final source-rendering helper also reproduces the initial
captured-raster150DPI experiment exactly, including PNG identities. It compiles with release21
and passes strict standalone Javadocs on both actual JDKs. All24 offline benchmark-tool guards
pass, including new checks for local-ID-independent control identity, changed cell geometry and
resolution changes that must not permit changed sources/placement. Production Java is unchanged;
the prior905-test baseline is not a new local full Maven verification claim for this diagnostic.
Original JPEG/XML, derivatives, weights and transcriptions remain fetch-only.

## Reproduction

Use original source and model identities from manifest.json and the linked source manifest.
Generate a JPEG filename/SHA256 TSV from the source manifest. Compile
CaptureRuledScanComposition.java with release21 against built core/PDF/ONNX classes and runtime
dependencies. On each actual JDK run dev.docweave.layout.CaptureRuledScanComposition with eight
arguments: original cache, pinned JPEG TSV, NEW output, detection graph/hash, structure graph/hash,
and a fixed DPI150 or300. Run all9 originals for every profile, without per-source overrides.
Then evaluate annotations only after inference, separately per resolution:

```sh
python3 benchmarks/tools/compare_ruled_scans.py \
  --capture21 /path/to/dpi150-java21 --capture25 /path/to/dpi150-java25 \
  --cache /path/to/ctdar --dpi 150 \
  --manifest benchmarks/review/pdf-physical-scans/extended-manifest.json \
  --baseline benchmarks/review/pdf-detector-structures --output /path/to/NEW-summary.json
# Repeat with dpi300 captures, --dpi 300, and a different NEW output.
python3 -m unittest discover -s benchmarks/tools -p 'test_*.py'
```

The comparator verifies all original source/annotation pins, paired bytes, source placement,
and hashed prior evidence before computing metrics. Only150DPI is required to reproduce that
baseline's exact model geometry/raster hash. Optional Python tooling uses the standard library,
is not a Java runtime dependency, and downloads nothing.
