# Recent scientific publisher header baseline

One2025 original article broadens the earlier historical scientific evidence:
Wu et al., PLOS One, published May20,2025,
[DOI10.1371/journal.pone.0324609](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0324609).
Selection.json records the primary-source search and first-main-table selection
before candidate extraction. Manifest.json freezes original publisher PDF/JATS
bytes, complete sourcepage7 ROI and reference counts. All15 original PDF pages
are extracted uniformly; only then is the fixed report page/table associated by
maximum ROI IoU>=0.5, stable first tie. No annotation/ROI/text is fed to inference.

The original page was visually inspected before extraction: one complete table,
with wrapped header labels, body merges and source-line wraps. The publisher's
JATS thead independently declares one header row/nine cells. It also declares a
16x9 logical table with136 anchors. Logical publishing spans are not automatically
physical PDF border truth: the rendered PDF has internal separators that need not
match every JATS rowspan. The table is retained regardless of extraction success.
No corpus strings, PDFs, XML, screenshots, crops or raw captures are redistributed.

## Unchanged default baseline

ActualJava21/25 agree on all numeric outcomes (runtime/hash fields differ): full
original SUCCESS, one selected-page model table, source associationIoU0.99063742,
observed16x9/137anchors.132/136 published logical span keys match;116/136 match both
logical span and exact normalized text. Four reference keys are missing, five
predictions are extra, and neither full topology nor full span/text is exact.

All nine independently marked publisher header cells match exact positional
spans/content. The model declares one header row in this case. This single positive
is not general header-role accuracy or broad scientific acceptance.

Sixteen matching-key text discrepancies remain in the default baseline:15 wrapped
ISO dates with an inserted line-joining space, and one native hyphenated word wrap.
The separate explicit date policy improves the former15; it does not alter the
publisher reference, remove the latter mismatch or change physical/logical spans.
See [the policy evidence](../pdf-native-iso-dates/README.md).

## Reproduction and annotation policy

Fetch only the original URLs pinned by manifest.json, verify byte identities, and
run CaptureNativeModelTables with cases.tsv and the existing pinned detector and
structure graphs. Keep the default cell policy for this baseline. Score using:

```
-Ddocweave.pdf.publisher.cache=/explicit/original-source-cache
-Ddocweave.pdf.publisher.captures=/explicit/captures
-Ddocweave.pdf.publisher.report=/new/numeric-report.json
```

The optional PdfScientificSpanTest reads the original JATS without fetching DTDs,
external entities or schemas; inert external DOCTYPE declarations are permitted,
internal declarations rejected. Owned tests verify this distinction, inline text,
explicit break-to-space conversion and body rowspan ownership. All other Unicode,
punctuation, hyphens, digits/case and whitespace outside ASCII normalization remain
unchanged. Expected and actual strings are compared at original logical indices;
there is no fuzzy matching, row repair or case-dependent strategy selection.

This adds recent scientific breadth and independent header truth; it does not close
complex native layout, historical/degraded OCR, held-out calibration or final PDF
comparison gates. Existing default behavior and historical reports remain intact.
