# Explicit quarter-turn orientation evidence

The core accepts a caller-owned orientation strategy; the optional managed Tesseract adapter
uses installed OSD with engine0/segmentation0, a raw-score gate15 and explicit process limits.
The pinned model is fetch-only; see manifest.json. Model bytes and source document rasters
are not committed.

Reproduce the authored controls using TesseractOrientationInstalledTest with explicit local
executable/tessdata, a fresh `docweave.tesseract.report` path and
`-Ddocweave.tesseract.orientation.enabled=true`. The test generates its own serif/sans
1800x2400 RGB fixtures and rotates each by0/90/180/270degrees. It preserves raw scores,
accepted/undetermined decisions and model/engine hashes in `.orientation.xml`.
At the default threshold, serif controls pass real six-cell RULED extraction and restore
original source coordinates, outside prose and one original image at each quarter turn.
The sans fixture retains low-score abstentions; no font-specific threshold is selected.
Blank input abstains. The fixtures are authored controls, not independent scans.

The earlier generic provider-cell variant missed RED/10/20 on the same drawn grid:
observed `ITEM COUNT BLUE`. The generic path retains boundary pixels; direct RED-crop
probes returned no text with segmentation6 and `Reo` with segmentation7. The successful
RULED path uses its established border-removal strategy. This is an explicit change of
strategy for the positive control, not an improvement claim for generic provider-cell OCR.
The negative outcome is retained in manifest.json; no default was tuned to hide it.

Core controls separately check exact colored pixels and inverse bounds, all quarter turns,
page rotation/crop origins/UserUnit at72/144DPI, merged provider cells, aligned-word tables,
mixed native/raster ownership, abstention and typed cancellation/failures. Managed adapter
controls exercise strict UTF-8/OSD schema, direct subprocess arguments, low scores, sparse
input, stdout/stderr caps, active interruption, timeout, permit reuse and PNG cleanup.

This checkpoint does not close physical-scan quality, complex layouts or calibration gates.


## Independent corpus observations

The existing published-corpus harnesses accept `-Ddocweave.pdf.scans.orientation=true`
and `-Ddocweave.pdf.pubtabnet.orientation=true` independently. They retain the original
factory and report-entry shape when the flag is absent. Enabled reports record each
page's correction/accepted/raw-score/provenance even when OCR yields no elements.
They use the installed engine/model properties and the default raw threshold15 without
per-case tuning. Scans retain150DPI; PubTabNet retains the prior300DPI profile. Both
can compose the explicitly supplied detection and structure graphs, whose hashes are
recorded by the existing harness. No model or source data is downloaded by these tests.

These inputs have published table structure labels, not an independent orientation
classification oracle or probability-calibration labels. Corrected decisions, abstentions,
table matches and OCR outcomes remain distinct evidence.


## Official orientation/transcription fixtures

`independent-manifest.json` pins four original Tesseract test images and their shared
published transcription to upstream test revision232ff181c66516116ec0e84c4963f70de15050fd.
The0/90/180/270 source labels follow Tesseract5.5.3 `unittest/osd_test.cc` (linked and
fingerprinted in the manifest). Assets remain fetch-only; no raw image or transcription
is redistributed. Model-training overlap is unknown. The source does not establish
physical-scanner provenance, so these are official OCR regression inputs, not a new
physical-scan benchmark or calibration set.

Enable `PdfIndependentOrientationTest` with `docweave.pdf.orientation.cache` and a fresh
`docweave.pdf.orientation.report`, plus the installed engine/tessdata properties.
The fixed two-profile evaluation retains default threshold15 and an explicitly unfiltered
threshold0 diagnostic for every original image; no per-image threshold is selected.
Inputs are embedded whole and losslessly with one source pixel per PDF user unit, rendered
at72DPI to preserve source pixels without fractional dimension rounding. Each result
reports acceptance, correction, raw score and exact whitespace-normalized transcription.
Case/punctuation remain significant; recognized text is not stored or repaired.

The first complete installed-engine probe abstained on all four originals at threshold15
(raw scores10.86/9.30/10.99/9.62 in source0/90/180/270 order). At explicit threshold0,
all four corrections matched source labels and all four PDF outputs matched the published
transcription with one original image each. The unfiltered profile demonstrates integration
and oracle correspondence for these inputs; it is not a proposed default or reliable
confidence policy for other documents. An initial launcher omitted the native library path
and failed with engine exit127; the corrected explicit environment passed. Failed attempts
are not counted as passing evidence.


Final matched-profile full verification passes848 tests on actual Java21 and25 with strict
Javadocs, ten installed-engine families, the official fixture profiles and both corpora.
Paired result entries are identical. With orientation fields removed, physical and
SINGLE_BLOCK PAGE PubTabNet extraction results match the prior page-detection checkpoint
exactly:19 tables/14 complete positional-span topologies/320 exact cells/zero exact text
tables; zero fully correct physical tables. All24 corpus inputs abstain at threshold15.
The earlier AUTO PAGE diagnostics are retained separately and must not be presented as
an orientation-caused text difference. See summary.json for counts and boundaries.
