# Separately reviewed1879 physical line order

The explicitly deferred reference-order review is now complete for the observed1879
Bebel page140. The original PAGE source declares region order but no line reading order.
A full source scan and independently generated50-polygon/index overlay were visually
reviewed before scoring OCR against the new order. The page has one page-number line,
a single-column body through numbered item3 and a bottom footnote. The last footnote
line continues on the next page; every annotated source line on this page remains.
No blind/human-expert review, training-overlap exclusion or broad-domain claim is made.

`reviewed-order.json` freezes all50 original line IDs/bounds, original TIFF/XML identities,
review status and hashes of the external preview/overlay. Three displaced IDs are l22
(XML9→reviewed19),l36(23→38),l64(43→49); intervening array indices shift accordingly.
This is a separately labelled complete order, **not a change to the original XML** or a
claim that its line order was explicit. Source transcriptions, normalization, punctuation,
case, long-s and physical hyphens stay unchanged. No OCR output supplies labels or text.
Original and derived reference sequences retain all3,049 code points.

| Pinned model | DocWeave reviewed-order character edits | Unstructured0.27.28 reviewed-order character edits |
| --- | ---: | ---: |
|German|9/3049(0.30%)|20/3049(0.66%)|
|Fraktur|55/3049(1.80%)|57/3049(1.87%)|

This is better-qualified measurement of existing outputs, **not an extraction improvement**.
The historical XML-sequence figures291/300(German) and334/334(Fraktur) remain in their original
reports and in the new paired output. Published `reference_line_order_explicit` stays false;
new fields use the distinct `reviewed_order_` prefix and bind the separate evidence hash.
No source/model winner routing, relaxed scoring or overall library ranking follows.

Both native models rerun through the existing public provider under AUTO/ENGINE_DEFAULT,
actual source TIFF DPI and no new preprocessing. Prediction hashes and all selected historical
metrics/source/model/frame fields exactly match the original source-resolution controls.
The two already pinned upstream public captures are reused read-only and scored on the JVM;
no Python inference, model substitution or invented upstream per-line boxes. Original shared
PDF container, engine/model/language/runtime pins are checked. All four library/model outcomes
and every source character remain. This reference version is not latest upstream-main evidence.

## Guarded evaluation implementation

Optional source cases may supply one explicit review-file/SHA binding. Review loading caps
bytes before reading, rejects already XML-declared order, validates original XML/TIFF hashes,
image dimensions, completed visual-review status, contiguous indices, complete unique line
membership and exact original bounds. It reorders the original immutable Line objects; the
review never supplies replacement strings. Original metrics still use original order, including
XML-qualified whole-sequence diagnostics and unchanged geometric associations. Reviewed global
character/word metrics are additional fields. Ordinary runs without the binding stay unchanged.

Two new owned harness methods cover complete independent ordering, immutable source/permutation,
same local geometry scores, distinct whole-text edits, public-text scoring without boxes, nine
corrupt review variants, changed review-file hashes and rejection of overriding declared XML.
The initial synthetic prediction control had a mismatched B rectangle; its corrected rectangle
matches the independently authored source and now asserts all3 geometrically exact lines.
Existing bounds/cancellation/Unicode/reference/engine controls remain.

11 selected harness methods pass including2 actual native OCR calls. A separate13-method
owned/paired run reproduces all16 historical paired-library results exactly. New standalone
Java sources compile; two-file private strict Javadocs and scoped formatting pass. Last full
production build remains1,224 Java21 tests; no new production extraction policy or full/Java25
run is claimed for this evaluation change. Raw scan/XML/raster/transcription/model data remain
outside Git. The incomplete broader historical/degraded/calibration gates are unchanged.

## Reproduction

Use exact original TIFF/XML/models and cached shared container named by the manifest. Run
PdfHistoricalTextCorpusTest with this reviewed manifest and original historical cache/report/
executable/tessdata properties, source.resolution=true, OMP_THREAD_LIMIT=1 and explicit shared
container cache; do not enable whitepoint/sparse/other preprocessing. Reports require fresh paths.
The new optional sidecar is used only for metrics, never passed to OCR or native extraction.

Compile ScoreReviewedHistoricalComparison.java against the Java21 reactor and validation-test
classpath. Supply this manifest, original source cache, fresh reviewed native report, original
pinned upstream cache and fresh output. Selection binds the original paired manifest and models;
runtime.json and all capture/container/model/language/source identities remain enforced.
Run the unmodified optional PdfInterimScanComparisonTest against original caches separately and
compare its complete results to pdf-interim-scans/java21.json. No historical pin is rewritten.
