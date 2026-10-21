# Trained-model header evidence replay

All10 fixed selected regions from7 complete original PDFs were replayed on actual Java21/25
with the original detector/structure graph bytes. Six model-selected tables retain positive
header declarations; four accepted native tables retain absent/unknown provider evidence.
No production behavior, thresholds, labels or table structure changed in this step.

## Observed declarations

| Selected region | Method | Shape | Declared top header rows |
| --- | --- | --- | --- |
| disability-first |model/native glyphs |26x11 |2 |
| disability-second |model/native glyphs |13x11 |3 |
| education-first |model/native glyphs |33x8 |3 |
| education-second |model/native glyphs |32x8 |2 |
| financial-disclosure |native closed grid |88x5 |unknown |
| transport-procurement |native complete rules |14x11 |unknown |
| education-statistics |native partial rules |50x7 |unknown |
| operations |model/native glyphs |27x5 |2 |
| balance-sheet |model/native glyphs |32x3 |1 |
| scientific-cycle |native closed grid |7x7 |unknown |

All selected regions report SUCCESS and associate geometrically to the frozen source region.
This is scoped extraction/association evidence, not10/10 header accuracy. The source references
provide cell/row content and spans, not an independently labelled header-role oracle. Missing
header evidence on native tables is intentionally not inferred from a discarded overlapping model.

The weak education table's extra row is inside its declared three-row header prefix. The control
has two header rows. This helps exclude body rows from a future header-specific investigation,
but cannot distinguish wrapped text from legitimate stacked subheaders. Inspecting existing
anchors also shows genuine transitions at both header boundaries: the declaration alone does
not make a boundary geometrically redundant. No boundary, label or score is shifted or removed.

## Quality controls, retained separately

Both JDKs preserve the original fixed results:

- CSV positions: financial440/440, procurement152/154, statistics339/350.
- Published scientific exact span/text anchors:43/43, also retained across all six native policies.
- Independent regions:260/278,88/127,2/214,208/215 exact span/text anchors, with every missing/extra key retained.

These denominators are not pooled into one accuracy number. The education2/214 remains a miss.
The recent issuer's original PDF was fully processed, including both selected statement pages.
However, SEC returned HTTP403 for the pinned HTML reference during restoration. Its earlier
59/59 content-row result is **not rerun or claimed as current validation**. Both PDF regions
remain in the header/geometry report; neither is excluded or assigned an invented content score.
No access-control workaround, substitute labels or candidate-derived reference was used.

## Original model pins restored

When reconstructing the temporary graphs and export environment, the official pinned safetensors were
restored and SHA256/byte counts verified before loading. The documented CPU torch2.9.1,
torchvision0.24.1, transformers4.57.3, timm1.0.22, onnx1.19.1 and safetensors0.8.0 recipe runs
with local-only/offline loading, no pretrained-backbone fetch, eager attention and opset17.
ONNX full checker passes. The detector's only unused keys are its three known frozen-normalization
training counters; no missing/mismatched/error weights are accepted.

The old abbreviated recipe used the wrapper attribute self.source. That produced different
initializer/node identifiers and hashes. Restoring self.model reproduces BOTH original graph
hashes exactly: detector0f625baa8849eff26e265ce08a2f6b1479e73f52edd5adc09799c7e7fcc689b9 and
structureff1d19a18cb5f041c1692ff681f891a5180f9430d68a71d2cf7a0a2e8e55b990. The conversion recipe
is corrected accordingly. No model pin changes or re-export-as-equivalent assumptions apply.
Prior numerical controls refer to the same final graph bytes; no new Torch/Java numerical parity
run is claimed. Verified models and source/capture caches were also saved locally outside Git
for repeatable validation; machine-specific paths are not part of the published protocol.

## Protocol and reproduction

manifest.json was frozen before capture and pins unchanged source/reference manifests plus the
original graphs. cases.tsv retains all10 regions. The same native-first PARTIAL_TEXT policy,
150DPI/16M pixels/1024 combined anchors/four native threads/KEEP_HIGHER_SCORE structure is applied
to every complete original document. Only report pages are selected afterward; no reference
ROI/page/text enters extraction. No OCR runs. There are36 native layout calls per JDK across the
seven originals, including14 for the issuer PDF. Per-region counters repeat document totals;
never sum those repeats as distinct calls. Unlabelled other tables are not declared correct.

Compile CaptureNativeModelTables.java and invoke its existing seven arguments (source cache,
cases.tsv, NEW output, detector path/hash, structure path/hash) plus native-first. Use headless
AWT when no display is available. Then compile/run the offline numeric summarizer:

```sh
java -cp "$CLASSPATH" dev.docweave.partition.AuditCapturedHeaderPrefixes \
  /capture-root benchmarks/review/pdf-header-evidence-replay/manifest.json \
  /absolute/NEW-header-report.json
```

The summarizer checks reference manifest, source page/hash/frame, model pins and strategy;
selects maximum parent IoU>=0.5 with first-index ties; retains failed/missing associations; and
validates positive declared prefixes/policy. It never copies source strings or treats metadata
as truth. Input JSON is bounded16MiB, geometry comparisons use shared cancellation/work checks,
and output paths must be fresh. Model/source downloads or inference are never invoked by this tool.
Bounded ordinary standalone controls cover absent, stacked, out-of-range, zero and unknown-policy
claims. Source/model work remains opt-in; no production/Python runtime dependency is added.

Reuse the unchanged CSV comparator and scientific/independent Java evaluators with new report
paths and these opt-in properties alongside the existing source-cache/model-capture properties:
`docweave.pdf.scientific.model.native.first=true` and the independent cache/captures/report trio.
Full Java21/25 verification passes958 tests per JDK and strict Javadocs, including those real
scientific/independent source replays. The new helper's private/public Javadocs pass both JDKs.
Owned Java formatting passes; whole-tree formatting identifies only the unrelated batch
refactor, which is untouched and excluded from this commit. Local full checks include that
workspace refactor; no clean exact-commit claim is made. Existing published reports stay unchanged.

Parsed captures/source frames and numeric metrics agree across JDKs. Capture byte hashes can
vary with serialized map order. Committed files contain only numeric geometry/counts/hash
identities; PDF/CSV/XML/HTML strings, captures, rasters, weights and graphs stay local/fetch-only.

Next: audit header supercell/span predictions with native token ownership before selecting any
refinement. Preserve true stacked headers, data rows, crossing spans and current misses. Header
context is measured, while header semantics, recent-source breadth, scan text, calibration and
final comparison gates remain open. Release preparation remains deferred; no publication occurs.
