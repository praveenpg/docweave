# Declared quarter turns in native provider layout

The explicit native/provider-layout strategy now supports declared page and native-baseline
quarter turns. The unchanged rotated procurement PDF succeeds with one14x11 table and152/154
saved CSV position matches, instead of failing before inference with0/154. This closes that
specific rotation gap. It does not fix the existing financial/statistics model row-count misses.

## Implementation and owned acceptance

`PdfNativeProviderTables` assigns source glyph centers through the existing
`PdfPageGeometry.center` mapping into the same displayed frame as the rendered provider raster.
It no longer requires declared rotation0/native direction0. Crop-relative source positions are
not cropped/rotated a second time. Parent/cell coordinates continue to use the displayed crop
width/height with explicit UserUnit metadata and no additional physical scaling.

The existing ordinary/ruled native line grouping is extracted unchanged into
`PdfGlyphLines.displayed`. Both ordinary native layout and provider cells reuse it: group
baseline directions separately, preserve native spacing/text, and order enclosing lines by
stable displayed top/left. This is geometric ordering, not semantic mixed-direction reading.
Arbitrary-angle native text still fails explicitly for this factory. No new API, provider
lifecycle transfer, raster correction, OCR call, source edit or model threshold is introduced.

Owned controls cover all16 declared-page/native-baseline quarter-turn combinations plus
0/90/180/270/noncanonical/inherited source page turns with crop offsets/UserUnit. A constant
source scene and independent red raster pixel verify the provider sees the same400x300
frame. The merged top row, exact parent/cell rectangles, source strings, unclaimed lines and
image ownership remain fixed. Existing render/anchor/slot/text/output/work/cancellation,
provider-failure, invalid-topology and concurrent-use regressions remain. Default native/OCR
factories retain their historical behavior.

## Unchanged-source replay on Java21/25

`manifest.json` froze source references/profiles before these captures. The existing live
capture helper processes each whole original document uniformly with pinned TATR detector/
structure graphs, four native threads, structure KEEP_HIGHER_SCORE,150DPI/16M pixels/1024 raw
anchors. No selected pages, ROI, labels or reference text enter extraction. PAGE/CELL OCR calls
remain zero. Geometry/text/thresholds and scoring labels are not tuned after results.

| Case | Earlier explicit native/model route | After quarter-turn support |
| --- | --- | --- |
| transport procurement |UNSUPPORTED_OPTION;0/154 CSV positions |SUCCESS;14x11;152/154 |
| financial disclosure |59x5;102/440 CSV positions |Unchanged |
| education statistics |51x7;72/350 CSV positions |Unchanged |
| recent issuer operations/balance |59/59 issuer HTML ordinal rows |Unchanged |
| scientific cycle |43/43 exact span/text anchors |Unchanged |

The three original CSV cases retain all944 reference positions, including the same two
procurement mismatches. CSV labels are saved extraction expectations, not physical-span labels.
Issuer rows and scientific spans are separate reference types and must not be pooled into a
PDF accuracy score. Procurement association IoU and document call counts are recorded in the
paired reports. There is no new automatic model/native routing or claim that other unlabelled
model-returned tables are correct. All6 prior native policies on the recent statements and
scientific source are replayed independently; their existing results remain unchanged.

Actual Java21/25 full clean verification passes940 tests each with strict Javadocs. All36
benchmark-tool guards and pinned formatting pass. Parsed captures/metrics agree across runtimes,
excluding runtime/serialization-byte identities. This work is unreleased: release
preparation deferral remains in force. No candidate/tag/prerelease/Central action occurs.

## Reproduction and retained evidence

Use the unchanged [live capture helper and model identities](../pdf-native-provider-layout/README.md)
with the fixed original business/current-business/scientific caches. Use the unchanged
[business CSV comparator](../pdf-native-model-breadth/README.md#reproduction-and-safeguards)
and explicit Java current-business/scientific capture properties. Supply new capture/report
paths. No ordinary Maven test downloads inputs, loads models or invokes an OCR engine.
Text-free business/current/scientific reports and identity hashes are committed here. Raw
sources/labels/models/capture text remain local/fetch-only. Earlier negative reports remain
untouched so the pre-inference rejection is reviewable rather than rewritten.

Fresh official upstream metadata remains0.27.28/main1b02b4d versus the historical0.27.27 pin,
the same previously reviewed drift. The strict online check correctly reports release drift,
not a freshness/parity pass; no historical reference repin occurs. `upstream-status.json`
records the new bounded metadata check.

## Progress reporting

The repeated85–90% global estimate is withdrawn, not increased to make progress appear larger.
The [current ledger](../../../docs/pdf-progress.md) names delivered functionality and remaining
quality gates. This milestone closes declared quarter turns; model row segmentation, broader
modern sources, scan/text fidelity and held-out calibration remain explicit open work.
Next audit row segmentation on the fixed financial/statistics cases with independent controls,
retaining default strategies and the observed misses.
