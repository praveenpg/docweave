# Paired historical and degraded OCR public text

Four original TIFF/PAGE pairs and both previously pinned single-language models are retained.
The JVM exports one lossless image-only PDF container per source. Both libraries process those
exact same PDF bytes, with no reference crop, transcription, line-order repair or per-case
model selection. Historical pages run first, then degraded pages. All eight upstream and
native source/model extractions succeed; all sixteen paired outcomes agree on actual Java21/25.
All eight previous native recognition, line-association and raster controls stay unchanged.

## Measured results

Lower edit counts are better within one source/model row; undeclared XML order prevents a
logical reading-error claim for three sources. Numbers are not merged into an overall ranking.

| Source | Model weights | DocWeave edits | Unstructured edits | Denominator / qualification |
| --- | --- | ---: | ---: | --- |
|1835 Arnim/Goethe|German|104|107|1059 code points; declared logical order|
|1835 Arnim/Goethe|German Fraktur|51|50|1059 code points; declared logical order|
|1879 Bebel|German|291|300|3049 code points; XML-sequence diagnostic|
|1879 Bebel|German Fraktur|334|334|3049 code points; XML-sequence diagnostic|
|1778 Bürger, degraded|German|419|413|948 code points; XML-sequence diagnostic|
|1778 Bürger, degraded|German Fraktur|371|374|948 code points; XML-sequence diagnostic|
|1780 Besuch, degraded|German|103|108|322 code points; XML-sequence diagnostic|
|1780 Besuch, degraded|German Fraktur|82|85|322 code points; XML-sequence diagnostic|

These are complete public output text diagnostics with the existing NFC plus ASCII-whitespace
normalization. Long-s, physical hyphens, case, punctuation and all missing/extra text remain.
No upstream paragraph block is treated as an exact OCR line box. The original complete native
line/geometry metrics remain in native21/25.json, independently of this public-text comparison.
References retain5378 code points per single-model profile. Training overlap and calibration
remain unknown; all sources are previous development data, not held-out acceptance.

## Explicit configuration differences

Native processing uses unchanged AUTO/ENGINE_DEFAULT Tesseract at verified source TIFF DPI
with the existing bounded provider, and source/render RGB identity checks. Upstream uses its
public ocr_only pipeline and supported PDF_RENDER_DPI environment at that same nominal source
DPI; this path does not use the hi-res pdf_image_dpi argument. Its actual Poppler raster frames
are recorded separately. Rendering, internal text-height resizing/filtering and element grouping
can differ; identical PDF bytes do not mean identical engine rasters or preprocessing.
No missing OCR or model packages are automatically installed.

The upstream language-code filter maps literal deu_latf to English, although that German
Fraktur traineddata is installed. Before extraction we verify that behavior and reject silent
fallback. An explicit supported frk filename alias contains exactly the same SHA256-pinned
Fraktur bytes. Native uses the original deu_latf filename; upstream passes languages=['frk']
against the recorded alias. This is an explicit model binding, not a stock naming-compatibility
claim or a different trained model. Both libraries use the same pinned Tesseract executable;
all requested/effective language identities and the literal mapping remain in the receipts.

## Reproduction and guards

1. Verify original TIFF/PAGE/model bytes using native-manifest.json. Compile the JVM
   CaptureScanComparisonSources launcher against current validation test classes. Run it with
   native-manifest.json, the original source cache and a NEW container directory. It verifies
   every original pin, metadata DPI, image dimensions and bounded RGB pixels before writing
   the complete frames.json descriptor. No reference text enters encoding or is written.
2. Prepare the three explicitly named local model bindings deu/deu_latf/frk. The last two are
   byte-identical copies of the pinned original Fraktur weights; no download/training occurs.
   Set the pinned Tesseract on PATH, its native library path where required and a writable
   MPLCONFIGDIR. Run capture_unstructured_scans.py with upstream-manifest.json, shared PDF
   containers, the model directory and NEW raw capture directory. It validates engine/model/
   source pins and the exact effective language; bounded child processes retain each failure.
3. Run PdfHistoricalTextCorpusTest with the original cache, native-manifest.json, NEW report,
   explicit engine/model paths, source.resolution=true, OMP_THREAD_LIMIT=1 and
   docweave.pdf.historical.container.cache pointing to the shared container directory.
   The property is opt-in; prior default tests/encoding behavior remain unchanged. Every shared
   PDF hash/descriptor and actual native source/render RGB frame are checked before scoring.
4. Run PdfInterimScanComparisonTest with docweave.pdf.interim.scans.originals/.native.report/
   .upstream/.report properties. It rechecks originals, references, model identities, exact PDF
   pairing, upstream language bindings and all complete output/failure denominators. Run both
   native extraction and paired JVM scoring under actual21 and25, choosing fresh report paths.
5. Run VerifyScanReports.java with native manifest, paired21/25, native21/25, the unchanged
   pdf-scan-language-composition/java21.json baseline and a NEW receipt. It requires all16
   outcomes equal and all8 previous native single-model observations unchanged. Only older
   generated PDF IDs are excluded from historical equality; same-byte current pairing remains
   separately checked. Original native runtime capture hashes are preserved in each report.

Raw original images/XML, generated PDF containers, recognized third-party text and stderr logs
stay outside Git. Only code, numeric outcomes, identities, dimensions and hashes are committed.
New owned controls preserve failed-output character/word penalties, canonical Unicode, physical
characters, original output order and the distinction between logical order and XML sequence.
Both full clean builds pass1033 tests (including real native OCR and paired tables) with strict
public docs. Focused paired scan scoring also passes on both actual JDKs; production APIs,
defaults, dependencies, previous scores and labels remain unchanged.
