# Mixed-image PDF OCR evidence

`real-mixed-control.xml` was produced by `TesseractMixedInstalledTest` with actual Tesseract5.5.3,
Leptonica1.87.0 and the same English model/native-library identity saved in
[the preceding OCR milestone](../pdf-ocr/runtime-and-packages.json). `runtime-fingerprints.json`
records the final PDF/OCR source and loaded class identities. `textless-recheck.xml` replays the
preceding owned raster/image-only PDF control without overwriting its historical report.

The new owned mixed PDF has two overlapping placements of one two-line text raster, plus a native
heading outside the image area and a native label inside it. Both expected scanned lines appear
once, the native elements remain exactly unchanged, both image occurrences remain, and one distinct
pixel rectangle is recognized. This is synthetic integration evidence, not general OCR accuracy.

`manifest.json` pins a separate, independently authored PDFBox regression PDF at the previously
verified3.0.8 source commit. The original is a one-page CCITT-compressed handwritten scan with no
native text. The test checks bytes, Git blob and SHA256, appends an owned native heading and exercises
the mixed-image policy. Final result: three OCR lines/37 joined UTF-16 units, native output preserved,
no duplicate owned heading. Text is recorded only by hash; no transcription accuracy is asserted.
The PDFBox fixture's handwriting and metadata were inspected locally; that is not an approved human
transcription or proof of a particular author/application's extraction fidelity. Original-author and
per-file redistribution rights were not established. Neither original/modified PDF, preview nor full
extracted text is committed. A second exploratory png_demo.pdf was fetched but not used as evidence.

The independent source is fetch-only. To replay, obtain the manifest's immutable raw URL, verify its
pins, and run the real-engine command from [the adapter guide](../../../docs/ocr-tesseract.md) with
`-Ddocweave.tesseract.mixed.pdf=/absolute/path/to/ccitt4-cib-test.pdf`. Use a new base report path;
the test creates `<report>.mixed.xml` exclusively. With the extracted local runtime, set
`LD_LIBRARY_PATH=/tmp/docweave-tesseract-runtime/usr/lib`, executable
`/tmp/docweave-tesseract-runtime/usr/bin/tesseract` and tessdata directory
`/tmp/docweave-tesseract-runtime/usr/share/tessdata`. Caches under /tmp are temporary.

Nine owned deterministic PDF tests additionally check geometric mask pixels, native/duplicate/ambiguous
line ownership, multiple/overlapping/clipped image rectangles, actual rendered coordinate ratios across
quarter-turn/crop/UserUnit cases, resource ceilings, cancellation and shared virtual-thread reuse.
Default hosted CI runs those tests; it does not fetch these inputs or invoke an installed OCR engine.
Real controls were run locally during full verification. No Windows VM was needed.
