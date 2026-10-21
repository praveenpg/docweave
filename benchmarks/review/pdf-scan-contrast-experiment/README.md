# Rejected local-contrast scan hypothesis (Java 21)

This experiment is **not promoted to a public preprocessing option**. All four fixed
historical/degraded pages and both pinned models complete8 real OCR calls, but the uniform
contrast-limited histogram hypothesis mostly regresses. No clip/tile retuning, per-source
winner, source crop, reference repair or additional text normalization follows the results.

| Source/model | Associated lines baseline→contrast | Exact lines baseline→contrast | Sequence edits baseline→contrast |
| --- | ---: | ---: | ---: |
|1835 German|21→21/22|0→1|104→181|
|1835 Fraktur|21→21/22|4→1|51→165|
|1879 German|49→49/50|42→42|291→291|
|1879 Fraktur|49→49/50|18→18|334→334|
|1778 German|17→12/38|0→0|419→475|
|1778 Fraktur|18→11/38|3→1|371→436|
|1780 German|9→9/10|1→0|103→108|
|1780 Fraktur|9→9/10|3→1|82→79|

Only1835 has complete explicit reference line order. The other three sources retain
**XML-sequence diagnostics, not whole-page CER**. The slight1780Fraktur edit reduction
coexists with fewer exact associated lines and does not qualify the option. Existing
managed whitepoint retains much better1778 coverage31/38 for both models, while its own
regressions remain documented separately. `whitepoint-comparison-java21.json` retains
all8 observations against that option as well. Full5,378 reference codepoints per model,
missing/extra lines, raw source/model/executable identities and outcomes remain.

## Frozen algorithm

Before original OCR, `selection.json` pins the three experiment/harness sources, manifest
and baseline report. The evaluator uses grayscale luminance after alpha-over-white,
64pixel tiles at300DPI scaled to actual source DPI, histogram clipping at twice mean bin
population, mass-preserving quotient/residual redistribution and cumulative output maps.
Uniform tiles use identity maps. Adjacent maps interpolate bilinearly at regular centers
with clamped borders and actual partial tile populations. This is a declared independent
variant of [contrast-limited adaptive histogram equalization](https://docs.opencv.org/4.5.5/d6/db6/classcv_1_1CLAHE.html),
not a claim of OpenCV bit parity. No external image-processing implementation/dependency
is added. This was a single predeclared parameter set, not source-dependent tuning.

Source dimensions and pixels remain fixed before preprocessing. Source pixels are never
mutated, output copies are flushed, input pixels/DPI are checked before output allocation,
lookup storage is capped at4,194,304 integers, and cooperative interruption preserves the
flag. This evaluation-only helper does not claim managed-provider admission/deadline or
production heap guarantees. The existing public preprocessing/defaults remain unchanged.

Four owned tests cover all256 uniform grays, transparency, independent64square histogram
values120/130→123/134, partial tiles, DPI, source immutability, invalid pixel/DPI/lookup bounds,
interruption and reuse. Together with9 existing historical controls they pass13 tests;
three-file private strict Javadocs and pinned formatting pass. The actual8-call replay
passes all9 historical test methods. Production remains unchanged from its1,200-test
Java21 verification; no new full-production run or Java25 check is claimed here.

Reproduce the historical optional test with the existing pinned engine, models and caches,
`OMP_THREAD_LIMIT=1`, this manifest, source-resolution=true and
`docweave.pdf.historical.contrast.limited=true`. Median/whitepoint composition is rejected.
The reused `CompareBackgroundScans` JVM tool validates complete populations and unchanged
source/reference/model/frame pins against both baseline and managed-whitepoint reports.
Raw scans, transcriptions, models and generated rasters remain outside Git.
