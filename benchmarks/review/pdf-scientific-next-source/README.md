# Additional preselected scientific source

The first official PLOS One result from the frozen search was selected before source
fetch or DocWeave extraction; its first ordinal main table was retained. The XML
confirms publication2025-08-22 (the provisional date in selection.json was unverified).
The original14-page PDF was independently rendered before extraction. Table1 is one
complete5x3 table on page7, with15 logical anchors and3 publisher-labelled headers.
The table lives inside JATS alternatives; no reference cells were dropped. SHA256,
byte counts and Git-blob identities pin the original sources. Git-blob verification
fields were added after the first diagnostic rejected their omission; source bytes,
labels, page/ROI and extraction configuration did not change.

## Paired results

| Uniform configuration | Logical span keys | Exact span/text | Header span/text |
| --- | --- | --- | --- |
| Native-first/model fallback |15/15|13/15|3/3|
| Tagged/native/model |15/15|13/15|3/3|

ActualJava21/25 agree in all four full-original runs. Both produce exactly15anchors
with no missing/extra logical keys. The tagged profile uses source structure; the
native-first profile uses model geometry. No model threshold, native option, date
policy, reference label or source crop changed per case. The two exact-text misses
are hard-hyphen word wrapping; no dictionary or fuzzy comparison repairs them.
Publisher JATS describes logical content/spans, not physical PDF rule borders.

This adds a prospectively selected source from the same publisher. It is development
breadth evidence, not publisher diversity, untouched held-out acceptance, general
accuracy or completion of modern scientific PDF work. It changes no extraction
behavior and does not warrant a release by itself.

## Reproduction

Fetch the manifest's two original URLs to an explicit cache; raw PDF/XML/rendered
images and captures remain outside Git. Run CaptureNativeModelTables with this
cases.tsv and the same pinned graphs/options as pdf-tagged-resolved-text, toggling
only docweave.pdf.native.tagged.first for the two profiles. Use fresh output paths.
Score with PdfScientificSpanTest and the explicit properties:

- docweave.pdf.publisher.manifest=benchmarks/review/pdf-scientific-next-source/manifest.json
- docweave.pdf.publisher.cache=YOUR_CACHE
- docweave.pdf.publisher.captures=YOUR_CAPTURE_DIRECTORY
- docweave.pdf.publisher.report=NEW_REPORT
- docweave.pdf.scientific.tagged.first=true for the tagged profile only

The old publisher manifest remains the diagnostic default. All original15/3
reference denominators remain on missing/failed extraction. Four numeric-only
reports are retained here; raw captures and source text remain off-Git. Eight
PdfScientificSpanTest methods pass in each of the four explicit runs on actual21/25;
ordinary full979-test builds on bothJDKs passed in the preceding extraction commit.
No new production code, dependency, model/source pin migration, release or publication.
