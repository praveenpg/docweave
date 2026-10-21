# Reviewed release and historical reference identities

Official release0.27.28 and main resolve to1b02b4d189c260ed9b1aca29583203e233d853cb.
They are six commits beyond the unchanged0.27.27 reference347fbbfd3e84e228017f125e5c5dbdc326ef7de2.
The first five changes retain their earlier classifications. The sixth adds underlying
Python OCR-agent import/attribute error text and explicit exception chaining; it does not
change PDF parser output or agent selection rules. The JVM uses explicit typed providers
and retains its existing typed failures and diagnostic policy, not Python environment-loader
error-string parity. No new full reference execution is claimed.

The freshness checker now distinguishes reference_release from latest_release. Offline checks
still require every fixture and all seven golden datasets to match the historical reference.
Online checks require the observed latest tag and its actual commit to match the latest
reviewed release, and independently require main/diff metadata to match the reviewed snapshot.
A newer reviewed release is allowed without silently rewriting old references. Unknown future
release/main/tag/path drift, failed requests and truncated comparisons continue to fail.

review.json records immutable source/response hashes, reviewed identities and the explicit
no-parser-change finding. Original official sources:
- https://github.com/Unstructured-IO/unstructured/releases/tag/0.27.28
- https://github.com/Unstructured-IO/unstructured/compare/347fbbfd3e84e228017f125e5c5dbdc326ef7de2...1b02b4d189c260ed9b1aca29583203e233d853cb
- https://github.com/Unstructured-IO/unstructured/commit/1b02b4d189c260ed9b1aca29583203e233d853cb

This updates reviewed-source currency, not historical golden coverage or a full PDF parity claim.

Both actual Java21/25 full clean builds pass997 tests; strict private Javadocs and official
opt-in online checks also pass on both. The new owned guard accepts distinct reviewed/latest
and reference identities, while rejecting malformed/collapsed/missing reference tracks.
All historical fixture and seven golden files remain unchanged.
