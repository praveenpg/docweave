> **Accepted after scope correction.** The additional tie pass now runs only in column-paragraph layouts. Plain PARAGRAPHS retains SMALLER_TYPE_BLOCKS placement; the previously reported row regression is fixed. See [final review](review/README.md). The initial candidate results and concerns below are retained as history.

# Explicit same-row sidebar tie policy (2026-10-22)

This adds the opt-in `PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES`, implementing the
whole-element rule evaluated in
[`pdf-sidebar-whole-element-evaluation`](../pdf-sidebar-whole-element-evaluation/README.md)
from development baseline `f42b0e0`. `PRESERVE` stays the default, and
`SMALLER_TYPE_BLOCKS` is unchanged. The archived paragraph-splitting candidate
([`pdf-sidebar-row-ties`](../pdf-sidebar-row-ties/README.md)) is not revived.

The rule is post-hoc development evidence, informed by an exhaustive placement diagnostic on the
same papers. None of the results below is held-out accuracy.

## Rule

`GeometricPdfReadingOrder` first builds the existing `SMALLER_TYPE_BLOCKS` output, with
unchanged side detection, joins and flush boundaries. It then makes one in-order pass over
whole items. An item tagged `pdf_reading_order=side_stream_blocks_v1` swaps with its immediate
predecessor only when the predecessor:

- is `ordinary()` text: upright, not a source paragraph, table or image, and not side-tagged;
- has `pdf_line_count` absent or 1;
- satisfies side right ≤ predecessor left;
- satisfies predecessor top ≤ side top < predecessor bottom.

Each side item swaps at most once. The pass uses `Work.step()`, so it shares cancellation and
`LAYOUT_LIMIT`. The predicates read geometry only, never text.

One refinement is stricter than the frozen simulation, which only required the predecessor to
be UncategorizedText. The implementation requires `ordinary()`, as the handoff specifies. The
all-page comparison below shows that this changes no output on any of the 17 sources: the
implementation equals the frozen simulation everywhere.

## Owned tests (written first)

`PdfSideStreamRowTiesTest` (7 tests) was compiled before the production change. It failed on
the missing enum value (`test-first.log` in the private return packet). It covers:

- an eligible left sidebar with a one-line predecessor;
- a joined five-line predecessor whose last line shares the row;
- a right-hand sidebar;
- a sidebar starting below the row, and one touching the row's bottom only;
- image, table, spanning-text, source-paragraph and rotated predecessors;
- one swap past two same-row items (no bubbling);
- five successive eligible side elements in interleaved side columns, with the element multiset
  and non-side order preserved;
- unchanged default, `PRESERVE`, `SMALLER_TYPE_BLOCKS` and accepted-gutter output;
- typed `LAYOUT_LIMIT` and cancellation;
- the public partitioner, including unchanged `LINES` output and option copies.

The seven existing `PdfSideStreamBlocksTest` cases are untouched and pass.

`mutation-summary.txt` records five deliberate rule breakages run against the new tests:

- dropping the line-count check;
- dropping `ordinary()`;
- accepting a side top equal to the predecessor bottom;
- dropping the left-of check;
- allowing repeated bubbling.

Each breakage makes at least one new test fail.

## Results (Java 21.0.12.1)

**A. Existing output unchanged.** `output-hashes-*-java21.json` hash the complete output (ids,
types, text, metadata) of all 17 pinned sources under every `TextLayout`, both inline-script
choices, and `PRESERVE` and `SMALLER_TYPE_BLOCKS`. That is 408 outputs, built once with
`f42b0e0` classes from a fresh archive and once with the candidate. **All 408 are identical.**

The first run of the hash tool serialized immutable metadata maps in a per-JVM order and was not
reproducible run to run. That was a tool defect. The tool now orders map entries by key, and
both runtimes were re-run. The first-run outputs are kept privately as superseded.

**B. Fixed pairs** (`line-order-java21.json`, `line-order-holdout-java21.json`). The tool is the
unchanged `CompareUpstreamLineOrder` with existing Unstructured 0.27.28 `fast` captures on page
1. Counts are reversed pairs, `SMALLER_TYPE_BLOCKS` → row ties:

| Paper | CP | CP compound wraps | CP + inline scripts | CP compound wraps + inline scripts |
| --- | ---: | ---: | ---: | ---: |
| recent (990 pairs) | 6 → 6 | 6 → 6 | 6 → 6 | 6 → 6 |
| next (703) | 3 → 1 | 3 → 1 | 3 → 1 | 3 → 1 |
| holdout (1,081; 990 with inline scripts) | 19 → 17 | 19 → 17 | 20 → 18 | 20 → 18 |
| eLife (1,485; 1,653 with inline scripts) | 43 → 43 | 43 → 43 | 43 → 43 | 43 → 43 |

- Every changed pair moves from reversed to correct. There are zero correct→reversed and zero
  newly unmatched pairs, and the complete populations are retained.
- Recent has one move with no measured benefit. It is disclosed, not counted as a gain.
- The remaining next reversal is the frozen target of the archived row-tie candidate: element
  16 before 17, collapsed-text hash `2e8b1fea…`. This policy does not fix it.
- The recent/next/eLife capture files differ in bytes from the ones pinned in
  `pdf-sidebar-blocks/followup`, but their element types, texts and pages are identical. The
  baseline counts reproduce exactly.

**C. Every page preserves everything except the swap** (`policy-comparison-java21.json`).
`CompareSidebarRowTiePolicy` covers 17 sources, all 181 pages, every text layout and both
inline-script choices: 204 source profiles and 2,172 page profiles. On every page:

- the output equals the frozen simulation (`EvaluateSidebarBlockMoves.move`) applied to the
  `SMALLER_TYPE_BLOCKS` output;
- the non-side sequence, compared by type, text and metadata (which includes coordinates and
  line counts), is unchanged;
- the whole-element multiset and the non-whitespace character multiset are equal.

In the column layouts, moves happen only on page 1: recent 1, next 2, holdout 2. Element ids
are excluded from these comparisons because they encode sequence position.

**D. Strict paragraphs** (`scientific-row-ties-java21.json`, `strict-paragraph-equality.txt`).
This uses the unchanged three-paper manifest with four row-tie profiles added
(`scientific-row-ties-manifest.json`). Totals are 58/59 and 60/61 of 143, equal to
`SMALLER_TYPE_BLOCKS` for every profile. On every case and profile, all scored fields and the
per-paragraph occurrences are equal. Only the candidate text hash differs, on recent and next,
because of the reorder.

**E. Row association.** `row-association-java21.json` uses the existing `CompareRowAssociation`
on the pinned 17-source TSV (`498bb6d8…`), with both column layouts and both inline-script
choices: 68 source profiles and 27,244 row pairs.

- No row-pair outcome changes.
- Kept, separated, unmatched, row-bound and merged-row line counts are identical to
  `SMALLER_TYPE_BLOCKS`.
- The 13 additional controls (11 business/statement documents plus PeerJ and Scientific
  Reports) and eLife have identical output in these layouts.

**Beyond the evaluated population** (`row-association-other-layouts-java21.json`). Side
detection also applies under `PARAGRAPHS` and the oriented compound-wrap layout, which neither
this nor the earlier side-stream evidence evaluated.

- **Oriented compound wraps:** moves match the column layouts and no row pair changes.
- **`PARAGRAPHS`:**
  - the rule moves 5 elements on us-001 page 2 and 1 on eLife page 1, with no row-pair change;
  - on holdout, it moves 4 elements across pages 1–2 and changes one row pair from kept to
    separated (26/11 → 25/12 kept/separated without inline scripts, 24/8 → 23/9 with them).

This is a review concern, not a tuned exclusion (see Limitations).

## Limitations

- These are post-hoc, development-paper results against one pinned upstream reading order. They
  are not semantic article order or held-out accuracy.
- Under `PARAGRAPHS`, the option changes one holdout row pair and moves elements on one business
  control page. Restricting the option to column layouts would be a separate, reviewable
  decision. It was not made after seeing these results.
- `next` keeps the one reversal that needs a paragraph split.

## Reproduction

All runs use Java 21 and are offline. They use the existing captures, with no OCR, model or
upstream execution.

```sh
javac --release 21 -Xlint:all -Werror -Xdoclint:all/private -cp "$runtime_cp" -d TOOLS \
  benchmarks/tools/CompareUpstreamLineOrder.java benchmarks/tools/CompareRowAssociation.java \
  benchmarks/tools/EvaluateSidebarBlockMoves.java benchmarks/tools/CompareSidebarRowTiePolicy.java \
  benchmarks/tools/HashPdfPolicyOutputs.java
java -Djava.awt.headless=true -cp "TOOLS:$runtime_cp" dev.docweave.validation.HashPdfPolicyOutputs ROW_SOURCES_TSV NEW
java -Djava.awt.headless=true -cp "TOOLS:$runtime_cp" dev.docweave.validation.CompareSidebarRowTiePolicy ROW_SOURCES_TSV NEW
java -Djava.awt.headless=true -cp "TOOLS:$runtime_cp" dev.docweave.validation.CompareUpstreamLineOrder \
  benchmarks/review/pdf-scientific-full-body/upstream-manifest.json SOURCES CAPTURES 1 PROFILES COMPARISONS \
  next:16:2e8b1fea3012a89bed0b85a5f99c85d6b067a9e4cd3ab10cefe05de1d1cf2584 NEW
java -Djava.awt.headless=true -cp "TOOLS:$runtime_cp" dev.docweave.validation.CompareRowAssociation \
  ROW_SOURCES_TSV PROFILES COMPARISONS NEW
mvn -o -pl docweave-validation -am test -Dtest=PdfScientificReadingOrderTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.scientific.reading.cache=SOURCES \
  -Ddocweave.pdf.scientific.reading.manifest=benchmarks/review/pdf-sidebar-row-tie-policy/scientific-row-ties-manifest.json \
  -Ddocweave.pdf.scientific.reading.report=NEW
```

Profiles and comparisons are recorded in each report. For the `f42b0e0` hashes, compile
`HashPdfPolicyOutputs` against classes built from `git archive f42b0e0`. The private return
packet holds the source TSV, logs and freeze receipt.
