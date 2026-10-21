# Native presentation-form policy and independent Unicode expectations

The15 exact pinned assets in manifest.json are five original PDFBox3.0.8 regression PDFs
and both maintained text-expectation variants. Selection covers named ligature, empty
ToUnicode, bidirectional-ligature and supplementary-character/diacritic regressions without
outcome-based exclusions. Raw PDFs and full reference/observed text remain fetch-only.
Saved expectations agree for this subset; they are external maintained goldens, not new
author/application provenance, human review or general font/script/layout accuracy.

Expectation files have a leading UTF-8 BOM recorded in the manifest. Only that reference
transport marker is removed before whitespace comparison; extracted PDF text is never
BOM-stripped or otherwise repaired. Numeric scalar inventory differences are reported
without redistributing source text. Font inspection remains diagnostic; mapping counts
are not semantic accuracy labels.

`PdfOptions.UnicodeNormalization.RAW` remains the default and matches three saved text
expectations exactly. Explicit PRESENTATION_FORMS expands only Alphabetic/Arabic BMP
presentation scalars U+FB00..FDFF and U+FE70..FEFF using per-scalar JDK NFKC. It matches
all five saved expectations, retaining original source element geometry/types. Mathematical
supplementary letters, micro signs, fullwidth/circled characters and combining sequences
outside those blocks remain unchanged; blanket NFKC would alter one mathematical fixture.
No contextual Arabic font adjustment, script reversal, bidi reconstruction, trimming,
font-map repair or OCR normalization is performed. This is representation policy, not a
claim that preserving decoded presentation forms in RAW mode is incorrect.

The rationale for limiting compatibility folds is also visible in the pinned
[PDFBox normalizeWord implementation](https://github.com/apache/pdfbox/blob/9286e47d89d6877005c9d2d0f2fd38793a62519a/pdfbox/src/main/java/org/apache/pdfbox/text/PDFTextStripper.java).
This independent per-glyph policy does not adopt its contextual/script-direction rules.

Selected-policy output is capped at16million retained UTF-16 units per page, checked before
builder appends and charged across glyphs; INPUT_TOO_LARGE reports the limit. Cancellation
is checked every1024input units. Unchanged strings retain their source instances; changed
glyph strings retain their original geometry. Source font-map decoding can allocate before
callbacks, so this is not a PDFBox heap/decompression sandbox. Expanded native cell text
still faces existing table-character limits. Native parent metadata records the policy;
IDs derive from the emitted text, while images/OCR output remain unchanged.

Run PdfUnicodeCorpusTest with explicit `docweave.pdf.unicode.cache` and a fresh
`docweave.pdf.unicode.report`. It verifies SHA256/bytes/gitblob for all15assets, retains RAW
and explicit-policy outcomes, checks geometry/type preservation and writes scalar counts
rather than source text. No downloads or regenerated reference-oracle outputs are used in this test.


Full actual Java21 and25 verification passes869 tests each with strict Javadocs, ten
installed-engine families, both Unicode profiles and the prior independent margin controls.
Paired Unicode report entries are identical. All five saved-text correspondences pass under
the explicit policy with original source geometry/types; RAW retains its three matches and
its decoded presentation-form representation. No default/golden is silently rewritten.
