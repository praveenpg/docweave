# Whole-document PDF goldens

Captured from unchanged 0.1.0-beta.13 source (commit 54f47a8) by `PdfWholeDocumentGoldenTest` with
`-Ddocweave.writeRangeGoldens=<dir>`, before the page-range refactor, on Java 21. Two captures in separate JVMs were
identical. Each file holds one corpus case from `PdfRangeCorpus` (one line per element, then warnings in order);
`limits.txt` holds the budget outcome (element count, or failure code and message) of each limit scenario.

Nested metadata maps are written in key order because some are built with `Map.of`, whose iteration order differs
between JVM runs; top-level metadata keeps its insertion order. Same-JVM tests compare full insertion order directly.
Do not regenerate these files to make a change pass: they are the beta.13 baseline.
