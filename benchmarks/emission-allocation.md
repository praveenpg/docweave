# Element emission allocation investigation

Measured 2026-10-15 on Linux with Corretto25.0.4+7-LTS, using the modular reactor's runtime
classpath. Baseline `f7d8cdb`. Earlier monolithic measurements are not reused as this baseline.
The synthetic inputs and measurement limitations match the preceding
[classification investigation](classification-allocation.md).

## Allocation-stack evidence and change

The JFR profiler now includes allocated classes and the top six stack frames for emission,
as well as its existing nearest-DocWeave-frame summary. It collects twenty calls after five
warmups, filters to the caller thread and excludes the first sample's weight. Identity-helper
frames are included after extraction; collection-return copies are not part of this emission
breakdown. Raw recordings remain local and ignored by Git.

Baseline samples inside ElementCollector.add include SHA256/MessageDigest delegate creation,
security-provider lookup, full digest HexFormat buffers/Strings, substring copies, hash-input
concatenation/UTF-8 bytes and metadata maps. For example, the ASCII run sampled approximately
11.1M weighted bytes in SHA256 creation,10.0M in hex buffers and9.5M in provider constraints.
These are weighted estimates across twenty calls, not exact per-call allocation counts.

A hidden, document-confined ElementIdGenerator now owns the identity work. It lazily obtains
one SHA-256 engine and reuses it for that document's elements; digest(byte[]) resets the engine
after each complete message. It formats the first16 bytes directly as32 lowercase hexadecimal
digits. No global or ThreadLocal digest cache is used. Facade instances remain thread-safe
because collectors/generators belong to individual document calls. Empty/source-only calls do
not initialize a digest engine.

The exact old hash message is retained: (filename or None) + text + None + decimal sequence,
encoded together as UTF-8. Separate component encoding would change behavior for surrogate
pairs crossing component boundaries, so concatenation and the full encoded hash input remain.
Metadata continues through the same deep immutable snapshot; no map/list ownership shortcut
was introduced. Failed metadata validation does not advance the collection sequence or retain
a digest prefix for the next element. Interrupt flags are preserved at identity checkpoints.

After-change profiles still show input concatenation/UTF-8 bytes and metadata entries. Digest
initialization now occurs once per nonempty document, rather than once per element. Sample
absence is not proof of zero allocation; the change is quantified separately below.

## Independent allocation measurement

`MeasurePartitionAllocation.java` uses five warmups and five measured calls per synthetic
fixture, excluding input construction. Full-partition observations:

| Input | Elements | Baseline bytes/call | After bytes/call | Reduction |
| --- | ---: | ---: | ---: | ---: |
| ASCII TXT | 4,096 | 7,653,904 | 5,983,156 | 22% |
| Unicode TXT | 4,096 | 12,545,336 | 10,794,256 | 14% |
| HTML | 2,048 | 9,616,238 | 8,479,150 | 12% |
| CSV | 1 | 2,906,102 | 2,798,027 | Inconclusive attribution |

The single-element CSV case does not exercise digest reuse across elements; its observed
variation cannot be attributed to that optimization. Source-only controls increased by16
bytes/call in these runs (460,065 ->460,081;1,577,120 ->1,577,136;653,489 ->653,505;
154,592 ->154,608), reflecting the small document-local generator object without a digest.

These are current-thread allocated heap bytes, not peak/retained heap, throughput guarantees,
or corpus-wide performance claims. JIT, sampling and runtime variability affect values.
Output elements, parser models, metadata snapshots and full hash messages still materialize.

## Validation and reproduction

Five new core tests compare against the former SHA256/UTF-8/full-hex/substr expression:
Unicode/null/empty filenames and component-boundary surrogate pairs;1000 seeded messages on
one generator; snapshot failure with sequence reuse and mutation isolation;100 simultaneous
virtual-thread calls sharing one facade; interruption flag preservation and reuse. Existing
format/reference tests continue to check IDs and output behavior. Full reactor verification:
430 Java tests, zero failures/errors/skips, strict per-module Javadocs.

After building the reactor, use its generated runtime classpath:

```sh
DOCWEAVE_CP="$(cat docweave-all/target/runtime-classpath.txt)"
java --class-path "$DOCWEAVE_CP" benchmarks/tools/MeasurePartitionAllocation.java
java --class-path "$DOCWEAVE_CP" benchmarks/tools/ProfilePartitionAllocation.java /tmp/docweave-emission-jfr
```

See [module build instructions](../docs/modules.md#build-tests-and-launchers) to regenerate the
classpath. Next investigate bounded UTF-8 hash-message processing, preserving exact replacement
and cross-component surrogate semantics, or profile metadata snapshots further before selecting
an ownership-safe change. No new public API, parser dependency or Java package migration occurs.
