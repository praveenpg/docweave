# Missing table anchors

This extends the existing fixed text-envelope profile without changing extraction or its
historical metrics. The same20 original PubTabNet train images, source rectangles, structure
graph,150DPI embedding and300DPI render are used. Originals/model remain fetch-only.
See ../pdf-text-envelope/manifest.json and README.md for source pins and reproduction;
use fresh report paths. No OCR, detector, orientation or sample-specific tuning is enabled.

Among1230 valid labeled cells,131 lack an identical row/column/span key:

| Exclusive symptom | Labels |
| --- | ---: |
| No predicted table |97 |
| Different span at the same origin |24 |
| Origin covered by another anchor |6 |
| Expected extent outside predicted grid |4 |
| Uncovered origin inside grid |0 |

The97 no-table labels all belong to PMC1626454_002_00.png. Its100 published anchors
include three without text-box labels. Missing-label counts must not be presented as all
missing cells or lost OCR strings. The other34 labeled mismatches span five images.
Symptoms have precedence: no table, outside grid, different span at origin, covered origin,
uncovered origin. A shifted/missing axis can cause several span symptoms; this is not a
causal model-error classification. The structure profile permits at most one table.

Java21/25 records match exactly. Removing the new reasons field reproduces all historical
per-image geometry metrics exactly. Analytical controls distinguish all five categories,
including a span that extends beyond the grid. Existing annotation/overlap controls remain.
No production policy or default changed; the result directs further investigation toward
rejected topology and span assembly rather than treating all131 as OCR recognition errors.

A separate temporary single-image object-deletion probe suggested overlapping merge
predictions cause the no-table result: omitting a0.5327-score merge yielded a9x12 layout,
while omitting each of the other three merge predictions did not. This is exploratory,
source-specific development evidence, not a proposed correction or held-out evaluation.
No cell-text improvement or complete reference topology was established by that probe.
The paired corpus reports above are the reproducible committed evidence.
