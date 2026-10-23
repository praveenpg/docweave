# Final review and scope correction

Accepted after one scope correction. The reviewed candidate applied the new tie pass to
plain PARAGRAPHS, where it separated a previously kept row pair. This lies outside the
column-layout scope established by the handoff. The pass now requires `TextLayout.columns()`:
the two ordinary column variants and oriented column compound wraps. Other layouts retain
SMALLER_TYPE_BLOCKS output without the additional pass. Existing policies/defaults are unchanged.
This is a disclosed review correction after observing a regression, not a new preregistered gain.

A new owned fixture reproduces the plain-paragraph issue: it fails on the submitted candidate
and passes after the guard. There are now 8 row-tie tests and 7 existing sidebar tests.
No rejected paragraph-splitting patch or geometric threshold adjustment was used.

## Independent checks

- Full Java21 verification: 1,402 tests, zero failures/errors/skips; strict Javadocs pass.
  The first sandbox run failed four local HTTP-mock socket binds. Offline verification with
  loopback access passes; no actual external model ran. Java25 was not repeated.
- All 408 complete-output hashes for default/older policies match the f42b0e0 baseline.
- All-page comparison: 17 sources, 181 pages, all six layouts and both inline choices
  (204 source profiles /2,172 page profiles). Whole-element contents, source characters and
  non-sidebar sequence are preserved. Every row-pair outcome is unchanged in every layout.
- Plain PARAGRAPHS now has no additional moves, including the business and eLife controls;
  the reported holdout row-pair separation no longer occurs.
- Fixed-pair reports on four papers reproduce the candidate JSON exactly: recent 6 unchanged,
  next 3->1, holdout 19->17 (20->18 inline), eLife43 unchanged. No new wrong/unmatched pair.
- Opt-in strict scientific paragraph study reproduces the submitted report exactly, including
  per-paragraph fields. Paragraph grouping remains intact.
- Six-JAR public API consumer compiles and runs with the new explicit selection.
- Java21 strict tool compilation/private doclint, pinned formatter and diff checks pass.

`scoped-policy-java21.json` records every layout, including the corrected PARAGRAPHS behavior.
Its `equals_frozen_simulation` field uses the frozen move in column layouts and unchanged
SMALLER_TYPE_BLOCKS placement in other layouts. The comparator source documents this scope.
The original candidate's reports remain unchanged in the parent folder. These are development
compatibility results, not semantic accuracy or independent held-out acceptance.
