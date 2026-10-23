# P2 attempts freeze (written before scoring either; baseline reproduced: YOLOX 71/110, TATR 36/110)

Observed (private probe): DocWeave emits each NVIDIA date header as ONE row whose cells hold two
physical lines (month-day line over year line); the reference keeps two rows. Upstream hi_res misses them.

## Attempt 1: physical-line header rows (new opt-in PdfNativeLayoutOptions policy)
For a provider layout row r (after leading/trailing inference): no anchor from an earlier row spans
into r; at least two anchors of r own glyphs; every glyph-owning anchor of r has exactly two upright
displayed physical lines (PdfGlyphLines); all first-line bottoms within 2 displayed units of each
other, all second-line bottoms within 2 units, and every first line ends above every second line.
Then r becomes two rows split at the midpoint between the lowest first-line bottom and the highest
second-line top; anchors of r split into two; later rows shift by one; spans crossing r grow by one;
declared header rows covering r grow by one. Text is never read. Re-validated by the existing
topology validator; failures keep the provider layout.
Acceptance: on both NVIDIA profiles, no reference row lost, gained rows are date-header rows,
unmatched observed rows not increased, strict ordinal reported unchanged-or-better; default output
unchanged; owned tests.

## Attempt 2: existing native aligned-text detector in native-first composition (evaluation)
Replace the replay's native-first detector RULED_AND_PARTIAL_TEXT with the existing
RULED_AND_ALIGNED_TEXT (same captures, same section-row + spacer policies) to recover rows where
TATR has no/partial proposals. Acceptance: TATR presence increases, YOLOX presence not lower,
unmatched observed rows not increased in any case/profile. No threshold changes.

## Results so far and one disclosed amendment (written after attempt-1 result, before 1b run)

- Attempt 1 as frozen: zero splits, all six case/profile records identical to baseline. Private probe:
  the date cells hold exactly two lines, but the empty label cell holds only the producer's whitespace
  spacer glyphs (the lattice used by SOURCE_CELL_SPACER_ROWS), which the frozen wording counts as a
  one-line glyph-owning cell.
- Attempt 2: rejected before measurement by an existing contract: native-first composition refuses
  aligned-text detection (no exact glyph ownership). No other detector substituted.
- Attempt 1b (amendment, post-hoc, disclosed): identical rule except whitespace-only glyphs are ignored
  for cell ownership and line counting (visible glyphs only), consistent with how the spacer-row rule
  treats spacer glyphs as structure rather than content. Same acceptance. If it fails, P2 stays parked.
