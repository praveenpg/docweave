# Fixed whitepoint and sparse-text composition: not promoted

One uniform combination of existing public options is fixed before inference: managed
LOCAL_WHITEPOINT plus SPARSE_TEXT/PSM11, with ENGINE_DEFAULT thresholding and actual TIFF
source DPI. All four historical/degraded sources and both pinned models remain. No new
preprocessor, model, dependency, production default or source-specific routing is added.
Baseline is the same managed whitepoint with AUTO/PSM3, not untreated source pixels.

| Source/model | Associated lines AUTO→SPARSE | Exact lines | Extra predictions | Sequence edits |
| --- | ---: | ---: | ---: | ---: |
|1835/deu|21→22/22|0→1|0→11|109→144 validated order|
|1835/deu_latf|21→22/22|5→6|0→11|52→121 validated order|
|1879/deu|49→48/50|42→34|1→14|291→297 XML only|
|1879/deu_latf|49→48/50|18→15|1→14|334→341 XML only|
|1778/deu|31→29/38|0→0|15→54|257→506 XML only|
|1778/deu_latf|31→29/38|5→5|15→55|206→476 XML only|
|1780/deu|8→9/10|1→1|8→69|112→237 XML only|
|1780/deu_latf|8→9/10|3→3|8→70|93→240 XML only|

All8 actual public-provider OCR calls succeed. Coverage gains on1835/1780 coexist with
many more unmatched predictions and worse sequence errors. The1778 coverage gain from
whitepoint alone is reduced. Do not recommend this composition as a general improvement;
keep AUTO unchanged. No threshold or preprocessing parameters are retuned after results.
Only1835 has independently explicit complete line order; the other sequence edits are
not validated page CER. Per-line errors do not charge extra predictions. Every reference,
prediction count, model hash, source/render identity and failure slot remains in the report.

The JVM comparator accepts only an explicitly declared manifest transition from AUTO to
SPARSE_TEXT under managed LOCAL_WHITEPOINT/ENGINE_DEFAULT. Without that declaration its
historical segmentation-equality guard remains mandatory. Source/model/reference/frame
checks stay in force. Six corrupted policy/source/model comparisons are rejected: changed
segmentation, model hash, original RGB hash, managed preprocessing, undeclared transition
and unsupported transition. Existing prototype/managed equivalence `.results` reproduce
exactly, including every previous metric. No historical captures or pins are rewritten.

Reproduce with `PdfHistoricalTextCorpusTest`, this exact manifest and the existing8-call
managed whitepoint command documented in `pdf-scan-background-experiment`. Use Java21,
OMP_THREAD_LIMIT=1, explicit pinned executable/tessdata/cache, actual source resolution,
background.whitepoint=true and background.managed=true, plus a fresh report path. The
selected harness run passes9 methods, including its owned controls; do not call this
8newJUnit tests or a new full-reactor run. JVM comparator accepts baseline, candidate,
fresh output and the explicit manifest path. Private strict Javadocs, scoped formatting,
standalone Java source compilation and guard checks pass. No Java25/publication.
