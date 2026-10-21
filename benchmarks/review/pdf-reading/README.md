# Native PDF reading-layout review

This review combines owned policy tests with two independently maintained Apache PDFBox regression
inputs and saved expected-text files. It is not a general PDF reading-order score, a new Unstructured
execution, or human approval of extraction. No third-party PDF, expected text, full extraction or
rendered preview is committed. Fetch-only identities/URLs are in manifest.json; source-identity.json
separately records the official release commit response. PDFBox project licensing is linked as a
source notice, without asserting original authorship or a per-document redistribution license.

## Independent inputs and observations

Pinned PDFBox3.0.8 commit9286e47d89d6877005c9d2d0f2fd38793a62519a provides
PDFBOX-3110-poems-beads.pdf and its crop-box variant, plus stream/sorted expected text files.
Downloaded bytes match catalog Git blob SHA1, SHA256 and size. The source mixes paired poem
stanzas and full-width prose; article beads encode an order not guaranteed by geometric columns.
A local rendered preview was inspected during development, without a human-reviewed output oracle.

The fresh Java report records source/runtime-class/JAR identities, input manifest hash and diagnostics:

| Mode | Elements per PDF | Elements with accepted column evidence |
| --- | ---: | ---: |
| LINES | 84 | 0 |
| PARAGRAPHS | 45 | 0 |
| COLUMN_PARAGRAPHS | 40 | 10 |

The two PDF variants have identical normalized output in each mode. Paragraph-only mode preserves
the default line mode's non-whitespace order exactly; column mode can reorder it. All modes preserve
its non-whitespace/BOM-filtered code-point multiset. This is an inventory check, not word ownership,
correct reading order or coverage of content omitted by the baseline.

Neither saved expected text order matches any mode, even with diagnostic NFKC normalization.
Baseline glyph extraction retains four U+FB01 ligatures where saved PDFBox text expands `fi`;
this pre-existing content representation is unchanged. With NFKC normalization, character inventories
match both independent expectations; order still differs. Production never applies NFKC. Differences
are retained in review.json; no expected text/golden was rewritten or fabricated.

## Reproduction

Fetch the four immutable manifest URLs to a cache using their exact filenames, verify their sizes,
SHA256 and Git object identities, then run (choose a fresh report destination):

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Dtest=PdfReadingCorpusTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.reading.cache=/tmp/docweave-pdf-reading-corpus \
  -Ddocweave.pdf.reading.report=/tmp/fresh-pdf-reading.json
```

Both properties are required together. The test revalidates all cached byte pins before extraction,
uses immutable byte snapshots and exclusively creates the report before asserting native inventory
and paragraph-order preservation. Independent saved order differences remain diagnostics. Default
CI does not fetch or replay this external cache. Temporary caches, previews and logs are not persistent;
the manifest makes exact refetch possible.

## Scope of owned controls

Owned public-facade tests cover opt-in paragraph grouping, retained hyphens and union bounds;
indent/list/font/gap boundaries; column-major order; unequal line lengths sharing a real gutter;
spanning text bands; unchanged table/image contents/metadata; weak or overlapping-band fallback;
page isolation; sixteen virtual-thread shared calls; old constructors and immutable option copies.
A separate internal stress control exercises the LAYOUT_LIMIT work guard and cancellation/reuse,
without presenting repeated geometry as an independent PDF sample.

This policy handles a fixed left-to-right two-column gutter on an unrotated page. It does not honor
logical article-bead/tag order, infer semantic headings, dehyphenate, support RTL/vertical/three-column
layouts, or guarantee the reading order of mixed layouts and overlapping content. See docs/pdf-support.md.
