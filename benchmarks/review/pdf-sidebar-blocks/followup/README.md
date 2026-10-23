# Side-stream blocks: review follow-up (2026-10-22)

This additive follow-up answers the two P2 findings of the independent review. The original
reports in the parent folder are unchanged.

## Frozen correction

`corrective-hypothesis-frozen.txt` (SHA-256 `2baa12f9…`) was written after the review and
before any corrective code. The private receipt log records its hash, the new row-association
tool (`8c1a5e9f…`) and the unmodified reviewed reading-order source.

- **R1, row-bound rejection (P2-1).** A side cluster is rejected when at least half of its
  outside lines share a baseline with a main-stream line. Baselines count as shared within the
  existing 2-unit native tolerance. Statement labels share baselines with their values; a prose
  sidebar runs on its own baseline grid, which is why the gutter detector rejected it.
- **R2, barrier division (P2-2).** Two consecutive side lines never join across an intervening
  item whose box horizontally overlaps them. Main-stream lines lie at least 4 units outside the
  side column, so they do not divide blocks. Images, tables and spanning text do.

## New measurement: row association

`CompareRowAssociation.java` derives row pairs from native `LINES` output: horizontally
consecutive, disjoint lines on one baseline. In each profile it reports:

- kept, separated or unmatched outcomes for each pair;
- *merged row lines*: located row-bound lines that share an output element with a row-bound
  line from another baseline, such as separate statement labels joined into one paragraph.

The run covers 17 originals: the ten acceptance documents, Apple Q3, Amazon, NVIDIA and the four
scientific papers. The private source list's paths are machine-specific; hashes are in the
reports.

Merged row lines under COLUMN_PARAGRAPHS (default → reviewed candidate → corrected); the same
pattern holds for the inline-script and compound-wrap profiles:

| Source | Default | Reviewed candidate | Corrected |
| --- | ---: | ---: | ---: |
| Apple Q2 2026 | 2 | **21** | 2 |
| AMD, US-001 | 8 each | 8 each | 8 each |
| PeerJ | 3 | 3 | 3 |
| Amazon | 2 | 2 | 2 |
| Other 8 business/statement originals | 0 | 0 | 0 |
| recent / next / holdout (scientific) | 0 / 0 / 12 | 2 / 2 / 14 | 2 / 2 / 14 |

- **Additional control originals.** All 13 (including the PeerJ and Scientific Reports articles)
  equal the default in merged lines *and* in every
  row-pair outcome under all three profiles (`row-association-corrected-java21.json`). The
  reviewed candidate's Apple Q2 regression (21 merged lines; 3 pairs kept→separated) is gone.
- **Scientific papers.** One coincidental same-baseline sidebar/abstract pair per paper becomes
  separated. This is the intended sidebar block, and the default is not an association reference
  here.
- **Residual (disclosed).** On Apple Q2's balance sheet, six value-less section headings at
  their own indent are not row-bound and receive the `side_stream_blocks_v1` tag as single-line
  elements. Text, order, membership and row association are unchanged, and they cannot merge
  across intervening label rows under R2. No other business document has a tagged element.

## Barrier probe (P2-2)

The review's unmodified `SidebarBarrierProbe` now reports `GROUP_CROSSES_IMAGE=false` for both
policies. `side 0` stays above the image, and `side 1`–`side 5` form a block below it, in order.

## Scientific gains preserved

- **Line order.** `line-order-corrected-java21.json` and `line-order-transfer-corrected-java21.json`
  are byte-equivalent in content to the reviewed candidate's reports on all four papers:
  - target reversed pairs 6→0 (CP) and 4→0 (CW);
  - next 22→3, holdout 35→19 (43→20 with inline scripts);
  - zero correct→reversed and zero newly unmatched pairs.
- **Strict paragraphs.** `scientific-side-streams-corrected-java21.json` equals the reviewed
  report: 58/58/59 of 143, and 60/60/61 with inline scripts.
- **Ten documents.** COLUMN_PARAGRAPHS(+INLINE_SCRIPTS)+SIDE_STREAMS now equals its default
  profile on every document. The reviewed candidate's Apple Q2 ordered-anchor gain (190→199)
  came from the rejected label grouping, and that gain is withdrawn.

## Owned tests

`PdfSideStreamBlocksTest` (now 7) adds two controls:

- **`statementLabelColumnsKeepRowAssociation`:** a smaller label column with independent pitches
  and four indents, value-less headings and two numeric value columns. Text, boxes and order
  equal the default, no element holds two labels, each row's three items stay contiguous, and
  only the value-less headings may carry provenance.
- **`interveningStructuralBarriersDivideSideBlocks`:** a full-width image, a Table and spanning
  text, each between joinable side lines. It checks membership, barrier order between the parts,
  the lower block's bounds and unchanged main-stream elements. An image beside the sidebar
  inside the main column does not divide it.

Both new controls fail when run against the reviewed candidate (private log), and pass now.
