# Alternative detector through the existing structure recognizer

This follows the [region-only comparison](../pdf-alternate-detectors/README.md) and the development
priority order: modern business/scientific PDFs, historical scans, then degraded scans. Identical
pinned rendered rasters and fixed detector proposals are passed through the same existing
Java crop planner and seven-label TATR structure recognizer. No OCR or annotations enter
inference. Scientific/modern table images do not establish business-document coverage.

| Domain | References | TATR structures | YOLOX structures | TATR exact topology | YOLOX exact topology |
| --- | ---: | ---: | ---: | ---: | ---: |
| Modern, four inputs | 12 | 10 | 12 | 2 | 2 |
| Historical, five inputs | 8 | 2 | 2 | 1 | 0 |
| All nine inputs | 20 | 12 | 14 | 3 | 2 |

Exact topology requires identical row/column counts and complete positional/span anchor sets,
after geometry-only maximum-cardinality one-to-one association at envelope IoU>=0.5. Labels
never choose a crop, threshold, detector or association to maximize topology agreement. Matching
uses original physical PDF extent, not a canvas-floor rescaling. The older highest-positive-
overlap comparison is retained separately in summary.json for continuity, not added to totals.
All9 inputs remain, including the s00116M source-pixel rejection. No text oracle is available.

The modern exact matches are the same two s102 tables under both detectors. On dense s104,
YOLOX emits all eight structures versus six. Two additional5x5 tables contain all24 published
anchors plus one extra model anchor each; this is not strict equality. Two other s104 grids
shift to7x6/29 matching keys versus TATR4x5/19 and6x6/34, with34 reference keys each.
The historical s0022x5 success disappears because YOLOX has no region proposal. Historical
s004 produces two unmatched structures. No blank labels or model anchors are silently repaired.

**Decision: no detector replacement or JVM YOLOX adapter is justified by this experiment.**
There is no exact-topology gain on the prioritized modern subset, while the historical success
regresses. Local region gains remain promising research evidence, not a useful validated
production addition or new beta milestone. Existing defaults and the signed beta2 candidate
remain unchanged. This closes no broad PDF quality gate.

## Controls, boundaries and validation

The two detectors retain their distinct published thresholds and preprocessing from the prior
comparison. Preparation maps ordinary TATR class0 and YOLOX Table class8 to a common numeric
schema without changing scores/boxes; no second score threshold is applied. Before crop planning,
raw boxes are uniformly clipped to the raster and every action audited. All proposals in this
capture are UNCHANGED: clipping/discard paths did not alter observed results. Other classes are
excluded from this ordinary-table diagnostic; it is not a full production-layout adapter.

Existing DetectionCrops supplies fixed10-pixel padding, containment suppression0.5, rejection
of remaining partial overlaps,32-crop and64M copied/filled pixel-visit ceilings. The unchanged
structure graph uses max-side800, score0.5 and explicit KEEP_HIGHER_SCORE. Each profile gets
its own30-second deadline and shared geometry-work counter. This diagnostic is not proof of
aggregate lifecycle/admission/slot hardening for a new detector or a complete PDF transaction.
The structure stage uses Java ONNX Runtime1.23.2 for both profiles; region generation still
has the runtime/model/preprocessing differences disclosed in the prior comparison.

All114 numeric files per JDK match byte-for-byte on actual Java21 and25. All16 TATR table/anchor
control files also reproduce the previous whole-page experiment exactly. The helper compiles
with release21 and passes strict standalone Javadocs on both JDKs. Twenty-one ordinary offline
benchmark-tool guards pass. Three new controls cover duplicate credit, geometry-only association
and preserving thresholded scores/raw boxes during prediction preparation. No production Java
changes; the prior905-test production baseline is retained, not a fresh local full Maven claim.

Manifest hashes bind all228 Java numeric captures and17 proposal/manifest files. No original
JPEG/XML, derivative PNG, model weights or text transcriptions are redistributed.

## Reproduction

First reproduce the prior region capture, retaining local PNGs. Use the exact pinned graphs
and source-cache identities from the linked manifests. Prepare predictions without annotations:

```sh
java -cp "docweave-validation/target/test-classes:$DOCWEAVE_CLASSPATH" \
  dev.docweave.validation.ScoreBenchmarkCaptures detector-structures prepare \
  --captures /path/to/region-capture21 --summary /path/to/region-summary.json \
  --output /path/to/NEW-proposals
```

The comparator was migrated to the JVM on 2026-10-22 (`ScoreBenchmarkCaptures detector-structures`, run from the
repository root after `mvn test-compile`); it reproduces this directory's retained report and retained proposal files byte for byte.
Identity files still pin the retired Python source, which remains in repository history. See the
[migration evidence](../python-scorer-migration-2026-10-22/README.md).

Compile CaptureDetectorStructures.java with release21 against built core/PDF/ONNX classes and
runtime dependencies. On each actual JDK, run dev.docweave.layout.CaptureDetectorStructures
with five arguments: region-capture directory, prepared proposals, NEW structure-output directory,
structure graph path, structure SHA256 from manifest.json. Only then evaluate original labels:

```sh
java -cp "docweave-validation/target/test-classes:$DOCWEAVE_CLASSPATH" \
  dev.docweave.validation.ScoreBenchmarkCaptures detector-structures compare \
  --capture21 /path/to/structure21 --capture25 /path/to/structure25 \
  --cache /path/to/ctdar \
  --manifest benchmarks/review/pdf-physical-scans/extended-manifest.json \
  --proposals /path/to/proposals --output /path/to/NEW-summary.json
# JUnit guards for this scorer run in `mvn verify` (docweave-validation).
```

The next useful diagnostic is to evaluate the existing observed-rule/model composition on the
modern whole-page set, preserving the same source labels and historical counterexamples. It may
separate grid evidence from model axis/span failures before investing in another detector adapter.
