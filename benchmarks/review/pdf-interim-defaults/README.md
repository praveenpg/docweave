# Documented defaults and independent source-raster audit

Nine whole documents run under each library's documented PDF entry point in a fixed installed
environment: four scientific originals, one business original and four shared lossless scan
containers. DocWeave uses PdfOptions.defaults() without an OCR provider. Unstructured0.27.28
uses partition_pdf(filename=...) with no strategy, language or model override. All18 document/
library outcomes succeed and their complete comparable summaries agree on actual Java21/25.
Defaults are compared as behavior, not as equal optional dependencies or equivalent OCR routing.

## Default behavior

DocWeave returns native UncategorizedText and original raster Image elements. For the four
image-only scans, it returns an image and no recognized text, with an OCR-not-performed warning.
The caller must configure an OCR provider. Unstructured auto routes those scans through the
installed OCR environment and returns recognized text; its default language is English here.
That is useful automatic routing, not the configured German/Fraktur accuracy experiment.
Native modern PDFs receive upstream text categories such as Title, Footer and NarrativeText;
their classification accuracy is not labelled in this study.

The complete text lengths, category counts, warnings, failure identities and source/capture
hashes are retained in java21.json and java25.json. No complete-text recall claim is derived
from character counts. Exact original bytes/configurations are fixed in manifest.json;
installed runtime inventories are retained separately. Custom OCR_AGENT/PDF_RENDER_DPI/model
overrides are cleared for the upstream default run; local resources are installed beforehand
and offline execution does not download anything.

## Independent raster-placement agreement

The JVM launcher separately invokes installed Poppler pdfimages -list. It compares ordinary
source-image entries to DocWeave Image metadata by original page, pixel width, pixel height
and occurrence multiplicity. Mask entries remain separate. This checks more than a total count.

| Original documents | Poppler ordinary placements | DocWeave placements | Page/dimensions/multiplicity |
| --- | ---: | ---: | --- |
| PLOS0324609 |7|7|Equal|
| PLOS0330904 |3|3|Equal|
| eLife90049v1 |9|9|Equal|
| PLOS0360958 |9|9|Equal|
| Amazon Q4 2025 |1|1|Equal|
| Four scan containers |4|4|Equal on each document|
| Total |33|33|Equal on all9 documents|

This is independent extractor agreement, not independently human-labelled figure accuracy.
An ordinary source raster may be a logo, decoration or full scanned page. This audit does not
score image payload bytes, bounding-box precision, captions, semantic figure boundaries or
vector illustration coverage. Unstructured default fast/native output does not expose these
source-raster objects in these runs; its high-resolution detected regions/crops have a different
contract. Reporting33 versus zero as an image-quality ranking would be misleading.

## Reproduction and controls

1. Verify all original PDF hashes and the shared scan-container descriptors from the paired scan
   evidence. No reference text, labels, ROIs or model choice enters default extraction.
2. Compile benchmarks/tools/CapturePdfDefaults.java at release21 against current reactor classes
   and validation dependencies. Run on actual21/25 with manifest, source cache, NEW output and
   the installed pdfimages executable. Runtime receipts bind that executable's bytes. Poppler
   metadata collection is bounded and output draining uses virtual threads.
3. Run benchmarks/tools/capture_unstructured_defaults.py with manifest, same source cache and
   NEW output using the pinned preinstalled Python environment. The runner uses bounded120s
   children and retains every failure; it does not score or execute DocWeave.
4. Run PdfDefaultComparisonTest with docweave.pdf.interim.defaults.native/.upstream/.originals/
   .report properties under both actual JDKs. Choose fresh report paths. JVM scoring verifies
   original hashes and full outputs before comparing independent source-raster metadata.
5. Run VerifyDefaultReports.java with manifest, reports21/25 and NEW verification receipt. It
   requires complete18-row coverage and cross-JVM behavior equality, excluding only separately
   retained raw-capture hashes. The receipt records33 source/native placements on9 documents.

Owned controls distinguish page/dimension/duplicate multiplicity, preserve masks separately,
retain complete public text, and reject failed captures containing partial successful output.
Raw third-party text, original PDFs and logs stay outside Git. No production API, dependency,
default policy or image-extraction algorithm changed in this comparison.
