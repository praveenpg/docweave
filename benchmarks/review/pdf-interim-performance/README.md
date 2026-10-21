# Native-text performance on five original PDFs

All nine independent forks succeeded. JVM verification checks all360 API observations,
including every first-pass and warmup sample, against the separately captured default text
hashes. No failed fork or slow sample is removed. The numeric summary is byte-identical when
computed on actual Java21 and25. Full samples, executable/tool hashes and resource observations
are in [results.json](results.json); [summary-java21.json](summary-java21.json) retains minimum,
median and maximum, including first-pass observations.

## Measured API latency

Median milliseconds per whole document, after two warmup passes. Each entry uses15 samples:
five measured passes in each of three independent forks. Documents are not pooled into one
latency score. Inputs are preloaded in memory; timing surrounds the partition API only, excluding
input creation, output hashing and serialization.

| Original source | DocWeave Java21 | DocWeave Java25 | Unstructured0.27.28 fast |
| --- | ---: | ---: | ---: |
| PLOS0324609 |63.30|58.89|1092.91|
| PLOS0330904 |48.13|47.71|860.43|
| eLife90049v1 |80.38|78.38|3815.67|
| PLOS0360958 |84.43|85.30|1475.32|
| Amazon Q4 2025 |49.06|47.19|2007.35|

DocWeave has lower measured API latency under this setup. This is native text extraction,
not OCR or table-model performance, and the libraries emit different categories/groupings and
image inventories. Matching each library's own default text hash guards against skipped work;
it does not establish equal semantic output or full-document accuracy.

## Whole-process observations

Each fork includes all five documents across eight passes: first pass, two warmups and five
measured passes. Whole-process wall time includes startup, imports, preparation, partitioning,
hashing, serialization and shutdown. Peak RSS is the process high-water mark including shutdown,
collected by zsh's wait4/rusage timing around the worker. Neither is a per-document measurement.

| Runtime | Median whole-fork seconds | Median peak RSS MiB | Peak RSS range MiB |
| --- | ---: | ---: | ---: |
| Java21 |4.61|496.84|494.49–497.84|
| Java25 |4.25|506.31|505.05–525.70|
| Unstructured0.27.28 |76.82|690.46|690.14–691.59|

The JVM heap limit is512MiB; RSS also includes native/off-heap memory. Python uses its standard
GC and the installed PDF stack, including Torch. These are different resource/GC policies,
not minimum memory requirements, retained-memory measurements or a general operating-cost
ranking. Worker setup slices have different import boundaries; full-process time includes all
boundaries. FIRST_PASS means the first pass in a fresh process, not disk-cold extraction or
an isolated fresh process for every document.

## Fixed execution conditions and reproduction

The [manifest](manifest.json) was frozen before execution. The host is an Intel i9-12900K;
taskset pins each worker to logical CPUs0–3, with four active JVM processors/Torch threads and
OMP limits. Runtime order rotates across three rounds. Forks execute serially with no concurrent
Maven/model work, but the host is not exclusive and OS/filesystem caches are not flushed.
No speedup or percentile confidence interval is inferred from three forks.

1. Verify the five original PDFs against manifest byte counts/hashes. Use the pinned installed
   upstream runtime from the defaults/table evidence; no package/model downloads occur.
2. Compile benchmarks/tools/BenchmarkPdfNative.java and RunPdfPerformance.java at release21,
   against current reactor classes and validation dependencies. Put current reactor classes
   before any older installed DocWeave artifacts. Include compiled workers in the classpath.
3. Run dev.docweave.validation.RunPdfPerformance with manifest, source cache, NEW raw directory,
   actual JDK21 root, actual JDK25 root and pinned Unstructured Python executable. Linux taskset,
   /proc/cpuinfo and /bin/zsh are explicit harness requirements; this is not a portable benchmark.
4. Run VerifyPerformance.java with repository root, complete results.json and NEW summary path
   using each actual JDK and the validation dependency classpath. It binds manifest/worker/
   orchestrator hashes and checks rotating order, all pass/source keys, affinity, JVM heap,
   positive timings, whole-process bounds and every output hash before producing summaries.
5. Compare the two summaries. Keep original results/failures; any changed policy or tool needs
   a separately identified new execution rather than relabelling these measurements.

Compile VerifyPerformanceControls.java with VerifyPerformance.java and run it with repository
root, complete results and a NEW control receipt. Eight altered-copy controls on each JDK
reject missing forks, duplicated samples, failed forks carrying output, changed text/manifest
hashes, wrong heap/affinity and impossible process wall times. Original captures are unchanged.

Only numeric observations, runtime versions and hashes are committed. Original PDFs, recognized
publisher text and process logs stay in the local source-review cache. Python executes only
Unstructured; JVM code orchestrates, captures DocWeave, verifies and summarizes both runtimes.
