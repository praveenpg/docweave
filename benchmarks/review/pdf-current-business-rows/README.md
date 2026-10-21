# Current issuer financial-statement baseline

The original Amazon Q4 2025 earnings release, published2026-02-05, supplies a genuinely
recent business PDF alongside its issuer-authored SEC HTML exhibit. On two fixed statement
pages, all six existing native policies emit ordinary text but **no Table**. This exposes a
current-business native layout gap that the older business/scientific regressions did not.
No production behavior, policy threshold or reference label is changed to make this pass.

## Fixed original sources and reference meaning

[The manifest](manifest.json) pins the complete original14-page PDF and corresponding SEC
HTML with byte counts/SHA256. The [issuer PDF](https://s2.q4cdn.com/299287126/files/doc_earnings/2025/q4/earnings-result/AMZN-Q4-2025-Earnings-Release.pdf)
and [SEC exhibit](https://www.sec.gov/Archives/edgar/data/1018724/000101872426000002/amzn-20251231xex991.htm)
are fetch-only; neither original files nor text/transcriptions are redistributed. Initial
investor-relations HTML requests returned403; the public SEC exhibit was selected instead
before freezing inputs or running DocWeave. No authentication or access-control changes.

Source selection, source pages/ROIs, HTML table indices,27 operations rows and32 balance-sheet
rows, normalized reference-row hashes and all comparison rules were fixed BEFORE candidate
extraction. `pdfinfo` checks the original612x792 unrotated source frame; independent
`pdftoppm` page rendering fixed displayed ROI[48,90,564,535] on source page7 and
[48,100,564,620] on source page10. These are evaluation envelopes, not partitioner inputs.
Source inspection used the published PDF/rendering, not candidate output or recognized text
similarity to choose a favorable table.

The reference is **publisher-authored row content**, independent of DocWeave, rather than
human-reviewed PDF cell/span/box annotations. SEC HTML uses presentational cells for currency,
parentheses and spacers; its column counts/spans must not be relabelled as PDF topology.
This one issuer/two-table development set is not a held-out sample, extraction accuracy
estimate, broad business acceptance target or a comparison with other libraries/services.

## Frozen protocol

- Process the complete original PDF once per native mode, then evaluate only the two fixed
  source pages. All defaults/limits remain except requested cell geometry and selected mode.
  No page copying, image rendering, OCR/model, source repair or reference crop enters extraction.
- Select the table with maximum source-parent ROI IoU>=0.5, stable first-index tie-break.
  Table text never chooses the association. All unmatched tables remain in the report count.
- Reference rows are direct td/th wholeText joined in original HTML row order. Map NBSP to
  display space, collapse/trim ASCII whitespace, drop wholly blank rows. Preserve numeric
  formatting, parentheses, currency, case, punctuation and other Unicode. The same declared
  content normalization applies to emitted table rows. This differs explicitly from the older
  ASCII-only CSV benchmark; the denominators are not combined.
- Compare exact text at the original nonblank row index. No row shifting, subsequence search,
  fuzzy alignment, fragment joining or fixes to currency/parenthesis spacing. Count missing
  and extra row positions. Full content equality requires equal row counts and all rows exact.
- Typed failures from any source page retain BOTH selected cases and all59 reference rows in
  that mode's denominator; no partial result is scored. Unexpected I/O/assertion failures fail
  the diagnostic run. The optional report never saves reference or recognized text.

## Actual Java21/25 results

All12 case/profile outcomes are SUCCESS, with identical outcomes/counts/geometry across JDKs.
Each native policy gives:

| Fixed page | Reference content rows | Structured Table output | Exact ordinal rows | Ordinary native output |
| --- | --- | --- | --- | --- |
| Operations, page7 |27 |None |0/27 |134 elements, nonblank text |
| Balance sheet, page10 |32 |None |0/32 |92 elements, nonblank text |

Zero structured-row matches does **not** imply zero extracted text or independently verify
that all printed text is correct. The report retains selected/document element counts and
nonblank-text/character observations. No best-mode selection, ignored misses or denominator
reduction is applied. OCR/model strategies are not measured in this native baseline.

## Source geometry and likely next investigation

The unchanged native geometry auditor records no vertical rules or selected-page images.
Operations has1473 glyphs/134 runs/70 segments across18 horizontal groups, with original
interval counts2 followed by seventeen groups of4. Balance has1395 glyphs/92 runs/22 segments
across12 groups, with1–2 intervals per group. There is no accepted closed ruled grid.
Proximity blocks have varying run counts and contain source section/wrapped rows; current
aligned strategies require complete uniform blocks. Existing horizontal/partial policies
require their explicit supporting rule/header/body contracts, not arbitrary financial
subtotal underlines. Broad colored backgrounds are not native closed-grid borders.

These are observed source metrics, not an exhaustive causal trace or permission to stretch
thresholds. Geometry reports contain no text. They audit selected source pages; full-document
success is established separately by the actual12 whole-document outcomes. Next compare the
existing model/OCR layout strategy on the SAME frozen pages, retaining this native control,
before choosing a source-supported financial-table algorithm. Current paint/section/wrapping
support remains an open native gap. Do not silently change defaults to fit these two examples.

## Reproduction and verification

Fetch the two original manifest URLs into a local cache and verify all sizes/hashes. The new
Java/JUnit validator has no network or Python requirement; default builds download no inputs.
Set both opt-in properties and a NEW report path:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Dtest=PdfCurrentBusinessRowsTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.current.business.cache=/path/to/cache \
  -Ddocweave.pdf.current.business.report=/path/to/new-current-business-report.json
```

Three test methods cover the optional original-source diagnostics, owned HTML/entity/blank/
span-content normalization, and strict ordinal comparison/missing output/geometry controls.
Normalization guards preserve Unicode spaces other than the explicitly mapped NBSP, numeric
formatting, case, currency spacing and parentheses. Diagnostic misses remain observations,
not a hardcoded expectation that future extraction must keep failing.

Actual21/25 full clean verify with the original-source opt-in passes927 tests each and strict
Javadocs. All36 tooling guards and pinned formatter pass. `java21.json`/`java25.json` save
text-free results; `identities.json` pins protocol/validator/report/log hashes. Geometry
reports use the unchanged `AuditNativeTableGeometry` tool. No raw source files are committed.
This is validation-only work; it adds no beta.6 functionality milestone or PDF-completion
claim. The beta.6 annotated-tag and GitHub prerelease-note workflow remains required for the
next functionality release. Overall85–90% is a subjective feature-planning estimate,
not readiness for current financial statements or recognition accuracy.

Follow-up: the [existing model/OCR comparison](../pdf-current-business-model/README.md)
uses these unchanged source identities and row references on deliberately rasterized selected
pages. It recovers both tables, with42/59 PAGE and44/59 CELL exact ordinal rows; it does not
change the original native results or establish complete table text.
