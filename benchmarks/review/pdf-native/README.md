# PDF native milestone upstream review

The opt-in Java freshness check observed Unstructured 0.27.28/main at
1b02b4d189c260ed9b1aca29583203e233d853cb, six commits beyond the saved released reference.
`upstream-status.json` retains the fresh response identities and paths. `review.json` records a
bounded source review of the single new commit since reviewed main3253c2b0.

Official comparison: https://github.com/Unstructured-IO/unstructured/compare/3253c2b0f6b0a5f0808910c085675672aff718f3...1b02b4d189c260ed9b1aca29583203e233d853cb

The new OCR-agent load failure includes the original exception message and chains the cause;
tests assert both. This informs future OCR adapter diagnostics. No native PDF extraction semantics
were adopted from this patch. Existing five-commit HTML/DOCX/XLSX triage remains in the earlier
HTML review. Historical references stay at0.27.27; no fresh upstream parser execution or PDF parity
claim. The strict online gate correctly remains red for release drift until explicit reference
review/regeneration. Owned PDF tests exercise Java contracts rather than fabricate reference results.

Apache PDFBox3.0.8 was selected from the official download page:
https://pdfbox.apache.org/download.cgi . Loading follows the3.x Loader API:
https://pdfbox.apache.org/3.0/migration.html .

The ruled-table expansion refreshed the same opt-in check; `upstream-status-tables.json` records
Unstructured0.27.28/main1b02b4d unchanged from the native-module review. The strict gate still reports
the known release/reference-pin difference. No new source drift, reference repin or upstream PDF
execution is claimed. The new table topology rules are explicit local Java policy.

The aligned-text borderless milestone refreshed the official check at2026-10-17T01:50:49.397343373Z;
`upstream-status-borderless.json` records unchanged0.27.28/main1b02b4d. Historical0.27.27reference
pins remain unchanged, so the strict opt-in freshness gate still fails the known release drift.
The opt-in aligned_numeric_v1 detector is local policy, with owned tests and explicit evidence;
no new source drift, independent PDF accuracy score or upstream PDF execution is claimed.

The native paragraph/reading-order milestone refreshed official status again;
`upstream-status-reading.json` records unchanged0.27.28/main1b02b4d. The strict optional online gate
still reports known drift from historical0.27.27references; those pins/goldens remain unchanged.
The separate pdf-reading review uses PDFBox expectations, not a new Unstructured execution.

The quarter-turn rotation milestone refreshed official status again;
`upstream-status-rotation.json` records unchanged0.27.28/main1b02b4d. Strict optional online freshness
still fails the known difference from historical0.27.27references. Pins/goldens remain unchanged;
PDFBox rotation corpus expectations are distinct from Unstructured reference execution.
