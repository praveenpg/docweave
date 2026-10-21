# Fixed sharpening pilot — mixed, not promoted

The complete retained 1778 scan shows blurred print, bleed-through and acquisition background.
One fixed source-only hypothesis applies a 3x3 Gaussian unsharp mask at amount 1:
`clamp(2*luminance - rounded_blur)`, using separable weights `[1,2,1]`, replicated borders
and alpha composited over white. It preserves raster dimensions and source coordinates;
no region selection, reference-derived crop or parameter search occurs.

Both pinned models run under AUTO/ENGINE_DEFAULT at the original declared 300 DPI. All
38 original reference lines and their text remain for each profile. The original PAGE
file does not declare complete line order: the following sequence errors are XML-sequence
diagnostics, not validated page character error rates.

| Model | Associated lines before -> after /38 | Exact lines | Missing lines | Extra predictions | XML-sequence character edits |
| --- | --- | --- | --- | --- | --- |
| German | 17 ->18 | 0 ->1 | 21 ->20 | 11 ->9 | 419 ->416 |
| Fraktur | 18 ->17 | 3 ->4 | 20 ->21 | 10 ->10 | 371 ->372 |

**Decision: park.** The German gains coexist with lost Fraktur association and slightly
worse character edits. No production preprocessor, automatic routing, default or general
recommendation follows. The other three scans are not evaluated in this pilot. Return to
other open issues rather than repeating kernels, amounts or threshold/model combinations.

[Manifest](manifest.json) records the selection and fixed policy before recognition, retaining
parent source/model pins. The [unapplied harness patch](candidate.patch) replaces only the
existing experimental filter call/property with the archived [filter source](filter-source.txt).
Compile the copied Java harness/filter and invoke its optional historical test with this
manifest, verified 1778 TIFF/XML cache, pinned executable/tessdata, source-resolution mode,
OMP_THREAD_LIMIT=1, sharpening enabled and a fresh report. Source/render RGB identity,
borrowed-raster immutability, source DPI, model/runtime and every failure slot remain guarded.
Engine-frame hashes identify the intentionally altered raster. Exactly two OCR calls occurred.

The copied harness initially retained its old `MEDIAN_3X3_REPLICATED_RGB` report descriptor,
although its actual filter call was `HistoricalSharpenTrial.apply`. The
[raw capture](raw-capture-java21.json) is preserved unchanged. The derived [audited report](java21.json)
explicitly corrects only that descriptor and binds the original capture SHA-256; all numeric
metrics and frame/model/source hashes remain unchanged. This metadata error does not justify
repeating inference, and the correction is not a scoring or source-text repair.

A JVM comparator verifies both complete profiles against the unchanged source-resolution
baseline, with matching runtime/model/reference/source/frame/DPI identities. See
[comparison](comparison.json) and [receipt](receipt.json). No Python evaluation, dependency,
model download, Java 25 or new full-reactor run is part of this negative pilot.
