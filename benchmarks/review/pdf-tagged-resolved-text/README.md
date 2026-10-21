# Exact source-tag ownership after content-stream replacements

The preceding tagged-table baseline recognized the publisher's16x9/136-anchor grid
but rejected40 content references. A source-only numeric audit confirms these page
MCIDs are unique and declared. PDFBox changes their Unicode under nested content-stream
ActualText scopes, so comparing retained text to the original pre-processing value
incorrectly classifies them as ambiguous glyph mutations. No missing text is inferred.

The pinned [PDFBox3.0.8 implementation](https://github.com/apache/pdfbox/blob/3.0.8/pdfbox/src/main/java/org/apache/pdfbox/text/PDFTextStripper.java)
applies content-stream ActualText in its native processTextPosition callback: the
first position receives replacement text with soft hyphens removed; subsequent
positions receive empty text. Nested begin/end handling also affects replacement
state. The tracker mirrors these existing callbacks to validate exactly the expected
retained text. It never changes PDFBox output, uses structure-element alternate text,
or accepts an arbitrary subsequent Unicode mutation. Diacritic merges across source
cell identities still reject the whole candidate. Form ownership remains unsupported.

## Results

| Same original publisher table | Prior tagged/native/model fallback | Resolved ownership |
| --- | --- | --- |
| Matching logical span keys |132/136|136/136|
| Exact span/text anchors |131/136|135/136|
| Header span/text anchors |9/9|9/9|
| Observed anchors |137|136|
| Missing/extra logical keys |4/5|0/0|

Both actualJava21/25 full-original runs agree. Accepted tagged table retains four
source empty cells with explicitly labelled inferred geometry and15 opt-in date
joins. One hard-hyphen word-wrap mismatch remains; there is no arbitrary word repair.
The published JATS labels describe logical cells, not physical PDF border truth.
This remains development evidence from one table/publisher, not general accuracy.
The source/ROI/reference pins are unchanged from pdf-recent-scientific-headers.

All20 other fixed regions are capture-equal to the prior tagged baseline on both
JDKs (40 comparisons). All42source frames agree. Replay hashes and complete cases are
in replay-results.json. Raw source PDFs/XML/captures remain off-Git.

## Contract checks and reproducibility

Two new PDF tests cover inline UTF16/soft-hyphen and resource properties, first versus
subsequent positions, nested wrappers, default native policy consistency, cross-cell
diacritic rejection with/without replacement, actual replacement cell-character
limits and37versus36 original-glyph limits. Existing source-structure ActualText
refusal and tag/native/provider limits remain covered. No public API/record/dependency
or existing factory extraction policy changes.

Replay CaptureNativeModelTables with the same frozen21cases and graph hashes,
-Djava.awt.headless=true, -Ddocweave.pdf.native.tagged.first=true,
-Ddocweave.pdf.native.iso.dates=true and native-first. Run publisher scoring with
-Ddocweave.pdf.scientific.tagged.first=true and existing publisher cache/captures/report
properties. Keep generated output directories fresh. See the original manifests and
verification.json. The opt-in official upstream refresh retains known0.27.28 drift;
historical pins/goldens are unchanged. No release/tag/signing/Central action.
