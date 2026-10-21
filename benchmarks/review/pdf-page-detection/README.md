# Explicit whole-page ONNX table detection — 2026-10-17

The two-options OnnxTableLayout factory composes separately pinned local detection and
structure graphs. Neither graph is downloaded/discovered by the library or tests. Public
services remain interfaces; hidden session/inference/crop/topology strategies share total
pipeline deadlines and resource limits. Details and usage: docs/pdf-support.md.

## Primary source and conversion

- [Microsoft detector](https://huggingface.co/microsoft/table-transformer-detection/tree/2357cbe2b5a5d1c03e54f32764f06058933b65ab), pinned revision2357cbe2b5a5d1c03e54f32764f06058933b65ab, MIT.
- Official LFS metadata verifies source115317516bytes, SHA2568f1aa73170102c038d40155e2734b343bf07e0fe12594228a8590943b01dccf7.
- Export SHA2560f625baa8849eff26e265ce08a2f6b1479e73f52edd5adc09799c7e7fcc689b9; package versions/loading report in manifest.json.
- Follow the complete [offline conversion recipe](../pdf-table-model-feasibility/conversion-recipe.md)
 with local root detection, this revision/hash, output detection.onnx and the detection
 model config. Same safetensors-only, no-pretrained-backbone, eager attention, named inputs,
 dynamic H/W, opset17/dynamoFalse export/checker procedure. Actual output shapes are
 logits[1,15,3]/pred_boxes[1,15,4]; labels table/rotated/background. Only three known
 frozen-normalization training counters are unused; no missing/mismatched/error keys.
- Java21/25 same-input-tensor numerical controls both pass: max absolute logit error
 1.91e-5, box6.86e-7 at800x1000/1000x667. Deliberately resized source tensors establish
 conversion equivalence, not extraction accuracy. No graph/model/source image is redistributed.

Production explicitly uses max-height/max-width800 floor, bilinear RGB/ImageNet, matching
our structure preprocessing profile. Published legacy size800/max_size800 resolves to
shortest/longest800 and differs in rounded shapes on9/20 examples. preprocessor-shapes.json
retains the full comparison. The uniform explicit Transformers size override agrees with
production shapes; do not claim unmodified published preprocessing or full Microsoft
inference parity. Original Microsoft source also uses a distinct rounding/resampling path.

## Owned contracts

Native micrographs exercise two ordinary tables, a near-duplicate, unsupported rotation
and background; exact clipped crop-origin translation, merged anchors, source ownership,
both pinned hashes, parent/child deadline cancellation, aggregate slots across crops,
graph role validation, virtual-thread concurrency and closure. A PDF integration control
combines both native graph sessions with independently specified source words, two exact
table texts/colspans and ordinary outside prose. Pure crop controls verify ambiguous overlap,
rotated-only fallback, positive-count ceiling before NMS and child pixel preflight.
Default structure-only graph controls, native finite-loop cancellation and Pillow RGB vectors
continue to run. No Python runtime/tests or pretrained model download is required by CI.

## Independent diagnostic outcomes

All20 original published PubTabNet train crops embedded whole at150DPI and rendered300DPI:
19 candidate tables,14 complete positional-span topologies,320 exact positional-span-text
cells, zero fully exact tables. This is whole-input detection plus structure, not an oracle
crop. PAGE English OCR defaults and fixed model thresholds are uniform. Structure-only
previously had342 exact cells; adding detection is not a demonstrated text-quality gain.
Training overlap is not excluded. Reports retain both graph identities and unchanged labels.

Four original cTDaR physical scans embedded/rendered150DPI: two candidate tables, neither
matches a complete published table. s003 predicts4x2/6anchors with2 best positional matches;
s10130x4/105anchors with19 best matches against duplicate-heavy source annotations;
s004/t00040none. Positional overlap alone does not validate table detection/geometry.
The historical handwritten pages are not English OCR text labels. No source annotation
repair/cropping, calibrated confidence, or physical-scan quality completion is claimed.

Historical verification details remain scoped to the recorded benchmark receipt.
The source Image remains emitted. Rotated detections remain ordinary PAGE-word fallback;
automatic orientation and broad physical-scan quality remain required work.
