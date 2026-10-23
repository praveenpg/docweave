# P1 freeze (written before measurement)

Start HEAD 890629c (runtime cf83cb2). Item: residual scientific line-order reversals under the
accepted SMALLER_TYPE_BLOCKS_ROW_TIES policy (recent 6, next 1, holdout 17 / 18 inline, eLife 43).

## Question

Can ANY whole-element permutation of the existing ROW_TIES output on page 1 reduce the
reversed pairs, while keeping every element (paragraph membership, text, bounds) intact?

## Method (frozen)

New JVM evaluator `benchmarks/tools/ClassifyResidualLineOrder.java`, reusing the unchanged
`CompareUpstreamLineOrder.atoms/outcomes/collapse` population and outcome functions.

1. For each paper and each column-layout profile with SIDE_STREAMS_ROW_TIES (with/without
   inline scripts), locate every mapped atom in exactly one output element (the element whose
   collapsed text contains the atom's position in the joined page text).
2. Classify each REVERSED pair:
   - INTRA_ELEMENT: both atoms in the same output element (upstream orders lines of one
     DocWeave paragraph differently; no element order can change it);
   - INTERLEAVED: different elements X,Y, and the upstream expectations between atoms of X and
     atoms of Y disagree in direction (some X-atom expected before a Y-atom, another after):
     no relative order of whole X and Y satisfies all of them;
   - ORDERABLE: different elements whose cross pairs all expect the same direction.
3. Lower bound on reversed pairs over every whole-element permutation:
   LB = intra + sum over element pairs {X,Y} of min(pairs expecting X<Y, pairs expecting Y<X).
   Report the current reversed count, LB and the gap. Also report, per element pair with
   ORDERABLE reversals, the element indices, type, pdf_reading_order tag and line counts
   (no text in the public report).

## Decision rule (frozen)

- If current == LB for every profile: whole-element ordering is exhausted; the residual needs a
  different representation (split element) -> document structural incompatibility, park with
  that prerequisite. No runtime change.
- If gap > 0 somewhere: inspect those ORDERABLE element pairs for one geometric, text-free
  signal; only if one exists that also passes all P1 acceptance (zero correct->reversed, zero new
  unmatched on four papers, 143-paragraph strict results unchanged, 17 row controls unchanged,
  defaults unchanged) implement it as a separate opt-in candidate. Otherwise park with the
  exact element pairs.

No thresholds tuned; no paragraph splitting; the archived split candidate is not used.

## Addendum A (frozen after the classification report, before any simulation of it)

Classification result: recent 6/6 and next 1/1 are INTERLEAVED at the lower bound (structural);
holdout 17 (18 inline) and eLife 43 are ORDERABLE (bound 0). The largest holdout block
(10 pairs) is side block 33 expected before main 28-32 and after side block 27 (same side
column, no crossing barrier between them).

Hypothesis HC (side-column continuity, threshold-free): after the ROW_TIES output in column
layouts, move each side element S to directly after the nearest preceding side element P when
every element between them is non-side and does not horizontally overlap the union extent of
P and S (exactly the existing `crossed` barrier test). Whole elements only; one pass in order.
It uses no new numeric parameter. It is post-hoc (informed by the holdout counterexample).

Acceptance (unchanged P1 acceptance): zero correct->reversed and zero newly unmatched pairs on
all four papers and all column profiles; otherwise HC is rejected and NOT tuned (no gap or
pitch threshold will be added to rescue it, since such a threshold would be fitted to the
counterexample geometry already seen).
