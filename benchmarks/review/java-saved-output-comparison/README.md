# Java saved-output comparison migration

The internal SavedOutputComparator interface/hidden implementation and classpath CLI replace
compare.py and its five Python tests. Seven JUnit tests verify the historical schema/report,
Unicode and segmentation behavior, immutable results, unknown-field differences and real CLI
process exit codes 0/1/2. Strict UTF-8 JSON handling rejects malformed input, BOM/trailing tokens,
nonstandard NaN/Infinity literals, missing files and invalid envelopes.

`summary.json` records the retained owned Python vectors' SHA-256/provenance and current validation
source hashes/Java version. The vectors are committed under
`docweave-validation/src/test/resources/saved-output-comparison-vectors.json` and need no Python
at test time: 1767 similarity pairs (including exhaustive short Unicode cases, directional ties,
long popular-symbol sequences and deterministic random inputs), 14 complete reports, and four
encoded numeric edge reports. Python numeric/boolean equality is retained, including binary
floating rounding and JSON exponent overflow/underflow. These diagnostics do not claim arbitrary
Python-object equality beyond the saved JSON domain.

The independently implemented longest-contiguous-block algorithm uses Unicode code points and
an explicit partition stack with no junk/popularity filtering. Matching block ties favor the
earliest reference index then earliest candidate index; argument order must remain intact.
Worst-case repetitive-text cost can be quadratic, as in the former utility.

Only saved arrays are compared; parsers, upstream generation, Word observations and historical
reference pins are untouched. No new parser parity, quality or human-review claim is made.
See [comparison commands](../../README.md) for Maven/fresh-report and CLI usage. Private CI retains
reports under `docweave-validation/target/saved-output-comparison`. No VM is needed.
