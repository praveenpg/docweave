# Java validation migration evidence

Recorded 2026-10-16 using Java 25. These are text-free diagnostic reports, not
independent human/Word rendering evidence. Historical Python reports are untouched.

- owned-review.json: both extraction modes pass on the two owned ownership fixtures;
  JUnit also compares every ownership summary with the saved Python-era summary.
- forms-review.json: both official external forms pass the Java ownership and
  manifest contracts, with the same ownership summaries as the saved Python results.
  External binaries/full text remain in a local fetch-only cache.
- upstream-status.json: live official GitHub metadata check confirms released
  0.27.27 at the pinned347fbbfd commit and reviewed main d4008b99, three commits ahead.
  Main HTML changes are tracked as pending, not claimed compatible.

Corpus reports pin manifest/input/source hashes, Java version and actual ordered
test-runtime classpath contents (directories or JARs). They include diagnostic
structural paths and selected manifest anchors, but no extracted paragraph text,
source XML or image bytes. Upstream metadata reports record observed identities,
URLs, timestamps, response hashes and ETags. Assertions about freshness apply to
the observed checkpoint, not future upstream state.

Reproduction and remaining Python migration scope are documented in the
[validation module](../../../docweave-validation/README.md) and
[upstream tracking policy](../../../docs/upstream-tracking.md).
