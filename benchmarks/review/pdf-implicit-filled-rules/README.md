# Native implicitly closed filled rules

A PDF fill operator implicitly closes open subpaths. The procurement fixture uses15 black,
three-edge subpaths for thin rectangular borders across its middle column. The old visitor
required four recorded edges and discarded those painted rules, leaving two independent native
grids. The correction restores the source-defined closing edge before the existing rectangle
validation; it does not synthesize a missing painted border or join separate table predictions.

The primary rule is specified in the [Adobe PDF reference, section4.4.2, filling](
https://opensource.adobe.com/dc-acrobat-sdk-docs/pdfstandards/pdfreference1.4.pdf).
Source input identities, original selected pages and CSV/ROI expectations remain pinned by
[the frozen business protocol](../pdf-business-cell-text/manifest.json). Historical baseline
[0759b84](../pdf-business-native/README.md) and model/rule reports remain unchanged.

## Cause and bounded correction

On the original rotated procurement page, an example black filled subpath has three straight
edges through displayed corners(157.74,61.44),(247.26001,61.44),(247.26001,61.92),(157.74,61.92).
There is no explicit final close operator. Closing it as specified yields the same thin rectangular
area painted by the PDF renderer. After unchanged half-thickness cap normalization, its horizontal
centerline spans157.5..247.5 at61.68. The same pattern occurs at all15 row boundaries.
Geometry-only inspection changes206 retained native segments to221, removing none at0.001-unit
rounded diagnostic comparison. `diagnosis.json` records the numeric observations and added rules;
raw source paths/PDFs/transcriptions are not redistributed.

`PdfPageGraphics` now caps its subpath counter at two. Only one noncurved three-edge fill subpath
gets its implicit final edge. The existing four-edge validator still requires connected nonzero
orthogonal edges, exact turns and a thin elongated rectangle. Triangles, diagonal closures and
curves do not qualify. Even coincident edges from multiple subpaths remain excluded, since filling
those independent subpaths is not the same shape as filling a single rectangle.

Subpath state resets with pending geometry after fill, stroke or discard. Existing color/alpha/
blend/mask, thickness<=1, length>=4 and8xthickness, half-thickness cap normalization and0.5-unit
grid tolerance remain. Open strokes are not closed; missing grid boundaries, compound fills,
white/pale/transparent/broad/skewed paths remain unsupported. Filled-and-stroked paths retain
their historical stroke behavior. No public API, dependency, OCR/model policy or rendering/visibility
claim is added. Existing operator, table/work and cancellation boundaries remain.

## Owned and independent validation

Four new PdfFilledRuleTest methods exercise both fill winding rules, all page quarter turns with
crop/UserUnit2 and exact table HTML/cell/parent geometry relative to explicit fills; triangles,
curves, three- and four-edge compound subpaths and white-fill rejection with ordinary text retained;
TableLimits and8 concurrent virtual-thread document calls; and an actual open stroke with only
its three source edges, plus state cleanup before a later independent implicit fill. The prior
closed/broken/adjacent/merged/nested and all other regression controls remain in the full suite.

Actual Java21/25 clean verify passes **915 tests each, zero failures/errors/skips**, with strict
Javadocs. All36 optional tooling guards and the pinned formatter pass. The existing published
native-cell regression is rerun on both JDKs: eu-010 retains its fixed22/22 positional text matches
under its supported modes; other original cases remain diagnostic misses. Those separate native
labels are not combined into the business denominator or a general accuracy claim.

All five native policies, applied uniformly to the same complete original PDFs, produce:

| Case | Before | After | Saved CSV position matches after |
| --- | --- | --- | --- |
| financial-disclosure |One88x5 table |One88x5 table |440/440 |
| transport-procurement |14x2 and14x8 fragments |One14x11 table |152/154 |
| education-statistics (control) |No Table |No Table |0/350 |

Procurement parent ROI IoU rises from0.671653 for the associated fragment to0.944868 for the
complete grid; the unmatched fragment disappears because source rules now connect the grid.
The two remaining CSV differences at zero-based(10,1) and(11,1) each contain one additional
ASCII space in native output. Text output and whitespace-only normalization are deliberately
unchanged. All154 procurement expected cells are nonempty. Financial preserves266/266 nonempty
and174/174 empty matches, with432 actual merged anchors projecting into440 evaluation positions.
CSV equality is not independently reviewed physical-span fidelity.

Per policy, business-only matches rise440/594 to592/594 (nonempty418/420,empty174/174).
Including the separate statistics control gives592/944; no Table there does not imply no text.
These are small development-fixture comparisons on older native PDFs, not held-out accuracy,
current/scientific coverage, physical-scan quality, or a guarantee of general PDF completion.
No rasterization, OCR, model, reference crop, per-case mode choice, source repair or label rewrite
enters extraction. One complete CSV text grid matches; procurement still has the two differences.

## Reproduction and release scope

Use the unchanged [native capture/comparison commands](../pdf-business-native/README.md) against
this production version and a fresh output directory. `java21.json`/`java25.json` retain text-free
counts/geometry/identities; all15 per-case records agree across JDKs except JSON-byte hashes, and
full local decoded captures agree. `prior-independent-java21.json` and25 retain the earlier
independent control results without raw text. `identities.json` pins source/tool/log/report identities.
Source PDFs/CSVs/annotations, recognized text and source raster/model payloads remain fetch-only.

The fresh official upstream guard still reports0.27.28/main1b02b4d189c260ed9b1aca29583203e233d853cb,
six commits ahead of historical0.27.27. This is the previously reviewed delta (including OCR agent
error-cause propagation), not a new native rule algorithm. `upstream-status.json` preserves metadata;
the strict live guard intentionally reports release drift. Historical pins/goldens are not rewritten
and this is not a freshness/parity pass. Ordinary offline verification passes separately.

This correction is a scoped beta4 functionality milestone, cumulative with beta3/beta2. The source
POM stays0.1.0-SNAPSHOT; local versioned preparation, signing, upload and public publication remain
separate. Existing beta2/3 candidates and published beta1 coordinates are untouched. Next broaden
independent scientific/current-business validation while retaining the separate statistics/borderless,
physical-scan and held-out quality gates.
