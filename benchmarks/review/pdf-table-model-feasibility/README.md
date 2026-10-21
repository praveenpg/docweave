# JVM table-model feasibility, not integrated table recognition

The previous aligned-word heuristic recognized zero tables on the20 pinned PubTabNet
train examples. These experiments investigate an optional learned geometry backend;
no production ONNX dependency or implicit model download has been added.

Two official MIT-labelled Microsoft source models are pinned in manifest.json, with
original safetensors byte/SHA256 pins checked against official HF LFS metadata. No
pickle/remote model code is loaded. The newer v1.1-all model reports no missing,
unexpected, mismatched or error weights. Its backbone is configured locally, without
pretrained-backbone downloads. Models/graphs/source images remain under /tmp, not committed.

Original source: https://github.com/microsoft/table-transformer/tree/16d124f616109746b7785f03085100f1f6247575
Initial model: https://huggingface.co/microsoft/table-transformer-structure-recognition/tree/f4d4bdc85c3fe4b1fa49658882a5d38bbdd0f343
Cross-domain model: https://huggingface.co/microsoft/table-transformer-structure-recognition-v1.1-all/tree/7587a7ef111d9dcbf8ac695f1376ab7014340a0c
Java runtime: https://onnxruntime.ai/docs/get-started/with-java.html
Microsoft license notice is retained separately. The source repository was archived;
this is not a claim of active upstream maintenance or a security/support endorsement.

## Numerical and preprocessing evidence

Temporary CPU-only torch2.9.1/torchvision0.24.1, transformers4.57.3/timm1.0.22,
onnx1.19.1/safetensors0.8.0 convert the pinned weights to dynamic-height/width
opset17 graphs. Java21/25 ONNX Runtime1.23.2 (official Maven JAR checksum verified)
closely matches source Torch on identical tensors at800x1000 and1000x667. These
artificially resized shapes are numerical controls, not a table-accuracy evaluation.
All measured maxima and exact artifact hashes are recorded in JSON. Native threads4/1.

Actual Java25 separable bilinear resizing matches the pinned PIL/Transformers RGB outputs
exactly on all20 original images:26,299,200 channel values for initial-model resizing
and18,240,000 for the cross-domain profile, no unequal values. Initial profile uses
short-edge800/max-long1000. Published v1.1-all config declares only longest_edge800,
which this pinned DetrImageProcessor rejects. An explicit equivalent max_height800 /
max_width800 override is used uniformly; it is not silently represented as unmodified
HF preprocessing. Source Microsoft inference.py instead uses MaxResize1000 and default
PIL resampling for structure recognition; this prototype does not claim parity with
that complete pipeline. RGB/255 and published ImageNet mean/std normalize NCHW floats;
all pixels are valid in the single-image INT64 mask. No oracle crops or repairs are used.

## Raw objects versus independent dimensions

Java-only whole-image probes use fixed class threshold0.5 and the published six labels
plus background. The20 original table crops/labels stay pinned to ../pdf-pubtabnet.
Expected dimensions come from the existing Java corpus parser of independent published
HTML, never candidate-generated goldens. The initial model matches raw row counts2/20
and columns15/20; both counts2/20. The newer cross-domain profile matches rows18/20 and
columns20/20; both18/20. JDK21/25 raw object results are identical for each model.
The profiles also differ; this is not a controlled weights-only comparison.
No topology/span resolution, cell anchors, OCR ownership, text accuracy or calibrated
confidence is evaluated by those counts. Two newer-model raw row mismatches remain:
PMC2838834_005_00.png36 expected/42 observed; PMC4003957_018_00.png21/22.
Training overlap with these published train examples has not been excluded.

## Next implementation boundary

Use an explicit PdfTableLayoutProvider SPI so PDF topology/word ownership remain
independent of ONNX. Validate complete spans, empty cells, disjoint geometry and raw
budgets before owning words. An optional managed runtime adapter still needs row/column
refinement, span resolution, model/input/output caps, lifetime/cancellation, evidence and
end-to-end comparison against the independent HTML/text/centers. Whole-page detection,
physical scans and calibration remain open. These object counts are not completed PDF
features or18/20 fully recognized tables. Ordinary CI does not fetch or run these models.
