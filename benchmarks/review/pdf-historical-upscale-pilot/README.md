# Fixed 2x historical OCR pilot — no general promotion

One bounded hypothesis tests the existing public nearest-neighbor magnification decorator
on the complete 1835 scan under both pinned German models. This known source has a declared
300 DPI raster small enough to fit the existing 16-million-pixel ceiling after 2x enlargement.
Both profiles retain AUTO segmentation and ENGINE_DEFAULT thresholding. The recognition
raster is 3350 by 4572 at an explicitly configured 600 DPI; coordinates are restored through
the public decorator before the unchanged scorer. No crop, label repair, threshold search,
model substitution or per-case winner selection is used.

| Model | Character edits / 1,059 before -> after | Word edits before -> after | Exact associated lines before -> after | Missing lines |
| --- | --- | --- | --- | --- |
| German | 104 ->112 | 74 ->78 | 0 ->0 | 1 ->1 |
| Fraktur | 51 ->45 | 35 ->30 | 4 ->4 | 1 ->1 |

Both calls succeed. All 22 reference lines and 1,059 ordered reference code points remain
per model, with no extra predicted lines. This source supplies explicit reference order;
these whole-sequence character edits are qualified for this source. The mixed result does
not establish general improvement or a recommended model/magnification combination.
No production option, default or new runtime functionality is introduced: magnification
already exists. The trial is parked; do not repeat multiplier/DPI/threshold sweeps.
The other three historical sources were not run and are not silently included in this pilot.

## Fixed inputs and protocol

[Selection](selection.json) and the derived [manifest](manifest.json) were written before
inference. The manifest retains original reference/source-selection metadata from the earlier
four-source study; it is a one-source derivative, not a newly selected corpus or a rewrite
of historical reports. Both exact traineddata identities, source TIFF/XML identities and
the complete reference remain pinned. The baseline is the matching AUTO/ENGINE_DEFAULT
subset of the unchanged [source-resolution report](../pdf-scan-source-resolution/java21.json).

The original harness performs reference, source RGB, model and executable checks and retains
all failures. The isolated [unapplied patch](candidate.patch) configures recognition DPI at
2x and wraps the existing provider with `PdfOcrProvider.upscaled`; source-container DPI and
source-frame scoring remain unchanged. Experimental recognition dimensions/DPI and multiplier
are explicit in [the report](java21.json). Recognition dimensions are derived from the fixed
decorator multiplier; `ocr_frames` records the before-enlargement source frame, not an
independently hashed enlarged raster. [Comparison](comparison.json) retains every delta.
No Python evaluation, new dependency, model download, Java 25 or repeated full build occurs.

A JVM comparison verifies the complete two-profile population, unique unchanged baseline
keys, executable/model/reference/source-frame identities, declared ordered reference,
1,059-code-point denominator, source 300 DPI / recognition 600 DPI and multiplier 2.
The raw reports expose numeric outcomes/hashes, not transcription text. See [receipt](receipt.json).
The production 1,284-test Java 21 verification remains applicable because runtime source
is unchanged by this experiment.

## Reproduction and return condition

Use an isolated source copy at development baseline `8f31789`, apply the patch, compile the
historical test harness and invoke its existing optional original-source method with this
manifest, the original TIFF/XML cache, explicit existing Tesseract executable/tessdata and
fresh report properties. Set source-resolution mode, OMP_THREAD_LIMIT=1 and the required
native library path. Keep all other experimental preprocessing flags off. Both engine
calls share the normal 60-second per-call limit; the pilot process has a 150-second cap.
Do not apply the patch to production or silently compare a magnified raster with 300 DPI
engine settings. Raw assets and compiled pilot classes remain outside Git.

Return after other issues have been addressed or parked, with a different source-supported
recognition hypothesis. The remaining missing line and opposite model tradeoffs should
be investigated rather than hidden by reference-driven profile selection.
