# Decoder, partition allocation and live-heap observations

Measured 2026-10-15 on Linux, Corretto 25.0.4+7-LTS. Production baseline: `2cb9c1e`.
These owned synthetic inputs are memory probes, not corpus fidelity evidence.

## Decoder selection

`tools/MeasureDecoderAllocation.java` compares the old full-buffer strict UTF-8 decoder
against bounded scratch-buffer validation followed by the JDK UTF-8 String constructor.
Five warmups and five measured calls, input construction excluded:

| Input | Full-buffer allocated bytes | Bounded validation + String allocated bytes |
| --- | ---: | ---: |
| 1,048,576 ASCII UTF-8 bytes | 3,146,040 | 1,065,168 |
| 2,359,296 mixed-Unicode UTF-8 bytes | 7,864,544 | 9,191,664 |

The generic two-pass approach increased allocations for mixed Unicode, so it was not selected.
Production instead verifies ASCII bytes (polling interruption) and uses the JDK UTF-8 String
path only for that case. All ASCII bytes are valid UTF-8; control-character policy still runs
after decoding. Non-ASCII UTF-8 and other charsets keep the reporting decoder, including
stateful and custom charset semantics. The fast path requires the standard UTF-8 charset instance;
a custom charset with the same canonical name still invokes its own decoder. BOM removal remains the source loader's responsibility.

The existing source-loading probe recorded these before/after results:

| Input/source | Baseline allocated bytes/load | After allocated bytes/load |
| --- | ---: | ---: |
| ASCII byte source, 1,048,576 bytes | 4,214,856 | 2,117,536 |
| ASCII file source, 1,048,576 bytes | 4,215,512 | 2,118,192 |
| Mixed Unicode byte source, 2,359,296 bytes | 10,267,888 | 10,267,888 |
| Mixed Unicode file source, 2,359,296 bytes | 10,268,544 | 10,268,544 |

Text-source controls remained 26,313 ASCII / 109,384 mixed-Unicode bytes/load. Only the ASCII
UTF-8 decoding path changed. No throughput guarantee is inferred from these small probes.

## Source versus full partition allocation

`tools/MeasurePartitionAllocation.java` measures identical byte sources/options through a
no-output strategy and the built-in parser, with five warmups/five measured calls each.
After the optimization:

| Input | Input bytes | Elements | Source-only bytes/call | Full partition bytes/call |
| --- | ---: | ---: | ---: | ---: |
| ASCII TXT | 225,280 | 4,096 | 460,065 | 15,747,896 |
| Unicode TXT | 266,240 | 4,096 | 1,577,120 | 22,479,180 |
| HTML | 83,994 | 2,048 | 581,721 | 9,368,577 |
| CSV | 22,539 | 1 | 154,592 | 2,637,702 |

The baseline ASCII TXT case was 910,793 source-only / 16,156,363 full-partition bytes/call.
Source/full differences include traversal, classification, IDs, metadata and output building;
they do not isolate a particular component. JIT/runtime variation affects values, especially
small before/after changes in paths whose code did not change. These are current-thread heap
allocations, excluding source construction and concurrent work, not retained or peak heap.

## Live heap with a result held and released

`tools/MeasurePartitionLiveHeap.java` uses the HotSpot diagnostic `gcClassHistogram` command,
which requests a full GC. After warming both parser and histogram machinery it measures the
whole process with the source held, source plus returned result held, then source held again.
The source is fenced for liveness through the final observation. This is intrusive and
requires the HotSpot diagnostic MBean; no management port is opened.

| Input | Before live bytes | Result held live bytes | Result released live bytes | Held minus released |
| --- | ---: | ---: | ---: | ---: |
| ASCII TXT | 12,474,016 | 14,357,888 | 12,473,624 | 1,884,264 |
| Unicode TXT | 12,748,648 | 14,895,056 | 12,748,648 | 2,146,408 |
| HTML | 12,963,944 | 13,922,608 | 12,964,040 | 958,568 |
| CSV | 12,970,080 | 13,138,848 | 12,970,080 | 168,768 |

The close before/released observations support attributing most of the held delta to the
result in these runs. They are not exact object-graph retained sizes, peak-memory figures,
leak proofs or portable thresholds; global caches, GC/JIT activity and unrelated live objects
can affect the totals. Each fixture runs in the same process with earlier parser classes warm.

## Reproduce and next boundary

After compiling the library, set `DOCWEAVE_CP` to the compiled classes plus runtime dependency
classpath, then run:

```sh
java benchmarks/tools/MeasureDecoderAllocation.java
java --class-path "$DOCWEAVE_CP" benchmarks/tools/MeasureTextSourceAllocation.java
java --class-path "$DOCWEAVE_CP" benchmarks/tools/MeasurePartitionAllocation.java
java --class-path "$DOCWEAVE_CP" benchmarks/tools/MeasurePartitionLiveHeap.java
```

The results point toward transient parser/emission allocations as the next profiling target:
full partition allocations greatly exceed the live result deltas for these samples. Obtain
allocation stack evidence before selecting a specific traversal/classification/ID/metadata
change. Keep the current result ownership contract; reducing retained output more broadly
would require an explicitly designed incremental-element API. File/byte payloads, non-ASCII
strict decoder buffers, parser models and output lists still materialize. No general streaming
or bounded-heap claim is made.
