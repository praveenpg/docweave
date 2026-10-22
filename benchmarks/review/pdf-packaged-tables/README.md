# Packaged native PDF table workload

This follow-up closes the zero-table execution gap in the earlier
[packaged study](../pdf-packaged-study/README.md). It changes no extraction code, options,
reference outputs or accuracy scores. Independent acceptance review remains open.

## Frozen scope and gates

`protocol.properties` was written before this measurement. Two existing source PDFs are
pinned by SHA-256, with provenance in
[the business cell-text manifest](../pdf-business-cell-text/manifest.json): financial
`frx_2012_disclosure.pdf` and transport procurement
`Publication_of_award_of_Bids_for_Transport_Sector__August_2016.pdf`.
The original PDFs are not redistributed here. The protocol records local cache paths;
substitute source paths while retaining source hashes when reproducing elsewhere.
A changed protocol has a different identity and must get a fresh report.

Both profiles use `RULED_AND_PARTIAL_TEXT`. Each launches three independent child JVMs;
each child performs one cold, one warmup, two sequential and two rounds of two concurrent
virtual-thread extractions: eight attempts per child, 24 per profile, 48 total.
Gates: at least one Table, zero failures, one logical output hash per profile across all
attempts/JVMs, each child within 60 seconds, positive Linux VmHWM strictly below 512 MiB.
Heap is capped at 256 MiB. No latency target was declared.

The existing JDK-only `MeasurePackagedPdfStudy` was compiled and run on Java 21 against
only the six packaged runtime JARs in `runtime-jars.sha256`: core, PDF, PDFBox, pdfbox-io,
FontBox and Commons Logging. No test classes, layout models, OCR engines or JSON library
were on the extraction subprocess classpath. The separate report checker uses the
existing validation classpath and Jackson; it is not a runtime library dependency.
Runtime library code is the previously verified `aceffe3` development baseline.

## Results

| Profile | Attempts | Tables per extraction | Child exits/deadlines | Distinct output hashes | Peak VmHWM KiB |
| --- | ---: | ---: | --- | ---: | ---: |
| Financial disclosure | 24 | 1 | 3/3 pass | 1 | 163,292 |
| Transport procurement | 24 | 5 | 3/3 pass | 1 | 235,892 |

All declared gates pass. The report retains all six child observations. Counts are recorded
on the cold extraction; the same full type/text/metadata fingerprint on every subsequent
successful attempt also establishes the same table-containing logical output. Hashes use
`typed-length-prefixed-v1`, excluding element IDs and the result envelope. Source identity
is checked by each child before extraction. Distinct source profiles need not have equal
hashes. Peak process memory includes the JVM and native/runtime overhead, not just heap.

`check-source.java.txt` is the exact JVM report checker used: protocol identity, population,
unique slots, nonzero tables, failures, per-profile hashes, deadline and heap/RSS gates.
`negative-controls.java.txt` verifies rejection of seven individually corrupted reports:
zero tables, incomplete attempts, child failure, missed deadline, memory ceiling equality,
duplicate process slot and changed inter-process output hash. All seven are rejected.
`checks.txt` records both checker outcomes. These controls do not rerun extraction.

## Reproduction

With `RUNTIME_CP` containing exactly the six listed packaged JARs and `VALIDATION_CP`
containing the existing validation dependencies:

```sh
javac --release 21 -cp "$RUNTIME_CP" -d "$WORK" benchmarks/tools/MeasurePackagedPdfStudy.java
java -cp "$WORK:$RUNTIME_CP" MeasurePackagedPdfStudy supervise \
  benchmarks/review/pdf-packaged-tables/protocol.properties "$WORK:$RUNTIME_CP" "$FRESH_REPORT"
cp benchmarks/review/pdf-packaged-tables/check-source.java.txt "$WORK/CheckPackagedTables.java"
cp benchmarks/review/pdf-packaged-tables/negative-controls.java.txt "$WORK/CheckPackagedTablesControls.java"
javac --release 21 -cp "$VALIDATION_CP" -d "$WORK" "$WORK/CheckPackagedTables.java" \
  "$WORK/CheckPackagedTablesControls.java"
java -cp "$WORK:$VALIDATION_CP" CheckPackagedTables \
  benchmarks/review/pdf-packaged-tables/protocol.properties "$FRESH_REPORT"
```

The archived negative controls target exact recorded observations, so run them against
`java21.json`, not a future host's report. Use Java 21 `java` and `javac` throughout.

## Boundaries

This is packaged native-table execution and repeatability evidence on two known sources,
not an independent correctness review, broad table accuracy claim, sustained-load test or
minimum memory recommendation. It does not exercise OCR/ONNX table providers, large/malformed
inputs, all table topologies or the complete earlier four-profile schedule. Historical
reports and their fingerprint encodings remain unchanged. Full Java 21 reactor verification
already passed 1,348 tests for this unchanged product code; it was not repeated for this
additive evidence-only follow-up. No release was performed.
