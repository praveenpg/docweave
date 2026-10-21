# Second-publisher scientific table baseline

The first official eLife result from the frozen domain-restricted search was selected
before source fetch/extraction. The regular article page returned a JavaScript client
challenge; the publisher's ordinary public API supplied fixed version1 PDF/XML CDN
links. No challenge automation or authentication was used. API metadata dates this
version of record2026-02-20. Original publisher bytes, version, DOI, metadata hash,
SHA256/Git-blob hashes and source-frame selection are pinned before extraction.

The first ordinal table is Appendix1 table1, not a main-body table. Its complete
10x3/20-anchor logical grid is on original page12 of the29-page PDF, independently
rendered before extraction. All3 source-labelled headers and body rowspans remain.
Publisher JATS is logical content/span truth, not physical PDF border ground truth.
Raw PDF/XML/API/text/images/captures remain outside Git.

## Baseline

Both native-first/model and tagged/native/model configurations complete the full
original on actualJava21/25. This PDF has no usable source tags. Allfour runs emit
one associated10x3 table with22anchors:12/20 span keys and exact span/text matches,
8missing/10extra logical keys,3/3 exact header anchors. No denominator is reduced.
No threshold, model, source crop, reference text or extraction setting was selected
from candidate results. Existing ISO-date policy is uniform and unchanged.

The source-only native audit (identical on21/25) finds3111glyphs,67runs,25segments,
no images/vertical rules and no accepted native table in existing detectors. The
source table has matching three-part top/bottom horizontal groups, every atomic
row boundary painted in the first column, and missing internal rules over the
spanning body groups. Model column boundaries also differ from painted segment
endpoints. These observations motivate a separate source-rule strategy; the audit
itself repairs no table and establishes no general semantic merge inference.

## Reproduction and limits

Use this manifest/cases.tsv with the same CaptureNativeModelTables graph pins and
uniform options as pdf-tagged-resolved-text, toggling only tagged precedence. Score
with the explicit publisher.manifest override and existing cache/captures/report
properties. Keep fresh output paths. Four numeric-only reports and paired full-page
geometry audits are retained. All8 PdfScientificSpanTest methods pass in each profile
on bothJDKs. This validation-only change adds no beta or extraction behavior.

This is another publisher and an additional prospectively selected development case,
not a representative/held-out quality claim. It keeps the negative body-span outcome
instead of replacing it with an easier paper or scoring a cropped derivative.
