# Frozen-code transfer to another financial reporting period

Production code was fixed at `1febf26606e26b24ce7f7de1cd39bf97fd28c6f3` before selecting and
extracting the original [Apple April2026 statement PDF](https://www.apple.com/newsroom/pdfs/fy2026q2/FY26_Q2_Consolidated_Financial_Statements.pdf).
The [issuer release](https://www.apple.com/newsroom/2026/04/apple-reports-second-quarter-results/)
links that source. Independent row content comes from the issuer-hosted SEC filing HTML,
with exact source byte identities and publisher table indices in the frozen manifest.
Direct SEC retrieval returned403; the unchanged public issuer-hosted filing was used instead.
No reference text was reconstructed from PDF output.

Original page renders, three fixed ROIs and all113 publisher rows were frozen before the
first DocWeave extraction. The eight policies ran uniformly on the complete three-page PDF.
The scorer, normalization, parent-IoU association and nonblank-row rules are the existing
business protocol. No production/policy change, outcome-based configuration selection or
label repair followed this run. This is limited same-issuer/layout-family transfer evidence,
not broad independent held-out acceptance or calibration.

| Statement | Reference rows | Each of seven prior native policies | Unchanged shaded-row policy |
| --- | ---: | ---: | ---: |
| Operations |40|0/40|37/40|
| Balance sheet |37|0/37|37/37|
| Cash flows |36|0/36|36/36|

The shaded-row policy recognizes all three tables, with113 observed rows and110 exact ordinal
content matches. No rows are missing or extra. The three operations text mismatches remain;
neither the labels nor strict matching were relaxed. Parent IoUs are approximately0.974,0.952
and0.935, without individual cell-box accuracy claims. These113 rows are a separate population
from the earlier July document's114 and must not be silently merged into one accuracy claim.

All24 complete case/policy observations agree on actual Java21/25; every extraction succeeds.
Four business reference/scorer tests pass on each JDK. This is a source-validation addition,
not another production improvement or a newly repeated full suite. The unchanged production
baseline already passed1,080 full tests on each JDK plus final focused controls/Javadocs.
No OCR/model/Python test dependency, beta candidate, signing, tag or Central action.

Reuse `PdfCurrentBusinessRowsTest` with the source cache and this manifest, as shown in the
[original shaded-row experiment](../pdf-unseen-business-apple-2026/README.md). The shared
`VerifyBusinessRows.java` now accepts an explicitly declared reference total for a frozen-code
24-row transfer run with no earlier report. It continues to require the old114 total and
unchanged18 prior observations for the July baseline/candidate paths. Reports contain numeric
observations and hashes; raw PDFs/HTML/source text remain fetch-only.
