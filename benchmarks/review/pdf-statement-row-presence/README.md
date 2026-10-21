# Exact statement-row presence diagnostic

The strict NVIDIA result stays0/110. Missing title/header rows outside model tables
can shift every ordinal comparison, so a separate diagnostic records whether each
complete frozen reference row occurs exactly anywhere in the geometrically associated
output. This does not realign output, repair references, assess logical order or replace
the original strict score. Every reference occurrence and unmatched output index remains.
No source strings are redistributed. Duplicate observed strings are consumed only once.

| Profile / complete110-row reference | Baseline exact occurrences | Experimental refined exact occurrences | Strict ordinal baseline/refined |
| --- | ---: | ---: | ---: |
| TATR |39/110|41/110|0/110→0/110|
| YOLOX |66/110|72/110|0/110→0/110|

The gain is confined to date/year header rows: balance-sheet indices4,5 for TATR;
income5,6, balance-sheet4,5 and cash-flows5,6 for YOLOX. No previously present complete
reference occurrence is lost. These are scoped exact-row presence observations, not
counts of newly correct numeric data rows or evidence of full statement recovery.
No per-source winner, fuzzy/partial match or new whitespace normalization is used.
All3 statements×2 model profiles and all11 source pages remain in both reports.
The existing frozen row parser and geometry-only association are reused verbatim.

Five JVM controls cover ordinal offsets, duplicate reuse, extra/missing rows and
exact whitespace differences. Strict private Javadocs, Java21 compilation and pinned
formatting pass. The unformatted pre-run tool hash and formatted final source identity
are separate. The first compile omitted the previously compiled replay helper from
its classpath; corrected compilation includes that unchanged helper. Both full runs
succeed; production remains unchanged since00ca0f1's1,172-test full verification.

## Reproduction

Use the exact source and original/derived captures described in
../pdf-synchronized-model-rows/README.md. Compile AuditStatementRowPresence.java,
CompareNvidiaCellLines.java and ReplayNativeLayoutCaptures.java against the Java21
validation/test classpath. Place the exact frozen8c4e990 scorer on the isolated
classpath and retain its source hash guard as described in that experiment.
Run --self-test, then invoke AuditStatementRowPresence with replay manifest, original
source cache, capture root and fresh report path. Repeat uniformly for original and
derived manifests/captures. All source bytes, reference row hashes and capture/raster
pins are checked. Numeric typed failures and complete missing denominators remain;
protocol changes fail rather than selecting a different case.

This experiment is not a production topology feature, release, held-out quality
estimate or general comparison ranking. Historical strict scores remain unchanged.
