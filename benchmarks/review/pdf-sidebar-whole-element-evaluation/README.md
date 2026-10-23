# Whole-element sidebar placement feasibility

Evaluation only, at development baseline `72d77dc`. No runtime policy, API, default or
extraction source was changed. No OCR, ONNX, upstream process, download or external service ran.

## Runnable implementation opportunity

The archived same-row candidate splits main paragraphs. This evaluation instead keeps every
existing output element intact and changes only an adjacent sidebar/main-element order.
A geometry-only simulation improves two papers while preserving all measured correct pairs,
main elements and row associations. This supports implementing and reviewing a **new explicit
policy**, not altering the current SMALLER_TYPE_BLOCKS policy.

| Paper, page 1 | Existing reversed pairs | Simulated reversed pairs |
|---|---:|---:|
| recent | 6 | 6 |
| next | 3 | 1 |
| holdout, without inline scripts | 19 | 17 |
| holdout, with inline scripts | 20 | 18 |
| eLife | 43 | 43 |

Both column-paragraph layouts, with/without inline scripts, give these results (16 profiles).
Every profile has zero correct-pair regressions and zero newly unmatched pairs. The file named
holdout is already development data; it is not independent acceptance. These are ordering
agreement counts against pinned Unstructured 0.27.28 fast, not paragraph accuracy or runtime gains.

## Method and limitations

1. Exhaustively try moving one existing side-tagged output element to every other insertion
   boundary. Preserve the relative sequence of all non-side elements. This is an oracle-like
   diagnostic: it deliberately ignores geometry barriers and is not a deployable rule.
2. Record one geometric hypothesis before its deterministic simulation. The hypothesis was
   informed by that diagnostic and is therefore **post-hoc development evidence**.
3. Starting with existing SMALLER_TYPE_BLOCKS output, scan in order. Move a side-tagged item
   before its immediate predecessor only when the predecessor is non-side UncategorizedText
   with one source line (absent pdf_line_count means 1), the sidebar lies wholly to its left,
   and predecessor.top <= sidebar.top < predecessor.bottom. At most one adjacent backward
   swap per sidebar. Never split, join, rewrite, drop or synthesize an element.
4. Score with the unchanged CompareUpstreamLineOrder pair population and outcome function.
   Missing/duplicated atoms retain their unmatched penalty. No reference content informs the rule.
5. Apply the same simulation across all pages of 17 pinned sources (181 pages), two layouts and
   two inline-script choices: 68 source profiles. Compare every existing row-pair outcome.

Results: all row-pair outcomes are identical; non-side elements and their relative sequence are
identical in every profile. The 13 additional control documents (11 business/statement plus
PeerJ and Scientific Reports) have entirely unchanged output; eLife also stays unchanged.
The only moved elements occur on page 1 of recent/next/holdout. Recent has one swap with no
measured pair benefit, disclosed rather than counted as a gain.

The simulation does not implement page-work accounting, exercise a new public option, or
establish unchanged default output in a modified runtime. Those are implementation acceptance
requirements. It also does not replace the strict paragraph regression study or owned tests.
The 17-source control checks membership/order and row-pair outcomes; merged-row counts were not
recomputed separately because element contents/membership are preserved by construction.

## Reproduction and validation

`benchmarks/tools/EvaluateSidebarBlockMoves.java` compiles with the existing evaluator using
Java 21 `--release 21 -Xlint:all -Werror -Xdoclint:all/private`. Pinned clang-format 23.1.1 passes.

```sh
javac --release 21 -Xlint:all -Werror -Xdoclint:all/private \
  -cp "$runtime_cp" -d "$tools_classes" \
  benchmarks/tools/CompareUpstreamLineOrder.java \
  benchmarks/tools/EvaluateSidebarBlockMoves.java
java -Xmx768m -Djava.awt.headless=true -cp "$tools_classes:$runtime_cp" \
  dev.docweave.validation.EvaluateSidebarBlockMoves \
  ../docweave-source-review/scientific-reading/sources \
  ../docweave-source-review/scientific-reading-upstream/captures NEW_REPORT.json
```

The final-tool report reproduces the earlier geometry simulation exactly. Numeric source and
capture hashes are in the reports. The private companion handoff preserves the control runner,
source TSV, the rule recorded before simulation and text-bearing diagnostics. No full reactor
or Java 25 repetition was needed for this evaluation-only change.

- `exhaustive-single-move.json`: unrestricted single-element feasibility, not an algorithm.
- `geometry-simulation.json`: actual fixed-rule results and preserved-element checks.
- `row-controls.json`: all 68 source-profile row checks.
