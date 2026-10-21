# Owned scanned complete-grid evidence

`java21-table-control.xml` and `java25-table-control.xml` were produced by actual Tesseract 5.5.3 cell OCR
on an owned image-only 2x2 grid plus raster prose. All four cell strings/leading zeros and HTML match
exactly, source image is retained and ordinary prose appears once. Each report identifies the generated
PDF, executable, English model, Java runtime and OS. Model/native-library provenance is retained in
[the preceding OCR package evidence](../pdf-ocr/runtime-and-packages.json). No engine/model download
or system installation occurs in the library.

`*-mixed-recheck.xml` and `*-textless-recheck.xml` replay earlier controls on both runtimes without
rewriting their historical evidence. Mixed checks include the previous pinned independent handwritten
scan with an owned heading appended; that input is not an independent scanned-table oracle.
`runtime-fingerprints.json` records final source/class identities and hashes of these reports.

Eleven owned deterministic PDF tests cover escaped/empty positions, leading zeros, ordinary fallback
for broken/skewed/blank-recognition grids, independent grids, aggregate cell-call and raw-output limits,
cell field/dimension limits, native/mixed ownership, quarter-turn coordinates, cancellation/failure
causes and concurrent reuse. One real-PDF integration test checks JSON round-trip and structural
TableChunk text preservation. These controls establish a conservative complete ruled-grid subset,
not noisy/merged/borderless or general scanned-table accuracy. No approved independent table
transcription, human review, model inference or upstream Unstructured PDF execution is claimed.

Use the real-engine command in [the adapter guide](../../../docs/ocr-tesseract.md), with a fresh base
report path. The new control writes `<report>.tables.xml`. Existing real controls also write the base
report and `<report>.mixed.xml`. For the previous independent mixed control, provide the optional pinned
`docweave.tesseract.mixed.pdf` cache path; it is unrelated to table-transcription accuracy. Temp inputs,
JDK21 and external runtime files are local prerequisites. No PDF/raster/model binaries are committed.

Next recommended milestone: permitted independent scanned-table corpus validation and hardening
before merged/noisy/borderless expansion. Historical PDF feature-plan completion: 65%, a planning estimate
rather than an accuracy score. Native images/text/tables, OCR and this table subset still have the
explicit limitations documented in [PDF support](../../../docs/pdf-support.md).
