# Classification allocation investigation

Measured 2026-10-15 on Linux with Corretto25.0.4+7-LTS. Parser baseline `53b1534`;
coordinates were moved to io.docweave without changing Java packages or parser behavior.

## Allocation stack evidence

`tools/ProfilePartitionAllocation.java` creates owned TXT/HTML/CSV fixtures, warms each
parser five times, and records twenty calls per fixture through JDK Flight Recorder.
It enables `jdk.ObjectAllocationSample` with stack traces and throttle off, filters to the
calling thread, discards the first sample's weight (it can include earlier allocations),
and groups remaining weights by the nearest recorded DocWeave stack frame.

Before the change, the ASCII TXT run contained208 samples; classification was the largest
sampled frame at an estimated160,770,224 weighted bytes, ahead of ElementCollector.add at
84,568,368. Unicode TXT contained115 samples; classification led at215,709,064, ahead of
ElementCollector.add at134,749,040. Weights describe estimated allocation pressure over
twenty calls, not exact allocated bytes or per-call costs. Counts/ranks depend on sampling,
JIT, GC and stack truncation; low-sample HTML/CSV results are especially inconclusive.

Inspection identified temporary regex Matchers and word-split arrays in classification,
which TXT invokes for both line grouping and paragraph classification. The implementation
now uses a hidden marker recognizer and bounded word counting, without those objects.
It preserves ASCII regex whitespace/digits/letter markers, greedy bullet cleaning, Unicode
letter/point rules, sentence endings and leading/interior/trailing-space word semantics.
Long marker scans poll interruption. Public interfaces, element IDs and metadata are unchanged.

Afterward the JFR runs had101 ASCII /153 Unicode samples. Classification no longer appeared
among sampled allocation frames; ElementCollector.add became the leading frame. These ranks
support the investigation but do not independently quantify savings or prove zero allocations.

## Independent thread-allocation comparison

The existing `MeasurePartitionAllocation.java` probe uses the same synthetic inputs, five
warmups and five measured calls per case, with input construction outside measurement.

| Input | Elements | Baseline full-partition bytes/call | After bytes/call |
| --- | ---: | ---: | ---: |
| ASCII TXT | 4,096 | 15,747,896 | 7,588,368 |
| Unicode TXT | 4,096 | 22,479,180 | 12,419,108 |
| HTML | 2,048 | 9,368,577 | 9,387,659 |
| CSV | 1 | 2,637,702 | 2,624,947 |

TXT reductions are about52% and45% for these fixtures. HTML/CSV differences are small and
not evidence of an improvement/regression. Source-only controls were unchanged:
460,065 /1,577,120 /581,721 /154,592 bytes respectively. These are current-thread allocated
heap bytes, not peak/retained heap, throughput guarantees or general corpus performance.

## Validation and reproduction

Four tests compare the optimized rules with the historical regex/string implementation:
all supported markers across ASCII/Unicode whitespace and bodies,10,000 seeded mixed-Unicode
strings, title word/point boundaries and long marker/space inputs, plus interruption/reuse.
The clean Maven verification passes405 Java tests and strict Javadocs. Java imports remain
io.docweave; Maven coordinates are io.docweave:docweave:0.1.0-SNAPSHOT.

With compiled classes and runtime dependencies in DOCWEAVE_CP:

```sh
java --class-path "$DOCWEAVE_CP" benchmarks/tools/ProfilePartitionAllocation.java /tmp/docweave-parser-jfr
java --class-path "$DOCWEAVE_CP" benchmarks/tools/MeasurePartitionAllocation.java
```

Raw JFR files remain local and are ignored by Git. They may contain runtime/environment
information; the repository records only this synthetic summary and the reproducer.
Next investigate finer allocation stacks inside element emission (IDs/metadata) before
selecting the next change. Immutable output ownership, full parser materialization and the
absence of a heap ceiling remain unchanged. Independent Word review remains deferred.
