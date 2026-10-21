# Published table-image/text/HTML evaluation

Primary source: [PubTabNet](https://github.com/ibm-aur-nlp/PubTabNet), pinned
commit8ffde9024bd331f5a61c3c549fdf30dd3c3e43a5. All20 published examples are evaluated,
containing1380 annotated anchor cells. They are all upstream **train** examples: independent of this
candidate's output, not proof of held-out Tesseract training data or a representative accuracy sample.
Images are article table crops supplied by the dataset, not original physical scans or whole pages.
No oracle crops, deskew, rule repair, re-layout, text correction, row shifting or case/numeric fixes.
Raw images, full annotations and previews stay fetch-only outside the repository. Annotation/data
attribution and separate PMC image-license terms are recorded in manifest.json. Authors: Xu Zhong,
Elaheh ShafieiBavani and Antonio Jimeno Yepes, *Image-based table recognition: data, model, and evaluation*
(ECCV2020). Source license: the pinned repository LICENSE.md; no blanket image redistribution claim.

PdfPubTabNetCorpusTest verifies all21 byte counts/SHA256/Git blob pins before parsing. Published HTML
structure and cell tokens are joined independently of candidate output. Span occupancy determines
anchor row/column/span positions; HTML formatting/entity decoding and whitespace reduction are explicit.
The original whole PNG's decoded pixels are embedded losslessly at dimensions targeting its original
pixels at150DPI. Rendering resolution is a separate, uniform profile choice for the entire corpus.
English Tesseract uses automatic PAGE segmentation and explicit SINGLE_BLOCK cell segmentation.

Reports retain reference counts, predicted table shape, positional anchor/span matches,
whitespace-normalized exact cell text matches and nonempty reference text-center containment inside
corresponding cell envelopes (one original-pixel tolerance). Containment is not cell-border IoU or a
proof of full glyph ownership. Wrong extra/absent anchors prevent complete shape/text matches.
The300DPI profile gates one full published merged topology for PMC4003957_018_00.png: one21x4 table,
69/69 anchor/span matches,69/69 labeled text centers in corresponding cells. Text remains imperfect
(52/69 exact normalized cell strings in the observed installed-engine run). Other examples remain
measured misses, not passing recognition goldens. No complete shape-plus-text table matched.

Whole-page text diagnostics include Unicode-code-point edit distance against published row-major
cell text (25M comparison cap), and exact case/punctuation-sensitive whitespace-token multiset
intersection/reference/observed counts. Edit distance conflates text and reading-order errors; token
counts ignore ordering and are not semantic, field-level or probability-calibration scores. No raw
source text or predicted transcriptions are retained in reports. TABLE_LIMIT/OCR_LIMIT remain typed
diagnostics; other failures fail evaluation. Fresh report files use CREATE_NEW.

Final-source Java25 resolution sweep, same20 inputs/model/policy, showed:

| Render DPI | Tables | Exact token intersection / reference / observed |
| --- | --- | --- |
|150|0|102 /2070 /462|
|300|1|696 /2070 /1358|
|600|1|662 /2070 /1748|

These are corpus-specific diagnostics, not a universal resolution recommendation or general OCR
accuracy.600DPI did not improve the strict matched-token count or exact cell text over300DPI.
No default DPI or recognition threshold was tuned/changed from these results. Final source reports
and Java21/25 verification are saved alongside this document;300DPI corpus measurements are identical
on both JDKs. Both full runs passed787 tests, zero failures/errors/skips and strict Javadocs, with
all seven installed OCR report families. Twelve reference guards and the pinned formatter passed.

Optional properties: docweave.pdf.pubtabnet.cache (all21 pinned files),
docweave.pdf.pubtabnet.report (fresh JSON), docweave.pdf.pubtabnet.dpi (uniform36–600; default150),
docweave.tesseract.executable and docweave.tesseract.tessdata. Ordinary CI checks only the opt-in
parameter guard and analytical span/text-metric controls; it neither fetches data nor executes OCR.
