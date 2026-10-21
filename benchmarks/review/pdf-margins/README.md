# Explicit running-margin policy on an independent native PDF

The source is the original RFC9110 PDF from RFC Editor, SHA256/bytes and publisher URL
in manifest.json. Only the first12 complete original pages are selected, in order, with
original page resources retained. No page omission, crop, text repair or source content
editing is used. Raw PDF/page images/body transcription are not redistributed.
The publisher's displayed repeated header/footer labels and physical page ranges form
fixed source oracles. This is one technical publisher, not broad semantic margin accuracy,
human-review coverage of diverse layouts or calibrated confidence.

The native source inventory has439 elements. Historical eight-percent bands and exact
body-occurrence veto produce0/57 source-labelled margin tags. A separate explicit footer
band of16percent produces12/57 tags; allowing matching body text only on physical page1
produces33headers and24footers with no extra tags. Labels also appear as first-page body
text, and footer lines fall outside the historical eight-percent band. All negative
profiles remain in the report rather than being redefined as successes.

`PdfMarginOptions` exposes separate header/footer band fractions (finite, greater than0,
at most0.2) and a first-page-body flag. The default remains0.08/0.08/false. This is caller
configuration, not automatic margin detection or semantic cover inference. Later-page
body matches still veto groups. All existing absolute support/physical-page coverage,
position consistency, short-height/text limits, ambiguity/rotation exclusions and resource
bounds remain unchanged. Configured tags record the policy/fractions/flag; default tag
metadata remains compatible. Source IDs/text/order/coordinates are preserved and tagging
is idempotent. Wider bands/first-page allowances may introduce false positives elsewhere.

Reproduce `PdfIndependentMarginsTest` with explicit
`-Ddocweave.pdf.margins.pdf=/local/rfc9110.pdf` and a fresh
`-Ddocweave.pdf.margins.report=/tmp/fresh-report.json`. It checks exact original hashes,
first12 page selection, all fixed source labels, retained default misses, no unexpected
tags, full inventory/identity/coordinate preservation and idempotence. No download or
external recognition occurs in this test. OCR/ONNX dependencies are not used by this path.

The official upstream refresh remains0.27.28/main1b02b4d189c260ed9b1aca29583203e233d853cb,
six commits beyond the historical0.27.27 reference pin. The opt-in freshness assertion fails
as expected; upstream-status.json retains that observation. Historical reference goldens
are not rewritten and configured margin policy is not claimed as Unstructured parity.


Full actual Java21 and25 verification passes864 tests each, strict Javadocs, ten
installed-engine families and prior official word/OSD controls. Independent margin
profile entries are identical across runtimes; all439 source element identities/text/
coordinates/order are preserved. See summary.json and paired independent reports.
Configured policy remains explicit; this does not make a general PDF-completion claim.
