# Original scientific paragraph-text and order baseline

This JVM-only diagnostic broadens the existing three scientific PDF/table sources to body
text. Original publisher PDFs and XML remain unchanged and hash-pinned; no third-party text
or binary is redistributed. These already selected development papers are not held-out data.

Before extraction, `FreezeScientificReading` verified earlier PDF/XML identities and selected
the first abstract's paragraphs plus up to eight direct paragraphs of the first top-level
body section. The same ordinal rule selects9/12/4 complete paragraphs from the original
PLOS/PLOS/eLife pairs. Nested body sections, later abstracts/subarticles and figures/tables
are not substituted for misses. Each full paragraph's hash/code-point count and order are frozen.

## Results and interpretation

All three layouts retain the same strict results on both actual JDKs:

| Original source | Selected paragraphs | Exact unique text spans | Missing full spans | Preserved/all order pairs |
| --- | --- | --- | --- | --- |
| PLOS0324609 |9 |1 |8 |0/36 |
| PLOS0330904 |12 |2 |10 |1/66 |
| eLife90049v1 |4 |0 |4 |0/6 |

Profiles are native `LINES`, `PARAGRAPHS` and `COLUMN_PARAGRAPHS`, with existing RAW Unicode
and default table/image policies. Each runs the whole original document. No paragraph
reference, source ROI or XML enters extraction. All nine case/profile results agree across
Java21/25, including full normalized candidate-text hashes and character counts.

An exact paragraph-text span fails after even one character difference or inserted running
header/sidebar. Missing full spans **do not mean all paragraph text was lost** and cannot prove
reversed order. None of the uniquely matched nonoverlapping spans has a reversed pair.
Missing/duplicated spans retain unresolved pairs in the full denominator. No matched-only
accuracy, fuzzy matching, case/hyphen/punctuation repair, Unicode folding, reference shifting
or best-occurrence selection is used. Overlapping spans cannot satisfy an order pair.
Exact span presence is not visual paragraph segmentation or general semantic accuracy.

A bounded private prefix audit finds physical word-wrap hyphens, interleaved source material
and Unicode spacing differences among first mismatches. Those associations are diagnostic
only; they never alter scores or select replacement references. More permissive normalization
would hide these output differences.

## Verification and reproduction

Full `clean verify` passes1012 tests on actual Java21.0.12.1+12-LTS and25.0.4+7-LTS, zero
failures/errors/skips, strict public Javadocs and nine original-document extractions per JDK.
Owned controls retain ordinal selection, structured abstracts/inline text, direct-body limits,
missing/duplicated/reversed/overlapping spans, complete denominators, ASCII-only normalization
and whole-document failure counts. Existing eight scientific span/parser tests stay green
after sharing their external-DTD/entity rejection policy in a Java helper.
Strict private Javadocs pass for the new helpers, harness and source tools on both JDKs;
the pinned formatter and diff checks pass.

The initial fullJava21 run exposed a property collision with the older reading corpus test.
The new properties were isolated; its valid nine-case report and failure log remain outside
Git. `final-helper.json` records the namespace-only change after freezing. `VerifyReports.java`
confirms all initial/final case outputs remain equal and checks complete paired source/profile
sets. No source/reference/scoring change was needed. Use a fresh output path:

```sh
mvn -B -ntp clean verify \
  -Ddocweave.pdf.scientific.reading.cache=/path/to/verified-source-cache \
  -Ddocweave.pdf.scientific.reading.report=/fresh/path/reading.json
```

The cache contains recent.pdf/xml, next.pdf/xml and elife.pdf/xml with the exact manifest pins.
Standard Maven verification needs no cache and performs no download/OCR. To compare retained
reports with Jackson on the validation test classpath:

```sh
java -cp "$VALIDATION_TEST_CLASSPATH" \
  benchmarks/review/pdf-scientific-reading/VerifyReports.java \
  benchmarks/review/pdf-scientific-reading/manifest.json \
  benchmarks/review/pdf-scientific-reading/java21.json \
  benchmarks/review/pdf-scientific-reading/java25.json - /fresh/path/verification.json
```

The freezer compiles with validation test classes/Jackson and reads publisher XML only.
Original frozen helper hashes remain historical; the final namespace-only harness identity
is separately recorded.

## Source-supported follow-up

An off-Git bounded source-tree inventory finds177/204 P elements outside source tables in
the PLOS originals, including169/195 with single-page references and6/7 crossing pages.
There are57/42 source soft-hyphen hints across36/29 paragraphs. These are structure counts,
**not** validated native glyph ownership or accepted paragraphs. The eLife original has no
paragraph structure tree and cannot use this evidence.

Next evaluate explicitly enabled source-paragraph grouping and source-only discretionary
joins, with whole-candidate fallback for unsupported ownership. Preserve defaults and these
complete selections, including untagged/cross-page/ambiguous controls. Source tags can be wrong;
they do not establish general page order, script correctness, calibrated confidence or completion.
