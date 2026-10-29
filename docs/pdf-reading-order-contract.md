# PDF reading-order contract (version 1, 29 October 2026)

This contract defines the **intended** element order for native-text PDF pages. It exists so that ordering changes are
judged against stated rules, not against agreement with one reference. Pinned Unstructured output, tagged structure,
content-stream order and publisher XML (JATS) are **evidence** about a page; none of them is automatically the
answer. Where evidence disagrees with this contract, the disagreement is reported, not silently resolved.

It describes the target for explicit column and side-stream policies (`TextLayout.COLUMN_PARAGRAPHS*`,
`PdfOptions.SideStreams.*`). Default `LINES` and `PARAGRAPHS` output keeps its documented geometric order. This
contract does not change any default.

## Units

- **Element:** one output element. The contract orders whole elements and never requires splitting, joining or
  rewriting one; intra-element order is the element's own line order.
- **Band:** a vertical region of a page between full-width barriers (text spanning the columns, images, tables, rules).
- **Main flow:** the text of the page's body column or columns.
- **Side stream:** a block of text set apart from the main flow: smaller type, outside the main text columns, typically
  a margin column, sidebar or first-page metadata box (correspondence, competing interests, funding, dates, editor,
  copyright, citation).

## Rules

| ID | Rule |
|---|---|
| R1 | Pages are in page order. An element never spans pages. Cross-page relations are metadata links, not reordering. |
| R2 | Bands are read top to bottom. |
| R3 | Within a band, columns are read left to right, each column top to bottom (column-major). |
| R4 | **A side stream is one contiguous unit.** Its elements are emitted consecutively in their own top-to-bottom order and are never interleaved line by line, or element by element, with main-flow elements. |
| R5 | **Side-stream placement.** A side stream is a column of the band in which it begins (R3): a left margin side stream precedes the main-flow elements that begin at or below its top within that band, and follows main-flow elements that end above its top. A right margin side stream follows the main flow of its band. |
| R6 | Page furniture (running headers and footers, page numbers) keeps its geometric position. This contract does not reorder it. |
| R7 | Inline scripts (superscript markers) belong to the line they annotate under `InlineScripts.NEARBY_SMALL_GLYPHS`; without that policy they are separate elements in geometric order. |

## Evidence and conflicts

For each disagreement between an output and a reference, report:
- which order the contract requires;
- which order each evidence source shows (pinned upstream `fast`, tagged structure, publisher JATS where available);
- whether the output violates the contract.

A residual is an **output defect** only when the output violates the contract. When the contract and a reference
disagree, it is a **reference difference**, recorded with its evidence. The contract changes only by a new version with
its rationale; never to fit a measurement.

Rationale for R4/R5: a side stream is a separate unit of meaning. Interleaving its fragments with an abstract or body
paragraph damages both for readers, chunking and retrieval, whatever any one reference does. Column-major placement
(R3) is the conventional reading of a margin column. It also matches the publishers' logical order in the observed
JATS front matter, where correspondence, dates and copyright precede the abstract.
