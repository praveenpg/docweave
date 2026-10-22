# Degraded-scan line-geometry diagnosis

Development pages `buerger_gedichte_1778_0039` (38 lines, two-column subscriber list) and
`nn_besuch_1780_00001` (10 lines), both pinned models, source-resolution AUTO/ENGINE_DEFAULT
(the handoff-derived four-observation subset). Baseline and candidate reports are retained in
[the wide-gap evidence](../pdf-ocr-wide-gap-lines/README.md) (`degraded-*-java21.json`). Raw OCR
text stays outside Git; this file records counts and geometry classes only.

## Causal hypothesis

Earlier pixel-filter attempts (sharpen, contrast, whitepoint, median) changed glyph recognition
with mixed results. Temporary, reverted instrumentation of the existing line association shows
that most 1778 misses are line-geometry failures: a correctly or nearly correctly recognized line
whose box spans both columns cannot reach IoU 0.5 with either column's reference line.

The explicit wide-gap line-splitting candidate frozen for the historical area (no new parameters)
transfers here: German associated 17→26, missing 21→12; Fraktur associated 18→27, missing 20→11;
recognized text and XML-sequence edits unchanged (419 / 371). Unreferenced predictions rise
11→17 and 10→12.

## Residual classes on 1778 with the candidate (German; Fraktur similar)

| Class | Reference lines | Cause |
| --- | --- | --- |
| Still merged across the gutter | 6 | inter-column gap below three line heights on three rows |
| Over-split inside one row | 4 | wide intra-row spacing in subscriber entries exceeds three line heights |
| Low IoU after a correct split | 2 | reference box wider than the recognized word extent |

Unreferenced predictions: the over-split fragments, plus three speck/punctuation predictions.

## 1780

The single miss is the large decorative title, recognized but with a box too small for IoU 0.5.
The nine unreferenced predictions are ruler/stain marks and a vertical library-ownership stamp:
real or nontext material that the transcription does not label, not invented text. Unchanged by
the candidate.

## Disposition

No separate degraded-specific runtime candidate. The per-line threshold under-splits narrow
gutters and over-splits wide intra-row spacing on the same page; tuning it would be a threshold
search and is not attempted. Next distinct hypothesis: page-level gutter evidence (a vertical
whitespace band consistent across many lines) instead of per-line gaps, with owned single-column,
tab-stop and two-column controls, applied uniformly to both pages and models.

## Page-level candidate follow-up

The [fixed page-gutter candidate](../pdf-page-gutter-attempt/README.md) gained line
associations on1778 but regressed strongly on1879 and worsened1778 Fraktur joint
character alignment. The complete eight-observation result is retained and the candidate
was reverted. Repeated gaps alone are insufficient; no threshold retuning followed.
