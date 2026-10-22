# NVIDIA statement miss classification

Classification of every missing reference row in the complete NVIDIA FY2026 Q4 replay
(11 original pages, both frozen detector profiles, 110 issuer rows each) under the existing
explicit `SOURCE_NUMERIC_OR_SECTION_ROW` policy. No runtime code, reference, scorer or capture
changed. `baseline-section-row-java21.json` reproduces the documented result exactly:
TATR 36/110 and YOLOX 68/110 exact complete-row presence, strict ordinal 0/110 for both.

## Classes (`classification.json`, reference indices only)

| Class | TATR | YOLOX |
| --- | --- | --- |
| Reference parenthesis spacing | 15 | 21 |
| Statement title outside any table proposal | 9 | 12 |
| Split date header rows | 8 | 6 |
| Absent or partial table proposal | 42 | - |
| Structural row outside a present proposal | - | 3 |
| **Missing total** | **74** | **42** |

- **Reference parenthesis spacing.** The HTML reference stores an accounting negative's closing
  parenthesis in its own cell, yielding `(5,491 )`. Source glyph positions show the PDF draws
  `)` abutting the last digit (`1` ends at x=348.2, `)` starts at x=348.3, page 6), and the
  independent Poppler line dump also reads `(5,491)`. The extracted rows carry identical content.
  These are reference presentation discrepancies; removing or adding source spaces to match
  would violate the source contract.
- **Titles / split headers.** Statement titles sit outside the provider table proposals; the PDF
  draws the date header as two physical lines in each column, while HTML splits it into two rows.
- **YOLOX structural remainder (3 rows).** A previous-page balance-sheet heading, and two rows
  below the page-7 cash-flow proposal: a label-only `Supplemental disclosures` row and a numeric
  row, separated from the table by a blank row (proposal bottom about 573.6 pt; rows at baselines
  606.5 and 624.4 pt). Recovering them requires crossing a blank-row gap with two rows, which is
  the condition the parked double-pitch row-gap attempt already left unchanged. No candidate.
- **TATR.** Remaining misses lie in regions with no or truncated proposals (including the absent
  page-6 balance table); a native leading/trailing row rule cannot recover an absent table.

## Disposition

Reference discrepancy for 36 rows across profiles; structural misses are limited to proposal
coverage. Next bounded action, if pursued: obtain an independently reviewed decision on whether
parenthesis spacing should be normalized in the reference protocol (a protocol change, not an
extraction change), and separately a design for gap-separated trailing section rows with owned
footnote-absorption negatives.

## Reproducible spacing diagnostic

An [explicit JVM diagnostic](../pdf-business-parenthesis-diagnostic/README.md) now reproduces
all 21 YOLOX and 15 TATR spacing-only cases with exact-first, one-to-one matching and
numeric-token-only normalization. Every strict result record is unchanged. This does
not adopt a normalized reference protocol or establish additional extraction accuracy.
