# Post-validation diagnoses (2026-10-22)

Three bounded investigations from the post-validation queue. None changes runtime code,
references or scores. Text-bearing review output and renders are kept privately; this folder is
numeric and content-free.

## 1. Cross-producer trailing-row transfer: natural cases exist, rule premise does not hold

The existing Apple and Amazon provider captures were reused without new inference.
`ProbeBelowProposals.java.txt` lists every source line just inside and below each captured
proposal, with whitespace-glyph positions and fonts. Membership was then checked against the
original renders before considering any candidate output.

**Natural incomplete proposals were found on both non-NVIDIA producers:**

- **Apple Q3 2026** (`dc0cf747…5aa5`, Word/Quartz), page 3 cash-flow table. The proposal ends at
  displayed y 659.5. Below it are a blank band, a section label and one value row with two
  values. Both rows continue the statement's full-width alternating gray row bands in the render.
- **Amazon Q4 2025** (`ad8d84cb…42915`, Workiva), page 7 income statement. The proposal ends at
  y 513.1, and the statement's final value row (four values) lies below it. It sits inside the
  statement's shaded band and above its closing double rule.

**The uniform rule cannot recover them.** `SOURCE_CELL_SPACER_ROWS` needs whitespace glyphs at
identical positions on every provider row. Neither producer paints them that way:

- **Apple:** spacer positions vary with the values. Blank rows sit at 419.8, 489.6 and 493.2;
  value rows at 422.4, 486.5–489.6 and 495.9.
- **Amazon:** value rows share spacer positions, such as 313.5, 366.8 and 430.5. Label-only
  rows in the same table paint none.

In both cases the every-row lattice is empty, so the rule correctly adds nothing; that matches
the earlier no-op report.

**Disposition: gate open.** No case recovered by the existing rule exists locally. The rule was
not broadened to fit these sources.

The candidate next signal is painted full-width row bands continuing below a proposal. These
same two documents motivated it, so they would be development data. Positive transfer would
then need a further independent original with a natural truncation. Independent human
confirmation of both memberships is listed in the private review form.

## 2. TATR absent and partial proposals: classified against fixed detector geometry

This reuses the frozen NVIDIA page-6 captures; no inference was run.

- **YOLOX** proposes two full-width tables, about x 117–1162 at 150 DPI: the balance-sheet
  continuation (y 120–577) and the cash-flow start (y 893–1670).
- **TATR detection** proposes one crop, x 639–1173 and y 947–1662.

That gives two classified misses:

- The balance-sheet continuation (HTML table 3, rows 20–32) is **absent at detection**.
- The cash-flow start (HTML table 4, rows 7–21) has a **partial proposal** without its label
  column, so no row can match exactly.

Both are detection-model recall misses. Structure, topology and boundary logic are not
involved. A trailing or leading boundary rule cannot recover them. A proposal design would need
independent table labels and its own precision and false-positive controls. Not attempted.

## 3. Degraded-scan printed rule: diagnostic failed its own expectations

`printed-rule-frozen.txt` (SHA-256 `d2b52732…317a`) was frozen before any pixel or OCR output
was inspected. It defines:

- **Ink:** pixels below a page-global Otsu threshold.
- **Rule:** a vertical run of at least 8 median word heights.
- **Acceptance:** at least 3 OCR lines must have words on both sides of the rule, and no
  multi-character word may cross it.

**Result** (`printed-rule-diagnostic.txt`, all eight source and model observations):

- The Otsu thresholds (128–132) separate the dark scanner borders from the paper, not ink from
  paper.
- The only long runs found are the page borders: 1778 x 2–259, 1780 x 1690–1997 and
  1835 x 1342–1672. Each is rejected for lack of support.
- 1879 yields no candidate.
- The faint 1778 column rule is never classified as ink.

**Disposition: parked before implementation.** The frozen expectation, detecting the 1778 rule
near x 964, failed, so no production option was written.

The next distinct hypothesis is page-area-local ink classification that excludes scanner
borders. It needs the reviewed extent of the ruled list (review form item 4) as an independent
positive control.
