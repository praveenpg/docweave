# Explicit wide-gap OCR line splitting

## Reproduced failure

Pinned Tesseract (`tesseract` SHA-256 `9c19bce3...b917`, one native thread, explicit source
resolution, AUTO/ENGINE_DEFAULT) on the complete retained historical subset (two pages, both
fixed `tessdata_best` models, 72 reference lines per model). `historical-baseline-java21.json`
reproduces the documented values (1835 German/Fraktur 104/51 global character edits).

Temporary, reverted instrumentation of the existing line association found:

- **1879, both models:** reference line `falten.` (x 250-456) was recognized correctly, but the
  same Tesseract line also contained a stray punctuation mark near x 2160. `TesseractTsv` builds
  each returned line as the union of its words' boxes, so the box spanned the page width, its IoU
  with the reference fell to 0.106 (threshold 0.5), and the line counted as both missing and an
  unreferenced prediction. This is a line-geometry failure, not a recognition failure.
- **1835, both models:** the missing line is an isolated page number with no overlapping
  prediction (a detection miss; no candidate here).

## Candidate (frozen before candidate inference)

`TesseractOptions.LineSplitting` with `TESSERACT_LINES` (default; unchanged behavior) and opt-in
`WIDE_GAPS`: within one Tesseract line, a new returned line starts where the horizontal gap
between consecutive words in recognition order exceeds three times the line's tallest word box.
Typographic word spacing is a fraction of an em, so only gutter-sized gaps split. Words, text and
recognition order are unchanged; overlapping or right-to-left steps never split; extra lines
count against `maxLines` with the existing typed `OCR_LIMIT`; the detailed word view splits
identically. A new 14th record component keeps the previous canonical constructor and all copy
methods. The validation harness reads an optional `line_splitting` profile field and reports it
only when present, so default reports are unchanged.

The candidate manifests (`*-wide-gaps-manifest.json`) are the handoff-derived manifests with only
`line_splitting: WIDE_GAPS` added to each profile; sources, references, models and DPI unchanged.

## Results (all observations retained)

| Page / model | Associated | Missing | Extra | Exact | Joint line errors | XML-sequence / global edits |
| --- | --- | --- | --- | --- | --- | --- |
| 1835 German | 21→21 | 1→1 | 0→0 | 0→0 | 103→103 | 104→104 |
| 1835 Fraktur | 21→21 | 1→1 | 0→0 | 4→4 | 50→50 | 51→51 |
| 1879 German | 49→50 | 1→0 | 1→1 | 42→43 | 14→7 | 291→291 |
| 1879 Fraktur | 49→50 | 1→0 | 1→1 | 18→19 | 60→53 | 334→334 |
| 1778 German (transfer) | 17→26 | 21→12 | 11→17 | 0→0 | 507→391 | 419→419 |
| 1778 Fraktur (transfer) | 18→27 | 20→11 | 10→12 | 3→4 | 457→333 | 371→371 |
| 1780 German (transfer) | 9→9 | 1→1 | 9→9 | unchanged | 51→51 | 103→103 |
| 1780 Fraktur (transfer) | 9→9 | 1→1 | 9→9 | unchanged | 27→27 | 82→82 |

The structurally distinct transfer case is the degraded 1778 page with a two-column subscriber
list, where Tesseract lines spanning both columns are now divided at the gutter. Reference
association improves for both models on the target and the transfer page. Unreferenced
predictions rise on 1778 (+6 German, +2 Fraktur); local joint errors do not charge extras, so
that increase is reported separately. Recognized text and its whole sequence are identical
(XML-sequence and global edit diagnostics unchanged on all eight observations): this is a
line-geometry/association gain, not a character-recognition gain, and not a logical-page CER.

## Verification

Owned `TesseractLineSplittingTest` (7 methods): default identity behavior, wide-gap split with
own boxes, ordinary/threshold/backward non-splitting, multiple gutters, detailed word view,
`maxLines` typed failure, defaults/constructors/copies. Existing Tesseract unit suites pass.
Full Java 21 `mvn verify`: 1,341 tests (1,334 baseline + 7), zero failures/errors/skips
(`full-verify-java21.log`). Pinned clang-format 23.1.1 applied to owned hunks only.

## Limits

Development pages already observed, not held-out evidence. A genuine tab-stop row, table row or
right-aligned page header can be divided into several lines when explicitly selected; no default
change is proposed. The page-number detection miss on 1835 is unaffected.


## Integration review

The reviewed implementation keeps the frozen gap rule and adds shared deadline/interruption
checks to the new scans and fragment-to-line construction. Fragment accumulation checks the
remaining returned-line allowance before retaining an excess population. The seven owned
methods and full Java 21 verification pass **1,341 tests**, zero failures/errors/skips.
Changed private methods/harness pass strict doclint and scoped pinned formatting.

Eight final real-engine calls replay all four complete source pages under both original
models. JVM comparisons reproduce every candidate geometry metric and preserve prediction
text hashes and source/reference/model identities. Fresh reports and comparisons use the
`integrated-` prefix; `integration-receipt.json` binds final sources/logs. The archived
candidate patch and initial verification log remain unchanged historical evidence.

This option changes returned line boundaries and boxes, not recognized words. It can split
intentional tab-stop rows and changes detailed word-line grouping as well as PAGE output.
TesseractOptions gains a record component; old constructors are retained but reflective
schemas and record-derived serialization/equality/hash/text change. No default change,
model download, source-label repair or character-recognition gain is claimed.
