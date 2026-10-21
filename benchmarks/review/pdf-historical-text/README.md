# Historical scan text baseline

Two prospectively selected OCR-D pages (1835 and 1879) were evaluated through the public
DocWeave OCR path, using both pinned German and Fraktur models on each page. Source,
reference and model identities are frozen in manifest.json and selection.json. Raw images,
transcriptions and models stay outside Git. Training overlap is unknown; these are development
measurements, not held-out accuracy or representative historical/degraded-scan acceptance.

| Page | Model | Exact geometrically associated lines | Unmatched reference / prediction lines | Whole-page character edits |
| --- | --- | --- | --- | --- |
| 1835 | deu | 0/21 | 1 / 0 | 104/1059 (9.82%) |
| 1835 | deu_latf | 4/21 | 1 / 0 | 51/1059 (4.82%) |
| 1879 | deu | 42/49 | 1 / 1 | Not validated: reference line order undeclared |
| 1879 | deu_latf | 18/49 | 1 / 1 | Not validated: reference line order undeclared |

Both models run uniformly on both sources; there is no per-page winner routing. Geometry
associations use text-blind IoU, retain missing reference lines and report extra predictions.
Local reference-line error counts include missing lines but do not charge extra predictions;
they must not be presented as complete page CER. NFC and whitespace normalization preserve
long-s, case, punctuation and hard hyphens. No reference is repaired against OCR output.

The 1879 PAGE XML declares region order but omits line order. Three XML line positions differ
from physical vertical order. Its whole-sequence distances remain xml_sequence_* diagnostics,
not validated logical-page CER/WER. See reference-order-qualification.json. Independent order
review remains on the return queue; all original reference content and sequence are retained.

Original decoded scans are placed losslessly in image-only PDF containers at one pixel per
point, then rendered at 72 DPI. Source and OCR-frame RGB hashes match for all four runs on
both JDKs. These containers are explicit derivatives, not original publisher PDFs. No image
crop, deskew or orientation correction is applied. Tesseract 5.5.3 uses OEM1/PSM3 and one
OpenMP thread. External executable and model paths are explicit; the library downloads none.

Full clean verify passes 991 tests on Java21 and Java25, including four actual OCR calls each.
All text, geometry and raster report fields agree across JDKs; runtime identities and random
PDF container IDs naturally differ. Six owned harness methods cover strict reference XML,
ordering, Unicode edits, geometry association and pixel-preserving containers. Full builds
also validate public Javadocs; private harness Javadocs are checked separately.

To reproduce, pass all four Maven properties: docweave.pdf.historical.cache (directory with
manifest-named XML/TIF files), docweave.pdf.historical.report (output JSON),
docweave.pdf.historical.executable and docweave.pdf.historical.tessdata. Set OMP_THREAD_LIMIT=1
and any platform-specific native library path required by the explicitly supplied executable.
Without these properties, ordinary builds run owned harness controls without external OCR.

Next: independently labelled degraded scans and review of the undeclared historical line
order. This validation-only milestone does not change extraction behavior or create a release.
