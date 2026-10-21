# Live provider layout with original native text

The explicit production `PdfPartitioner.createWithNativeLayout` strategy recovers both fixed
current-business statement tables with **59/59** exact issuer HTML ordinal content rows.
This implements the earlier [offline feasibility probe](../pdf-native-model-probe/README.md)
using actual model inference on the complete original PDF, without any OCR engine.
Defaults and previous native/OCR factories remain unchanged.

## Frozen source and experiment

`manifest.json` was frozen before running the live capture. It pins the unchanged
[current-business reference manifest](../pdf-current-business-rows/manifest.json) and existing
[model identities/protocol](../pdf-current-business-model/manifest.json). The complete original
14-page PDF is passed once to the production service per JDK. The service renders each native
page at150DPI,16M maximum pixels, with explicit1024 raw provider anchors and the historical
pinned two-graph TATR policies (four threads, structure KEEP_HIGHER_SCORE). No reference HTML,
ROI or selected source pages enter extraction. Model lifecycle is managed outside the service.

`CaptureNativeModelTables.java` selects original pages7 and10 only after full-document
extraction. Typed document failures retain both cases; unexpected I/O fails the capture.
Original59 issuer HTML rows, row hashes, strict normalization/ordinal comparison and maximum
parent IoU>=0.5 association remain unchanged. No text/geometry/threshold tuning after results.
All raw captures and third-party source text remain local/fetch-only.

## Actual Java21/25 results

| Case | Reference rows | Emitted nonblank rows | Exact ordinal rows | Parent IoU |
| --- | --- | --- | --- | --- |
| operations |27 |27 |27 |0.974409 |
| balance-sheet |32 |32 |32 |0.979576 |

Both runtimes complete SUCCESS, with14 layout calls on the original14 native-text pages,
9 model-returned tables document-wide, and zero PAGE/CELL OCR calls. Only the two selected
tables have independent content references; do not call all9 tables correct. Runtime raster
sizes are1275x1649. Each selected page emits one associated table, with no missing/extra
nonblank row positions. Per-case report `layout_calls=14` and `layout_tables=9` repeat the
**same document-wide counters**; do not add them across selected pages. Parsed captures,
model geometry, text and count results agree across21/25.

Earlier image-only selected-page derivatives with PAGE/CELL OCR matched42/59 and44/59 rows;
the offline frozen-geometry native probe matched59/59. Live original rendering yields slightly
different model boxes/IoUs from raster-derivative rendering. Thus this is a practical comparison
of existing representations, not an isolated experiment changing only text recognition.
One issuer/two development tables are not held-out general accuracy or independent physical
cell/span labels. Native font quality, model accuracy, broad reading layouts and scans remain
open gates. No calibrated confidence, total-document deadline or peak-memory claim.

## Production contract and owned controls

See [the feature contract](../../../docs/pdf-support.md#explicit-provider-geometry-with-native-text)
and [beta.6 draft notes](../../../docs/releases/0.1.0-beta.6.md). The new interface factory uses
an immutable explicit render policy. It replaces native table heuristics on native-text pages,
keeps unclaimed text/images, and retains textless pages without silently starting OCR. Complete
provider topology is validated before half-open native-glyph ownership. Empty tables claim
nothing. Existing native spacing and wrapped-line joining preserve source text without repair.
Unsupported native rotations fail; cropped source/UserUnit metadata remains supported.

Nine owned tests cover section colspans, wrapped labels, subtotals, currency/parentheses,
empty layouts/cells, mixed native/textless pages and images, provider error causes, invalid
coverage, render/raw-anchor/slot/text/output limits, cancellation, declared rotation,
crop/UserUnit and concurrent virtual-thread reuse. Full Java21/25 clean verification passes
937 tests each with strict Javadocs;36 tooling guards and pinned formatting pass. The standalone
capture helper also passes explicit public/private Javadocs on both JDKs. The earlier
independent scientific-span source regression was rerun on both JDKs; its six native profiles
retain43/43 exact text/span anchors.

## Reproduction

Compile `CaptureNativeModelTables.java` for release21 with current module classes and
PDFBox/Jackson/ONNX dependencies in the development classpath. Run:

```sh
java -Xmx1g -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED \
  -cp "$classes:$classpath" dev.docweave.partition.CaptureNativeModelTables \
  /path/to/source-cache benchmarks/review/pdf-current-business-model/cases.tsv \
  /path/to/NEW-live-captures \
  /path/to/detection.onnx 0f625baa8849eff26e265ce08a2f6b1479e73f52edd5adc09799c7e7fcc689b9 \
  /path/to/structure.onnx ff1d19a18cb5f041c1692ff681f891a5180f9430d68a71d2cf7a0a2e8e55b990
```

Score with `PdfCurrentBusinessRowsTest` and the original source cache, a new native control
report, `.model.captures=/path/to/live-captures`, a new `.model.report`, and
`-Ddocweave.pdf.current.business.model.native.live=true`. The full property prefix is
`docweave.pdf.current.business`; `.cache` and `.report` remain as in the original experiment.
Do not also select `.model.native.probe=true`. Ordinary Maven verification requires no models,
OCR engine, source downloads or external network. Scores remain diagnostic rather than
hardcoded expectations preventing later improvements.

Fresh official upstream metadata is recorded in `upstream-status.json`. The strict online
check correctly detects0.27.28/main1b02b4d versus historical0.27.27 pins, the same already
reviewed drift (including OCR agent error causes), not a new native-layout upstream algorithm.
This is not a freshness/parity pass; historical goldens remain unchanged.

This useful production addition is a beta.6 development milestone. Draft notes are ready;
no candidate/tag/GitHub prerelease/Central deployment is created by this change. The next
release must follow the annotated-source-tag and reviewed-prerelease-note workflow.
