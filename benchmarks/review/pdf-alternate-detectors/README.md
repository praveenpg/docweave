# Alternative table-region detector diagnostic

Development prioritizes modern business/scientific PDFs, then historical scans, then degraded
scans. This fixed development-selected cTDaR set supplies an initial modern/historical
comparison; it is not held-out evaluation or evidence of business-document coverage.

| Domain | Annotated regions | TATR matches | YOLOX matches |
| --- | ---: | ---: | ---: |
| Modern s101–s104 | 12 | 10 | 12 |
| Historical s001–s004 and t00040 | 8 | 2 | 0 |
| All inputs | 20 | 12 | 12 |

Matches use maximum-cardinality one-to-one axis-aligned envelope IoU>=0.5. Duplicate
predictions cannot multiply reference credit. All9 inputs remain, including s001 rejected
by the16M whole-source pixel limit. TATR emits13 proposals, YOLOX14; unmatched proposals
are retained, not called false positives because source annotation coverage is imperfect.
These are region metrics, not the three exact topologies from earlier structure experiments.
No structure recognition, OCR, cell strings, confidence calibration or complete-PDF metric
is evaluated here. Neither pipeline is an across-domain winner on this set.

## Controls and identities

Both models receive byte-identical150DPI PDFBox-rendered RGB rasters. All42 capture files,
including eight local PNGs, match across actual Java21 and25. Only34 numeric/hash files per
JDK are saved here; originals, annotations, derived images and model weights are fetch-only.
The manifest records source, graph and saved-capture identities; summary.json retains raw
YOLOX objects, reference source-code identity, package versions and all outcomes.

TATR uses the existing Java ONNX Runtime1.23.2 implementation, max-side800 normalization,
score0.5, ordinary class0 and clipped boxes. The alternative calls pinned unmodified
unstructured-inference1.6.13 YOLOX prediction code using Python ONNX Runtime1.31.0,
RGB/OpenCV bilinear preprocessing,1024x768 input,114 padding, score>0.25, class-agnostic
NMS0.1 and Table class8. Raw YOLOX boxes are retained without boundary repair. A trusted
local CPU session is assigned explicitly with four intra-op/one inter-op threads, avoiding
model discovery/downloads. This differs from the standard initialization path. Runtime,
preprocessing, thresholds and model architectures differ; results do not isolate weights
or compare programming languages. Scores are not calibrated probabilities of correctness.

Source JPEG/XML pins are verified before inference; annotations are used only for evaluation.
Coordinates invert encoded PDF physical extent and DPI, not the floored raster-canvas ratio.
No annotation changes, case-specific settings, oracle crops or source repair are applied.
Both Java capture helpers passed release21 compilation and strict standalone Javadocs on
actual21/25. Eighteen ordinary offline benchmark-tool tests pass, including duplicate,
augmenting-path matching and coordinate boundaries. Production Java is unchanged; the
previous905-test production baseline is not claimed as a new local full verification run.

## Reproduction

Compile CaptureScanDetectorInput.java in package dev.docweave.layout using release21 and
the built core/PDF/ONNX runtime classpath. Run it on actual21 and25 with five arguments:
source cache, JPEG filename/SHA256 TSV from the source manifest, NEW capture directory,
pinned TATR graph path and SHA256. Each capture writes local PNGs for the reference run.
Do not redistribute those derivatives.

Using the pinned optional Python reference environment (requirements.freeze.txt in
../pdf-upstream-table), run:

```sh
python benchmarks/tools/compare_scan_detectors.py \
  --capture21 /path/to/capture21 --capture25 /path/to/capture25 \
  --cache /path/to/ctdar \
  --manifest benchmarks/review/pdf-physical-scans/extended-manifest.json \
  --model /path/to/yolox_l0.05.onnx --output /path/to/NEW-summary.json
python3 -m unittest discover -s benchmarks/tools -p 'test_*.py'
```

Graphs and installed prediction source must match the tool's hashes. The tool forces offline
model access and rejects a path containing `quantized`, which upstream uses to switch thresholds.
Python is optional benchmark tooling, not a Java runtime dependency. No new dependency is
added to the Maven reactor.

**Decision:** retain YOLOX as a candidate for a modern-document structure-stage experiment.
Validate whether its extra regions yield usable tables before implementing a JVM adapter.
Historical regressions remain visible. No production/default change or beta milestone follows
from region-only evidence; the existing signed beta2 candidate remains unchanged.
