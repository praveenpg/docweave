# Explicit side-stream blocks for first-page sidebars (2026-10-22)

## Problem and reference

On PLOS first pages, a metadata sidebar sits beside the abstract: x 36–184, line height about
4.7, pitch about 11. The abstract occupies x 198–578, height about 5.7, pitch about 15. The
existing column detector needs paired baselines and at least 24 units of whitespace, so it
rejects the page. All lines are then ordered top to bottom, and sidebar lines alternate with
abstract lines.

Pinned Unstructured 0.27.28 `fast` (existing captures, runtime `7b913aaa…`) does not read the
sidebar as a column either. It keeps each sidebar paragraph as one block placed by its top and
leaves the abstract as separate lines. The compatible correction is therefore asymmetric:
block the smaller-type side stream and leave the main stream as it is.

Earlier attempts differed:

- The [disjoint-gutter attempt](../pdf-disjoint-gutter/README.md) read a whole column first.
- The [continuation attempt](../pdf-sidebar-continuation-attempt/README.md) let both streams
  join across each other.

Both were judged only by whole-paragraph matches, which cannot express this order.

## Frozen target, metric and predictions

`frozen-target-and-prediction.txt` (SHA-256 `bf330e53…`) and the metric tool
`CompareUpstreamLineOrder.java` (`edc1cfd7…` when frozen) were written before any candidate
code. The private receipt log records both hashes and a clean product tree at that point.

- **Target.** Recent PLOS (`acb3af41…`), page 1, upstream element 11: the five-line citation
  block (collapsed-text SHA-256 `32ef0aa9…`).
- **Atoms.** DocWeave `LINES` lines, using the measured profile's inline-script policy, each
  mapped to the single upstream element containing it.
- **Expected order.** Upstream element index, then offset.
- **Unmatched penalty.** A pair is unmatched when either atom is not found exactly once in the
  output, so deleting or duplicating text cannot help.

After the freeze, the tool gained only a `SIDE_STREAMS` profile modifier; its metric code is
unchanged.

## Candidate

`PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS` (default `PRESERVE`) applies only on pages
without an accepted gutter, under paragraph layouts:

1. Cluster ordinary upright lines by left edge (4-unit tolerance). The largest cluster is the
   main stream.
2. Another cluster's lines lying wholly at least 4 units outside the main extent form a side
   stream when there are at least three, their median height is below 0.9 of the main
   stream's, and they span at least three main lines.
3. Side lines join among themselves with the existing paragraph rule. Each block is emitted at
   its first line.
4. Main-stream grouping and order are unchanged; a side line still ends adjacent main groups.

No text, wording, source name or upstream output is used at runtime.

## Results

### Line order (`line-order-*-java21.json`)

Reports run on the four papers' page 1. Counts are correct / reversed / unmatched pairs, before
→ after; CP is COLUMN_PARAGRAPHS, CW compound wraps, IS inline scripts:

| Source | Profile | Pairs | Before | After |
| --- | --- | ---: | --- | --- |
| recent | CP | 990 | 967 / 23 / 0 | 984 / 6 / 0 |
| recent | CP_CW | 990 | 925 / 21 / 44 | 940 / 6 / 44 |
| recent | CP+IS | 990 | 967 / 23 / 0 | 984 / 6 / 0 |
| recent | CP_CW+IS | 990 | 925 / 21 / 44 | 940 / 6 / 44 |
| next | CP | 703 | 681 / 22 / 0 | 700 / 3 / 0 |
| next | CP_CW | 703 | 647 / 19 / 37 | 663 / 3 / 37 |
| next | CP+IS | 703 | 681 / 22 / 0 | 700 / 3 / 0 |
| next | CP_CW+IS | 703 | 647 / 19 / 37 | 663 / 3 / 37 |
| eLife | all four | 1,485 / 1,653 | unchanged | unchanged (accepted column band; policy does not apply) |
| holdout (transfer) | CP, CP_CW | 1,081 | 1,046 / 35 / 0 | 1,062 / 19 / 0 |
| holdout (transfer) | CP+IS, CP_CW+IS | 990 | 947 / 43 / 0 | 970 / 20 / 0 |

- **T1 (target).** Every reversed target pair becomes correct: 6→0 under CP and 4→0 under CW.
  There are no new reversed or unmatched pairs. **Passes.**
- **T2 (controls).** There are zero correct→reversed and zero transitions into unmatched, on
  every paper and profile. **Passes.**
- **Remaining reversals.** These are relations outside the sidebar class and stay in the
  population. Compound-wrap unmatched pairs come from separator removal and are identical before
  and after.

### Strict paragraphs (`scientific-side-streams-java21.json`)

T3: totals are unchanged at 58/58/59 and 60/60/61 of 143. All 18 no-policy rows equal the
accepted report. **Passes.** The paragraph metric does not register this order-only change.

### Ten documents (`acceptance-*-java21.json`)

T4, CP and CP+IS each with and without the policy:

- Tokens are identical on all ten documents: matched 28,970 and 28,992 respectively.
- The policy fires only on Apple Q2, where ordered anchors rise 190→199.
- **Limitation.** On Apple's statement pages, the row-label column beside smaller-type values
  qualifies as a side stream. Labels are grouped into blocks apart from their values. This
  agrees better with upstream token order but changes row association in tabular text. It is
  disclosed, not tuned away.

### Existing Scientific Reports one-token loss

From actual output (private probe), the inline-script policy attaches citation markers to their
words (`cells17.`-style tokens, which upstream also emits). Standalone `.`, `1` and `2` tokens
that had matched unrelated upstream tokens disappear. The non-whitespace character multiset is
identical (33,763 code points). This is token-boundary redistribution, not lost glyphs, and it
remains a measured trade-off.

## Owned tests

`PdfSideStreamBlocksTest` (5):

- smaller sidebar blocks with an unchanged main stream, image barrier, bounds and characters;
- equal type, too few lines, a column reaching into the main extent, small text inside the main
  column and rotated text all unchanged;
- accepted two-column bands unchanged;
- `LAYOUT_LIMIT` and cancellation;
- public option, `LINES` unaffected, copies and compatibility constructors.

## Reproduction

Compile `benchmarks/tools/CompareUpstreamLineOrder.java` and `CompareUpstreamTextAgreement.java`
against the validation classpath, then run with `-Djava.awt.headless=true`:

```sh
CompareUpstreamLineOrder ../pdf-scientific-full-body/upstream-manifest.json ORIGINALS FAST_CAPTURES 1 \
  PROFILES COMPARISONS recent:11:32ef0aa96a1bdcd18563d19f570f7d1ff8b90430cd2d05258da37a5feea8be51 NEW_REPORT
CompareUpstreamLineOrder ../pdf-scientific-paragraph-holdout/manifest.json SOURCES HOLDOUT_CAPTURES 1 PROFILES COMPARISONS - NEW_REPORT
CompareUpstreamTextAgreement ../pdf-unstructured-alignment/acceptance-manifest.json SOURCES CAPTURES NEW_REPORT --profile=COLUMN_PARAGRAPHS+SIDE_STREAMS
mvn -o -pl docweave-validation -am test -Dtest=PdfScientificReadingOrderTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.scientific.reading.cache=ORIGINALS \
  -Ddocweave.pdf.scientific.reading.manifest=benchmarks/review/pdf-sidebar-blocks/scientific-side-streams-manifest.json \
  -Ddocweave.pdf.scientific.reading.report=NEW_REPORT
```

Profiles and comparisons are listed in the reports. No upstream model was executed in this
pass; all upstream outputs are existing pinned captures.
