# Education table header row diagnosis

The 2/214 exact span/text result in the [independent check](../pdf-native-first-independent/README.md)
comes primarily from model header segmentation before native text assignment. No production
policy, reference label or quality result changes. The source/reference/code hashes in manifest.json
were frozen before these diagnostic captures. This is development diagnosis, not held-out accuracy.

| Stage | Source page1, weak case | Source page3, control |
| --- | --- | --- |
| Published XML rows |32 |32 |
| Decoded positive row objects |33 |32 |
| Excluded by table membership |0 |0 |
| Removed by duplicate suppression |0 |0 |
| Emitted model rows |33 |32 |
| Model anchors |225 |222 |
| Unchanged exact span/text matches |2/214 |208/215 |

Published page1 reference row1 has five single-row header text boxes at displayed y223–245.
Each crosses the model boundary at y235.54694852213055 between model rows1 and2. The reference
represents a multiline header as one row; the prediction represents its two lines as two atomic
rows. All30 subsequent reference rows2–31 have every single-row text-box center in model
row(reference+1). No centers are unassigned. On page3 all30 body rows2–31 have centers in the
same-index model row. This localizes the persistent displacement to page1 header segmentation
without consulting strings or shifting labels. Source row0's heading is a separate merged region.

Three page1 and one page3 multirow reference anchors are counted and never split to fabricate
row boxes. XML text envelopes are not physical cell boundaries. Many other text boxes slightly
cross model edges (150 on page1 and66 on page3), so crossing alone is not a safe merge rule.
The original page was inspected visually locally; no image/transcription is committed.

The earlier hypothetical+1 comparison201/214 remains diagnostic, not a corrected score. Actual
quality stays2/214; merge/text/extra/missing-anchor differences remain. No evaluator repairs,
source rewrites or threshold tuning apply. Suppression tuning cannot remove this row: all33
survive already. The decoder is post-class-selection/score/clipping, not raw logits or calibrated
confidence. Page1 crop985x914 becomes800x742; page3's983x1013 becomes776x800. Resizing is measured,
not established as the cause or proof that greater render DPI would help.

## Reproduce

Use unchanged CaptureDetectedTableInput original-pdf mode: original PDF, NEW output, detector
path/hash, structure path/hash, original-pdf, source page, PDF SHA256. Use java.awt.headless=true.
This selects pages1 and3 only; it is not another whole-document quality run. The original complete
captures and all four quality denominators remain. Same pinned TATR graphs/fourthreads/150DPI/
KEEP_HIGHER_SCORE policies apply, without reference ROI/text in inference. It replays actual
private topology membership/suppression with existing budgets; no public diagnostic API is added.

Compile AuditPublishedRowBands.java with the project runtime classpath, then invoke:

```sh
java -cp "$CLASSPATH" dev.docweave.partition.AuditPublishedRowBands \
  /source-cache/us-002-str.xml \
  /full-document-captures/education-first/source.json \
  /full-document-captures/education-first/native-first.json \
  1 /absolute/NEW-numeric-report.json
```

Repeat for education-second and actual Java21/25. Caller verifies original XML/PDF identities
against the frozen source manifest. Reports record XML/capture/PDF hashes. The helper reads
only XML row/span and bounding-box attributes, never content strings. Omitted end-row means unit
span; source row origin uses the common minimum. Unrecoverable model rows keep original indices;
no rows are invented. Positive intersections exclude edge contact, centers use half-open intervals.
Duplicate/missing regions, invalid boxes, inconsistent/overlapping model rows and mismatched
source frames fail explicitly. Inputs are bounded16MiB, geometry/cancellation shares the existing
work counter, XML forbids DTD/entities/schema access, and outputs require fresh paths.

Standalone-source verification compiles release21 and runs bounded split/edge/missing-index/
forbidden-XML controls without external sources or engines. Helper public/private Javadocs pass
both JDKs. Full clean verification passes950 tests per JDK, strict documentation and the
standalone controls; pinned formatting/diff checks pass. Geometry/stage metrics agree across runtimes; capture byte hashes differ only because
serialized map order can differ. Numeric counts/geometry/hash identities are committed; XML text,
PDFs, rasters, model graphs and raw table captures remain fetch-only/local.

## Next decision

Do not globally merge adjacent header rows or delete a row by index. The next useful experiment
is an explicit native-text-assisted multiline-header policy with conservative geometric evidence
and adversarial controls preserving legitimate stacked subheaders, distinct data rows and spans.
It must use no reference strings/ROIs, preserve budgets and glyph ownership, and replay all fixed
sources including negative cases before acceptance. Existing production behavior stays unchanged.
Recent business/scientific breadth, scans and final comparison gates remain open. Release stays
deferred; this diagnosis does not warrant publication or establish broad PDF completion.
