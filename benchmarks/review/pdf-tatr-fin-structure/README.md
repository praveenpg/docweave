# Financial-domain structure checkpoint: retained negative comparison

The official Microsoft-hosted `table-transformer-structure-recognition-v1.1-fin`
checkpoint is trained on FinTabNet.c, per the pinned publisher model card. The HF model
card labels its license MIT and explains that the card was authored by the HF team.
We evaluate this financial-domain checkpoint separately from the existing v1.1-All
checkpoint, without substituting models based on source outcomes. Weights and graphs
remain fetch-only outside Git. This is an observed-source development comparison,
not broad model ranking, held-out recognition accuracy or production default selection.

Immutable revision `c8e8338b3aad32867e015e81a7a4fc48b4c955d1`; safetensors
115,437,156bytes/SHA256`b9e576cd167f6f460aebb53afc0f799e876751b5c1073b593f929aea5c13e46b`.
Graph115,828,335bytes/SHA256`0d422609343b99a5c776aa1c17c7640e6c51414067bff5642038591e3702d813`.
Source identity, config/card/processor hashes, package versions, tensor fixtures and
local preparation source identity are retained. See the conversion note below.

All original11 NVIDIA pages and both frozen detector proposal populations remain.
The exact structure-capture tool is restored from15299fd with original SHA256
`65774c98f7b5866e8c4c378ed0333d47cd4a046559bd9e5fbfc4645b685739a1`.
The first attempted6bf6570 restoration had a different hash and was not used. No guard
was relaxed. Identical original rasters/proposals are verified in Java before inference;
all22 capture outcomes are OK. Same score0.5, bounded crop planning, resize/normalization,
CPUthreads4,30-second profile deadline and KEEP_HIGHER_SCORE policy. No threshold search,
source ROI/labels in inference or query/topology repair. All raw capture hashes remain.

| Complete statement metric | v1.1-All/TATR | v1.1-Fin/TATR | v1.1-All/YOLOX | v1.1-Fin/YOLOX |
| --- | ---: | ---: | ---: | ---: |
| Associated statement regions |3/6|3/6|6/6|6/6|
| Observed content rows |66|66|90|90|
| Strict ordinal rows |0/110|0/110|0/110|0/110|
| Exact complete-row occurrence presence |39/110|35/110|66/110|66/110|

All six source/case/profile extractions complete under the unchanged8c4e990/f88af667
reference scorer and frozen original HTML row hashes. Presence consumes an observed
occurrence once without row/reference repair or reordering. The TATR balance sheet gains
one reference occurrence but loses four; income loses another. Complete per-reference
gains/losses remain in the JVM delta report. YOLOX occurrence identities are unchanged.
Equal row counts do not mean equal text or geometry. **Do not promote this checkpoint**
on this evidence. No default recommendation, production algorithm or recognition gain.

## Model preparation and JVM numerical checks

Only the existing local Python environment prepares the ONNX graph and source-model
reference tensors; no Python DocWeave test or new dependency is introduced. Exact package
versions are in preparation.json and differ from the historical All export. Loading is
local safetensors-only with remote/pretrained-backbone fetching disabled, eager attention,
no missing/mismatched/error keys and only permitted training batch-counter extras.
Geometry wrapper `self.model`, opset17, legacy exporter, dynamic height/width, outputs
logits/pred_boxes. Exporter tracing warnings are retained outside Git; successful export
alone is not numerical correctness. No complete preprocessing parity claim is added.

`VerifyPreparedOnnxGeometry.java` validates prepared graph/tensor identities, all scalar
counts/finite values, exact125x7/125x4 output schema and both predeclared800x1000/1000x667
controls using Java21 ORT1.23.2. Fixed acceptance bounds before Java execution are
0.001 logits and0.0001 boxes. Actual maxima are0.0000248 and0.00000203. Full source Torch
outputs, not Java-generated goldens, supply references. These are artificial numerical
controls, not recognition inputs or production latency/accuracy claims. Independent model
export environments prevent claiming a byte-identical weights-only export experiment.

Reproduce graph preparation with the existing documented recipe under
`pdf-table-model-feasibility/conversion-recipe.md`, substituting this immutable checkpoint
and the exact package versions/graph filename from preparation.json. Reference tensor i
has shape(1,3,H,W), flattened values `(arange(3*H*W)%251).float()/125-1`, and an all-one
INT64 mask. Save source input/logit/box floats as little-endian float32 and mask as int64;
all file identities are retained. Keep the wrapper attribute self.model. Validate your
actual graph hash before using the immutable replay captures; do not silently repin them.

Compile the pinned capture tool against the Java21 reactor classpath, run all source
rasters/proposals with the fixed graph/hash into a new directory, and verify each file
against replay-manifest.json. Run the existing JVM ReplayNativeLayoutCaptures and
AuditStatementRowPresence from the isolated frozen8c4e990 scorer checkout/classpath.
The current main scorer has a different identity and must not be substituted.

Strict private Javadocs, standalone Java source compatibility and scoped formatting pass.
Last full production verification remains1,224 Java21 tests; no redundant full/Java25
verification or beta publication is claimed.
