# Managed alternative table detector

`OnnxTableLayout.createWithYoloX(detection, structure)` exposes the JVM alternative
as an explicit managed application provider. Both local graphs are SHA256-pinned.
Existing factories/defaults retain TATR detection; nothing is downloaded/discovered.

Internal `TableModel<P>` strategies separate tensor/preprocessing rules from shared
`ManagedTableSession` admission, native cancellation and cleanup. Both public pipelines
share deadlines and detector/crop/topology work. Parent lifecycle ownership remains
active through child inference. The TATR facade retains existing diagnostic entrypoints.

The YOLOX contract is FLOAT images[1,3,1024,768]/output[1,16128,16], eleven classes,
table class8. RGB input uses top-left114 padding, pixel-center bilinear uint8 rounding
and white alpha composition. All raw probabilities/coordinates are validated, even
for discarded background queries. Strict score filtering precedes inclusive-area
class-agnostic NMS0.1, then table selection/clipping. Query ties preserve original order.
Fixed2,048 candidates and shared work bound suppression. `yoloXDefaults` selects
16,128 raw queries and a strict greater-than0.25 objectness-times-class threshold.
Structure thresholds remain separate. Raw-query options now allow up to16,128;
ordinary TATR defaults stay256. This JVM profile is not bitwise OpenCV parity.

Provenance identifies the detector as `yolox114_v1`. Both graph hashes and complete
structure policy remain present. A compact hash label prevents long valid score
representations from exceeding the256-character contract; no identity/policy is
truncated, and normal TATR provenance remains unchanged.

## Validation

Six JVM tests cover pixel/alpha vectors, borrowed input, complete translated/merged
child layouts, concurrent calls, competing non-table suppression, invalid background
values, dense candidate limits, query/schema rejection, deadlines/interruption and
long-score provenance. Native fixtures are generated in Java from the public
[ONNX1.19.1 schema](https://raw.githubusercontent.com/onnx/onnx/v1.19.1/onnx/onnx.proto),
without Python/protobuf dependencies. Existing native-loop cancellation and lifecycle
tests exercise the shared implementation. Full Java21 verification passes1,149 tests,
including the opt-in original PNG regression; strict private Javadocs/formatting pass.
Java25 remains deferred.

The verifier retains every pinned original NVIDIA page raster and checks all source,
proposal, structure and graph identities. Fixed four-thread results are exact:

| Stage | Pages | Exact successful matches | Whole-document observations |
| --- | ---: | ---: | --- |
| Managed YOLOX raw proposals |11|11|14 proposals |
| Public TATR detector + TATR structure |11|11|8 tables |
| Public YOLOX detector + TATR structure |11|11|14 tables |

All33 observations remain in `java21.json`. These are cached original rasters, not
a fresh PDF rendering or new text/OCR run. The legacy format omits provenance and
headers; only those two fields are projected away, retaining every row/column/span/
coordinate. Current declared header counts are reported without correctness labels.
Typed failures remain in the full report; numeric discrepancies fail after all pages.

This makes the earlier6/6 region-association result available to applications. It
does not improve or replace the previous0/110 strict statement-row result, establish
header accuracy or close model segmentation/independent acceptance gates.

## Reproduction

Verify `candidate-identities.sha256`, then restore the original page/proposal/structure
caches from the [preceding protocol](../pdf-nvidia-proposal-structures/README.md) and
the same pinned local [YOLOX graph](../pdf-nvidia-yolox-jvm/manifest.json).
Compile `benchmarks/tools/VerifyManagedYoloX.java` against current module/runtime
classes and run with a fresh report path:

```sh
java -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED \
  -cp "$audit_classes:$runtime_classpath" dev.docweave.layout.VerifyManagedYoloX \
  benchmarks/review/pdf-nvidia-proposal-structures/replay-manifest.json \
  /path/to/page-captures /path/to/proposals /path/to/structures \
  /path/to/tatr-detection.onnx /path/to/yolox.onnx /path/to/tatr-structure.onnx \
  /path/to/new-report.json
```

Source PDFs, images and weights remain outside Git. The application example is in
[PDF support](../../../docs/pdf-support.md#whole-page-detection-before-structure).
