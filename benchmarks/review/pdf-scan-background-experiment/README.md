# Explicit local background whitepoint normalization

One predeclared uniform hypothesis runs on all four previous historical/degraded scans
and both pinned models: local95th-percentile luminance whitepoints, tiles64pixels at300DPI
scaled by actual source DPI, bilinear interpolation, clamped border centers and grayscale
output. Parameters are fixed before any source OCR. AUTO/ENGINE_DEFAULT, original source
DPI, whole-raster geometry, scorer/reference sequence, models and executable remain fixed.
Raw scans/transcriptions/models stay outside Git. No crop, oracle geometry, source-specific
winner, reference repair or additional text normalization is introduced.

The prototype is evaluation-only. The managed public API uses
TesseractPreprocessing.LOCAL_WHITEPOINT with explicit source DPI. It runs only on PAGE
calls, inside existing process admission, input-pixel bounds and the shared total deadline.
TABLE_CELL calls retain their preparation. Owned copies are flushed on success/failure;
borrowed source images remain intact. Alpha is explicitly composited over white. All source
study rasters are opaque, so this does not change the prototype's luminance calculation.

## Complete fixed-source results

| Source/model | Associated lines baseline→managed | Exact associated lines baseline→managed | Sequence character edits baseline→managed |
| --- | ---: | ---: | ---: |
|1835 German|21→21/22|0→0|104→109|
|1835 Fraktur|21→21/22|4→5|51→52|
|1879 German|49→49/50|42→42|291→291|
|1879 Fraktur|49→49/50|18→18|334→334|
|1778 German|17→31/38|0→0|419→257|
|1778 Fraktur|18→31/38|3→5|371→206|
|1780 German|9→8/10|1→1|103→112|
|1780 Fraktur|9→8/10|3→3|82→93|

Only1835 declares complete logical line order; the other sequence edit values are
XML-sequence diagnostics, not validated whole-page CER. Local errors include missing
reference lines but do not charge extra predictions. The1778 gain includes more extra
predictions(11→15 German,10→15 Fraktur), and exact text remains poor. On1780 missing
reference lines increase1→2; extra predictions fall9→8. Every denominator and failure
remains in both complete numeric reports. Per single-model profile there are120 reference
lines and5,378 reference code points. Training overlap is unknown; these are development
observations, not held-out general scan accuracy, calibration or Unstructured ranking.

All8 calls succeed. Managed prediction hashes and metrics exactly reproduce the prototype;
reference/model/source RGB/DPI/frame invariants match the frozen baseline. Prototype reports
record filtered engine RGB hashes. Managed reports record original public input rasters and
the explicit preparation policy; they do not claim an externally observed internal PNG hash.
Owned actual-JVM subprocess controls inspect encoded page/cell behavior, original boxes,
source pixels, failure cleanup and provider reuse. Ten new test methods(three prototype,
four managed pixel/deadline and three managed process/factory controls) pass. Known legitimate
gray-field erasure and color loss are preserved negative controls, never reasons to enable
the policy automatically. Defaults and previous factory behavior remain unchanged.

Full Java21 verify passes1,185 tests with zero failures/errors/skips, including eight actual
managed OCR calls. Strict public/private Javadocs and pinned formatting pass. Java25 stays
deferred. No new dependency, default selection, table topology, release or publication changes.

## Reproduction

Use the pinned original sources/models from manifest.json and existing historical corpus
harness instructions. Source selection/model/component identities are unchanged from
../pdf-scan-source-resolution. selection.json freezes the prototype source/protocol before
OCR; managed-selection.json freezes the managed implementation before the public API replay.
Later formatting and comparator-only changes are recorded with final source identities.

Set OMP_THREAD_LIMIT=1, explicit engine/native-library paths and historical cache/report/
executable/tessdata/manifest properties. Select docweave.pdf.historical.source.resolution=true
and docweave.pdf.historical.background.whitepoint=true. Add
-Ddocweave.pdf.historical.background.managed=true to run the public API. The prototype and
managed hypotheses are independent; median composition is rejected. Reports require fresh
paths. The managed profile uses one provider per actual source DPI and language, without
resampling original pixels or using source labels as preprocessing input.

Compile benchmarks/tools/CompareBackgroundScans.java against the existing Java21 JSON
runtime classpath. Supply baseline, candidate and fresh report paths. For prototype versus
managed reproduction add --require-equivalent. The tool retains all8 fixed observations,
validates source/model/reference/frame identities and rejects changed prediction hashes or
metrics in equivalence mode. Reference XML order is never repaired against OCR output.
