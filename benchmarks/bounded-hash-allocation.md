# Bounded UTF-8 identity hashing

Measured 2026-10-15 on Linux with Corretto25.0.4+7-LTS, modular baseline `9904cbd`.
The legacy ID remains the first32 lowercase SHA-256 hex digits of the UTF-8 encoding of
(filename or None) + text + None + decimal sequence.

## Implementation and compatibility

A document-local Utf8Digest streams logical components through the JDK UTF-8 CharsetEncoder
using256 UTF-16 input slots and1024 output bytes. It preserves an unconsumed high surrogate
when compacting input, so pairs can span both scratch buffers and component boundaries.
Malformed UTF-16 uses the encoder's legacy question-mark replacement, verified against
String.getBytes(UTF_8). An unfinished surrogate is replaced only when the logical message ends
or a following character proves it malformed. Empty components do not prematurely end it.

The generator reuses its SHA-256 engine and encoder/scratch buffers. Finally/reset discards
partial state on failure or interruption. Checks occur between bounded encode/update steps;
no input String reference is retained. Scratch arrays may retain bounded copied characters
and bytes during the document lifetime. No global or ThreadLocal buffers are introduced.

Unpromoted generators use the former join/encode path for the first eight elements when a
conservative total-size bound is at most256 UTF-16 units. This keeps short-message temporary
arrays bounded and avoids paying encoder/scratch overhead for tiny documents. Large messages
stream immediately; later messages amortize the reusable buffers. Once promoted, the same
generator keeps the streaming path. A preliminary always-buffered implementation increased
the short-document probe from3256 to5016 allocated bytes; the bounded fast path reduced it
to3264. That preliminary variant was not selected as the final policy.

## Isolated full-element emission

`tools/MeasureEmissionAllocation.java` uses a no-segmentation custom TXT strategy emitting
one complete decoded input as one element. Loading/immutable output creation are included;
input construction is excluded. Five warmups and five measured calls per case, with
current-platform-thread allocation counters:

| Input | UTF-16 units | Baseline bytes/call | Final bytes/call |
| --- | ---: | ---: | ---: |
| Short. | 6 | 3,256 | 3,264 |
| ASCII | 1,048,576 | 2,124,705 | 29,256 |
| Repeated é漢🧵 | 1,048,576 | 7,712,944 | 112,464 |

IDs were identical before and after in all three cases. The large probes allocate about99%
less in this isolated workflow; this is not a99% reduction in whole-parser or retained memory.
Small inputs retain a modest object-layout cost. The probe also prints elapsed nanoseconds,
but five calls without a dedicated timing harness do not establish throughput or latency
improvements. Bounded encoding adds per-block codec/digest calls and can trade CPU for memory.

## Built-in partition allocation

The existing MeasurePartitionAllocation probe uses identical byte inputs and built-in parsers,
with five warmups and five measured calls. Measurements are separate from JFR sampling:

| Input | Baseline bytes/call | Final bytes/call |
| --- | ---: | ---: |
| ASCII TXT,4096 elements | 5,977,641 | 5,429,152 |
| Unicode TXT,4096 elements | 10,794,256 | 8,872,051 |
| HTML,2048 elements | 8,494,678 | 7,856,801 |
| CSV,one table | 2,886,600 | 2,666,892 |

Source-only controls increased by8 bytes/call in these runs, reflecting the generator's new
lazy encoder reference. Allocated-byte observations are runtime/JIT dependent, not peak heap,
retained sizes, stable thresholds, or corpus-wide guarantees. Full decoded inputs, parser
models, metadata snapshots, result lists and elements still materialize. This change bounds
identity-encoding scratch; it is not a general streaming parser or bounded-heap contract.

## Tests and reproduction

Six new tests extend the existing identity compatibility/concurrency tests: tiny character
and byte buffers forcing overflow/carry paths;1000 seeded raw-UTF-16 component partitions
and every standalone BMP code unit; deterministic mid-traversal interruption and recovery;
source failure and trailing-surrogate reset; large messages; small-path promotion/boundaries.
Legacy whole-message SHA256/getBytes/full-hex/substr is the independent ID oracle.
Reactor verify passes436 Java tests, zero failures/errors/skips, strict per-module Javadocs.

```sh
DOCWEAVE_CP="$(cat docweave-all/target/runtime-classpath.txt)"
java --class-path "$DOCWEAVE_CP" benchmarks/tools/MeasureEmissionAllocation.java
java --class-path "$DOCWEAVE_CP" benchmarks/tools/MeasurePartitionAllocation.java
```

See [module build instructions](../docs/modules.md#build-tests-and-launchers) to regenerate
the runtime classpath. No public API, Java package or parser dependency changed. Metadata
ownership remains deeply immutable. Further memory work should profile metadata handling
before introducing any ownership shortcut.
