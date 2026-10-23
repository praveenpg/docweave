# Trailing table rows from the source whitespace cell lattice

This adds an explicit, opt-in way to append rows below a provider table:
`PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS`. On the frozen NVIDIA population it
recovers all three YOLOX rows that the
[miss classification](../pdf-business-miss-classification/README.md) left as structural misses
outside a present proposal. Default output is unchanged.

## Source signal

The [trailing-row check](../pdf-trailing-source-support/README.md) had already found no structure
tree and no supporting rules below the page-7 cash-flow proposal, and the
[row-gap attempt](../pdf-financial-row-gap-attempt/README.md) gained nothing. A glyph-level dump
of the same original (`ProbeTrailingTypography.java.txt`) shows a different source fact.

The producer paints an explicit space glyph for each empty or padding table cell. It does so at
fixed column positions on every row of the statement: body, section, total and visually blank
rows alike. On page 7, every row of the cash-flow continuation repeats spacer glyphs at displayed
x = 356.9, 425.5 and 494.7. Those positions continue through the blank row at baseline 588.6 and
through both missing rows (606.5 and 624.4), then end. The next line (667.0) carries a single
space at x = 54.3, outside the lattice, before the following statement's title. The page-5
balance-sheet fragment shows the same structure: its lattice (x = 432.4, 498.1, …) continues
through a blank row to the cross-page section heading at the bottom of the page.

This is a structural signal from the source. It is distinct from row gaps, rules, tags,
vocabulary and number grammar.

## Rule

`PdfNativeTrailingRows` (hidden) runs after the optional leading-row inference and before the
existing topology revalidation.

- **Lattice.** The lattice is the set of whitespace glyph positions repeated by every nonempty
  provider row, within 0.1 displayed units (floating-point transform noise, far below one glyph
  width). At least two positions are required. Rows with no glyphs do not constrain it.
- **Scan.** Lines below the table are read in baseline order, using the same 2-unit
  common-baseline grouping as the leading-row rule. A line continues the table only while it
  meets all three conditions:
  - it repeats every lattice position;
  - every glyph is upright;
  - every glyph lies within the table's horizontal extent.

  The first line that fails ends the scan.
- **Rows.** Blank lattice lines are crossed but never become rows. Each visible line becomes one
  row, provided each native run (`PdfGlyphLines` grouping) fits inside one of the last provider
  row's unmerged columns. Otherwise the provider table is kept.
- **Unchanged contracts.** Existing anchors, row indices and provenance are unchanged. A conflict
  with another proposed or accepted table keeps the original. Added anchors count toward
  `maxTableAnchors`, and work steps are charged to the page budget and checked for interruption.
- **Metadata.** Accepted tables carry `pdf_table_trailing_boundary_policy` and
  `pdf_table_added_trailing_rows`. The leading-row metadata keys are unchanged.

## Results: frozen NVIDIA population

- Source: the 11-page original, SHA-256 `89de999d…13564`.
- Captures: both frozen profiles.
- Reference: 110 rows per profile.
- Scorer: the frozen scorer from its frozen working directory.
- Prerequisite: the baselines reproduce the historical report exactly. Its `results` array equals
  `pdf-business-miss-classification/baseline-section-row-java21.json`.

| Policy | TATR presence | YOLOX presence | Strict ordinal (both) |
| --- | ---: | ---: | ---: |
| `PROVIDER_ONLY` (default) | 35/110 | 66/110 | 0/110 |
| `SOURCE_NUMERIC_ROW` | 36/110 | 67/110 | 0/110 |
| `SOURCE_NUMERIC_OR_SECTION_ROW` | 36/110 | 68/110 | 0/110 |
| Section row + `SOURCE_CELL_SPACER_ROWS` | 36/110 | **71/110** | 0/110 |

YOLOX gains three rows, which are exactly the classified structural targets:

- Balance sheet, reference 21: the heading at the bottom of page 5.
- Cash flows, references 47 and 48: the two page-7 rows below the blank gap.

No reference row is lost. Observed rows rise by exactly the gained rows (27→28 and 42→44).
Unmatched observed rows are unchanged in all six case/profile records, so no extra or duplicated
row was produced. Strict ordinal remains 0/110. TATR is unchanged.

A full-output comparison of all 11 pages, under both profiles, covered 14 YOLOX and 8 TATR tables.
Only these three source rows were added. The only ordinary-text elements removed were the ones
carrying those rows' text. TATR output is identical. The non-GAAP reconciliation pages, which
carry footnotes, gained nothing.

## Transfer and applicability

The page-5 heading is a separate case from the page-7 target: a different statement, page and
miss class. The classification had expected it to need a cross-page mechanism. The same rule,
applied uniformly, recovers it. It is still the same document and producer.

A positive transfer on a different producer needs provider table captures that do not exist for
those documents. `spacer-applicability.txt` (`ProbeCellSpacers.java.txt`) counts isolated
whitespace glyphs in other original business PDFs:

| Producer | Isolated whitespace glyphs | Expected effect of the option |
| --- | --- | --- |
| AMD, FRX disclosure, complete Walmart filing | None | Cannot act |
| Apple Q2/Q3 2026, Amazon Q4 2025 | Present | Could act |

AMD, FRX and Walmart lack a lattice, so the option is a no-op there by construction. For Apple
and Amazon, earlier native studies emitted no provider tables. A positive cross-producer transfer
therefore remains open; its prerequisite is a frozen provider capture on one of those originals.

## Owned controls

`PdfNativeTrailingRowsTest` has 9 tests on actual PDFBox-authored pages.

- **Positives:**
  - lattice rows cross a blank gap, and a footnote stops the scan;
  - the same rows are inferred at a higher render resolution;
  - leading and trailing inference compose.
- **Negatives:**
  - footnotes, separate notes and coincidentally aligned numbers without the lattice;
  - a partial lattice;
  - blank-only lattice lines;
  - a footnote between the table and later lattice lines;
  - wider lines;
  - rotated glyphs;
  - labels crossing a column boundary;
  - a single-position or internally inconsistent table lattice;
  - competing table ownership.
- **Contracts:** the anchor budget (TABLE_LIMIT), cancellation (InterruptedIOException), defaults,
  copy independence and null rejection.

The leading-row, section-row, native-layout, native-first and cell-text suites pass unchanged.

## Limits

- Membership is inferred from producer structure. It is not semantic proof: a note authored
  inside the same HTML table would correctly be absorbed as a table row.
- The rule does nothing for producers that paint no whitespace cells, and nothing when the
  table's own rows do not all repeat at least two positions.
- A wrapped label whose first line lacks the lattice stops the scan, so the row is missed
  rather than mis-joined.
- TATR's 42 absent or partial proposals need a proposal or detection design. A boundary rule
  cannot recover an absent table.
- The 36 reference parenthesis-spacing rows, the titles and the split date headers are
  presentation classes and are unchanged.
- These are development measurements on known data, not held-out acceptance.
