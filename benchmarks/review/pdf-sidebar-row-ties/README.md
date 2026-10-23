# Same-row sidebar ties: archived candidate (2026-10-22)

**Decision: not promoted.** The candidate is archived as `candidate.patch.gz`. It applies to
`6760da2` and is not active code. Production behavior is unchanged from the integrated
`SideStreams.SMALLER_TYPE_BLOCKS`.

## Diagnosis

After the integrated sidebar policy (COLUMN_PARAGRAPHS), the remaining reversed mapped pairs
against pinned Unstructured 0.27.28 `fast` page-1 order are recent 6, next 3 and holdout 19. This
was diagnosed from existing captures and DocWeave lines; no upstream execution was needed.

- **Same-row near ties.** Every recent and next reversal, and two holdout pairs, are near ties. A
  side block's first line overlaps an abstract line on the same visual row. The abstract line's
  top is 0.4–2.6 units higher, so strict top/left sorting emits it first, while upstream reads the
  left block first. Upstream's own boxes show the same tie on the frozen target (abstract top
  528.2, sidebar top 529.3, sidebar read first).
- **Other holdout reversals (17).** These have different causes:
  - upstream orders `OPEN ACCESS` before an earlier affiliation block;
  - upstream joins the citation continuation and editor lines into one block.

  Neither is a DocWeave ordering target.

## Frozen protocol

`frozen-target-and-prediction.txt` (SHA-256 `b2115565…`) was written before any candidate code.
The private receipt log records its hash, the unchanged metric tool and the unchanged reading-order
source.

- **Target.** next.pdf (`0c96e723…`) page 1, upstream element 16 (`2e8b1fea…`) before element 17.
- **Candidate.** Within the side-stream policy, a side block whose first line immediately follows
  a main line, overlaps it vertically and lies wholly to its left is emitted first. The main group
  ends there.

## Results

The candidate's reports are kept here: `line-order-*-candidate`, `scientific-side-streams-candidate`
and `row-association-candidate`.

| Prediction | Result |
| --- | --- |
| D1 target | **Met.** next target pairs: reversed→correct under every profile; none broken or newly unmatched. |
| D2 controls | **Met.** Reversed pairs: recent 6→0, next 3→0, holdout 19→17 (20→18 with inline scripts); eLife unchanged. No correct→reversed or new unmatched pair against the default. Per-pair, every candidate reversed pair was already reversed under the integrated policy (`reversed-pair-subset-check.tsv`). |
| D3 strict paragraphs | **Met.** Every paragraph outcome is identical: 58/58/59, and 60/60/61 with inline scripts. Only output text hashes change. |
| D4 row association | **Met.** All 13 additional control sources (11 business/statement documents plus PeerJ and Scientific Reports) are identical to the integrated reports. On next, one scientific pair moves each way. |
| D5 existing controls | **Not met.** `smallerSidebarLinesFormBlocksWithoutChangingTheMainStream` fails: the candidate ends a main paragraph group where a side line overlaps it on the same row. |

On the real papers, the candidate splits exactly one main-stream paragraph element on recent and one
on next (34→35 main elements each). Its two holdout fixes need no split, and eLife and the holdout
keep identical main elements.

Fixing these ties requires relaxing the documented contract that main-stream grouping and order
are unchanged. That is a product decision, not a measured win, so the candidate stays archived.
A narrower variant limited to main lines that start a new group would be the same mechanism
restricted after measurement, and was not run.

One new negative fixture (a right-hand sidebar) was itself invalid: its 20-unit lines are below
the 40-unit join width and never form a block. This is recorded rather than counted for or against
the candidate.
