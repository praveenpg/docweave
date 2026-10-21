# Java saved Word replay evidence

Recorded 2026-10-16. Both packets (16 cases) have zero property differences with the
saved Word16.0 build16.0.20430 observations. Human review remains pending; no Word
process or VM was started. Four direct-heading controls remain excluded from this
comparator and covered by existing Java policy tests.

summary.json pins the evidence catalog, raw manifests/observations, fresh candidates,
comparisons, Java validation sources and actual runtime classpath. comparisons.json
contains both fresh comparison results. Full copied inputs and candidates are generated
under docweave-validation/target/word-evidence and retained by private CI; originals
remain in their unchanged review packets. upstream-status.json records the successful
live freshness check against the existing released pin and reviewed main.

Reproduce using mvn verify or the commands in benchmarks/word-style-validation.md.
Historical Python comparisons remain unchanged; JUnit reproduces their original
differences exactly and separately verifies the current library has no differences.
