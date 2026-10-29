# Frozen protocol: public Unstructured table ground truth (PDF area 1, P2/P3)

Frozen on 2026-10-29, before any DocWeave output on these inputs was observed. Changes after observing results
need a new versioned protocol; this file is not edited to fit results.

## Acceptance question

On independently labelled business pages that DocWeave has never processed, how much labelled table content does
each existing table profile miss, how much non-table content does it add, and which missed cells are
detector misses, structure errors or intentionally excluded titles and prose? A candidate improvement must recover
labelled content without accepting more prose as tables.

## Fixtures (independent labels; development use only after this run)

Unstructured `unstructured-eval-metrics`, commit `b148439dd428bb10d7f2bb80c7386ea08cbd7447`, Apache-2.0
(the policy-preferred "publicly supplied Unstructured examples", [scope](../../../docs/pdf-evaluation-scope.md)).
Raw files stay outside Git; identities are frozen in `manifest.json`.

| Role | Page | Labels |
|---|---|---|
| Positive | `mini-holistic-1-v2-NVIDIA-p006.pdf` (1 page, NVIDIA FY24 Q2 release, borderless statements; a different original from the FY26 Q4 development document) | 3 labelled tables: 12x6, 5x6, 5x6 cells (`uns-table-json-v1.0.0`), plus page plain text |
| Negative | `mini-holistic-1-v2-005-CISA-AA22-076-…-p002.pdf` (1 page, government advisory prose) | Labelled table list is empty; page plain text |

The negative is a labelled table-free page under Unstructured's own annotation; DocWeave treats an empty label list
as "no table" only for this file, not as a general rule.

## Profiles (existing behaviour only, no new code)

Native (`PdfPartitioner.create`, `withTableCellCoordinates(true)`): `RULED_ONLY` (default),
`RULED_AND_ALIGNED_TEXT`, `RULED_AND_MULTILINE_ALIGNED_TEXT`, `RULED_AND_PARTIAL_TEXT`,
`RULED_AND_FINANCIAL_ALIGNED_TEXT`, `RULED_AND_HORIZONTAL_TEXT`.

Model (`PdfPartitioner.createWithNativeTablesFirst`, `RULED_AND_PARTIAL_TEXT`, `PdfNativeLayoutOptions(150, 16000000,
1024, SPACE_SEPARATED)`, real pinned ONNX models through `OnnxTableLayout`, CPU):

- `yolox-provider`: YOLOX `yolox_l0.05.onnx` (sha256 `134301ca…`) detection, TATR structure (`ff1d19a1…`); provider rows only.
- `yolox-established`: as above with `LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW`, `TrailingRows.SOURCE_CELL_SPACER_ROWS`,
  `RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS` (the accepted opt-ins).
- `tatr-provider`, `tatr-established`: TATR detection (`0f625baa…`) with the same structure model and options.

Continuation (`TableContinuation`) cannot be measured: both fixtures are single pages. That is recorded as a resource
limitation, not a passed check.

## Metrics (per profile, per page)

Text normalization for matching only: Unicode NFKC, whitespace runs collapsed to one space, trimmed. Nothing else
(currency signs, parentheses, commas and footnote markers are significant).

- **Predicted cells:** nonblank cells of each `Table` element's `text_as_html` (spans expanded to their anchor cell once).
- **Association:** each labelled table is associated with the predicted table on the page sharing the largest
  multiset intersection of normalized nonblank cell strings; ties go to the earlier output table. A predicted table
  is associated with at most one labelled table (largest overlap first). No overlap → unassociated.
- **Recalled / missed cells:** multiset intersection per associated pair; missed = labelled minus recalled.
- **Missed-cell classification**, in order:
  - `DETECTOR_MISS`: the labelled table is unassociated;
  - `OUTSIDE_TABLE`: the string occurs in a non-table element of the page;
  - `CELL_SEGMENTATION`: the string is a strict substring of, or contains, an unmatched predicted cell of the associated table;
  - `STRUCTURE_OTHER`: anything else.
- **Exact rows:** labelled rows (ordered nonblank strings) that equal some predicted row of the associated table.
- **False additions:** unmatched nonblank predicted cells of associated tables, plus all cells of unassociated
  predicted tables. Each is `PROSE_ADDED` if it occurs in the page's labelled plain text outside every labelled table
  cell, else `OTHER_ADDED`.
- **Negative page:** any predicted `Table` is `FALSE_TABLE`; its cells are false additions.
- **Outcome:** a typed `PartitionException` is recorded as the profile's outcome with full denominators.

## Acceptance for a candidate change (one candidate, one focused validation)

A candidate is a measured improvement only if, on at least one profile, recalled cells or exact rows on the positive
page increase while, for every profile:
- recalled cells and exact rows on the positive page do not decrease;
- `PROSE_ADDED` and `OTHER_ADDED` do not increase;
- the negative page keeps zero tables.

The existing regression suites (full Java 21 `mvn verify` with strict Javadoc) must also pass, and defaults must not
change unless the change is a corrected defect. Otherwise the result is recorded and the area moves on.
