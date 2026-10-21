# Rule-preferred composition on all nine cTDaR inputs

This is a fixed-corpus regression check of the explicit rule-preferred layout strategy,
not a general scan-accuracy benchmark. The source selection and byte/Git-blob pins are
unchanged from [the original scan evaluation](../pdf-physical-scans/README.md).
All nine whole images are retained, including the oversized input's `OCR_LIMIT` result.
There is no cell-text transcription oracle; some source cell records are duplicates or
omit empty cells. Modern raster provenance does not establish physical-scan provenance.

Two profiles differ only in `docweave.pdf.scans.preferRuled=false/true`. Both explicitly
select `KEEP_HIGHER_SCORE`, the same pinned detector and structure graphs, English PAGE
word OCR, whole-image embedding/rendering at150DPI, and orientation with the default raw
minimum score15. No source crop, repair, relabeling, score tuning, model download, or
reference-dependent strategy selection occurs. The manifest records these settings
because the historical scan report schema does not record the two composition flags.

On actual Java21 and25, every per-input result entry is identical between profiles and
between runtimes:12 candidate tables, one `OCR_LIMIT` input, eight orientation abstentions,
and three exact dimension/anchor-set matches at the highest table-envelope overlap.
The control also reproduces the historical REJECT_TABLE result entries exactly. No
additional scan improvement or regression is demonstrated. This does not establish
recognition accuracy, general safety of rule precedence, or exact OCR text equality:
these reports contain structural diagnostics and ordinary OCR line counts, not text.

The positive scientific-table-crop result in [the earlier evaluation](../pdf-ruled-preference/README.md)
therefore remains scoped to that corpus. Keep the composition explicit and defaults
unchanged. Worn/open/skewed rules, handwriting, complex topology and broader scan
recognition remain open quality work rather than being resolved by this evaluation.

Reports contain counts, geometry comparisons, hashes, runtime and orientation diagnostics.
Original images/XML, transcriptions and model binaries are fetch-only and are not
redistributed here. `manifest.json` records exact source commit, executable/data/model
hashes, invocation and report hashes; `source-sha256.json` pins the evaluated Java sources.
The evaluation uses `PdfPhysicalScanCorpusTest` and the existing opt-in local resource
properties. Four sequential Maven invocations avoid overlapping reactor builds.
