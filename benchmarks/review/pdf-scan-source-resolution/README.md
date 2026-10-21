# Source-resolution scan replay

This fixed development study uses the same four original scans, complete PAGE references,
two pinned German models, AUTO segmentation and three threshold policies as the
[previous threshold study](../pdf-scan-thresholding/README.md). Selection and source identities
were frozen before the new source-resolution recognition calls. No additional sources,
Python dependencies, OCR models, cropping, enhancement or reference repairs are introduced.

Original TIFF X/Y resolution declarations are300DPI for1835/1778/1780 and600DPI for1879,
with inch units. The Java harness verifies those metadata fields against the frozen case
values before extraction. These declarations are not independent physical measurements.

The lossless derivative PDFs now have physical dimensions matching those declarations.
Rendering uses the same declared DPI, and the adapter receives the explicit numeric DPI.
Exact original/rendered decoded RGB equality is asserted **before** OCR; there is no
post-render resizing or pixel correction. One outward float step on derivative page bounds
prevents PDFBox flooring a raster dimension short. That rounding is permitted only while
the exact source-sized RGB hash check passes. Original image bytes remain unchanged.

Public displayed PDF point coordinates are mapped back to source pixels using DPI/72
before the unchanged association/transcription scorer runs. Owned RGB/gray/packed-color
controls verify exact raster replay at72/300/600DPI. A separate fake-provider test exercises
the real public PDF extraction path and independently checks returned pixel coordinates.

## Observed tradeoffs

Actual Java21.0.12.1+12-LTS and25.0.4+7-LTS each pass full offline `clean verify` with
1001 tests, zero failures/errors/skips, strict public Javadocs and24 actual OCR calls.
All24 paired per-case results agree except random PDF document identifiers. All original
and rendered pixel frames remain identical to the prior fixed study. Strict private
Javadocs pass for the changed harness and standalone comparison helper on both JDKs.

Complete per-case results, including misses and extra predictions, are retained; no policy
is selected by reference text. The source-resolution setting is not a universal improvement:

| Fixed case/policy | Prior72DPI engine handling | Explicit source resolution |
| --- | --- | --- |
| 1835 Fraktur, adaptive Otsu | 696/1059 character edits;0 geometry-associated lines | 59/1059 edits;20 associated,5 exact lines |
| 1835 Fraktur, Sauvola | 44/1059 edits;7 exact lines | 58/1059 edits;6 exact lines,1 extra prediction |
| 1835 Fraktur, engine-default threshold | 51/1059 edits;4 exact lines | Same edits and exact lines |
| 1879 German, all threshold policies | 49/50 associated;42 exact,1 missing,1 extra | Same counts |
| 1778 Fraktur, Sauvola | 6/38 associated;0 exact,32 missing,28 extra | 23/38 associated;4 exact,15 missing,12 extra |
| 1778 Fraktur, engine-default threshold | 18/38 associated;4 exact | 18/38 associated;3 exact |
| 1780 Fraktur, adaptive Otsu | 1/10 associated;0 exact,9 missing,3 extra | 7/10 associated;2 exact,3 missing,14 extra |
| 1780 Fraktur, Sauvola | 8/10 associated;3 exact,6 extra | Same associated/exact counts;12 extra |

Only1835 declares complete logical line order. Other full-page edit counts remain labelled
`xml_sequence` diagnostics; undeclared XML order is not repaired or represented as logical
reading-order CER. Additional predictions may correspond to unlabelled manuscript, decoration
or acquisition marks; they are retained, not automatically classified as hallucinations.
Training overlap remains unknown. These four development pages do not establish held-out
accuracy, broad language/font coverage, physical-scan acceptance or PDF completion.

## Reproduction

Use actual JDK21 or25 and the already pinned executable/model/source cache:

```sh
OMP_THREAD_LIMIT=1 mvn -B -ntp clean verify \
  -Ddocweave.pdf.historical.cache=/path/to/verified-source-cache \
  -Ddocweave.pdf.historical.manifest=benchmarks/review/pdf-scan-source-resolution/manifest.json \
  -Ddocweave.pdf.historical.source.resolution=true \
  -Ddocweave.pdf.historical.executable=/path/to/tesseract \
  -Ddocweave.pdf.historical.tessdata=/path/to/tessdata \
  -Ddocweave.pdf.historical.report=/fresh/path/report.json
```

Without the explicit properties, normal Maven tests launch no external OCR. Historical
manifests and the default72DPI diagnostic policy are retained. The new per-case report fields
record actual container and engine DPI. Source/reference identities and scoring rules remain
unchanged; old evidence is not overwritten.

`CompareReports.java` checks unique complete case/profile sets, unchanged executable/model/
reference identities, exact source/render frames and complete paired JVM rows (ignoring only
random PDF document identity). Run it with Jackson from the validation test classpath:

```sh
java -cp "$VALIDATION_TEST_CLASSPATH" \
  benchmarks/review/pdf-scan-source-resolution/CompareReports.java \
  benchmarks/review/pdf-scan-thresholding/java21.json \
  benchmarks/review/pdf-scan-source-resolution/java21.json \
  benchmarks/review/pdf-scan-source-resolution/java25.json \
  /fresh/path/verification.json
```

The verification output retains numeric baseline/current diagnostics for every selected row.
No extraction output is supplied as a reference or used to alter a selected region/policy.
