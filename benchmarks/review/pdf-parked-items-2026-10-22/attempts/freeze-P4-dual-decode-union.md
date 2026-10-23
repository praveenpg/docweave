# P4 attempt freeze: dual-decode union (written before any OCR run of it)

Mechanism (distinct from the rejected stored-sample switch and 2x magnification): keep the
colour-managed decode as the primary recognition, unchanged. Only for profile-tagged, non-sRGB,
three-band sources (the existing stored-sample gate), ALSO recognize the stored samples, and add each
stored-sample line whose box overlaps no primary line box (zero-area intersection). The added lines
go into the line list at their top position (stable: before the first primary line with a greater
top). Primary lines and text are never changed. Untagged/gray/sRGB sources are byte-for-byte
unchanged (no second pass).

Harness only: opt-in property docweave.pdf.historical.dual.decode.union=true in
PdfHistoricalTextCorpusTest. No runtime/API/default change (a PDF-level change still requires Q2).

Run once: the frozen historical manifest (1835, 1879) x (deu, deu_latf), AUTO/ENGINE_DEFAULT,
source resolution, OMP_THREAD_LIMIT=1, pinned executable/tessdata; plus a baseline rerun.

Acceptance (all required):
- 1835 associated lines 21 -> 22/22 for both models (the page number recovered);
- 1835 global character edits not above baseline (deu 104, deu_latf 51) for either model;
- predicted_lines_without_reference not above baseline;
- 1879 results identical to baseline (gate excludes it);
- reviewed-order metrics, where present, not worse.
Otherwise rejected. No parameter changes after results.

## Attempt 2 freeze: sparse-segmentation union (written before any OCR run of it)

Checked first (profile only, no OCR): the 1835 scanner profile maps stored black to sRGB black
(0->0) and lightens midtones (0.25->0.35), so black-point compensation is a no-op; not attempted.

Mechanism: primary = managed decode with the frozen AUTO segmentation (unchanged). Secondary =
the SAME managed pixels recognized with SPARSE_TEXT segmentation. Add each secondary line that
overlaps no primary line box, at its top position (same insertion rule as attempt 1). Applies to
every source (no colour gate), so 1879 may change. Harness property
docweave.pdf.historical.sparse.union=true; mutually exclusive with attempt 1.

Acceptance: identical to attempt 1, except 1879 need not be byte-identical but must not worsen
associated lines, character edits, word edits or extra predictions for either model.
