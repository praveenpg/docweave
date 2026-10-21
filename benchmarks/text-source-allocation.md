# Decoded text source-loading allocation probe

Measured 2026-10-15 on Linux with Corretto 25.0.4+7-LTS, before baseline `b25af8a`
was changed and after the decoded-text loading optimization. The same Java source launcher
was used against each compiled production version in a fresh process.

| Input | UTF-16 units | UTF-8 bytes | Before: allocated bytes/load | After: allocated bytes/load |
| --- | ---: | ---: | ---: | ---: |
| ASCII | 1,048,576 | 1,048,576 | 5,358,041 | 26,313 |
| Repeated é漢🧵 | 1,048,576 | 2,359,296 | 18,331,736 | 109,384 |

`tools/MeasureTextSourceAllocation.java` uses a custom no-output TXT strategy to isolate
loading through the public engine. Five calls warm up each input, then five calls are
measured using the current platform thread's optional JDK allocation counter. Source
construction is outside the measured interval. The results include validation, routing,
small parser previews and engine bookkeeping. They exclude parser model construction,
output elements and concurrent workers. These numbers are allocation observations, not
peak/retained heap measurements, throughput claims, stable thresholds or full-document
memory guarantees. JIT and runtime differences can change results.

With the compiled project/runtime dependencies in `DOCWEAVE_CP`, reproduce using:

```sh
java --class-path "$DOCWEAVE_CP" benchmarks/tools/MeasureTextSourceAllocation.java
```

Decoded Text sources now retain the original String, validate surrogate pairs and count
the UTF-8 footprint directly, and encode only an at-most-8,196-byte routing prefix. A small
bounded intermediate prefix encoding can be larger before truncation. A leading Unicode
BOM is removed using a substring, which still copies the remaining text. The original
UTF-8 footprint including that BOM remains the input-byte count. Malformed Unicode,
limits, byte signatures, detection previews, warnings, and decoded-text charset policy
remain covered by tests.

File and byte sources still materialize their encoded payloads. Parsers, individual text
lines/paragraphs, metadata and result lists also materialize; this change does not expose
a streaming partition API or bound the heap. Further work should measure file decoding
and parser/output retention before selecting the next streaming boundary.

## File-loading allocation follow-up

Measured on the same Linux/Corretto runtime on 2026-10-15, using baseline `c2bdfec`
and the bounded channel-reader implementation. The launcher now also constructs byte
sources and temporary files outside the measured intervals; each variant has its own
five warmups and five measured calls through the same no-output TXT strategy.

| Input | Before: file bytes/load | After: file bytes/load | Byte-source control before/after |
| --- | ---: | ---: | ---: |
| ASCII, 1,048,576 UTF-8 bytes | 6,312,832 | 4,215,512 | 4,214,856 |
| Mixed Unicode, 2,359,296 UTF-8 bytes | 18,657,352 | 10,268,544 | 10,267,888 |

Text controls remained 26,313 and 109,384 bytes/load. These measurements concern
current-thread heap allocations; direct/native buffers, filesystem cache and peak heap
are not measured. File reads use chunks of at most 8 KiB to avoid making the channel's
individual heap-to-native staging buffer scale with the complete file.

Stable files now allocate one exact payload array using the already-open channel's size
as a hint, avoiding geometric growth and a final array copy. Actual reads still enforce
the byte limit, including one extra detection byte. A shrinking file may require trimming;
a growing or inaccurately sized file may require bounded buffer growth and copies.
No atomic file snapshot is promised. Complete encoded bytes and decoder/output strings
still materialize. Byte sources retain defensive-copy semantics. Decoder and parser/output
retention remain future profiling targets.
