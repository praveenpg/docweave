# P1 attempts freeze (written before measuring either)

Baseline: SMALLER_TYPE_BLOCKS_ROW_TIES (accepted). Residual page-1 reversed pairs: recent 6, next 1,
holdout 17 (18 inline), eLife 43. Earlier pass: recent/next are interleaved with a 2-line main
paragraph (whole-element lower bound reached).

## Attempt 1: explicit row-tie paragraph fragments (new opt-in representation)

New enum value PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS (column layouts only, like
ROW_TIES; other layouts = SMALLER_TYPE_BLOCKS). Behaviour = ROW_TIES plus: when a side block's first
line starts on the row of the LAST line of a multi-line main group (same tie predicate: that line's
top <= side top < its bottom, side wholly left of it) the group is emitted as two fragments: lines
1..n-1, then the side block, then line n alone. Split only if fragment1.text + " " + line_n.text
equals the unsplit paragraph text exactly (so no hyphen-wrap boundary is split). Both fragments
carry pdf_paragraph_fragment_group (per-page ordinal), pdf_paragraph_fragment_index (1/2),
pdf_paragraph_fragment_count (2) and pdf_reading_order_fragment_policy=row_tie_fragments_v1.
Every other element identical to ROW_TIES.
Acceptance: recent and next reversed pairs strictly reduced; zero correct->reversed and zero newly
unmatched on all four papers (column profiles, both inline choices); joining fragments (single space,
first box union) reproduces the ROW_TIES output exactly on every page of the 17 row sources in every
layout; default and existing policies byte-identical; owned tests first.

## Attempt 2: upstream XY-cut ordering of whole elements (evaluation simulation first)

Reproduce pinned Unstructured fast page ordering (installed 0.27.28 source, read only):
SORT_MODE_BASIC (stable sort by top, then left of points[0]), then recursive_xy_cut_swapped with
bbox shrink 0.9 (left/top kept, right/bottom shrunk, int truncation), projection split min_value 0,
min_gap 1, x-first. Applied to the ROW_TIES page elements (images included as upstream sorts all
page elements), whole elements only. numpy argsort is not stable; the simulation uses stable sorts
(disclosed). Acceptance (same as P1): zero correct->reversed, zero newly unmatched, fewer reversed
pairs on at least one paper, on all column profiles. If met, implement as an opt-in policy; else
reject. No parameter changes after results.
