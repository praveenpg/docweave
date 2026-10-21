# Complete-layout provider integration

PdfTableLayoutProvider is a public interface with immutable Box/Anchor/Table values;
implementations stay independent of any model runtime. Explicit PROVIDER_LAYOUT plus
four-argument PdfPartitioner factory requires actual-word and layout providers. One
PAGE word call and one layout call; no cell OCR, discovery/download or default changes.

Complete raw layouts are checked before mixed-page selection: raster/envelope bounds,
disjoint source rectangles/logical slots, physical row/column order, exact complete
coverage and dimension/aggregate-anchor/work ceilings. Disjoint in-range rectangles
and summed slot areas avoid allocating a slot matrix. Invalid claims are OCR_FAILURE,
budgets TABLE_LIMIT. Word centers assign uniquely; empty/merged/multiline cells produce
escaped HTML, optional source-scaled cell bounds and explicit provider provenance.
Unowned words use shared PdfOcrTokens remainders, also used by aligned-word extraction.
No continuation or calibrated confidence inferred.

Six owned controls cover independent call counts, unordered anchors, colspan/empty/
multiline cells/escaping, original spanning-line remainders, empty layouts, malformed
coverage/geometry/order/overlap, missing capabilities, typed cancellation, raw/table/text
ceilings, giant shapes without matrix allocation and invariant72/144DPI cell coordinates.
Full actual Java21/25 verify810 tests each, zero failures/errors/skips, strict Javadocs,
eight installed Tesseract families and20-image RULED/AUTO300DPI corpus. Historical corpus
measurements unchanged, including69-anchor merged topology/center positive. Pinned
formatter,12 reference guards and diff check pass. Logs /tmp/docweave-provider-layout-final-java21
and java25. No learned model is integrated by this checkpoint. Separate model numerical/
raw-count feasibility at ../pdf-table-model-feasibility is not end-to-end extraction.

Final capability guard rejects a missing layout before invoking OCR. Official upstream
refresh14:55:55UTC still reports0.27.28 release/main six commits beyond the historical
0.27.27 pin; the opt-in freshness assertion reports known drift, no repin/parity claim.
