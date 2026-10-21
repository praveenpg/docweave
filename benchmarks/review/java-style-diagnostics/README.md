# Java conditional-style diagnostics

Java probe version 2 reruns three hash-pinned Apache POI samples from the original
five-source style manifest. NRS reports were not rerun or downloaded. Original pinned
reports remain untouched. No external binaries, full extracted text or raw XML are committed.

`review.json` records current independent stored-style inventory and separately instrumented
probes, hashes, actual ordered runtime classpath fingerprints and validation-source hashes.
`historical-comparison.json` records exact equality of the three source inventories, original
summaries and enabled/disabled summaries/checks against the historical Python report.
Derivative ZIP hashes differ because Java serialization/compression differs.

The direct-body variant has 0/3/54 table-style ranges respectively for TestTableColumns,
TestTableCellAlign and table-alignment. The old report expected zero for all inputs.
Current behavior follows the later saved Word evidence: direct body outline 9 can retain
conditional table-style outlines. Version 2 corrects the audit assertion without changing
production semantics. Source text/type hashes remain unchanged for every derivative.
Existing Word policy regression tests and the 16-case saved replay independently remain green.
Metamorphic checks compare the candidate to itself; shared defects remain possible.
Human rendering review, upstream parity and broad document representativeness are not established.

Repeat with Java 25 from the repository root, using exact cached inputs and a fresh output:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Ddocweave.styles.manifest=benchmarks/review/java-style-diagnostics/manifest.json \
  -Ddocweave.styles.cache=/tmp/docweave-corpus-inventory \
  -Ddocweave.styles.report=/tmp/fresh-style-report.json
```

No Python, network or Windows VM is required. Default Maven tests use owned fixtures.
