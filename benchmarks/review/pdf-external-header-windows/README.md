# Next ordinal published cell-span controls

Selection.json froze the next two ordinal US ICDAR2013 originals, us-003/us-004,
from the existing pinned Camelot tree before extraction. All published regions
are retained: one table on sourcepage1 and one on sourcepage2 respectively.
Manifest.json pins every original PDF/structure/region XML by size/SHA256/Git-blob
identity, source coordinates, dimensions and every expected anchor. Originals and
raw captures remain fetch-only. These are historical native documents, not recent
scientific material, physical scans or a held-out population sample.

The published XML has cell spans/text but no semantic header annotation. The
fixed first-two-logical-row window is therefore positional. Do not call it true
header recognition or infer missing header roles from model predictions. Spans
originating in the window are retained whole, even when they cross its boundary.
The full-table result scores all published cells; sparse unlabelled slots are not
filled or deleted to improve results.

ActualJava21/25 metrics agree except runtime strings/capture byte identities:

| Case | Exact published span/text | Observed anchors | Leading window |
| --- | --- | --- | --- |
| document-three |19/19|20|7/7 references, one additional prediction; not exact complete window|
| document-four |87/89|89|10/10, exact complete window|

Both full originals succeed and each selected region is geometrically associated
(maximum source-parent IoU>=0.5, stable first tie). Neither full table exactly
matches the complete published anchor set. Document-three's additional anchor
occupies an unlabelled position; sparse labels do not prove that emitting a blank
cell is a production defect. Do not remove it to force equality. Document-four
retains two missing positional-span keys and two extra predictions. No tuning,
source repair, row shifting, case removal or new extraction behavior occurred.

Reproduce with CaptureNativeModelTables, cases.tsv and the same pinned graphs in
pdf-header-evidence-replay. Score with optional PdfScientificSpanTest properties:

```
-Ddocweave.pdf.independent.manifest=benchmarks/review/pdf-external-header-windows/manifest.json
-Ddocweave.pdf.independent.cache=/explicit/original-source-cache
-Ddocweave.pdf.independent.captures=/explicit/captures
-Ddocweave.pdf.independent.report=/new/numeric-report.json
```

The existing historical independent manifest remains the default. Tests verify
source bytes/blob hashes, original XML geometry/dimensions, capture/model/source
identities and selection policy; independent labels and ROI never enter inference.
Window controls retain crossing spans, wrong-text matches, extras and missing-table
denominators. The subsequent wrapped-date policy leaves both parsed captures
unchanged on bothJDKs; see pdf-native-iso-dates/unchanged-replays.json.
