# Explicit native table-cell word wraps

The opt-in SOURCE_WORD_WRAPS_AND_ISO_DATES policy retains physical compound
hyphens while omitting a supported generated inter-line separator. Source-tagged
cells compose the existing unambiguous source U+00AD hints, with discretionary
removal taking precedence. Valid wrapped ISO dates retain their existing joining.
This assumes compound continuation from bounded geometry; it cannot prove semantics.
Default and earlier policies remain unchanged. No model/OCR provider is added.

Three JVM test methods cover provider/tagged routes with and without cell coordinates,
physical compounds and ISO dates, source discretionary precedence, exact outside-text
and geometry preservation, explicit boundary whitespace, numeric/punctuation fallback,
rotated source tables and original prejoin cell character limits. The existing shared
chunk test additionally verifies whole-table counter retention, removal from both
structural/text-only fragments and exact original-element provenance round trips.
Java21 full reactor verify passes1,172 tests with zero failures, errors or skips.

## Whole-original regression

CompareScientificCellWordWraps.java runs the complete pinned PLOS0324609,
PLOS0330904 and eLife90049 originals under both supported-cell-axis and logical-cell
geometry. Every pair compares the existing SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE
policy with the new policy, using the same binary/source-first extraction and closed-rule
fallback. All six pairs succeed: four table observations, zero changed tables, zero new
physical joins. eLife yields zero accepted source tables and remains in the denominator.
Ordinary elements and other table metadata are exact. HTML cell roles/attributes and
ordered characters excluding only ASCII spaces are exact. This invariant is not
source-space ground truth; authored source-space tests supply separate constraints.
No accuracy gain, reference row rescore, held-out acceptance or NVIDIA recovery is claimed.

## Reproduction

Obtain pinned originals from the manifests in ../pdf-recent-scientific-headers,
../pdf-scientific-next-source and ../pdf-scientific-second-publisher. Raw PDFs are
fetch-only. Create a local TSV in that order: safe case ID, absolute PDF path, byte
length and SHA256. Source hashes are retained in every java21.json result; local
TSV paths are not distributed. Compile benchmarks/tools/CompareScientificCellWordWraps.java
with --release21 and the existing DocWeave/JSON/jsoup runtime classpath, then run:

```sh
java -Djava.awt.headless=true -cp "$audit_classes:$runtime_classpath" \
  dev.docweave.validation.CompareScientificCellWordWraps \
  /path/to/pinned-sources.tsv /path/to/new-report.json
```

The tool refuses an existing report and validates complete source bytes. Typed
extraction failures and representation discrepancies remain in its complete report.
The TSV hash changes if local paths change. No reference repairs or source-specific
policy selection enter this comparison. Java25 is deferred to PDF completion.
