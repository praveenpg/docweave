# Follow-up: exact safeguard diagnosis and a validated fix (2026-10-21)

This note supplements, and does not replace, the original README.md/receipt.json/candidate.patch
from the parked attempt. Those remain the historical record of the first (negative) pass. This is
a second, bounded pass against the same parked backlog item, under the one-fix/one-validation
cap. Production remains unchanged: the archived patch below was applied locally, instrumented,
diagnosed, fixed, validated, then reverted. Nothing was committed, pushed or published.

## Reproduced baseline

Rebuilding `docweave-pdf` at HEAD `7090144` and replaying the frozen captures with
`AuditStatementRowPresence --section-row` (guarded candidate reapplied from `candidate.patch`)
reproduced the parked outcome exactly: YOLOX 67/110, TATR 36/110, strict ordinal 0/110 for both -
the permissive gain (68/110) did not survive the guard, matching receipt.json.

## Exact safeguards that rejected `Current liabilities:`

Instrumenting `PdfNativeLeadingRow.supportsSection` (temporary `System.err` tracing, removed before
archiving the fixed patch) and replaying just page 6 showed the guard failing for two independent
reasons, both inside `supportsSection`, not in the alignment/numeric *idea* itself:

1. **Single-displayed-run requirement on numeric body cells.** The real first body row below the
   section ("Accounts payable") renders its monetary cells as a currency mark glyph run followed by
   a large horizontal gap, then the digit run, e.g. column 1 glyphs: `$` at pixel x=922.56, then `9`
   at x=982.58 - a ~55px/~26pt gap, comfortably over `PdfGlyphLines.lines()`'s run-split threshold
   (`Math.max(12, glyph.space() * 3)`). `PdfGlyphLines.displayed()` therefore returns **two** lines
   for that one logical cell value ("$" and "9,812"), and `supportsSection` required `lines.size()
   == 1`, so it rejected the row immediately on this very common accounting layout. Production's own
   established cell-text assembly in `PdfNativeProviderTables.extract()` already handles this by
   *joining* multi-run cells with spaces instead of demanding one run - `supportsSection` was
   stricter than the rest of the pipeline's own notion of "one cell value," for no stated reason.

2. **Left-alignment measured from the wrong glyph.** `Current liabilities:` and `Accounts payable`
   genuinely start at the identical PDF x-coordinate, 57.719612 points - confirmed independently via
   `/tmp/docweave-financial-structure21/rows.tsv` (lines for page 6) and via direct glyph inspection:
   `Accounts payable`'s line literally begins with a leading space glyph at x=57.719612 (width
   2.545662), used for visual indentation, with the visible `A` starting at x=65.79. `supportsSection`
   filtered out blank-text glyphs before taking the minimum x for `bodyLeft`, so it measured from the
   first *visible* character instead of the line's true run origin, manufacturing an ~8.07-point
   "misalignment" against a 2-unit tolerance, for two lines that are genuinely flush left. The
   candidate section label's own left edge (`labelLeft`) has no such leading blank glyph, so only one
   side of the comparison was affected - an asymmetric measurement bug, not a flawed invariant.

Both are measurement defects in `supportsSection`, not evidence that alignment/numeric-completeness
is the wrong rule. "No further guard/threshold/model/crop search" from the first pass was accurate as
stated (no such search was needed) but the parked conclusion that "the measured gain disappeared"
understated the cause: the guard was never actually exercising its intended check on this row.

## Proposed fix and validation (one pass)

- Join a numeric body cell's displayed lines with spaces (as production already does for provider
  cells) instead of requiring exactly one line; strip one optional leading generic currency glyph
  (`$`, `£`, `€`) plus following whitespace before matching the existing, unmodified `NUMBER` pattern.
  The shared `NUMBER` pattern used by the default `SOURCE_NUMERIC_ROW` policy is untouched.
- Compute both `labelLeft` and `bodyLeft` from a shared `leftEdge` helper that scans the full glyph
  pool for the leftmost glyph (including whitespace) inside the relevant displayed box, instead of
  taking the minimum over an already blank-filtered content list.

Full archived diff: `followup-2026-10-21-fixed-candidate.patch`, applied on top of the repository
at HEAD `7090144` (it supersedes, i.e. already includes, the original parked `candidate.patch`).

Patch SHA-256: 352930c225b361f621aa3dbdf292370804d47e2a3379351bc533b32e91524f45

Validation (same frozen captures/scorer workspace, all 11 pages, both profiles, all 110 reference
rows, `AuditStatementRowPresence`):

| Report | YOLOX presence | TATR presence | Strict ordinal |
| --- | --- | --- | --- |
| `followup-2026-10-21-guarded-fixed-after-java21.json` (`--section-row`) | 68/110 | 36/110 | 0/110 |
| `followup-2026-10-21-numeric-only-after-java21.json` (`--leading-row`, default policy, unchanged check) | 67/110 | 36/110 | 0/110 |

The fixed guard recovers the full permissive gain (67 -> 68, the specific `Current liabilities:`
occurrence) while still genuinely requiring a complete numeric first body row and real left
alignment - it no longer passes vacuously. TATR is unaffected, as expected (no balance-sheet table
is present in that profile's page-6 capture). The default `SOURCE_NUMERIC_ROW` policy's own output
(67/110, 36/110) is bit-for-bit the documented baseline - this change only affects the non-shipped
experimental enum. `PdfNativeLeadingRowTest` (6/6) and the full `docweave-pdf` module (472/472)
passed against the fixed candidate. Java 25 verification remains deferred per the owner's current
validation schedule.

## Status

Still parked: this is one validated fix for one diagnosed pair of defects, not a decision to ship
`SOURCE_NUMERIC_OR_SECTION_ROW`. It has not been exercised against the acceptance criteria's broader
checklist (owned JVM tests with real glyph extraction for ordinary colon headings, centered titles,
partial numeric rows, multiline labels, rotated/cross-column text, competing tables, old-policy
default, added-anchor budget failure; a second, structurally distinct transfer case). No new public
API, test, or production change was made or committed in this pass; production sources were restored
to HEAD `7090144` and the module was recompiled from the restored sources afterward.
