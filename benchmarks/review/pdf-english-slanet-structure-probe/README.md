# English SLANet structure probe (Java 21)

The separately trained official English SLANet graph does not improve the complete known
NVIDIA FY26Q4 document. Both detector profiles retain all 110 reference rows: strict
ordinal matches **0/110**, exact whole-row presence **0/110**, target-region association
**0/6**. Existing TATR structure remains better at 39/110 and 66/110 row presence.
No production API, dependency, model selection or default is changed.

All 11 original pages, 22 detector profiles and 23 crops remain. Every crop completes
inference with an end token. Six predictions satisfy the provider contract, but none
covers a target statement region. Seventeen predictions are rejected; their raw tokens,
coordinates and validation failures remain. No candidate geometry/span repair, threshold
selection, source-specific winner or reduced reference denominator is used.

The publisher-pinned graph is [RapidTable English SLANet](https://www.modelscope.cn/models/RapidAI/RapidTable/resolve/v2.0.0/en_ppstructure_mobile_v2_SLANet.onnx),
7,704,409 bytes, SHA-256 `2cae17d16a16f9df7229e21665fe3fbe06f3ca85b2024772ee3e3142e955aa60`.
Official wrapper revision and source/license pins are identical to
`../pdf-slanet-structure-probe/manifest.json`. The wrapper Apache-2.0 license is reviewed;
independent weights distribution clearance is not established. No weights, third-party
implementation, original PDF or raster is redistributed.

The frozen manifest predates original English-model inference. The shared JVM probe uses
explicit publisher-hash protocols, never selects a graph from measured results, and
validates dynamic output shape against that model. English SLANet emits four-coordinate
axis-aligned rectangles with independently scaled original width/height, unlike Plus's
eight-coordinate longest-side scaling. Rectangles expand to equivalent four corners;
coordinates are not clamped. An asymmetric 200x80 owned decoder control checks that
width/height cannot accidentally be swapped or replaced by longest-side scaling.

Preprocessing and its limits match the Plus probe: published BGR/488/normalization/padding,
explicit JVM half-pixel 8-bit bilinear rounding, **OpenCV bit identity unverified**. These
are results of this pinned JVM protocol on observed development data, not official-wrapper
bit parity or held-out acceptance. No Python execution or dependency was added.

Reproduce using `ProbeSlanetModel` with this manifest and graph, then
`ValidateSlanetTopology` and the unchanged full-source replay/presence tools as documented
in `../pdf-slanet-structure-probe/README.md`. Preserve the frozen scorer source at8c4e990
and all input hashes. All standalone compilation, owned controls, private strict Java21
Javadocs and pinned formatting checks pass. Production remains unchanged from68ee809's
1,195-test Java21 verification; no Java25 or redundant production run is claimed.
