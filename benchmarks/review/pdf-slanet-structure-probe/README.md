# SLANet-Plus structure-model probe (Java 21)

This benchmark does **not** qualify SLANet-Plus for production. On the complete known
NVIDIA FY26Q4 document, the alternative produces fewer exact whole reference rows
than the existing TATR structure model. No library API, default, dependency or model
selection changes result from this experiment.

| Measurement | TATR detector crops | YOLOX detector crops |
| --- | ---: | ---: |
| Reference rows, all three statements | 110 | 110 |
| Existing TATR structure: exact row present | 39 | 66 |
| SLANet-Plus: exact row present | 4 | 7 |
| SLANet-Plus: strict ordinal rows | 0 | 0 |
| Associated statement regions | 1/6 | 2/6 |

These profile names identify the **detector**, with SLANet-Plus supplying both structure
predictions. Do not combine per-source winners. All 11 original pages and both profiles
remain, including five page/profile records without detector proposals. All 23 proposed
crops complete native inference and emit an end token. Four predictions pass the existing
provider contract; three have incomplete logical slots and sixteen fail geometry checks.
Raw predictions and every rejection remain in the reports. Rejected candidates yield no
provider table; they are not repaired or silently counted as successful tables.

## Protocol and provenance

`manifest.json` was frozen before original-crop inference, after an owned 3x2 smoke
probe. It pins the original PDF, 11 rendered pages, 22 detector-crop inventories, Java
capture source, graph and official wrapper sources. This is already observed development
data, not held-out acceptance evidence. `raw-java21.json` retains greedy tokens, uncalibrated
scores and all eight raw cell coordinates. `topology-java21.json` records strict XML/span
parsing and unchanged DocWeave provider geometry validation. `captures/` contains only
compatible numeric tables; `replay-manifest.json` pins every exported file. Native and
row-presence reports preserve the full 110-row denominator. `receipt.json` pins artifacts.

The graph is the [RapidTable official SLANet-Plus export](https://www.modelscope.cn/models/RapidAI/RapidTable/resolve/v2.0.0/slanet-plus.onnx),
7,758,305 bytes, SHA-256 `d57a942af6a2f57d6a4a0372573c696a2379bf5857c45e2ac69993f3b334514b`.
The [official wrapper revision](https://github.com/RapidAI/RapidTable/tree/22592283c1f9d7c5a014c96c4ecc57de0b3ebfce)
is pinned in the manifest, including its Apache-2.0 license. This records wrapper licensing;
it does not establish independent weights licensing clearance for distribution. No model,
third-party implementation, source document or raster is redistributed here.

The JVM probe uses existing ONNX Runtime CPU support, one inference thread and a 30-second
per-crop termination deadline. Published BGR input, longest side 488, truncated resized
shape, channel normalization and top-left normalized-zero padding are reproduced.
Java half-pixel bilinear interpolation rounds to 8-bit samples; **bit identity with the
wrapper's OpenCV resize is unverified**. Therefore these numbers describe this explicit
JVM protocol, not a claim of bit-exact official-wrapper results. No Python runtime or
new Python dependency was used. Raw quadrilaterals are mapped using the published
SLANet-Plus longest-side scale without clipping or geometry repair.

## Reproduction

For exact historical source replay, obtain `ProbeSlanetModel.java` from commit `c2b7d97`
(the original manifest pins those bytes). The later shared probe adds an explicit English
model protocol and its asymmetric box control; do not rewrite the original source pin.
Compile `benchmarks/tools/ProbeSlanetModel.java` and `ValidateSlanetTopology.java` against
the existing Java21 reactor/test dependency classpath. Run the former from the checkout:

```text
ProbeSlanetModel MODEL manifest.json RASTER_ROOT ORIGINAL_CROP_ROOT FRESH_RAW_JSON
ValidateSlanetTopology RAW_JSON RASTER_ROOT FRESH_TOPOLOGY_JSON PRIOR_REPLAY_MANIFEST FRESH_EXPORT_ROOT
```

The original roots and crop protocol come from `../pdf-nvidia-proposal-structures/`.
Then use `ReplayNativeLayoutCaptures` and `AuditStatementRowPresence` with the exported
replay manifest, original source cache and exported capture root. Preserve the scorer
source from commit `8c4e990`, SHA-256
`f88af667ef1c160906003b3dc2b235107d3c34d7c2e4b7bb25b8d39de04c85f6`;
run from an isolated workspace containing that frozen source path so its identity check
remains active. Do not replace it with a changed scorer or disable its check.

Owned controls exercise BGR/normalization/padding, token/end handling, coordinate scaling,
merged slot coverage, incomplete slots and malformed row grouping. Both standalone tools
compile and pass private strict Java21 doclint; the pinned formatter and diff checks pass.
The optional numeric export leaves every topology result recursively identical to the
initial validation report. Production remains at verified commit `68ee809`: 1,195 Java21
tests passed. No redundant production test run or Java25 validation is claimed here.
