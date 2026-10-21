# Actual-word OCR correction

Explicit `PdfWordOcrProvider.rotated` and `deskewed` factories preserve actual-word capability
for word-driven PDF modes. Existing `PdfOcrProvider` factories retain their line-only contract.
Line and word correction compose the same owned bounded canvas/inverse-polygon helper.
No source pixels are modified, no provider word boxes are invented, and no table-detection
pixels/topology are corrected by this OCR-only decorator.

Raw line/character budgets apply before clipping. Tokens entirely in added white margins
are discarded; retained line text joins retained actual token strings in encounter order.
Source-clipped word rectangles retain their supplied token identity. Restored line envelopes
also include their restored child boxes, avoiding floating-point discrepancies from separately
clipped parent/child polygons without arbitrary coordinate tolerances or altered word boxes.
A seeded200-case test initially exposed this issue; the fix and all edge controls pass.

Owned controls cover exact quarter-turn pixels/word bounds and input purpose/ownership,
45degree polygon clipping, null/malformed output, raw limits before discarded padding,
expanded-canvas preflight, cancellation before/after recognition and reuse, small-angle
capability/CELL passthrough,20virtual-thread calls and provider-layout source-frame integration.
The existing line correction and projection estimator regressions are retained.

Real installed-engine controls use the unchanged pinned official Tesseract rotation fixtures
and transcription from ../pdf-orientation/independent-manifest.json. Four supplied published
corrections are tested through actual-word OCR and whole-image PDF embedding at72DPI with
one source pixel per PDF user unit. Each must preserve actual-word/source-line containment,
exact whitespace-normalized source transcription, one recognition call and one original Image.
Corrections are supplied labels: this is not automatic orientation or calibrated confidence,
model-training independence or physical-scanner provenance. No raw images/transcription/models
are redistributed.

Enable the existing `docweave.pdf.orientation.cache`/`.report` and installed executable/tessdata
properties. `PdfIndependentWordRotationTest` writes `.report.words.json`; the earlier independent
OSD fixture test retains its own primary report and score policies. No engine/model/data download
or automatic capability discovery occurs. PAGE-only deskew passes CELL recognition directly to
the supplied provider and its own budgets; it does not infer cell correction or table geometry.


Full actual Java21 and25 verification passes860 tests each with strict Javadocs and
ten installed-engine families. Both runtimes retain all60 actual words in each of the
four official fixtures, exact normalized transcription and valid source/parent bounds.
Paired report entries are identical. The separate OSD primary reports also pass their
previous default/unfiltered policies; supplied-angle word controls do not override or
relabel those OSD observations. This checkpoint adds capability-preserving correction,
not a general table-recognition or confidence-calibration claim.
