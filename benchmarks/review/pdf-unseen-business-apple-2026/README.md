# New business issuer: shaded native statement rows

The original three-page [Apple July2026 statement PDF](https://www.apple.com/newsroom/pdfs/fy2026q3/FY26_Q3_Consolidated_Financial_Statements.pdf)
was linked from the [issuer release](https://www.apple.com/newsroom/2026/07/apple-reports-third-quarter-results/).
The independent content reference is the issuer-hosted SEC filing HTML pinned in the manifest.
Source bytes, hashes, original pages, displayed ROIs and HTML table indices14/15/16 were frozen
before first DocWeave extraction. Only numeric metrics and hashes are redistributed here.

The first run used six unchanged native policies uniformly on the complete original PDF.
Every policy emitted ordinary text but no Tables on all three statement pages:0/40 operations,
0/37 balance and0/37 cash-flow ordered content rows. These new cases became development data
during the subsequent implementation; this is not a claim of broad held-out acceptance.

The unchanged scoring protocol compares exact nonblank row positions against publisher HTML,
maps NBSP to display spaces, collapses ASCII whitespace and preserves other Unicode, case,
punctuation and numeric formatting. It does not shift rows or accept fuzzy text. Fixed source
page/ROI association uses highest parent IoU at least0.5 and stable first-index ties, never
reference text. HTML presentation spans are not PDF physical cell/span labels. Every source
page/reference denominator remains even if extraction fails elsewhere in the complete document.

The separately frozen `manifest-shaded.json` retains those sources/labels and adds the existing
segmented-row policy plus the explicit new shaded-row policy, uniformly across all three pages.

| Statement | Reference rows | All seven earlier native policies | Explicit shaded rows |
| --- | ---: | ---: | ---: |
| Operations |40|0/40|40/40|
| Balance sheet |37|0/37|0/37|
| Cash flows |37|0/37|37/37|

The two recognized tables have no missing/extra content rows. Parent IoUs are about0.976 and
0.964; individual cell-box accuracy is not measured. The balance sheet retains ordinary text:
native projection includes an internally spaced numeric string that fails the new policy's
complete-numeric-row guard. It is not silently repaired or dropped from the114-row denominator.

The strategy uses source pale paint and three separately spaced matching numeric subtotal
rules. Adjacent same-color cell rectangles may define an inferred row envelope; duplicate/
contained paint does not add rows. Label-column paint delimits section rows. Nonblank glyph
extents define header spans, without padding-driven artificial merges. Unsupported visible
crossings, images, vertical barriers, competing patterns or numeric syntax reject candidates
without partial ownership. Defaults and earlier native policies remain unchanged. All18 first
case/profile rows are identical;24 final rows agree across actual Java21 and25.

Eleven owned JVM tests cover actual public-API untagged PDFs, complete ownership/outside text,
section rows, multiline labels, accounting syntax, multilevel header spans, per-cell/contained
paint, padding ownership, insufficient/double-underline support, color/gap/overlap/raster/vertical
barriers, table/cell/work bounds and interruption. Full clean verification passes1,080 tests on
each JDK, including both this source experiment and all52 earlier scientific rows unchanged.
After hoisting one repeated paint-bound reduction outside the rule loop, targeted controls and
the original-source experiment are checked again on both JDKs. Strict public/private Javadocs
and the pinned formatter remain required. No OCR/model/Python test dependency was added.

Reproduce with verified originals in a local cache and a fresh output path:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Dtest=PdfCurrentBusinessRowsTest,StandaloneSourceCompatibilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.current.business.cache=/path/to/verified/cache \
  -Ddocweave.pdf.current.business.manifest=benchmarks/review/pdf-unseen-business-apple-2026/manifest-shaded.json \
  -Ddocweave.pdf.current.business.report=/path/to/new-report.json
```

The original first-run manifest remains unchanged. `VerifyBusinessRows.java` takes the
manifest, paired reports21/25, prior report (or `-` for the first run) and a fresh receipt path.
It binds the baseline bytes, requires every case/policy and denominator, checks exact-count
accounting and rejects changed prior observations. No beta candidate or publication occurred.
