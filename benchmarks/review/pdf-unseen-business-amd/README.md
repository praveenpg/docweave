# AMD original-source transfer and presentation attribute classes

The original seven-page issuer PDF and matching release HTML were frozen before
DocWeave extraction, along with three source regions, all 120 publisher rows and
the full 12-profile population. Independent Poppler text and original rendered
pages were inspected first. These are new-issuer frozen-code transfer observations,
not independently human-reviewed held-out accuracy or physical-cell annotations.
Original PDF/HTML bytes, derived images and source strings remain outside Git.

| Profile | Baseline exact rows | Candidate exact rows | Associated statements |
| --- | ---: | ---: | ---: |
| Each of ten native policies | 0/120 | 0/120 | 0/3 |
| Source-tagged supported axes | 0/120 | 33/120 | 1/3 |
| Source-tagged logical envelope | 0/120 | 33/120 | 1/3 |

Both source-tagged profiles now emit all 36 balance-sheet rows, with 33 exact
ordinal matches. Operations (32 reference rows) and cash flow (52) still fall back;
their full denominators remain. Every profile processed the complete original
document successfully. All 30 native case/profile observations remain exactly
unchanged. No configuration is selected based on reference matches.
The three balance-sheet mismatches are one HTML em dash versus a PDF hyphen and
two HTML rows with presentation spaces before closing numeric parentheses. The
source strings and strict score remain unchanged; these are not missing rows.

The original source planner rejected all nine table definitions because source
Table/TR/TH/TD dictionaries use named presentation attribute classes. The candidate
resolves only bounded revision-zero names through the document's original ClassMap.
Definitions require explicit attribute owners and cannot contain RowSpan/ColSpan.
Actual class styles, text alternatives and geometry are not interpreted. Direct
table attributes retain existing validation; missing classes, aliases, malformed
definitions, revisions, topology-bearing classes and excessive lists retain whole
fallback. The candidate accepts all nine logical plans, then existing native
ownership/geometry validation independently decides whether a table can be emitted.
Accepting a plan alone is not evidence of successful table extraction.

The [PDF Association usage specification](https://pdfa.org/download-area/specifications/Deriving_HTML_from_PDF.pdf)
describes named attribute classes and direct-attribute precedence. This change is
a restricted span-free class subset, not full attribute inheritance/revision support;
class-defined spans remain unsupported even if direct attributes could override them.

Source limits remain explicit: HTML rows are content references, not PDF topology
labels. An invisible `Quarter Group` text item was present in independent PDF text
inventory before extraction. Source date line layout also differs from HTML cell
presentation. Neither hidden-text filtering nor reference repair is introduced.
The discovery history and exact publisher URLs/hashes are recorded in manifest.json.

Validation: complete Java 21 Maven verification passes 1,117 tests, zero failures or
errors, with strict Javadocs. Four new JVM controls cover resolved class arrays and
owners, fallback for malformed/topology/revision inputs, bounded lists, interruption,
and actual public extraction preserving text/spans/coordinates. Strict private
Javadocs pass. The online upstream freshness check passes on Java 21; release/main
and historical reference tracks remain unchanged. No Java 25 run or release occurs.

Walmart's complete original 59-page filing was replayed under all 12 existing
profiles: all 36 case/profile observations match the previous report exactly,
including 105/105 exact rows in its explicit logical-envelope profile.

Reproduce with the existing optional PdfCurrentBusinessRowsTest and frozen source
cache/manifest plus a fresh report path. VerifyBusinessRows21.java checks all rows,
associations, failures, arithmetic and complete profile populations on Java 21.
For AMD regression comparison, only SOURCE_TAGGED_TABLES and
SOURCE_TAGGED_LOGICAL_TABLES are declared changed; for Walmart none are changed.
Candidate source/baseline identities were frozen before the first candidate run
in candidate-identities.sha256. Historical baseline and candidate reports remain
separate; no labels, thresholds or original reference rows were replaced.

## Painted-mode follow-up

The source-only audit after class resolution found all original references declared,
no duplicate claimed glyphs and twelve unclaimed nonblank glyphs inside each of the
operations and cash-flow table envelopes. They spell a source `Quarter Group` item
that was absent in the inspected page render. The balance sheet has no such interior
unclaimed glyphs. Existing exclusive-envelope validation therefore explains these
two fallbacks without a missing source table/cell plan.

A separately frozen experiment appended two uniform explicit PAINTED_MODES profiles,
retaining all previous profiles and 120 rows per profile. It produced no gain: both
new profiles remain 0/32 operations, 33/36 balance and 0/52 cash flow. All 36 earlier
observations remain equal; all 42 case/profile observations are retained. Source-state
inspection reports ordinary fill mode, black fill and alpha 1 for the extra item;
render-mode filtering alone cannot resolve it. No special text deletion or broad
visibility assumption was added to production.

The validator now supports explicit added profiles while requiring every baseline
key to remain present. Painted reports/manifests/receipts remain separate from the
class-handling result. This negative experiment closes no additional PDF gate.

## Explicit logical cell text envelopes

A separately frozen candidate appends SOURCE_TAGGED_CELL_ENVELOPE_TABLES. The new
caller-selected geometry preserves source-authored cells while leaving unclaimed
inter-cell gap text as ordinary output. Nonblank unclaimed glyph centers within a
real nonempty cell envelope still reject the whole table. It does not delete the
extra source item, classify its visibility, invent empty-cell rectangles or change
the earlier whole-parent exclusion policy.

| Complete statement | Earlier logical profile | Cell-envelope profile | Rows emitted |
| --- | ---: | ---: | ---: |
| Operations | 0/32 | 25/32 | 32 |
| Balance sheet | 33/36 | 33/36 | 36 |
| Cash flows | 0/52 | 23/52 | 52 |
| Total | 33/120 | 81/120 | 120 |

All 42 prior case/profile observations remain equal and all 45 observations retain
the complete reference populations. Candidate source and scorer identities were
frozen before the new source run in cell-envelopes-manifest.json. All original
seven pages are processed once per profile; no reference content enters extraction.

The source-character preservation diagnostic on the complete original document
finds identical Unicode code-point counts excluding ASCII whitespace inserted by
layout. The original extra source item remains a separate ordinary element exactly
once on each affected page. This checks preservation, not semantic order/accuracy.
Local diagnostic helper and source strings remain outside Git. Owned Java controls
also assert complete character-count preservation, separate gap text, exact cell
content and spans, retained raster/outside text, rejected cell-interior contamination,
unsupported references/orientation and configuration constraints.

Full Java 21 Maven verification passes 1,119 tests with zero failures/errors and
strict Javadocs. Strict private Javadocs pass for the changed API, implementation
and owned tests. Existing Walmart regression is rerun: every one of its 36 original
observations remains equal, including 105/105 logical-envelope rows. Java 25 remains
deferred. No release, default or dependency change occurs.

The new cell-envelope profile was then transferred prospectively to the complete
original Walmart filing using a separate frozen manifest. It emits 29/29 operations,
40/40 balance and 36/36 cash-flow exact rows (105/105 total). Every one of the 36
earlier observations remains equal; all 39 case/profile records retain their full
denominators. This confirms transfer to an earlier development source, not held-out
or general PDF accuracy. No production code changed for this data-only check.

Subsequent [class-defined cell-span support](../pdf-class-defined-spans/README.md)
extends the earlier span-free class subset. All 45 AMD and 39 Walmart observations
remain equal under that extension; it does not add an external accuracy gain here.

The subsequent [same-index presentation diagnostic](../pdf-business-row-presentation/README.md)
accounts for the39 nonexact rows as19 ASCII-space-only,7 em-dash/hyphen-only and13
combined differences. Strict scoring remains81/120. This is post-hoc diagnosis,
not a repaired reference, alternate accuracy score or evidence that typography and
spacing can safely be ignored. The same complete-original Walmart control stays105/105.
