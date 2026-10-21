# Explicit ONNX table structure — 2026-10-17

Optional `docweave-layout-onnx` adds one managed CPU ONNX Runtime1.23.2 session behind
the public OnnxTableLayout interface. It is excluded from the convenience artifact,
does not register services/download models, and requires an explicit trusted local
standalone graph and SHA256. Java21 source baseline; native Linux x64 execution.

## Model identity and preprocessing

Use the cross-domain source/export identity and reproducible conversion recipe in
[feasibility evidence](../pdf-table-model-feasibility/README.md).
Graph SHA256: ff1d19a18cb5f041c1692ff681f891a5180f9430d68a71d2cf7a0a2e8e55b990.
Max-height/max-width800 floor aspect sizing, bilinear8-bit RGB/ImageNet and valid mask;
fixed score threshold0.5. This is the documented explicit Transformers size override,
not the untouched published processor config or original Microsoft inference pipeline.
No model binary/raw source image/transcription is redistributed here.

## Actual production evaluation

`pubtabnet-java25.json` evaluates all20 pinned published train images using the
unchanged independent Java HTML/span/text/center evaluator. Each whole PNG is embedded
at150DPI then rendered at300DPI, Tesseract5.5.3 English PAGE SINGLE_BLOCK, one actual-word
PAGE call and one ONNX layout call. No oracle crop, per-case adjustment or cell OCR.
Model/table limits use their defaults. Image elements remain present.

-19 emitted tables;14 have every published positional span and matching shape.
-1221 emitted anchors,1182 positional-span matches out of1380 published anchors;
 342 match positional span and normalized text. Zero completely exact tables.
-724/2070 strict whitespace tokens matched,1751 observed tokens, identical to the
 previously measured ALIGNED_WORDS PAGE-word totals; layout does not improve OCR itself.
-Text-center containment is the existing label diagnostic, not border IoU; absent/wrong
 logical positions count as misses. It does not independently validate empty-cell edges.

`original-image-candidates.tsv` instead runs the production provider directly on original
PNG pixels:17 candidate tables, with three conservative rejections. This differs from
PDF-rendered inputs; the extra resampling changes predictions. Counts are not accuracy.
Training overlap with these upstream train samples has not been excluded. Neither these
measurements nor the older18/20 raw row/column counts establish held-out/general accuracy.

## Contracts and controls

Owned micrographs test actual native schema/inference, merged geometry, virtual-thread
concurrency, identity/input/query caps, closure, deadline and active-native interruption.
A finite ONNX Loop demonstrates cooperative termination; it is not an untrusted-graph
sandbox. Independent Pillow12.3.0 deterministic RGB vectors exercise up/downsampling;
Java tests also cover alpha ownership, crossing-span rejection, duplicate spans, empty
cells and prospective-slot limits. Strict Javadocs cover hidden production methods.
MIT Microsoft postprocessing notice is packaged under META-INF.

Full reactor21/25 verification, installed OCR families and paired real corpus results
are recorded in the historical verification results. Existing RULED historical reports/defaults remain
unchanged. Convenience-artifact tests explicitly assert native adapter/runtime absence.

Reproduce the explicit corpus with `mvn verify`, local Maven cache if desired, existing
`docweave.tesseract.executable`/`.tessdata` and `docweave.pdf.pubtabnet.cache`, plus:

```
-Ddocweave.pdf.pubtabnet.dpi=300
-Ddocweave.pdf.pubtabnet.pageSegmentation=SINGLE_BLOCK
-Ddocweave.pdf.pubtabnet.tableDetection=PROVIDER_LAYOUT
-Ddocweave.pdf.pubtabnet.layoutModel=/local/structure.onnx
-Ddocweave.pdf.pubtabnet.layoutSha256=ff1d19a18cb5f041c1692ff681f891a5180f9430d68a71d2cf7a0a2e8e55b990
-Ddocweave.pdf.pubtabnet.report=/fresh/output.json
```

Open work: cell OCR refinement, whole-page detection, physical scans, automatic
orientation and independent calibration. Structure candidates alone close none of these.
